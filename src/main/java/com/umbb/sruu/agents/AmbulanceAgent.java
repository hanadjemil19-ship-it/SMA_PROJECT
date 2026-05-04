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

    private void handleCFP(ACLMessage cfp) {
        try {
            HasEmergency hasEmergency = (HasEmergency) getContentManager().extractContent(cfp);
            Emergency emergency = hasEmergency.getEmergency();
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
                status.setUnitId(getLocalName());
                status.setState(state.name());
                status.setCurrentLocation(movement.getCurrentLocation());
                status.setWorkload(workload);
                UnitAvailable unitAvailable = new UnitAvailable();
                unitAvailable.setStatus(status);
                getContentManager().fillContent(reply, unitAvailable);
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

    private void handleAccept(ACLMessage accept) {
        String eId = accept.getConversationId();

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
                        HospitalAssignment ha = (HospitalAssignment) getContentManager().extractContent(msg);
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

    private void returnToBase() {
        transitionTo(State.RETURNING);
        Location basePos = baseLocation();
        movement.setTarget(basePos, () -> {
            sendLifecycleInform("MISSION_COMPLETE", currentMissionId);
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

    private Location baseLocation() {
        if (getLocalName().equals("ambulance-1")) return new Location(5, 5);
        if (getLocalName().equals("ambulance-2")) return new Location(45, 45);
        return new Location(5, 45);
    }

    private void initiateHospitalHandoff() {
        if (assignedHospital == null || currentMissionId == null) {
            returnToBase();
            return;
        }

        System.out.println("[" + getLocalName() + "] Arrived at " + assignedHospital.getName()
                + ", starting patient handoff for " + currentMissionId);

        ACLMessage handoff = new ACLMessage(ACLMessage.REQUEST);
        if (assignedHospitalAid != null) {
            handoff.addReceiver(assignedHospitalAid);
        } else {
            handoff.addReceiver(new AID(assignedHospital.getName(), AID.ISLOCALNAME));
        }
        handoff.setConversationId(currentMissionId + ":HANDOFF");
        handoff.setOntology(EmergencyOntology.getInstance().getName());
        handoff.setLanguage(new SLCodec().getName());
        // Bypass JADE serialization bug by sending it as a simple string message
        handoff.setContent("PATIENT_HANDOFF:" + currentMissionId + ":" + getLocalName());
        send(handoff);

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchConversationId(currentMissionId + ":HANDOFF");
                ACLMessage msg = receive(mt);
                if (msg == null) {
                    block();
                    return;
                }

                if (msg.getPerformative() == ACLMessage.INFORM
                        && msg.getContent() != null
                        && msg.getContent().startsWith("PATIENT_HANDOFF_COMPLETE:")) {
                    System.out.println("[" + getLocalName() + "] Hospital handoff complete for "
                            + currentMissionId + " at " + assignedHospital.getName());
                } else {
                    System.out.println("[" + getLocalName() + "] Hospital handoff not accepted for "
                            + currentMissionId + ": " + msg.getContent());
                }
                sendLifecycleInform("MISSION_COMPLETE", currentMissionId);
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
        System.out.println("[" + getLocalName() + "] AmbulanceAgent terminated");
    }
}
