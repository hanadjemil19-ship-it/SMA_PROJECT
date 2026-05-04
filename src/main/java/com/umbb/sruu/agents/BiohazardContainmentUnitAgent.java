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
import java.util.concurrent.atomic.AtomicInteger;

public class BiohazardContainmentUnitAgent extends Agent {

    public enum State {
        IDLE, EN_ROUTE, ACTIVE, RETURNING, DECONTAMINATING
    }

    private MovementBehaviour movement;
    private State state = State.IDLE;
    private int workload = 0;

    private Map<String, Emergency> pendingEmergencies = new HashMap<>();
    private Emergency assignedEmergency = null;
    private String currentMissionId = null;

    /**
     * Invariant: suit integrity is stored in exactly one place and is visible across threads.
     * Any read/write must go through this AtomicInteger.
     */
    private final AtomicInteger suitIntegrity = new AtomicInteger(100);
    private static final int SUIT_DEGRADATION_PER_MISSION = 30;
    private static final int SUIT_THRESHOLD = 20;
    private static final int MIN_SUIT_FOR_LOW_RISK_RESPONSE = 70;
    private static final int LOW_RISK_MAX_SEVERITY = 3;
    private static final int DECONTAMINATION_TIME_MS = 6000;
    private static final Location BASE_LOCATION = new Location(80, 80);

