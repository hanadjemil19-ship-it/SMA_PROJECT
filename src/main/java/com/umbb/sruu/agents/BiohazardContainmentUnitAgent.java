package com.umbb.sruu.agents;

import com.umbb.sruu.behaviours.MovementBehaviour;
import com.umbb.sruu.ontology.Emergency;
import com.umbb.sruu.ontology.EmergencyOntology;
import com.umbb.sruu.ontology.HasEmergency;
import com.umbb.sruu.ontology.Location;
import com.umbb.sruu.ontology.UnitAvailable;
import com.umbb.sruu.ontology.UnitStatus;
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

public class BiohazardContainmentUnitAgent extends Agent {

    public enum State {
        IDLE, EN_ROUTE, ON_SITE, DECONTAMINATING, RETURNING
    }

    private static final int SUIT_INTEGRITY_THRESHOLD = 25;
    private static final int SUIT_DAMAGE_PER_INCIDENT = 20;
    private static final int DECONTAMINATION_TIME_MS = 10000;

    private MovementBehaviour movement;
    private State state = State.IDLE;
    private int workload = 0;
    private int suitIntegrity = 100;

    private Map<String, Emergency> pendingEmergencies = new HashMap<>();
    private Emergency assignedEmergency;
    private String currentMissionId;

    @Override
    protected void setup() {
        System.out.println("[" + getLocalName() + "] BiohazardContainmentUnitAgent started");

        getContentManager().registerLanguage(new SLCodec());
        getContentManager().registerOntology(EmergencyOntology.getInstance());

        movement = new MovementBehaviour(this, 200, new Location(35, 10), getLocalName());
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
                ACLMessage msg = receive(MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL));
                if (msg != null) handleAccept(msg);
                else block();
            }
        });

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive(MessageTemplate.MatchPerformative(ACLMessage.REJECT_PROPOSAL));
                if (msg != null) handleReject(msg);
                else block();
            }
        });
    }

    private void registerInDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());

        ServiceDescription biohazard = new ServiceDescription();
        biohazard.setType("BIOHAZARD");
        biohazard.setName("bcu-biohazard-" + getLocalName());
        dfd.addServices(biohazard);

        ServiceDescription cryogenic = new ServiceDescription();
        cryogenic.setType("CRYOGENIC_LEAK");
        cryogenic.setName("bcu-cryogenic-" + getLocalName());
        dfd.addServices(cryogenic);

        ServiceDescription medicalSupport = new ServiceDescription();
        medicalSupport.setType("MEDICAL");
        medicalSupport.setName("bcu-medical-support-" + getLocalName());
        dfd.addServices(medicalSupport);

        try {
            DFService.register(this, dfd);
            System.out.println("[" + getLocalName() + "] Registered in DF as BIOHAZARD and CRYOGENIC_LEAK");
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }

    private void handleCFP(ACLMessage cfp) {
        try {
            HasEmergency hasEmergency = (HasEmergency) getContentManager().extractContent(cfp);
            Emergency emergency = hasEmergency.getEmergency();
            String eId = cfp.getConversationId();

            ACLMessage reply = cfp.createReply();

            if (!canHandle(emergency)) {
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("Unsupported incident type: " + emergency.getType());
                send(reply);
                return;
            }

            if (suitIntegrity <= SUIT_INTEGRITY_THRESHOLD) {
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("LOW_SUIT_INTEGRITY");
                send(reply);
                return;
            }

            if (state == State.IDLE && workload == 0) {
                pendingEmergencies.put(eId, emergency);

                UnitStatus status = new UnitStatus();
                status.setUnitId(getLocalName());
                status.setState(state.name());
                status.setCurrentLocation(movement.getCurrentLocation());
                status.setWorkload(workload);

                UnitAvailable available = new UnitAvailable();
                available.setStatus(status);

                reply.setPerformative(ACLMessage.PROPOSE);
                getContentManager().fillContent(reply, available);
                System.out.println("[" + getLocalName() + "] Sending PROPOSE for " + eId);
            } else {
                reply.setPerformative(ACLMessage.REFUSE);
                reply.setContent("Busy: state=" + state + ", workload=" + workload);
            }

            send(reply);
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error handling CFP: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private boolean canHandle(Emergency emergency) {
        return emergency.getType().equals("BIOHAZARD")
                || emergency.getType().equals("CRYOGENIC_LEAK")
                || emergency.getType().equals("MEDICAL");
    }

    private void handleAccept(ACLMessage accept) {
        String eId = accept.getConversationId();

        if (state != State.IDLE) {
            ACLMessage failure = accept.createReply();
            failure.setPerformative(ACLMessage.FAILURE);
            failure.setContent("ALREADY_ASSIGNED");
            send(failure);
            pendingEmergencies.clear();
            return;
        }

        assignedEmergency = pendingEmergencies.get(eId);
        pendingEmergencies.clear();

        if (assignedEmergency == null) {
            System.err.println("[" + getLocalName() + "] No stored emergency for accepted assignment " + eId);
            return;
        }

        currentMissionId = assignedEmergency.getId();
        transitionTo(State.EN_ROUTE);
        workload = 1;

        Location target = assignedEmergency.getLocation();
        System.out.println("[" + getLocalName() + "] Assigned to " + assignedEmergency.getId()
                + ", moving to " + target);

        movement.setTarget(target, () -> {
            transitionTo(State.ON_SITE);
            sendLifecycleInform("MISSION_ARRIVED", currentMissionId);
            System.out.println("[" + getLocalName() + "] ON SITE handling " + assignedEmergency.getType()
                    + " incident " + assignedEmergency.getId());

            addBehaviour(new WakerBehaviour(this, 5000) {
                @Override
                protected void onWake() {
                    suitIntegrity -= SUIT_DAMAGE_PER_INCIDENT;
                    transitionTo(State.DECONTAMINATING);
                    System.out.println("[" + getLocalName() + "] Containment complete, decontaminating...");
                    startDecontamination();
                }
            });
        });
    }

    private void startDecontamination() {
        addBehaviour(new WakerBehaviour(this, DECONTAMINATION_TIME_MS) {
            @Override
            protected void onWake() {
                transitionTo(State.RETURNING);
                movement.setTarget(new Location(35, 10), () -> {
                    sendLifecycleInform("MISSION_COMPLETE", currentMissionId);
                    if (suitIntegrity <= SUIT_INTEGRITY_THRESHOLD) {
                        suitIntegrity = 100;
                        System.out.println("[" + getLocalName() + "] Protective suit replaced, integrity restored");
                    }

                    transitionTo(State.IDLE);
                    workload = 0;
                    assignedEmergency = null;
                    currentMissionId = null;
                    notifyDispatcherIdle();
                    System.out.println("[" + getLocalName() + "] Back at base, READY");
                });
            }
        });
    }

    private void handleReject(ACLMessage reject) {
        String eId = reject.getConversationId();
        pendingEmergencies.remove(eId);
        System.out.println("[" + getLocalName() + "] Proposal rejected for " + eId + ": " + reject.getContent());

        if (pendingEmergencies.isEmpty()) {
            transitionTo(State.IDLE);
            workload = 0;
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
        idle.addReceiver(new AID("dispatcher", AID.ISLOCALNAME));
        idle.setContent("UNIT_IDLE:" + getLocalName());
        send(idle);
    }

    private void sendLifecycleInform(String event, String emergencyId) {
        if (emergencyId == null) return;
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.addReceiver(new AID("dispatcher", AID.ISLOCALNAME));
        msg.setContent(event + ":" + getLocalName() + ":" + emergencyId);
        send(msg);
    }

    private void sendAbortToDispatcher(String emergencyId, String reason) {
        ACLMessage abort = new ACLMessage(ACLMessage.INFORM);
        abort.addReceiver(new AID("dispatcher", AID.ISLOCALNAME));
        abort.setContent("UNIT_ABORT:" + getLocalName() + ":" + reason + ":" + emergencyId);
        send(abort);
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
}
