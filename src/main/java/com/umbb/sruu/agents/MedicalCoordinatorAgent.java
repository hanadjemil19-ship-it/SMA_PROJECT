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
import java.util.concurrent.ConcurrentHashMap;

public class MedicalCoordinatorAgent extends Agent {

    private List<Hospital> hospitals = new ArrayList<>();
    private Map<String, AID> hospitalAgents = new HashMap<>();

    /**
     * Contexte d'une demande d'admission en attente de réponse hospitalière.
     * Keyed by admissionConvId dans pendingAdmissions.
     *
     * Invariant : chaque entrée est insérée juste avant send(admissionRequest)
     * et retirée dans handleHospitalReply() — jamais laissée orpheline car
     * la réponse du HospitalAgent est toujours envoyée (INFORM ou FAILURE).
     */
    private static final class AdmissionContext {
        final ACLMessage ambulanceRequest; // message REQUEST original de l'ambulance
        final String emergencyId;
        final Location emergencyLoc;
        final String hospitalName;        // hôpital déjà essayé (pour le fallback)

        AdmissionContext(ACLMessage ambulanceRequest, String emergencyId,
                         Location emergencyLoc, String hospitalName) {
            this.ambulanceRequest = ambulanceRequest;
            this.emergencyId     = emergencyId;
            this.emergencyLoc    = emergencyLoc;
            this.hospitalName    = hospitalName;
        }
    }

    /**
     * Admissions en attente de réponse hospitalière : admissionConvId → contexte.
     * ConcurrentHashMap car insérée depuis setup() et lue/retirée depuis behaviours.
     */
    private final ConcurrentHashMap<String, AdmissionContext> pendingAdmissions =
            new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------

