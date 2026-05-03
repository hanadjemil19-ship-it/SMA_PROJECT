package com.umbb.sruu.agents;

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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MedicalCoordinatorAgent extends Agent {

    private List<Hospital> hospitals = new ArrayList<>();
    private Map<String, AID> hospitalAgents = new HashMap<>();

    @Override
    protected void setup() {
        System.out.println("[" + getLocalName() + "] MedicalCoordinator started");

        getContentManager().registerLanguage(new SLCodec());
        getContentManager().registerOntology(EmergencyOntology.getInstance());

        registerInDF();

        // Handle hospital requests
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.REQUEST);
                ACLMessage msg = receive(mt);
                if (msg != null) {
                    handleHospitalRequest(msg);
                } else {
                    block();
                }
            }
        });

        // Periodic discovery and live capacity sync with HospitalAgent
        addBehaviour(new TickerBehaviour(this, 15000) {
            @Override
            protected void onTick() {
                discoverHospitals();
                refreshHospitalCapacities();
            }
        });
    }

    private void discoverHospitals() {
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("HOSPITAL");
        template.addServices(sd);
        try {
            DFAgentDescription[] results = DFService.search(this, template);
            Map<String, Hospital> previousByName = new HashMap<>();
            for (Hospital existing : hospitals) {
                previousByName.put(existing.getName(), existing);
            }
            hospitals.clear();
            hospitalAgents.clear();
            for (DFAgentDescription result : results) {
                String name = result.getName().getLocalName();
                Hospital h = new Hospital();
                h.setName(name);
                h.setLocation(defaultHospitalLocation(name));
                Hospital previous = previousByName.get(name);
                if (previous != null) {
                    h.setTotalBeds(previous.getTotalBeds());
                    h.setAvailableBeds(previous.getAvailableBeds());
                } else {
                    h.setTotalBeds(50);
                    h.setAvailableBeds(50);
                }
                hospitals.add(h);
                hospitalAgents.put(name, result.getName());
            }
            if (!hospitals.isEmpty()) {
                System.out.println("[" + getLocalName() + "] Discovered " + hospitals.size() + " hospital agent(s)");
            }
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }

    private Location defaultHospitalLocation(String name) {
        if (name.endsWith("-1")) return new Location(10, 10);
        if (name.endsWith("-2")) return new Location(40, 40);
        return new Location(25, 5);
    }

    private void registerInDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("MEDICAL_COORDINATION");
        sd.setName("medical-coordinator");
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
            System.out.println("[" + getLocalName() + "] Registered in DF");
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }

    private void handleHospitalRequest(ACLMessage request) {
        try {
            System.out.println("[" + getLocalName() + "] Received hospital request from " + request.getSender().getLocalName());

            String content = request.getContent();
            if (content == null || !content.startsWith("HOSPITAL_REQUEST")) {
                System.out.println("[" + getLocalName() + "] Ignoring non-hospital request: " + content);
                return;
            }

            Location emergencyLoc = parseEmergencyLocation(content);
            discoverHospitals();
            refreshHospitalCapacities();
            Hospital bestHospital = findClosestHospital(emergencyLoc);

            ACLMessage reply = request.createReply();

            if (bestHospital != null && bestHospital.getAvailableBeds() > 0) {
                boolean admitted = requestHospitalAdmission(bestHospital, content);
                if (!admitted) {
                    refreshHospitalCapacities();
                    bestHospital = findClosestHospital(emergencyLoc);
                    admitted = bestHospital != null && bestHospital.getAvailableBeds() > 0
                            && requestHospitalAdmission(bestHospital, content);
                }

                if (!admitted) {
                    reply.setPerformative(ACLMessage.FAILURE);
                    reply.setContent("NO_BEDS_AVAILABLE");
                    alertDispatcherSaturation();
                    send(reply);
                    return;
                }

                reply.setPerformative(ACLMessage.INFORM);
                reply.setOntology(EmergencyOntology.getInstance().getName());
                reply.setLanguage(new SLCodec().getName());

                HospitalAssignment assignment = new HospitalAssignment();
                assignment.setHospital(bestHospital);
                assignment.setEmergencyId("EMERGENCY-XXX");

                getContentManager().fillContent(reply, assignment);

                System.out.println("[" + getLocalName() + "] Assigned CLOSEST hospital: " + bestHospital.getName() +
                        " (distance=" + String.format("%.1f", euclideanDistance(emergencyLoc, bestHospital.getLocation())) + ")" +
                        ", beds remaining: " + bestHospital.getAvailableBeds());

            } else {
                reply.setPerformative(ACLMessage.FAILURE);
                reply.setContent("NO_BEDS_AVAILABLE");
                System.out.println("[" + getLocalName() + "] ALERT: All hospitals saturated!");
                alertDispatcherSaturation();
            }

            send(reply);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Location parseEmergencyLocation(String content) {
        try {
            String[] parts = content.split(":");
            if (parts.length >= 3) {
                String[] coords = parts[2].split(",");
                if (coords.length == 2) {
                    return new Location(Integer.parseInt(coords[0]), Integer.parseInt(coords[1]));
                }
            }
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error parsing location: " + e.getMessage());
        }
        return new Location(25, 25);
    }

    private Hospital findClosestHospital(Location emergencyLoc) {
        Hospital closest = null;
        double minDistance = Double.MAX_VALUE;

        for (Hospital h : hospitals) {
            if (h.getAvailableBeds() > 0) {
                double dist = euclideanDistance(emergencyLoc, h.getLocation());
                if (dist < minDistance) {
                    minDistance = dist;
                    closest = h;
                }
            }
        }
        return closest;
    }

    private double euclideanDistance(Location loc1, Location loc2) {
        int dx = loc1.getX() - loc2.getX();
        int dy = loc1.getY() - loc2.getY();
        return Math.sqrt(dx * dx + dy * dy);
    }

    private void alertDispatcherSaturation() {
        ACLMessage alert = new ACLMessage(ACLMessage.INFORM);
        alert.addReceiver(new AID("dispatcher", AID.ISLOCALNAME));
        alert.setContent("HOSPITAL_SATURATION_ALERT");
        send(alert);
    }

    private boolean requestHospitalAdmission(Hospital hospital, String requestContent) {
        AID hospitalAid = hospitalAgents.get(hospital.getName());
        if (hospitalAid == null) return false;
        ACLMessage admission = new ACLMessage(ACLMessage.REQUEST);
        admission.addReceiver(hospitalAid);
        String convId = "ADMISSION-" + UUID.randomUUID();
        admission.setConversationId(convId);
        admission.setContent("ADMISSION:" + requestContent);
        send(admission);

        MessageTemplate mt = MessageTemplate.and(
                MessageTemplate.MatchConversationId(convId),
                MessageTemplate.MatchSender(hospitalAid)
        );
        ACLMessage ack = blockingReceive(mt, 1500);
        if (ack == null || ack.getPerformative() != ACLMessage.INFORM) {
            return false;
        }

        String[] parts = ack.getContent() != null ? ack.getContent().split(":") : new String[0];
        if (parts.length >= 2 && "ADMITTED".equals(parts[0])) {
            try {
                hospital.setAvailableBeds(Integer.parseInt(parts[1]));
            } catch (NumberFormatException ignored) {
            }
        }
        return true;
    }

    private void refreshHospitalCapacities() {
        for (Hospital hospital : hospitals) {
            AID aid = hospitalAgents.get(hospital.getName());
            if (aid == null) continue;

            ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
            req.addReceiver(aid);
            String convId = "BED_STATUS-" + UUID.randomUUID();
            req.setConversationId(convId);
            req.setContent("BED_STATUS");
            send(req);

            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchConversationId(convId),
                    MessageTemplate.MatchSender(aid)
            );
            ACLMessage res = blockingReceive(mt, 800);
            if (res == null || res.getPerformative() != ACLMessage.INFORM || res.getContent() == null) continue;

            String[] parts = res.getContent().split(":");
            if (parts.length >= 3 && "BEDS".equals(parts[0])) {
                try {
                    hospital.setAvailableBeds(Integer.parseInt(parts[1]));
                    hospital.setTotalBeds(Integer.parseInt(parts[2]));
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException e) {
            e.printStackTrace();
        }
        System.out.println("[" + getLocalName() + "] MedicalCoordinator terminated");
    }
}
