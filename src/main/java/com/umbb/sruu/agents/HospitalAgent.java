package com.umbb.sruu.agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;

import java.util.HashSet;
import java.util.Set;

public class HospitalAgent extends Agent {
    private static final int TOTAL_BEDS = 50;
    private int availableBeds = TOTAL_BEDS;
    private Set<String> reservedAdmissions = new HashSet<>();

    @Override
    protected void setup() {
        getContentManager().registerLanguage(new jade.content.lang.sl.SLCodec());
        getContentManager().registerOntology(com.umbb.sruu.ontology.EmergencyOntology.getInstance());
        
        registerInDF();

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive();
                if (msg != null) {
                    handleMessage(msg);
                } else {
                    block();
                }
            }
        });
    }

    private void registerInDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("HOSPITAL");
        sd.setName(getLocalName());
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
            System.out.println("[" + getLocalName() + "] Registered in DF as HOSPITAL, beds=" + availableBeds);
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }

    private void handleMessage(ACLMessage msg) {
        try {
            jade.content.ContentElement ce = getContentManager().extractContent(msg);
            if (ce instanceof com.umbb.sruu.ontology.PatientHandoff) {
                handlePatientHandoffOntology(msg, (com.umbb.sruu.ontology.PatientHandoff) ce);
                return;
            }
        } catch (Exception e) {
            // Fallback to string processing
        }

        String content = msg.getContent();
        if (content == null) return;

        if (content.startsWith("ADMISSION:")) {
            ACLMessage reply = msg.createReply();
            if (availableBeds > 0) {
                availableBeds--;
                String emergencyId = parseEmergencyId(content);
                if (emergencyId != null) {
                    reservedAdmissions.add(emergencyId);
                }
                reply.setPerformative(ACLMessage.INFORM);
                reply.setContent("ADMITTED:" + availableBeds);
                if (availableBeds < TOTAL_BEDS * 0.2) {
                    alertSaturation();
                }
            } else {
                reply.setPerformative(ACLMessage.FAILURE);
                reply.setContent("NO_BEDS_AVAILABLE");
            }
            send(reply);
        } else if (content.startsWith("PATIENT_HANDOFF:")) {
            handlePatientHandoff(msg, content);
        } else if (content.startsWith("BED_STATUS")) {
            ACLMessage reply = msg.createReply();
            reply.setPerformative(ACLMessage.INFORM);
            reply.setContent("BEDS:" + availableBeds + ":" + TOTAL_BEDS);
            send(reply);
        }
    }

    private void handlePatientHandoffOntology(ACLMessage msg, com.umbb.sruu.ontology.PatientHandoff ph) {
        String emergencyId = ph.getEmergencyId() != null ? ph.getEmergencyId() : "UNKNOWN";
        String ambulanceName = ph.getAmbulanceId() != null ? ph.getAmbulanceId() : msg.getSender().getLocalName();

        ACLMessage reply = msg.createReply();
        if (reservedAdmissions.remove(emergencyId)) {
            reply.setPerformative(ACLMessage.INFORM);
            reply.setContent("PATIENT_HANDOFF_COMPLETE:" + emergencyId + ":" + getLocalName() + ":" + availableBeds);
            System.out.println("[" + getLocalName() + "] Handoff complete for " + emergencyId
                    + " from " + ambulanceName + ", beds remaining=" + availableBeds);
        } else if (availableBeds > 0) {
            availableBeds--;
            reply.setPerformative(ACLMessage.INFORM);
            reply.setContent("PATIENT_HANDOFF_COMPLETE:" + emergencyId + ":" + getLocalName() + ":" + availableBeds);
            System.out.println("[" + getLocalName() + "] Handoff complete without prior reservation for "
                    + emergencyId + " from " + ambulanceName + ", beds remaining=" + availableBeds);
            if (availableBeds < TOTAL_BEDS * 0.2) {
                alertSaturation();
            }
        } else {
            reply.setPerformative(ACLMessage.FAILURE);
            reply.setContent("PATIENT_HANDOFF_REJECTED:" + emergencyId + ":NO_BEDS_AVAILABLE");
            System.out.println("[" + getLocalName() + "] Handoff rejected for " + emergencyId + ": no beds available");
        }
        send(reply);
    }

    private void handlePatientHandoff(ACLMessage msg, String content) {
        String[] parts = content.split(":");
        String emergencyId = parts.length >= 2 ? parts[1] : "UNKNOWN";
        String ambulanceName = parts.length >= 3 ? parts[2] : msg.getSender().getLocalName();

        ACLMessage reply = msg.createReply();
        if (reservedAdmissions.remove(emergencyId)) {
            reply.setPerformative(ACLMessage.INFORM);
            reply.setContent("PATIENT_HANDOFF_COMPLETE:" + emergencyId + ":" + getLocalName() + ":" + availableBeds);
            System.out.println("[" + getLocalName() + "] Handoff complete for " + emergencyId
                    + " from " + ambulanceName + ", beds remaining=" + availableBeds);
        } else if (availableBeds > 0) {
            availableBeds--;
            reply.setPerformative(ACLMessage.INFORM);
            reply.setContent("PATIENT_HANDOFF_COMPLETE:" + emergencyId + ":" + getLocalName() + ":" + availableBeds);
            System.out.println("[" + getLocalName() + "] Handoff complete without prior reservation for "
                    + emergencyId + " from " + ambulanceName + ", beds remaining=" + availableBeds);
            if (availableBeds < TOTAL_BEDS * 0.2) {
                alertSaturation();
            }
        } else {
            reply.setPerformative(ACLMessage.FAILURE);
            reply.setContent("PATIENT_HANDOFF_REJECTED:" + emergencyId + ":NO_BEDS_AVAILABLE");
            System.out.println("[" + getLocalName() + "] Handoff rejected for " + emergencyId + ": no beds available");
        }
        send(reply);
    }

    private String parseEmergencyId(String content) {
        String[] parts = content.split(":");
        // Historically: ADMISSION:HOSPITAL_REQUEST:eId:loc
        // Now: ADMISSION:eId
        if (parts.length >= 3 && content.contains("HOSPITAL_REQUEST")) return parts[2];
        return parts.length >= 2 ? parts[1] : null;
    }

    private void alertSaturation() {
        String percent = String.format("%.1f", (availableBeds * 100.0) / TOTAL_BEDS);

        ACLMessage alert = new ACLMessage(ACLMessage.INFORM);
        AID mc = findAgentByService("MEDICAL_COORDINATION");
        if (mc != null) alert.addReceiver(mc);
        AID dispatcher = findAgentByService("COORDINATION");
        if (dispatcher != null) alert.addReceiver(dispatcher);
        alert.setContent("HOSPITAL_SATURATION:" + getLocalName() + ":" + availableBeds + ":" + TOTAL_BEDS + ":" + percent);
        send(alert);
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
    }
}