    @Override
    protected void setup() {
        System.out.println("[" + getLocalName() + "] MedicalCoordinator started");

        getContentManager().registerLanguage(new SLCodec());
        getContentManager().registerOntology(EmergencyOntology.getInstance());

        registerInDF();

        // Behaviour 1 : requêtes d'affectation hospitalière venant des ambulances
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.REQUEST);
                ACLMessage msg = receive(mt);
                if (msg != null) {
                    handleAmbulanceRequest(msg);
                } else {
                    block();
                }
            }
        });

        // Behaviour 2 : réponses INFORM/FAILURE venant des hôpitaux
        // (BED_STATUS replies + ADMISSION replies) — 100 % asynchrone, sans blockingReceive.
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.or(
                        MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                        MessageTemplate.MatchPerformative(ACLMessage.FAILURE)
                );
                ACLMessage msg = receive(mt);
                if (msg != null) {
                    handleHospitalReply(msg);
                } else {
                    block();
                }
            }
        });

        // Behaviour 3 : découverte périodique + rafraîchissement capacités (fire-and-forget)
        addBehaviour(new TickerBehaviour(this, 15000) {
            @Override
            protected void onTick() {
                discoverHospitals();
                fireRefreshHospitalCapacities();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Behaviour handlers
    // -------------------------------------------------------------------------

    /**
     * Traite la requête REQUEST d'une ambulance.
     * Envoie une demande d'admission asynchrone à l'hôpital le plus proche.
     * La réponse sera traitée dans handleHospitalReply().
     */
    private void handleAmbulanceRequest(ACLMessage request) {
        try {
            System.out.println("[" + getLocalName() + "] Hospital request from "
                    + request.getSender().getLocalName());

            jade.content.ContentElement ce = getContentManager().extractContent(request);
            if (!(ce instanceof HospitalRequest)) {
                System.out.println("[" + getLocalName() + "] Ignoring non-HospitalRequest content.");
                return;
            }

            HospitalRequest hr  = (HospitalRequest) ce;
            Location emergencyLoc = hr.getEmergencyLocation();
            String   eId          = hr.getEmergencyId() != null ? hr.getEmergencyId() : "EMERGENCY-XXX";

            // Redécouverte synchrone légère (DF lookup, pas de réseau externe)
            discoverHospitals();

            Hospital best = findClosestHospital(emergencyLoc);
            if (best == null || best.getAvailableBeds() <= 0) {
                System.out.println("[" + getLocalName() + "] ALERT: All hospitals saturated!");
                alertDispatcherSaturation();
                ACLMessage failure = request.createReply();
                failure.setPerformative(ACLMessage.FAILURE);
                failure.setContent("NO_BEDS_AVAILABLE");
                send(failure);
                return;
            }

            // Envoi asynchrone : on NE BLOQUE PLUS — le contexte est stocké dans pendingAdmissions
            sendAsyncAdmissionRequest(request, best, eId, emergencyLoc);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Traite toutes les réponses INFORM / FAILURE venant des hôpitaux.
     * Deux cas :
     *  - contenu "BEDS:n:total" → mise à jour de capacité (BED_STATUS reply)
     *  - convId présent dans pendingAdmissions → finalisation d'une admission
     */
    private void handleHospitalReply(ACLMessage reply) {
        String content = reply.getContent();
        if (content == null) return;

        // --- Cas 1 : mise à jour de capacité (fire-and-forget BED_STATUS) ---
        if (content.startsWith("BEDS:")) {
            String senderName = reply.getSender().getLocalName();
            String[] parts = content.split(":");
            if (parts.length >= 3) {
                for (Hospital h : hospitals) {
                    if (h.getName().equals(senderName)) {
                        try {
                            h.setAvailableBeds(Integer.parseInt(parts[1]));
                            h.setTotalBeds(Integer.parseInt(parts[2]));
                        } catch (NumberFormatException ignored) {}
                        break;
                    }
                }
            }
            return;
        }

        // --- Cas 2 : réponse à une demande d'admission ---
        String convId = reply.getConversationId();
        if (convId == null) return;

        AdmissionContext ctx = pendingAdmissions.remove(convId);
        if (ctx == null) return; // réponse inattendue / déjà traitée

        if (reply.getPerformative() == ACLMessage.INFORM && content.startsWith("ADMITTED:")) {
            // Succès : mettre à jour le compteur de lits et notifier l'ambulance
            Hospital hospital = findHospitalByName(ctx.hospitalName);
            if (hospital != null) {
                String[] parts = content.split(":");
                if (parts.length >= 2) {
                    try { hospital.setAvailableBeds(Integer.parseInt(parts[1])); }
                    catch (NumberFormatException ignored) {}
                }
            }
            sendHospitalAssignmentToAmbulance(ctx, hospital);

        } else {
            // Échec (FAILURE ou hôpital plein) : essayer un autre hôpital
            System.out.println("[" + getLocalName() + "] Hospital " + ctx.hospitalName
                    + " rejected admission for " + ctx.emergencyId + " — trying next.");
            Hospital alt = findClosestHospitalExcluding(ctx.emergencyLoc, ctx.hospitalName);
            if (alt != null && alt.getAvailableBeds() > 0) {
                sendAsyncAdmissionRequest(ctx.ambulanceRequest, alt, ctx.emergencyId, ctx.emergencyLoc);
            } else {
                System.out.println("[" + getLocalName() + "] All hospitals saturated for " + ctx.emergencyId);
                alertDispatcherSaturation();
                ACLMessage failure = ctx.ambulanceRequest.createReply();
                failure.setPerformative(ACLMessage.FAILURE);
                failure.setContent("NO_BEDS_AVAILABLE");
                send(failure);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Envoie une demande d'admission asynchrone à l'hôpital.
     * Stocke le contexte dans pendingAdmissions AVANT l'envoi.
     */
    private void sendAsyncAdmissionRequest(ACLMessage ambulanceRequest, Hospital hospital,
                                           String emergencyId, Location emergencyLoc) {
        AID hospitalAid = hospitalAgents.get(hospital.getName());
        if (hospitalAid == null) {
            ACLMessage failure = ambulanceRequest.createReply();
            failure.setPerformative(ACLMessage.FAILURE);
            failure.setContent("NO_BEDS_AVAILABLE");
            send(failure);
            return;
        }

        String convId = "ADMISSION-" + UUID.randomUUID();

        // Invariant : stocker le contexte AVANT l'envoi pour éviter toute race condition
        pendingAdmissions.put(convId,
                new AdmissionContext(ambulanceRequest, emergencyId, emergencyLoc, hospital.getName()));

        ACLMessage admission = new ACLMessage(ACLMessage.REQUEST);
        admission.addReceiver(hospitalAid);
        admission.setConversationId(convId);
        admission.setContent("ADMISSION:" + emergencyId);
        send(admission);

        System.out.println("[" + getLocalName() + "] Async ADMISSION request → "
                + hospital.getName() + " (convId=" + convId + ")");
    }

    /**
     * Envoie l'assignation hospitalière à l'ambulance après succès d'admission.
     */
    private void sendHospitalAssignmentToAmbulance(AdmissionContext ctx, Hospital hospital) {
        if (hospital == null) {
            ACLMessage failure = ctx.ambulanceRequest.createReply();
            failure.setPerformative(ACLMessage.FAILURE);
            failure.setContent("NO_BEDS_AVAILABLE");
            send(failure);
            return;
        }

        try {
            ACLMessage reply = ctx.ambulanceRequest.createReply();
            reply.setPerformative(ACLMessage.INFORM);
            reply.setOntology(EmergencyOntology.getInstance().getName());
            reply.setLanguage(new SLCodec().getName());

            HospitalAssignment assignment = new HospitalAssignment();
            assignment.setHospital(hospital);
            assignment.setHospitalAid(hospitalAgents.get(hospital.getName()));
            assignment.setEmergencyId(ctx.emergencyId);

            getContentManager().fillContent(reply, assignment);
            send(reply);

            System.out.println("[" + getLocalName() + "] Assigned hospital: " + hospital.getName()
                    + " (distance="
                    + String.format("%.1f", euclideanDistance(ctx.emergencyLoc, hospital.getLocation()))
                    + "), beds remaining: " + hospital.getAvailableBeds());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Envoie les requêtes BED_STATUS sans attendre de réponse (fire-and-forget).
     * Les réponses INFORM "BEDS:n:total" seront traitées par handleHospitalReply().
     */
    private void fireRefreshHospitalCapacities() {
        for (Hospital hospital : hospitals) {
            AID aid = hospitalAgents.get(hospital.getName());
            if (aid == null) continue;

            ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
            req.addReceiver(aid);
            String convId = "BED_STATUS-" + UUID.randomUUID();
            req.setConversationId(convId);
            req.setContent("BED_STATUS");
            send(req);
            // Pas de blockingReceive — la réponse sera interceptée par le Behaviour 2
        }
    }

    // -------------------------------------------------------------------------
    // Hospital registry helpers
    // -------------------------------------------------------------------------

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
                System.out.println("[" + getLocalName() + "] Discovered "
                        + hospitals.size() + " hospital agent(s)");
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

    private Hospital findClosestHospitalExcluding(Location emergencyLoc, String excludeName) {
        Hospital closest = null;
        double minDistance = Double.MAX_VALUE;
        for (Hospital h : hospitals) {
            if (h.getAvailableBeds() > 0 && !h.getName().equals(excludeName)) {
                double dist = euclideanDistance(emergencyLoc, h.getLocation());
                if (dist < minDistance) {
                    minDistance = dist;
                    closest = h;
                }
            }
        }
        return closest;
    }

    private Hospital findHospitalByName(String name) {
        return hospitals.stream()
                .filter(h -> h.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    private double euclideanDistance(Location loc1, Location loc2) {
        int dx = loc1.getX() - loc2.getX();
        int dy = loc1.getY() - loc2.getY();
        return Math.sqrt(dx * dx + dy * dy);
    }

    // -------------------------------------------------------------------------
    // Misc
    // -------------------------------------------------------------------------

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
        AID dispatcher = findAgentByService("COORDINATION");
        if (dispatcher != null) alert.addReceiver(dispatcher);
        alert.setContent("HOSPITAL_SATURATION_ALERT");
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
        System.out.println("[" + getLocalName() + "] MedicalCoordinator terminated");
    }
}