    @Override
    protected void setup() {
        System.out.println("[" + getLocalName() + "] BiohazardContainmentUnitAgent started");

        getContentManager().registerLanguage(new SLCodec());
        getContentManager().registerOntology(EmergencyOntology.getInstance());

        movement = new MovementBehaviour(this, 150, BASE_LOCATION, getLocalName());
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

        // Service interne utilisé par le Dispatcher pour router les incidents BIOHAZARD/CRYOGENIC_LEAK
        ServiceDescription sd1 = new ServiceDescription();
        sd1.setType("HAZMAT");
        sd1.setName("bcu-containment");
        dfd.addServices(sd1);

        // Service formel exigé par le cahier des charges
        ServiceDescription sd2 = new ServiceDescription();
        sd2.setType("BIOHAZARD_CONTAINMENT");
        sd2.setName("bcu-biohazard-containment");
        dfd.addServices(sd2);

        try {
            DFService.register(this, dfd);
            System.out.println("[" + getLocalName() + "] Registered in DF as HAZMAT and BIOHAZARD_CONTAINMENT");
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }

    private void handleCFP(ACLMessage cfp) {
        try {
            HasEmergency hasEmergency = (HasEmergency) getContentManager().extractContent(cfp);
            Emergency emergency = hasEmergency.getEmergency();
            String eId = cfp.getConversationId();

            if (state == State.IDLE && workload == 0 && canAcceptWithCurrentSuit(emergency)) {
                pendingEmergencies.put(eId, emergency);
            }

            System.out.println("[" + getLocalName() + "] Received CFP for: " + emergency.getId());
            System.out.println("[" + getLocalName() + "] Suit integrity READ(CFP): " + suitIntegrity.get() + "%");

            ACLMessage reply = cfp.createReply();

            if (!canAcceptWithCurrentSuit(emergency)) {
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("COMPROMISED_SUIT");
                System.out.println("[" + getLocalName() + "] Sending REFUSE - COMPROMISED SUIT! ("
                        + suitIntegrity.get() + "%)");

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
            System.out.println("[" + getLocalName() + "] ACTIVE - containing hazard for " + assignedEmergency.getId());

            int afterDegrade = suitIntegrity.updateAndGet(prev -> {
                int next = prev - SUIT_DEGRADATION_PER_MISSION;
                return Math.max(0, next);
            });
            System.out.println("[" + getLocalName() + "] Suit integrity WRITE(DEGRADE): " + afterDegrade + "%");
            
            if (afterDegrade <= SUIT_THRESHOLD && currentMissionId != null && state == State.ACTIVE) {
                sendAbortToDispatcher(currentMissionId, "COMPROMISED_SUIT");
                transitionTo(State.RETURNING);
                workload = 0;
                movement.setTarget(BASE_LOCATION, () -> {
                    startDecontamination();
                });
                return;
            }

            addBehaviour(new WakerBehaviour(this, 5000) {
                @Override
                protected void onWake() {
                    sendLifecycleInform("MISSION_COMPLETE", currentMissionId);
                    System.out.println("[" + getLocalName() + "] Hazard contained for " + assignedEmergency.getId() + ", returning to base");
                    transitionTo(State.RETURNING);
                    workload = 0;

                    movement.setTarget(BASE_LOCATION, () -> {
                        if (shouldDecontaminateAfterMission(assignedEmergency)) {
                            startDecontamination();
                        } else {
                            transitionTo(State.IDLE);
                            assignedEmergency = null;
                            currentMissionId = null;
                            notifyDispatcherIdle();
                            System.out.println("[" + getLocalName() + "] Back at base, suit integrity READ(BASE): "
                                    + suitIntegrity.get() + "%, READY");
                        }
                    });
                }
            });
        });
    }

    private void startDecontamination() {
        transitionTo(State.DECONTAMINATING);
        final int expectedDegradedValue = suitIntegrity.get();
        System.out.println("[" + getLocalName() + "] DECONTAMINATING AND REPLACING SUIT... (" + DECONTAMINATION_TIME_MS
                + "ms, expected=" + expectedDegradedValue + "%)");
        addBehaviour(new WakerBehaviour(this, DECONTAMINATION_TIME_MS) {
            @Override
            protected void onWake() {
                // Invariant: replacement must be monotonic (no "phantom re-degrade" after replacement).
                // We only replace if the current integrity is still the degraded value we observed when starting decon.
                int before = suitIntegrity.get();
                boolean replaced = suitIntegrity.compareAndSet(expectedDegradedValue, 100);
                if (!replaced) {
                    // If something changed it, force to 100 anyway but keep evidence in logs.
                    suitIntegrity.set(100);
                }
                System.out.println("[" + getLocalName() + "] Suit integrity WRITE(REPLACE): "
                        + before + "% -> " + suitIntegrity.get() + "% (CAS expected=" + expectedDegradedValue + "%, ok=" + replaced + ")");
                transitionTo(State.IDLE);
                assignedEmergency = null;
                currentMissionId = null;
                notifyDispatcherIdle();
                System.out.println("[" + getLocalName() + "] Suit REPLACED to 100%, READY for next call");
            }
        });
    }

    private boolean canAcceptWithCurrentSuit(Emergency emergency) {
        int current = suitIntegrity.get();
        System.out.println("[" + getLocalName() + "] Suit integrity READ(ACCEPT?): " + current + "%");
        if (current <= SUIT_THRESHOLD) {
            return false;
        }
        if (emergency == null) {
            return current >= MIN_SUIT_FOR_LOW_RISK_RESPONSE;
        }
        if (emergency.getSeverity() <= LOW_RISK_MAX_SEVERITY) {
            return current >= MIN_SUIT_FOR_LOW_RISK_RESPONSE;
        }
        return current > SUIT_THRESHOLD;
    }

    private boolean shouldDecontaminateAfterMission(Emergency emergency) {
        int current = suitIntegrity.get();
        System.out.println("[" + getLocalName() + "] Suit integrity READ(DECON?): " + current + "%");
        if (current <= SUIT_THRESHOLD) {
            return true;
        }
        if (emergency == null) {
            return false;
        }
        return emergency.getSeverity() > LOW_RISK_MAX_SEVERITY;
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
        System.out.println("[" + getLocalName() + "] BiohazardContainmentUnitAgent terminated");
    }

    // Visible for tests (package-private): deterministic unit test without spinning up JADE runtime.
    int testSuitIntegrityRead() {
        return suitIntegrity.get();
    }

    // Visible for tests (package-private)
    void testSuitIntegritySet(int value) {
        suitIntegrity.set(value);
    }

    // Visible for tests (package-private)
    boolean testSuitIntegrityCas(int expected, int update) {
        return suitIntegrity.compareAndSet(expected, update);
    }
}
