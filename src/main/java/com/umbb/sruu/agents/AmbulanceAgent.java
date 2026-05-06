package com.umbb.sruu.agents;

import com.umbb.sruu.behaviours.MovementBehaviour;
import com.umbb.sruu.ontology.*;
import jade.content.lang.sl.SLCodec;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.HashMap;
import java.util.Map;

public class AmbulanceAgent extends Agent {

    public enum State { IDLE, EN_ROUTE, ON_SITE, HOSPITAL_TRANSPORT, RETURNING }

    private MovementBehaviour movement;
    private State state = State.IDLE;
    private int workload = 0;

    private Map<String, Emergency> pendingEmergencies = new HashMap<>();
    private Emergency assignedEmergency = null;
    private Hospital assignedHospital = null;
    private AID assignedHospitalAid = null;
    private String currentMissionId = null;

    @Override
    protected void setup() {
        System.out.println("[" + getLocalName() + "] AmbulanceAgent started");

        getContentManager().registerLanguage(new SLCodec());
        getContentManager().registerOntology(EmergencyOntology.getInstance());

        Location startPos = baseLocation();
        movement = new MovementBehaviour(this, 200, startPos, getLocalName());
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
    // setup(): Boots the agent at its home base position, attaches a MovementBehaviour for
    // grid navigation, registers in the DF as "MEDICAL", then adds three CyclicBehaviours
    // to drive the Contract-Net lifecycle: CFP (propose/refuse), ACCEPT_PROPOSAL (start
    // mission), and REJECT_PROPOSAL (stay idle).

    private void registerInDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("MEDICAL");
        sd.setName("ambulance-service-" + getLocalName());
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
            System.out.println("[" + getLocalName() + "] Registered in DF as 'MEDICAL'");
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }
    // registerInDF(): Publishes this ambulance in the JADE Directory Facilitator under
    // service type "MEDICAL" so the dispatcher can discover it for medical emergency CFPs.

