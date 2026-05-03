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
        return parts.length >= 3 ? parts[2] : null;
    }

    private void alertSaturation() {
        String percent = String.format("%.1f", (availableBeds * 100.0) / TOTAL_BEDS);

        ACLMessage alert = new ACLMessage(ACLMessage.INFORM);
        alert.addReceiver(new AID("medical-coordinator", AID.ISLOCALNAME));
        alert.addReceiver(new AID("dispatcher", AID.ISLOCALNAME));
        alert.setContent("HOSPITAL_SATURATION:" + getLocalName() + ":" + availableBeds + ":" + TOTAL_BEDS + ":" + percent);
        send(alert);
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
