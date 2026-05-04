package com.umbb.sruu.agents;

import com.umbb.sruu.behaviours.MovementBehaviour;
import com.umbb.sruu.ontology.*;
import jade.content.lang.sl.SLCodec;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class PoliceAgent extends Agent {

    public enum State {
        IDLE, EN_ROUTE, ON_SITE
    }

    private MovementBehaviour movement;
    private State state = State.IDLE;
    private int workload = 0;
    private Random random = new Random();

    // FIX: Store pending per emergency ID
    private Map<String, Emergency> pendingEmergencies = new HashMap<>();
    private Emergency assignedEmergency = null;
    private String currentMissionId = null;
    private boolean idleNotified = false;

    private TickerBehaviour patrolBehaviour;

    @Override
    protected void setup() {
        System.out.println("[" + getLocalName() + "] PoliceAgent started");

        getContentManager().registerLanguage(new SLCodec());
        getContentManager().registerOntology(EmergencyOntology.getInstance());

        movement = new MovementBehaviour(this, 200,
                new Location(random.nextInt(50), random.nextInt(50)), getLocalName());
        addBehaviour(movement);

        registerInDF();

        patrolBehaviour = new TickerBehaviour(this, 5000) {
            @Override
            protected void onTick() {
                if (state == State.IDLE) {
                    Location patrolPoint = new Location(random.nextInt(50), random.nextInt(50));
                    movement.setTarget(patrolPoint, () -> {
                        System.out.println("[" + getLocalName() + "] Patrolling at " + patrolPoint);
                    });
                }
            }
        };
        addBehaviour(patrolBehaviour);

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

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = new MessageTemplate(msg ->
                        msg.getPerformative() == ACLMessage.INFORM
                                && msg.getContent() != null
                                && (msg.getContent().startsWith("PERIMETER_RELEASED:")
                                || msg.getContent().startsWith("RELEASE_PERIMETER:")));
                ACLMessage msg = receive(mt);
                if (msg != null) handlePerimeterReleased(msg);
                else block();
            }
        });
    }

    private void registerInDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());

        ServiceDescription sd1 = new ServiceDescription();
        sd1.setType("CROWD_CONTROL");
        sd1.setName("police-crowd");
        dfd.addServices(sd1);

        ServiceDescription sd2 = new ServiceDescription();
        sd2.setType("TRAFFIC_CONTROL");
        sd2.setName("police-access");
        dfd.addServices(sd2);

        try {
            DFService.register(this, dfd);
            System.out.println("[" + getLocalName() + "] Registered in DF as CROWD_CONTROL and TRAFFIC_CONTROL");
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }

    private void handleCFP(ACLMessage cfp) {
        try {
            HasEmergency hasEmergency = (HasEmergency) getContentManager().extractContent(cfp);
            Emergency emergency = hasEmergency.getEmergency();
            String eId = cfp.getConversationId();

            if (state == State.IDLE && workload == 0) {
                pendingEmergencies.put(eId, emergency);
            }

            System.out.println("[" + getLocalName() + "] Received CFP for: " + emergency.getId());

            ACLMessage reply = cfp.createReply();

            if ((emergency.getType().equals("FIRE") || emergency.getType().equals("STRUCTURAL_COLLAPSE"))
                    && state == State.IDLE
                    && workload == 0) {

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
                if (state != State.IDLE) {
                    reply.setContent("Not available: state=" + state);
                } else if (workload > 0) {
                    reply.setContent("Not available: workload=" + workload);
                } else {
                    reply.setContent("Not available: wrong type or state=" + state);
                }
                System.out.println("[" + getLocalName() + "] Sending REFUSE: " + reply.getContent());
            }

            send(reply);

        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error: " + e.getMessage());
            e.printStackTrace();
        }
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

        System.out.println("[" + getLocalName() + "] Patrol suspended");

        final Location targetLocation = assignedEmergency.getLocation();
        System.out.println("[" + getLocalName() + "] Target: " + targetLocation + " for " + assignedEmergency.getId());

        movement.setTarget(targetLocation, () -> {
            transitionTo(State.ON_SITE);
            System.out.println("[" + getLocalName() + "] ON SITE, securing perimeter for " + assignedEmergency.getId());
            sendLifecycleInform("PERIMETER_SECURED", currentMissionId);
        });
    }

    private void handlePerimeterReleased(ACLMessage msg) {
        String content = msg.getContent();
        String[] parts = content != null ? content.split(":") : new String[0];
        if (parts.length < 2) {
            return;
        }
        String incidentId = parts[1];
        // Invariant: police must release perimeter even if EN_ROUTE (or before ON_SITE).
        boolean matchesAssigned = assignedEmergency != null && incidentId.equals(assignedEmergency.getId());
        boolean matchesCurrent = currentMissionId != null && incidentId.equals(currentMissionId);
        boolean matchesPending = pendingEmergencies.containsKey(incidentId);
        if (!matchesAssigned && !matchesCurrent && !matchesPending) {
            return;
        }

        System.out.println("[" + getLocalName() + "] Perimeter release received for " + incidentId
                + " (state=" + state + "), cancelling and resuming patrol");
        transitionTo(State.IDLE);
        workload = 0;
        assignedEmergency = null;
        currentMissionId = null;
        notifyDispatcherIdle();
        if (movement != null) {
            Location patrolPoint = new Location(random.nextInt(50), random.nextInt(50));
            movement.setTarget(patrolPoint, () -> {
                System.out.println("[" + getLocalName() + "] Patrolling at " + patrolPoint);
            });
        }
    }

    private void handleReject(ACLMessage reject) {
        String eId = reject.getConversationId();
        pendingEmergencies.remove(eId);
        System.out.println("[" + getLocalName() + "] Proposal REJECTED for " + eId + ": " + reject.getContent());

        if (pendingEmergencies.isEmpty()) {
            System.out.println("[" + getLocalName() + "] Continuing patrol");
        } else {
            System.out.println("[" + getLocalName() + "] Still have " + pendingEmergencies.size() + " pending proposal(s)");
        }
    }

    private void transitionTo(State newState) {
        if (state != newState) {
            pendingEmergencies.clear();
            state = newState;
            if (newState != State.IDLE) {
                idleNotified = false;
            }
        }
    }

    private void notifyDispatcherIdle() {
        if (idleNotified) {
            return;
        }
        AID dispatcher = findAgentByService("COORDINATION");
        if (dispatcher == null) {
            dispatcher = new AID("dispatcher", AID.ISLOCALNAME);
        }

        ACLMessage idle = new ACLMessage(ACLMessage.INFORM);
        idle.addReceiver(dispatcher);
        idle.setContent("UNIT_IDLE:" + getLocalName());
        send(idle);
        idleNotified = true;
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
        System.out.println("[" + getLocalName() + "] PoliceAgent terminated");
    }

    // Visible for tests (package-private)
    void testSetMissionForRelease(String incidentId, State s) {
        this.state = s;
        this.workload = 1;
        this.currentMissionId = incidentId;
    }

    // Visible for tests (package-private)
    void testHandleReleaseMessage(String incidentId) {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setContent("RELEASE_PERIMETER:" + incidentId);
        handlePerimeterReleased(msg);
    }

    // Visible for tests (package-private)
    String testState() {
        return state.name();
    }

    // Visible for tests (package-private)
    String testCurrentMissionId() {
        return currentMissionId;
    }
}