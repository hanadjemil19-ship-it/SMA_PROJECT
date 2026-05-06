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
                MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                        MessageTemplate.MatchOntology(EmergencyOntology.getInstance().getName())
                );
                ACLMessage msg = receive(mt);
                if (msg != null) handlePerimeterReleased(msg);
                else block();
            }
        });
    }
    // setup(): Starts the agent at a random grid position, registers in the DF, and adds:
    //   - A TickerBehaviour (every 5 s) that moves the idle officer to a random patrol point.
    //   - CyclicBehaviours for CFP (propose/refuse), ACCEPT_PROPOSAL (mission start),
    //     REJECT_PROPOSAL (continue patrol), and RELEASE_PERIMETER (return to idle).

    private void registerInDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());

        ServiceDescription sd1 = new ServiceDescription();
        sd1.setType("CROWD_CONTROL");
        sd1.setName("police-crowd");
        dfd.addServices(sd1);

        ServiceDescription sd2 = new ServiceDescription();
        sd2.setType("PERIMETER");
        sd2.setName("police-perimeter");
        dfd.addServices(sd2);

        try {
            DFService.register(this, dfd);
            System.out.println("[" + getLocalName() + "] Registered in DF as CROWD_CONTROL and PERIMETER");
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }
    // registerInDF(): Publishes this officer under two service types in the DF:
    // "CROWD_CONTROL" (for the dispatcher's perimeter Contract-Net) and "PERIMETER"
    // (secondary lookup).  Dual registration lets the dispatcher find police units for
    // fire/collapse incidents that require crowd management.

    private void handleCFP(ACLMessage cfp) {
        try {
            Emergency emergency = (Emergency) cfp.getContentObject();
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
                status.setUnitName(getLocalName());
                status.setState(state.name());
                status.setPosition(movement.getCurrentLocation());
                status.setWorkload(workload);

                cfp.createReply();
                reply.setContent("PROPOSE:" + getLocalName() + ":" + state.name());
                reply.setContentObject(status);
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
    // handleCFP(): Handles a Call-For-Proposal from the dispatcher.
    // Police only propose for FIRE or STRUCTURAL_COLLAPSE incidents while IDLE with
    // zero workload — these are the scenarios that require crowd control / perimeter
    // management.  Any other situation (wrong type, busy, non-zero workload) results
    // in a REFUSE with an explanatory reason string.

    private void handleAccept(ACLMessage accept) {
        Object contentObj = null;
        try {
            contentObj = accept.getContentObject();
        } catch (Exception e) {
            String content = accept.getContent();
            if (content != null && content.startsWith("ASSIGNMENT:")) {
                // handle legacy string assignment
            }
        }

        String eId = accept.getConversationId();
        if (contentObj instanceof Assignment assignment) {
            eId = assignment.getEmergencyId();
        }
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
    // handleAccept(): Called when the dispatcher selects this officer as the CNP winner.
    // Rejects if already busy (FAILURE reply). Otherwise retrieves the stored emergency,
    // transitions to EN_ROUTE (suspending patrol), navigates to the scene, and on arrival
    // transitions to ON_SITE and sends PERIMETER_SECURED to the dispatcher.

    private void handlePerimeterReleased(ACLMessage msg) {
        String incidentId = null;
        try {
            if (msg.getContentObject() instanceof ReleasePerimeter rp) {
                incidentId = rp.getEmergencyId();
            } else {
                return;
            }
        } catch (Exception e) {
            return;
        }
        if (incidentId == null) {
            return;
        }
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
    // handlePerimeterReleased(): Called when the dispatcher sends RELEASE_PERIMETER or
    // PERIMETER_RELEASED (e.g. because the primary incident is resolved or aborted).
    // Validates the incident ID against current/pending assignments, then transitions back
    // to IDLE, notifies the dispatcher, and resumes random patrol movement.

    private void handleReject(ACLMessage reject) {
        String eId = reject.getConversationId();
        pendingEmergencies.remove(eId);
        String reason = reject.getContent();
        try {
            if (reject.getContentObject() instanceof Assignment a) {
                reason = "assigned to " + a.getUnitName();
            }
        } catch (Exception e) {}
        System.out.println("[" + getLocalName() + "] Proposal REJECTED for " + eId + ": " + reason);

        if (pendingEmergencies.isEmpty()) {
            System.out.println("[" + getLocalName() + "] Continuing patrol");
        } else {
            System.out.println("[" + getLocalName() + "] Still have " + pendingEmergencies.size() + " pending proposal(s)");
        }
    }
    // handleReject(): Removes the rejected emergency from pendingEmergencies.
    // The officer simply resumes patrol — no state change needed since it was never
    // assigned and remains IDLE throughout.

    private void transitionTo(State newState) {
        if (state != newState) {
            pendingEmergencies.clear();
            state = newState;
            if (newState != State.IDLE) {
                idleNotified = false;
            }
        }
    }
    // transitionTo(): Changes the agent's internal state and clears any pending proposals.
    // Also resets the idleNotified flag when transitioning away from IDLE so the next
    // idle notification is sent exactly once after the mission ends.

    private void notifyDispatcherIdle() {
        if (idleNotified) {
            return;
        }
        AID dispatcher = findAgentByService("COORDINATION");
        if (dispatcher == null) {
            dispatcher = new AID("dispatcher", AID.ISLOCALNAME);
        }

        ACLMessage idle = new ACLMessage(ACLMessage.INFORM);
        idle.setOntology(EmergencyOntology.getInstance().getName());
        idle.setLanguage(new SLCodec().getName());
        idle.addReceiver(dispatcher);
        try {
            UnitStatus status = new UnitStatus();
            status.setUnitName(getLocalName());
            status.setState("IDLE");
            idle.setContent("IDLE:" + getLocalName());
            idle.setContentObject(status);
            send(idle);
            idleNotified = true;
            System.out.println("[" + getLocalName() + "] Sent IDLE notification to dispatcher");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    // notifyDispatcherIdle(): Sends "UNIT_IDLE:<name>" to the dispatcher exactly once
    // (guarded by idleNotified) so it removes this officer from its busy list and can
    // re-dispatch any queued incidents.  Falls back to a direct AID if the DF lookup fails.

    private void sendLifecycleInform(String event, String emergencyId) {
        if (emergencyId == null) return;
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setOntology(EmergencyOntology.getInstance().getName());
        msg.setLanguage(new SLCodec().getName());
        AID dispatcher = findAgentByService("COORDINATION");
        if (dispatcher != null) msg.addReceiver(dispatcher);
        try {
            if ("PERIMETER_SECURED".equals(event)) {
                PerimeterSecured ps = new PerimeterSecured();
                ps.setEmergencyId(emergencyId);
                ps.setUnitName(getLocalName());
                msg.setContent("PERIMETER_SECURED:" + emergencyId + ":" + getLocalName());
                msg.setContentObject(ps);
            }
            send(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    // sendLifecycleInform(): Sends a plain-text lifecycle event (e.g. PERIMETER_SECURED)
    // with the unit name and emergency ID to the dispatcher so it can record the event
    // in the audit log and update the incident status.

    private AID findAgentByService(String serviceType) {
        return com.umbb.sruu.utils.AgentUtils.findAgentByService(this, serviceType);
    }
    // findAgentByService(): DF lookup helper — returns the AID of the first agent offering
    // the given service type, or null if no match is found at this moment.

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException e) {
            e.printStackTrace();
        }
        System.out.println("[" + getLocalName() + "] PoliceAgent terminated");
    }
    // takeDown(): Deregisters from the DF on shutdown so the dispatcher stops sending CFPs
    // to this destroyed agent instance.

    // Visible for tests (package-private)
    void testSetMissionForRelease(String incidentId, State s) {
        this.state = s;
        this.workload = 1;
        this.currentMissionId = incidentId;
    }
    // testSetMissionForRelease(): Test helper that directly injects a mission ID and state
    // so unit tests can verify handlePerimeterReleased() without running a full CNP cycle.

    // Visible for tests (package-private)
    void testHandleReleaseMessage(String incidentId) {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        try {
            ReleasePerimeter rp = new ReleasePerimeter();
            rp.setEmergencyId(incidentId);
            msg.setContentObject(rp);
        } catch (Exception e) {}
        handlePerimeterReleased(msg);
    }
    // testHandleReleaseMessage(): Test helper that constructs a synthetic RELEASE_PERIMETER
    // message and passes it directly to handlePerimeterReleased() for isolated testing.

    // Visible for tests (package-private)
    String testState() {
        return state.name();
    }
    // testState(): Returns the current state name for use in unit test assertions.

    // Visible for tests (package-private)
    String testCurrentMissionId() {
        return currentMissionId;
    }
    // testCurrentMissionId(): Returns the current mission ID for use in unit test assertions,
    // allowing tests to verify that the mission is cleared after a release.
}