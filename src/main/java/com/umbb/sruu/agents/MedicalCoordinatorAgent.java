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
import java.util.List;

public class MedicalCoordinatorAgent extends Agent {

    private List<Hospital> hospitals = new ArrayList<>();

    @Override
    protected void setup() {
        System.out.println("[" + getLocalName() + "] MedicalCoordinator started");

        getContentManager().registerLanguage(new SLCodec());
        getContentManager().registerOntology(EmergencyOntology.getInstance());

        // Initialize 4 internal hospitals
        initHospitals();

        registerInDF();

        // Behaviour 1 : requêtes d'affectation hospitalière et de handoff venant des ambulances
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.REQUEST);
                ACLMessage msg = receive(mt);
                if (msg != null) {
                    try {
                        // Use extractContent for predicates (HospitalRequest) 
                        // and getContentObject for serialized concepts (PatientHandoff)
                        Object content;
                        try {
                            content = getContentManager().extractContent(msg);
                        } catch (Exception e) {
                            content = msg.getContentObject();
                        }

                        if (content instanceof PatientHandoff ph) {
                            handlePatientHandoff(msg, ph);
                        } else if (content instanceof HospitalRequest hr) {
                            handleAmbulanceRequest(msg, hr);
                        }
                    } catch (Exception e) {
                        System.err.println("[" + getLocalName() + "] Error parsing request: " + e.getMessage());
                    }
                } else {
                    block();
                }
            }
        });

        // Behaviour 2 : résumé périodique des capacités
        addBehaviour(new TickerBehaviour(this, 15000) {
            @Override
            protected void onTick() {
                printCapacitySummary();
            }
        });
    }

    private void initHospitals() {
        Hospital h1 = new Hospital(); h1.setName("hospital-north"); h1.setLocation(new Location(10, 5)); h1.setTotalBeds(20); h1.setAvailableBeds(20); hospitals.add(h1);
        Hospital h2 = new Hospital(); h2.setName("hospital-south"); h2.setLocation(new Location(40, 45)); h2.setTotalBeds(20); h2.setAvailableBeds(20); hospitals.add(h2);
        Hospital h3 = new Hospital(); h3.setName("hospital-east"); h3.setLocation(new Location(45, 10)); h3.setTotalBeds(15); h3.setAvailableBeds(15); hospitals.add(h3);
        Hospital h4 = new Hospital(); h4.setName("hospital-west"); h4.setLocation(new Location(5, 40)); h4.setTotalBeds(15); h4.setAvailableBeds(15); hospitals.add(h4);
    }

    private void handleAmbulanceRequest(ACLMessage request, HospitalRequest hr) {
        try {
            System.out.println("[" + getLocalName() + "] Hospital request from " + request.getSender().getLocalName());

            Location emergencyLoc = hr.getEmergencyLocation();
            String   eId          = hr.getEmergencyId() != null ? hr.getEmergencyId() : "EMERGENCY-XXX";

            Hospital best = findClosestHospital(emergencyLoc);
            if (best == null || best.getAvailableBeds() <= 0) {
                System.out.println("[" + getLocalName() + "] ALERT: All hospitals saturated!");
                alertDispatcherSaturation();
                ACLMessage failure = request.createReply();
                failure.setPerformative(ACLMessage.FAILURE);
                failure.setContent("NO_BEDS_AVAILABLE");
                failure.setOntology(EmergencyOntology.getInstance().getName());
                failure.setLanguage(new SLCodec().getName());
                send(failure);
                return;
            }

            // Decrement bed count
            best.setAvailableBeds(best.getAvailableBeds() - 1);
            
            // Send assignment immediately
            sendHospitalAssignmentToAmbulance(request, eId, emergencyLoc, best);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handlePatientHandoff(ACLMessage handoff, PatientHandoff ph) {
        String eId = ph.getEmergencyId();
        System.out.println("[" + getLocalName() + "] Patient handoff received for incident: " + eId);
        
        for (Hospital h : hospitals) {
            if (h.getAvailableBeds() < h.getTotalBeds()) {
                h.setAvailableBeds(h.getAvailableBeds() + 1);
                break;
            }
        }
        
        try {
            ACLMessage reply = handoff.createReply();
            reply.setPerformative(ACLMessage.INFORM);
            reply.setOntology(EmergencyOntology.getInstance().getName());
            reply.setLanguage(new SLCodec().getName());
            reply.setContentObject(ph); // Echo back the handoff object as confirmation
            send(reply);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendHospitalAssignmentToAmbulance(ACLMessage request, String emergencyId, Location emergencyLoc, Hospital hospital) {
        try {
            ACLMessage reply = request.createReply();
            reply.setPerformative(ACLMessage.INFORM);
            reply.setOntology(EmergencyOntology.getInstance().getName());
            reply.setLanguage(new SLCodec().getName());

            HospitalAssignment assignment = new HospitalAssignment();
            assignment.setHospital(hospital);
            assignment.setHospitalAid(getAID()); // Route back to coordinator
            assignment.setEmergencyId(emergencyId);

            // DUAL-MODE: Use setContentObject and add fallback string
            reply.setContent("HOSPITAL_ASSIGNED:" + hospital.getName() + ":" + emergencyId);
            reply.setContentObject(assignment);
            send(reply);

            System.out.println("[" + getLocalName() + "] Assigned hospital: " + hospital.getName()
                    + " (distance=" + String.format("%.1f", euclideanDistance(emergencyLoc, hospital.getLocation()))
                    + "), beds remaining: " + hospital.getAvailableBeds());

        } catch (Exception e) {
            e.printStackTrace();
        }
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

    private void printCapacitySummary() {
        System.out.println("[" + getLocalName() + "] Hospital Capacity Summary:");
        for (Hospital h : hospitals) {
            System.out.println("  - " + h.getName() + ": " + h.getAvailableBeds() + "/" + h.getTotalBeds() + " beds");
        }
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

    private void alertDispatcherSaturation() {
        ACLMessage alert = new ACLMessage(ACLMessage.INFORM);
        alert.setOntology(EmergencyOntology.getInstance().getName());
        alert.setLanguage(new SLCodec().getName());
        AID dispatcher = findAgentByService("COORDINATION");
        if (dispatcher != null) alert.addReceiver(dispatcher);
        
        try {
            HospitalSaturation hs = new HospitalSaturation();
            hs.setHospitalName("all-medical-system");
            hs.setAvailableBeds(0);
            hs.setTotalBeds(60);
            hs.setPercent(100.0);
            alert.setContentObject(hs);
            send(alert);
        } catch (Exception e) {
            e.printStackTrace();
        }
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
        System.out.println("[" + getLocalName() + "] MedicalCoordinator terminated");
    }
}
