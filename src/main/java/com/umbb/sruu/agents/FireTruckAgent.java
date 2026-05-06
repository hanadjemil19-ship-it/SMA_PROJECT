package com.umbb.sruu.agents;

import com.umbb.sruu.behaviours.MovementBehaviour;
import com.umbb.sruu.ontology.*;
import jade.content.lang.sl.SLCodec;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public class FireTruckAgent extends Agent {

    public enum State {
        IDLE, EN_ROUTE, ACTIVE, RETURNING, REFILLING
    }

    private MovementBehaviour movement;
    private State state = State.IDLE;
    private int workload = 0;

    private Map<String, Emergency> pendingEmergencies = new HashMap<>();
    private Emergency assignedEmergency = null;
    private String currentMissionId = null;
    private final ReentrantLock stateLock = new ReentrantLock();
    private boolean idleNotified = false;

    private int waterLevel = 40;
    private static final int WATER_PER_FIRE = 30;
    private static final int WATER_THRESHOLD = 25;
    private static final int REFILL_TIME_MS = 5000;

    @Override
    protected void setup() {
        System.out.println("[" + getLocalName() + "] FireTruckAgent started");

        getContentManager().registerLanguage(new SLCodec());
        getContentManager().registerOntology(EmergencyOntology.getInstance());

        movement = new MovementBehaviour(this, 200, new Location(20, 20), getLocalName());
        addBehaviour(movement);

        registerInDF();

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.MatchPerformative(ACLMessage.CFP),
                        MessageTemplate.MatchOntology(EmergencyOntology.getInstance().getName())
                );
                ACLMessage msg = receive(mt);
                if (msg != null) handleCFP(msg);
                else block();
            }
        });

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL);
                ACLMessage msg = receive(mt);
                if (msg != null) handleAccept(msg);
                else block();
            }
        });

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.REJECT_PROPOSAL);
                ACLMessage msg = receive(mt);
                if (msg != null) handleReject(msg);
                else block();
            }
        });
    }

    private void registerInDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());

        ServiceDescription sd1 = new ServiceDescription();
        sd1.setType("FIRE");
        sd1.setName("firetruck-fire");
        dfd.addServices(sd1);

        ServiceDescription sd2 = new ServiceDescription();
        sd2.setType("RESCUE");
        sd2.setName("firetruck-rescue");
        dfd.addServices(sd2);

        try {
            DFService.register(this, dfd);
            System.out.println("[" + getLocalName() + "] Registered in DF as FIRE and RESCUE");
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }

    private void handleCFP(ACLMessage cfp) {
        try {
            Emergency emergency = (Emergency) cfp.getContentObject();
            String eId = cfp.getConversationId();

            stateLock.lock();
            try {
                if (state == State.IDLE && workload == 0 && waterLevel > WATER_THRESHOLD) {
                    pendingEmergencies.put(eId, emergency);
                }
            } finally {
                stateLock.unlock();
            }

            System.out.println("[" + getLocalName() + "] Received CFP for: " + emergency.getId());
            System.out.println("[" + getLocalName() + "] Water level: " + waterLevel + "%");

            ACLMessage reply = cfp.createReply();

            stateLock.lock();
            try {
                if (waterLevel <= WATER_THRESHOLD) {
                    reply.setPerformative(ACLMessage.REFUSE);
                    reply.setContent("LOW_WATER");
                    System.out.println("[" + getLocalName() + "] Sending REFUSE - LOW WATER! (" + waterLevel + "%)");
                } else if (state == State.IDLE && workload == 0) {
                    reply.setPerformative(ACLMessage.PROPOSE);
                    UnitStatus status = new UnitStatus();
                    status.setUnitName(getLocalName());
                    status.setState(state.name());
                    status.setPosition(movement.getCurrentLocation());
                    status.setWorkload(workload);
                    status.setWater(waterLevel);
                    reply.setContent("PROPOSE:" + getLocalName() + ":" + waterLevel);
                    reply.setContentObject(status);
                    System.out.println("[" + getLocalName() + "] Sending PROPOSE for " + emergency.getId());
                } else {
                    reply.setPerformative(ACLMessage.REFUSE);
                    reply.setContent("Busy: state=" + state);
                    System.out.println("[" + getLocalName() + "] Sending REFUSE (busy)");
                }
            } finally {
                stateLock.unlock();
            }
            send(reply);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleAccept(ACLMessage accept) {
        String eId = accept.getConversationId();
        try {
            if (accept.getContentObject() instanceof Assignment assignment) {
                eId = assignment.getEmergencyId();
            }
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Failed to read Assignment object: " + e.getMessage());
        }
        stateLock.lock();
        try {
            if (state != State.IDLE) {
                ACLMessage reject = accept.createReply();
                reject.setPerformative(ACLMessage.FAILURE);
                reject.setContent("ALREADY_ASSIGNED");
                send(reject);
                pendingEmergencies.clear();
                return;
            }
            assignedEmergency = pendingEmergencies.get(eId);
            pendingEmergencies.clear();
            if (assignedEmergency == null) return;
        } finally {
            stateLock.unlock();
        }

        System.out.println("[" + getLocalName() + "] *** ASSIGNED to " + assignedEmergency.getId() + "! ***");
        currentMissionId = assignedEmergency.getId();
        transitionTo(State.EN_ROUTE);
        workload = 1;

        final Location targetLocation = assignedEmergency.getLocation();
        movement.setTarget(targetLocation, () -> {
            transitionTo(State.ACTIVE);
            sendLifecycleInform("MISSION_ARRIVED", currentMissionId);
            System.out.println("[" + getLocalName() + "] ACTIVE - starting fire suppression ticks for " + assignedEmergency.getId());

            // Tick 1
            addBehaviour(new WakerBehaviour(this, 2000) {
                @Override
                protected void onWake() {
                    if (state != State.ACTIVE) return;
                    stateLock.lock();
                    try {
                        waterLevel = Math.max(0, waterLevel - WATER_PER_FIRE);
                    } finally {
                        stateLock.unlock();
                    }
                    System.out.println("[" + getLocalName() + "] Tick 1 complete. Water: " + waterLevel + "%");

                    // Tick 2
                    addBehaviour(new WakerBehaviour(myAgent, 2000) {
                        @Override
                        protected void onWake() {
                            if (state != State.ACTIVE) return;
                            stateLock.lock();
                            try {
                                waterLevel = Math.max(0, waterLevel - WATER_PER_FIRE);
                            } finally {
                                stateLock.unlock();
                            }
                            System.out.println("[" + getLocalName() + "] Tick 2 complete. Water: " + waterLevel + "%");

                            if (waterLevel <= 0) {
                                abortDueToWater();
                            } else {
                                completeMission();
                            }
                        }
                    });
                }
            });
        });
    }

    private void abortDueToWater() {
        System.out.println("[" + getLocalName() + "] ABORTING mission - WATER DEPLETED!");
        ACLMessage abortMsg = new ACLMessage(ACLMessage.INFORM);
        abortMsg.setOntology(EmergencyOntology.getInstance().getName());
        abortMsg.setLanguage(new SLCodec().getName());
        AID dispatcher = findAgentByService("COORDINATION");
        if (dispatcher != null) abortMsg.addReceiver(dispatcher);
        try {
            MissionAbort ma = new MissionAbort(currentMissionId, "WATER_DEPLETED");
            abortMsg.setContent("ABORT:" + currentMissionId + ":WATER_DEPLETED");
            abortMsg.setContentObject(ma);
            send(abortMsg);
        } catch (Exception e) {
            e.printStackTrace();
        }

        transitionTo(State.RETURNING);
        workload = 0;
        movement.setTarget(new Location(20, 20), () -> startRefill());
    }

    private void completeMission() {
        sendLifecycleInform("MISSION_COMPLETE", currentMissionId);
        System.out.println("[" + getLocalName() + "] Mission complete for " + currentMissionId + ", returning to base");
        transitionTo(State.RETURNING);
        workload = 0;
        movement.setTarget(new Location(20, 20), () -> {
            if (waterLevel < 100) startRefill();
            else finishMission();
        });
    }

    private void startRefill() {
        transitionTo(State.REFILLING);
        System.out.println("[" + getLocalName() + "] REFILLING water tank... (" + REFILL_TIME_MS + "ms)");
        addBehaviour(new WakerBehaviour(this, REFILL_TIME_MS) {
            @Override
            protected void onWake() {
                stateLock.lock();
                try {
                    waterLevel = 100;
                    finishMission();
                } finally {
                    stateLock.unlock();
                }
            }
        });
    }

    private void finishMission() {
        transitionTo(State.IDLE);
        assignedEmergency = null;
        currentMissionId = null;
        notifyDispatcherIdle();
        System.out.println("[" + getLocalName() + "] Back at base, Water: " + waterLevel + "%, IDLE and READY");
    }

    private void handleReject(ACLMessage reject) {
        String eId = reject.getConversationId();
        pendingEmergencies.remove(eId);
        if (pendingEmergencies.isEmpty() && state == State.IDLE) {
            workload = 0;
        }
    }

    private void transitionTo(State newState) {
        stateLock.lock();
        try {
            if (state != newState) {
                pendingEmergencies.clear();
                state = newState;
                if (newState != State.IDLE) idleNotified = false;
            }
        } finally {
            stateLock.unlock();
        }
    }

    // Test helpers for SystemicBugsTest
    void testSetRefillingState(int water) {
        stateLock.lock();
        try {
            this.state = State.REFILLING;
            this.waterLevel = water;
        } finally {
            stateLock.unlock();
        }
    }

    void testCompleteRefillAtomic() {
        stateLock.lock();
        try {
            this.waterLevel = 100;
            this.state = State.IDLE;
        } finally {
            stateLock.unlock();
        }
    }

    String testState() {
        return state.name();
    }

    int testWaterLevel() {
        return waterLevel;
    }

    private void notifyDispatcherIdle() {
        ACLMessage idle = new ACLMessage(ACLMessage.INFORM);
        idle.setOntology(EmergencyOntology.getInstance().getName());
        idle.setLanguage(new SLCodec().getName());
        AID dispatcher = findAgentByService("COORDINATION");
        if (dispatcher != null) idle.addReceiver(dispatcher);
        try {
            UnitStatus status = new UnitStatus();
            status.setUnitName(getLocalName());
            status.setState("IDLE");
            status.setWater(waterLevel);
            idle.setContent("IDLE:" + getLocalName() + ":" + waterLevel);
            idle.setContentObject(status);
            send(idle);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendLifecycleInform(String event, String emergencyId) {
        if (emergencyId == null) return;
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setOntology(EmergencyOntology.getInstance().getName());
        msg.setLanguage(new SLCodec().getName());
        AID dispatcher = findAgentByService("COORDINATION");
        if (dispatcher != null) msg.addReceiver(dispatcher);
        try {
            if ("MISSION_ARRIVED".equals(event)) {
                MissionArrived arr = new MissionArrived(getLocalName(), emergencyId);
                msg.setContent("MISSION_ARRIVED:" + getLocalName() + ":" + emergencyId);
                msg.setContentObject(arr);
            } else if ("MISSION_COMPLETE".equals(event)) {
                MissionComplete mc = new MissionComplete(emergencyId, getLocalName(), true);
                msg.setContent("MISSION_COMPLETE:" + emergencyId + ":" + getLocalName());
                msg.setContentObject(mc);
            }
            send(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private AID findAgentByService(String serviceType) {
        return com.umbb.sruu.utils.AgentUtils.findAgentByService(this, serviceType);
    }

    @Override
    protected void takeDown() {
        try { DFService.deregister(this); } catch (FIPAException e) {}
    }
}