    private void handleCFP(ACLMessage cfp) {
        try {
            Emergency emergency = (Emergency) cfp.getContentObject();
            String eId = cfp.getConversationId();

            // FIX: Only store pending if truly idle
            if (state == State.IDLE && workload == 0) {
                pendingEmergencies.put(eId, emergency);
            }

            System.out.println("[" + getLocalName() + "] Received CFP for: " + emergency.getId());

            ACLMessage reply = cfp.createReply();

            // FIX: Strict idle check - reject if already assigned
            if (state == State.IDLE && workload == 0) {
                reply.setPerformative(ACLMessage.PROPOSE);
                UnitStatus status = new UnitStatus();
                status.setUnitName(getLocalName());
                status.setState(state.name());
                status.setPosition(movement.getCurrentLocation());
                status.setWorkload(workload);
                reply.setContent("PROPOSE:" + getLocalName() + ":" + state.name());
                reply.setContentObject(status);
                System.out.println("[" + getLocalName() + "] Sending PROPOSE (state=" + state + ")");
            } else {
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("Busy: state=" + state + ", workload=" + workload);
                System.out.println("[" + getLocalName() + "] Sending REFUSE (busy)");
            }
            send(reply);
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    // handleCFP(): Responds to a Call-For-Proposal from the dispatcher.
    // If IDLE with zero workload, stores the emergency and replies PROPOSE with a UnitStatus.
    // Otherwise replies REFUSE, ensuring a busy ambulance is never double-assigned.

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

        // FIX: Reject acceptance if already busy (prevents double-assignment)
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

        final Location targetLoc = assignedEmergency.getLocation();

        movement.setTarget(targetLoc, () -> {
            transitionTo(State.ON_SITE);
            sendLifecycleInform("MISSION_ARRIVED", currentMissionId);
            System.out.println("[" + getLocalName() + "] ON SITE at " + targetLoc + ", providing medical aid...");
            requestHospitalAssignment();
        });
    }
    // handleAccept(): Called when the dispatcher selects this ambulance as the CNP winner.
    // Guards against double-assignment (sends FAILURE if already busy), then retrieves the
    // stored Emergency, transitions to EN_ROUTE, and navigates to the scene. On arrival it
    // transitions to ON_SITE, notifies the dispatcher, and triggers requestHospitalAssignment().

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
            System.out.println("[" + getLocalName() + "] Remaining IDLE, ready for next call");
            transitionTo(State.IDLE);
            workload = 0;
        } else {
            System.out.println("[" + getLocalName() + "] Still have " + pendingEmergencies.size() + " pending proposal(s)");
        }
    }
    // handleReject(): Called when the dispatcher chose a different unit for this CFP round.
    // Removes the rejected emergency from pendingEmergencies; if the map is empty the
    // ambulance explicitly transitions back to IDLE with workload=0.

    private void requestHospitalAssignment() {
        System.out.println("[" + getLocalName() + "] Requesting hospital from MedicalCoordinator...");
        ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
        AID mc = findAgentByService("MEDICAL_COORDINATION");
        if (mc != null) request.addReceiver(mc);
        request.setOntology(EmergencyOntology.getInstance().getName());
        request.setLanguage(new SLCodec().getName());
        HospitalRequest hr = new HospitalRequest();
        hr.setEmergencyId(assignedEmergency.getId());
        hr.setEmergencyLocation(assignedEmergency.getLocation());
        try {
            getContentManager().fillContent(request, hr);
        } catch (Exception e) {
            e.printStackTrace();
        }
        send(request);

        final AID mcTarget = findAgentByService("MEDICAL_COORDINATION");

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.INFORM);
                if (mcTarget != null) {
                    mt = MessageTemplate.and(mt, MessageTemplate.MatchSender(mcTarget));
                }
                ACLMessage msg = receive(mt);
                if (msg != null) {
                    try {
                        HospitalAssignment ha = (HospitalAssignment) msg.getContentObject();
                        assignedHospital = ha.getHospital();
                        assignedHospitalAid = ha.getHospitalAid();
                        System.out.println("[" + getLocalName() + "] Hospital assigned: " + assignedHospital.getName());
                        transitionTo(State.HOSPITAL_TRANSPORT);
                        System.out.println("[" + getLocalName() + "] Transporting patient to " + assignedHospital.getName());
                        movement.setTarget(assignedHospital.getLocation(), () -> {
                            initiateHospitalHandoff();
                        });
                        removeBehaviour(this);
                    } catch (Exception e) {
                        System.out.println("[" + getLocalName() + "] Failed to parse hospital assignment: " + e.getMessage());
                        returnToBase();
                        removeBehaviour(this);
                    }
                } else {
                    MessageTemplate failMt = MessageTemplate.MatchPerformative(ACLMessage.FAILURE);
                    if (mcTarget != null) {
                        failMt = MessageTemplate.and(failMt, MessageTemplate.MatchSender(mcTarget));
                    }
                    ACLMessage failMsg = receive(failMt);
                    if (failMsg != null) {
                        System.out.println("[" + getLocalName() + "] No hospital beds available! Returning to base.");
                        returnToBase();
                        removeBehaviour(this);
                    } else {
                        block();
                    }
                }
            }
        });
    }
    // requestHospitalAssignment(): Sends a HospitalRequest ontology object to the
    // MedicalCoordinatorAgent, then installs an inline CyclicBehaviour to wait asynchronously
    // for the reply. On INFORM success the ambulance stores the assigned hospital and moves
    // there (HOSPITAL_TRANSPORT). On FAILURE (no beds) it falls back to returnToBase().

    private void returnToBase() {
        transitionTo(State.RETURNING);
        Location basePos = baseLocation();
        final String safeMissionId = currentMissionId;
        movement.setTarget(basePos, () -> {
            if (safeMissionId != null) {
                sendLifecycleInform("MISSION_COMPLETE", safeMissionId);
            }
            transitionTo(State.IDLE);
            workload = 0;
            assignedEmergency = null;
            assignedHospital = null;
            assignedHospitalAid = null;
            currentMissionId = null;
            notifyDispatcherIdle();
            System.out.println("[" + getLocalName() + "] Back at base, READY for next call");
        });
    }
    // returnToBase(): Transitions the ambulance to RETURNING and navigates back to base.
    // On arrival it sends MISSION_COMPLETE, resets all mission state, and sends UNIT_IDLE
    // to the dispatcher so queued incidents can be retried with this unit.

    private Location baseLocation() {
        if (getLocalName().equals("ambulance-1")) return new Location(5, 5);
        if (getLocalName().equals("ambulance-2")) return new Location(45, 45);
        return new Location(5, 45);
    }
    // baseLocation(): Returns this ambulance's fixed home position on the 50×50 grid.
    // ambulance-1 = (5,5) north-west, ambulance-2 = (45,45) south-east, others = (5,45).

    private void initiateHospitalHandoff() {
        if (assignedHospital == null || currentMissionId == null) {
            returnToBase();
            return;
        }

        System.out.println("[" + getLocalName() + "] Arrived at " + assignedHospital.getName()
                + ", starting patient handoff for " + currentMissionId);

        ACLMessage handoff = new ACLMessage(ACLMessage.REQUEST);
        AID mc = findAgentByService("MEDICAL_COORDINATION");
        if (mc != null) handoff.addReceiver(mc);
        handoff.setConversationId(currentMissionId + ":HANDOFF");
        handoff.setOntology(EmergencyOntology.getInstance().getName());
        handoff.setLanguage(new SLCodec().getName());
        try {
            PatientHandoff ph = new PatientHandoff();
            ph.setEmergencyId(currentMissionId);
            ph.setUnitName(getLocalName());
            handoff.setContentObject(ph);
            send(handoff);
        } catch (Exception e) {
            e.printStackTrace();
        }

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchConversationId(currentMissionId + ":HANDOFF");
                ACLMessage msg = receive(mt);
                if (msg == null) {
                    block();
                    return;
                }

                try {
                    Object content = msg.getContentObject();
                    if (msg.getPerformative() == ACLMessage.INFORM && content instanceof PatientHandoff) {
                        System.out.println("[" + getLocalName() + "] Hospital handoff complete for "
                                + currentMissionId + " at " + (assignedHospital != null ? assignedHospital.getName() : "assigned hospital"));
                    } else {
                        System.out.println("[" + getLocalName() + "] Hospital handoff not accepted or failed for "
                                + currentMissionId);
                    }
                } catch (Exception e) {
                    System.out.println("[" + getLocalName() + "] Error reading handoff reply: " + e.getMessage());
                }
                
                final String safeMissionId = currentMissionId;
                if (safeMissionId != null) {
                    sendLifecycleInform("MISSION_COMPLETE", safeMissionId);
                }
                transitionTo(State.IDLE);
                workload = 0;
                assignedEmergency = null;
                assignedHospital = null;
                assignedHospitalAid = null;
                currentMissionId = null;
                notifyDispatcherIdle();

                Location basePos = baseLocation();
                movement.setTarget(basePos, () -> System.out.println("[" + getLocalName() + "] Back at base, READY for next call"));
                removeBehaviour(this);
            }
        });
    }
    // initiateHospitalHandoff(): Sends a plain-text PATIENT_HANDOFF request to the hospital
    // (using text to avoid JADE serialisation bugs) and waits for a PATIENT_HANDOFF_COMPLETE
    // reply on the same conversation ID. Regardless of the hospital's answer the ambulance
    // sends MISSION_COMPLETE to the dispatcher, resets to IDLE, and heads back to base.

    private void transitionTo(State newState) {
        if (state != newState) {
            pendingEmergencies.clear();
            state = newState;
        }
    }
    // transitionTo(): Changes the internal state and clears pending proposals to prevent
    // stale CFP entries from being processed after the ambulance changes mission phase.

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
            idle.setContent("IDLE:" + getLocalName());
            idle.setContentObject(status);
            send(idle);
            System.out.println("[" + getLocalName() + "] Sent IDLE notification to dispatcher");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    // notifyDispatcherIdle(): Sends "UNIT_IDLE:<name>" to the dispatcher so it can remove
    // this unit from its busy list and attempt to re-dispatch any queued incidents.

    private void sendLifecycleInform(String event, String emergencyId) {
        if (emergencyId == null) return;
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setOntology(EmergencyOntology.getInstance().getName());
        msg.setLanguage(new SLCodec().getName());
        AID dispatcher = findAgentByService("COORDINATION");
        if (dispatcher != null) msg.addReceiver(dispatcher);
        try {
            if ("MISSION_ARRIVED".equals(event)) {
                MissionArrived arr = new MissionArrived();
                arr.setEmergencyId(emergencyId);
                arr.setUnitName(getLocalName());
                msg.setContent("MISSION_ARRIVED:" + getLocalName() + ":" + emergencyId);
                msg.setContentObject(arr);
            } else if ("MISSION_COMPLETE".equals(event)) {
                MissionComplete mc = new MissionComplete();
                mc.setEmergencyId(emergencyId);
                mc.setUnitName(getLocalName());
                mc.setSuccess(true);
                msg.setContent("MISSION_COMPLETE:" + emergencyId + ":" + getLocalName());
                msg.setContentObject(mc);
            }
            send(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    // sendLifecycleInform(): Sends a structured lifecycle event (e.g. MISSION_ARRIVED,
    // MISSION_COMPLETE) to the dispatcher so it can track incident progress and update
    // the incident status machine accordingly.

    private AID findAgentByService(String serviceType) {
        return com.umbb.sruu.utils.AgentUtils.findAgentByService(this, serviceType);
    }
    // findAgentByService(): Performs a DF lookup by service type and returns the AID of
    // the first matching agent, or null if no agent offers that service at the moment.

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException e) {
            e.printStackTrace();
        }
        System.out.println("[" + getLocalName() + "] AmbulanceAgent terminated");
    }
    // takeDown(): Deregisters from the DF on shutdown so the dispatcher stops sending CFPs
    // to this agent instance after it has been destroyed.
}
