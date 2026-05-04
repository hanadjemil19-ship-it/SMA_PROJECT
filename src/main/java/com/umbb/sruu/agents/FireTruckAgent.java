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

    private int waterLevel = 150;
    private static final int WATER_PER_FIRE = 30;
    private static final int WATER_THRESHOLD = 20;
    private static final int REFILL_TIME_MS = 8000;

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
            HasEmergency hasEmergency = (HasEmergency) getContentManager().extractContent(cfp);
            Emergency emergency = hasEmergency.getEmergency();
            String eId = cfp.getConversationId();

            if (state == State.IDLE && workload == 0 && waterLevel > WATER_THRESHOLD) {
                pendingEmergencies.put(eId, emergency);
            }

            System.out.println("[" + getLocalName() + "] Received CFP for: " + emergency.getId());
            System.out.println("[" + getLocalName() + "] Water level: " + waterLevel + "%");

            ACLMessage reply = cfp.createReply();

            if (waterLevel <= WATER_THRESHOLD) {
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("LOW_WATER");
                System.out.println("[" + getLocalName() + "] Sending REFUSE - LOW WATER! (" + waterLevel + "%)");

            } else if (state == State.IDLE && workload == 0) {
                reply.setPerformative(ACLMessage.PROPOSE);

                UnitStatus status = new UnitStatus();
                status.setUnitId(getLocalName());
                status.setState(state.name());
                status.setCurrentLocation(movement.getCurrentLocation());
                status.setWorkload(workload);

                UnitAvailable unitAvailable = new UnitAvailable();
                unitAvailable.setStatus(status);

                getContentManager().fillContent(reply, unitAvailable);
                System.out.println("[" + getLocalName() + "] Sending PROPOSE for " + emergency.getId());

            } else {
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("Busy: state=" + state);
                System.out.println("[" + getLocalName() + "] Sending REFUSE (busy)");
            }

            send(reply);

        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void sendAbortToDispatcher(String emergencyId, String reason) {
        ACLMessage abort = new ACLMessage(ACLMessage.INFORM);
        AID dispatcher = findAgentByService("COORDINATION");
        if (dispatcher != null) abort.addReceiver(dispatcher);
        String eId = (emergencyId != null && !emergencyId.isEmpty()) ? emergencyId : "UNKNOWN";
        abort.setContent("UNIT_ABORT:" + getLocalName() + ":" + reason + ":" + eId);
        send(abort);
        System.out.println("[" + getLocalName() + "] Sent ABORT notification to dispatcher for " + eId);
    }

    private void handleAccept(ACLMessage accept) {
        String eId = accept.getConversationId();
        if (state != State.IDLE) {
            System.err.println("[" + getLocalName() + "] REJECTING ACCEPT - already busy (state=" + state + ")");
            ACLMessage reject = accept.createReply();
            reject.setPerformative(ACLMessage.FAILURE);
            reject.setContent("ALREADY_ASSIGNED");
            send(reject);
            pendingEmergencies.clear();
            return;
        }

        assignedEmergency = pendingEmergencies.get(eId);
        pendingEmergencies.clear();

        if (assignedEmergency == null) {
            System.err.println("[" + getLocalName() + "] ERROR: No emergency stored for assignment! (eId=" + eId + ")");
            return;
        }

        System.out.println("[" + getLocalName() + "] *** ASSIGNED to " + assignedEmergency.getId() + "! ***");
        currentMissionId = assignedEmergency.getId();
        transitionTo(State.EN_ROUTE);
        workload = 1;

        final Location targetLocation = assignedEmergency.getLocation();
        System.out.println("[" + getLocalName() + "] Target: " + targetLocation + " for " + assignedEmergency.getId());

        movement.setTarget(targetLocation, () -> {
            transitionTo(State.ACTIVE);
            sendLifecycleInform("MISSION_ARRIVED", currentMissionId);
            System.out.println("[" + getLocalName() + "] ACTIVE - fighting fire/rescuing for " + assignedEmergency.getId());

            waterLevel -= WATER_PER_FIRE;
            System.out.println("[" + getLocalName() + "] Water consumed. Remaining: " + waterLevel + "%");
            if (waterLevel <= WATER_THRESHOLD && currentMissionId != null && state == State.ACTIVE) {
                sendAbortToDispatcher(currentMissionId, "LOW_WATER");
                transitionTo(State.RETURNING);
                workload = 0;
                movement.setTarget(new Location(20, 20), () -> {
                    startRefill();
                });
                return;
            }

            addBehaviour(new WakerBehaviour(this, 4000) {
                @Override
                protected void onWake() {
                    sendLifecycleInform("MISSION_COMPLETE", currentMissionId);
                    System.out.println("[" + getLocalName() + "] Mission complete for " + assignedEmergency.getId() + ", returning to base");
                    transitionTo(State.RETURNING);
                    workload = 0;

                    movement.setTarget(new Location(20, 20), () -> {
                        if (waterLevel <= WATER_THRESHOLD) {
                            startRefill();
                        } else {
                            transitionTo(State.IDLE);
                            assignedEmergency = null;
                            currentMissionId = null;
                            notifyDispatcherIdle();
                            System.out.println("[" + getLocalName() + "] Back at base, water: " + waterLevel + "%, READY");
                        }
                    });
                }
            });
        });
    }

    private void startRefill() {
        transitionTo(State.REFILLING);
        System.out.println("[" + getLocalName() + "] REFILLING water tank... (" + REFILL_TIME_MS + "ms)");
        addBehaviour(new WakerBehaviour(this, REFILL_TIME_MS) {
            @Override
            protected void onWake() {
                waterLevel = 150;
                transitionTo(State.IDLE);
                assignedEmergency = null;
                currentMissionId = null;
                notifyDispatcherIdle();
                System.out.println("[" + getLocalName() + "] Water REFILLED to 150%, READY for next call");
            }
        });
    }

    private void handleReject(ACLMessage reject) {
        String eId = reject.getConversationId();
        pendingEmergencies.remove(eId);
        System.out.println("[" + getLocalName() + "] Proposal REJECTED for " + eId + ": " + reject.getContent());

        if (pendingEmergencies.isEmpty()) {
            System.out.println("[" + getLocalName() + "] Remaining IDLE, ready for next call");
            transitionTo(State.IDLE);
            workload = 0;
        } else {
            System.out.println("[" + getLocalName() + "] Still have " + pendingEmergencies.size() + " pending proposal(s)");
        }
    }

    private void transitionTo(State newState) {
        if (state != newState) {
            pendingEmergencies.clear();
            state = newState;
        }
    }

    private void notifyDispatcherIdle() {
        ACLMessage idle = new ACLMessage(ACLMessage.INFORM);
        AID dispatcher = findAgentByService("COORDINATION");
        if (dispatcher != null) idle.addReceiver(dispatcher);
        idle.setContent("UNIT_IDLE:" + getLocalName());
        send(idle);
        System.out.println("[" + getLocalName() + "] Sent IDLE notification to dispatcher");
    }

    private void sendLifecycleInform(String event, String emergencyId) {
        if (emergencyId == null) return;
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        AID dispatcher = findAgentByService("COORDINATION");
        if (dispatcher != null) msg.addReceiver(dispatcher);
        msg.setContent(event + ":" + getLocalName() + ":" + emergencyId);
        send(msg);
    }

    private AID findAgentByService(String serviceType) {
        return com.umbb.sruu.utils.AgentUtils.findAgentByService(this, serviceType);
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException e) {
            e.printStackTrace();
        }
        System.out.println("[" + getLocalName() + "] FireTruckAgent terminated");
    }
}