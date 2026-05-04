package com.umbb.sruu.agents;

import com.umbb.sruu.ontology.*;
import com.umbb.sruu.utils.UtilityCalculator;
import jade.content.lang.sl.SLCodec;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.*;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.*;
import java.util.stream.Collectors;

public class DispatcherAgent extends Agent {

    // Contract-Net proposal collection window (PROPOSE / REFUSE)
    private static final long PROPOSAL_TIMEOUT = 2000;
    private static final long RETRY_DELAY = 5000;
    private static final long QUEUE_RETRY_PERIOD = 10000;

    private Map<String, Emergency> emergencyData = new HashMap<>();
    private Map<String, String> emergencyServiceType = new HashMap<>();

    private Map<String, ProposalCollector> activeCollectors = new HashMap<>();
    private Map<String, Integer> retryCounts = new HashMap<>();
    private static final int MAX_RETRIES = 2;
    private Map<String, String> assignedUnits = new HashMap<>();
    private Set<String> failedIncidents = new HashSet<>();
    private Set<String> resolvedIncidents = new HashSet<>();
    private Queue<String> queuedIncidents = new LinkedList<>();
    private Set<String> queuedIncidentIds = new HashSet<>();
    private Map<String, Integer> queueRounds = new HashMap<>();
    private Map<String, Long> queueSince = new HashMap<>();
    private Map<String, String> policeAssignments = new HashMap<>();
    private Set<String> clearedByPolice = new HashSet<>();
    private Map<String, String> policeConversationParents = new HashMap<>();
    private final Map<String, ContractNetSession> contractNetSessions = new HashMap<>();

    // FIX: Track which units are currently assigned to prevent double-assignment
    private Set<String> busyUnits = new HashSet<>();
    private final Object assignmentLock = new Object();

    @Override
    protected void setup() {
        System.out.println("[" + getLocalName() + "] DispatcherAgent started - CONTRACT NET MODE");

        getContentManager().registerLanguage(new SLCodec());
        getContentManager().registerOntology(EmergencyOntology.getInstance());

        registerInDF();
        UtilityCalculator.printFormula();

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                        MessageTemplate.and(
                                MessageTemplate.MatchOntology(EmergencyOntology.getInstance().getName()),
                                MessageTemplate.not(MessageTemplate.MatchSender(
                                        findAgentByService("TRAFFIC_CONTROL")))
                        )
                );
                ACLMessage msg = receive(mt);
                if (msg != null) {
                    handleEmergencyAlert(msg);
                } else {
                    block();
                }
            }
        });

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                        MessageTemplate.MatchOntology(EmergencyOntology.getInstance().getName())
                );
                ACLMessage msg = receive(mt);
                if (msg != null) {
                    handlePropose(msg);
                } else {
                    block();
                }
            }
        });

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                // Contract Net responses are often plain-text (e.g., LOW_WATER); do not require ontology match here.
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.REFUSE);
                ACLMessage msg = receive(mt);
                if (msg != null) {
                    handleRefuse(msg);
                } else {
                    block();
                }
            }
        });

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = new MessageTemplate(msg -> {
                    String content = msg.getContent();
                    return msg.getPerformative() == ACLMessage.INFORM
                            && content != null
                            && (content.startsWith("UNIT_ABORT:")
                            || content.startsWith("ABORT:")
                            || content.startsWith("UNIT_IDLE:")
                            || content.startsWith("MISSION_ARRIVED:")
                            || content.startsWith("MISSION_COMPLETE:")
                            || content.startsWith("PERIMETER_SECURED:")
                            || content.startsWith("HOSPITAL_SATURATION:"));
                });
                ACLMessage msg = receive(mt);
                if (msg != null) {
                    handleUnitStatusInform(msg);
                } else {
                    block();
                }
            }
        });

        addBehaviour(new TickerBehaviour(this, QUEUE_RETRY_PERIOD) {
            @Override
            protected void onTick() {
                retryNextQueuedIncident();
            }
        });
    }

    private void registerInDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("COORDINATION");
        sd.setName("emergency-dispatcher");
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
            System.out.println("[" + getLocalName() + "] Registered in DF as 'dispatcher'");
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }

    private AID findAgentByService(String serviceType) {
        return com.umbb.sruu.utils.AgentUtils.findAgentByService(this, serviceType);
    }

    private void handleEmergencyAlert(ACLMessage msg) {
        try {
            String content = msg.getContent();
            if (content != null && !content.startsWith("(")) {
                System.out.println("[" + getLocalName() + "] Note: Plain text from "
                        + msg.getSender().getLocalName() + " - skipped");
                return;
            }

            HasEmergency hasEmergency = (HasEmergency) getContentManager().extractContent(msg);
            Emergency emergency = hasEmergency.getEmergency();

            System.out.println("[" + getLocalName() + "] *** NEW EMERGENCY: " + emergency);
            System.out.println("[" + getLocalName() + "] From: " + msg.getSender().getLocalName());

            String eId = emergency.getId();
            notifyLogger("DETECTED:" + eId + ":" + emergency.getType());
            if (failedIncidents.contains(eId)) {
                System.out.println("[" + getLocalName() + "] Ignoring emergency " + eId + " because it is already marked FAILED");
                return;
            }
            if (assignedUnits.containsKey(eId) || activeCollectors.containsKey(eId) || queuedIncidentIds.contains(eId)) {
                System.out.println("[" + getLocalName() + "] Ignoring duplicate emergency alert for " + eId);
                return;
            }

            emergencyData.put(eId, emergency);

            String serviceType = determineServiceType(emergency);
            emergencyServiceType.put(eId, serviceType);

            ProposalCollector collector = new ProposalCollector(eId, serviceType, emergency);
            activeCollectors.put(eId, collector);

            requestTrafficClearance(emergency);
            searchAndSendCFP(serviceType, emergency, eId);

            if (emergency.getType().equals("FIRE") || emergency.getType().equals("STRUCTURAL_COLLAPSE")) {
                System.out.println("[" + getLocalName() + "] Starting police perimeter Contract Net concurrently with primary assignment");
                startPoliceContractNet(eId, emergency);
            }

        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error: " + e.getMessage());
        }
    }

    private String determineServiceType(Emergency emergency) {
        // Safe fallback: unknown/invalid types are routed to RESCUE instead of null.
        if (emergency == null || emergency.getType() == null) {
            return "RESCUE";
        }

        String type = emergency.getType().trim().toUpperCase(Locale.ROOT);
        switch (type) {
            case "FIRE":
                return "FIRE";
            case "STRUCTURAL_COLLAPSE":
                return "RESCUE";
            case "BIOHAZARD":
                return "BIOHAZARD";
            case "CRYOGENIC_LEAK":
                return "CRYOGENIC_LEAK";
            case "MEDICAL":
                return "MEDICAL";
            default:
                System.out.println("[" + getLocalName() + "] Unknown emergency type '" + emergency.getType()
                        + "' -> fallback service type RESCUE");
                return "RESCUE";
        }
    }

    private void requestTrafficClearance(Emergency emergency) {
        ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
        AID tc = findAgentByService("TRAFFIC_CONTROL");
        if (tc != null) request.addReceiver(tc);
        request.setOntology(EmergencyOntology.getInstance().getName());
        request.setLanguage(new SLCodec().getName());
        Location emergencyLoc = emergency.getLocation();
        request.setContent("ROUTE:0,0," + emergencyLoc.getX() + "," + emergencyLoc.getY());
        send(request);
        System.out.println("[" + getLocalName() + "] Requested traffic clearance to " + emergencyLoc);
    }

    private void searchAndSendCFP(String serviceType, Emergency emergency, String conversationId) {
        if (serviceType == null || serviceType.trim().isEmpty()) {
            serviceType = determineServiceType(emergency);
            System.out.println("[" + getLocalName() + "] Null/empty serviceType fixed by fallback: " + serviceType);
        }

        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType(serviceType);
        template.addServices(sd);

        try {
            DFAgentDescription[] results = DFService.search(this, template);

            if (results.length == 0) {
                System.out.println("[" + getLocalName() + "] NO UNITS AVAILABLE for " + serviceType);
                return;
            }

            System.out.println("[" + getLocalName() + "] Found " + results.length + " unit(s) for " + serviceType);

            Set<String> invitedLocals = new LinkedHashSet<>();
            for (DFAgentDescription result : results) {
                AID unitAID = result.getName();
                String local = unitAID.getLocalName();
                synchronized (assignmentLock) {
                    if (busyUnits.contains(local)) {
                        System.out.println("[" + getLocalName() + "] Skipping CFP to busy unit " + local);
                        continue;
                    }
                }
                sendCFP(unitAID, emergency, conversationId);
                invitedLocals.add(local);
            }

            startContractNetSession(conversationId, invitedLocals.size());

        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }

    private void sendCFP(AID unit, Emergency emergency) {
        sendCFP(unit, emergency, emergency.getId());
    }

    private void sendCFP(AID unit, Emergency emergency, String conversationId) {
        ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
        cfp.addReceiver(unit);
        cfp.setLanguage(new SLCodec().getName());
        cfp.setOntology(EmergencyOntology.getInstance().getName());
        cfp.setConversationId(conversationId);

        HasEmergency he = new HasEmergency();
        he.setEmergency(emergency);

        try {
            getContentManager().fillContent(cfp, he);
            send(cfp);
            System.out.println("[" + getLocalName() + "] Sent CFP to " + unit.getLocalName());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Tracks a Contract Net round for a given conversation id.
     * We wait up to {@link #PROPOSAL_TIMEOUT}ms OR until all invited responders reply (PROPOSE/REFUSE).
     */
    private void startContractNetSession(String conversationId, int invitedCount) {
        if (conversationId == null || conversationId.isEmpty()) {
            return;
        }

        synchronized (contractNetSessions) {
            if (contractNetSessions.containsKey(conversationId)) {
                return;
            }
            contractNetSessions.put(conversationId, new ContractNetSession(conversationId, invitedCount));
        }

        addBehaviour(new WakerBehaviour(this, PROPOSAL_TIMEOUT) {
            @Override
            protected void onWake() {
                finalizeContractNet(conversationId);
            }
        });

        if (invitedCount <= 0) {
            finalizeContractNet(conversationId);
        }
    }

    /**
     * Idempotent finalize: evaluates winner once per conversation id (timeout or early all-replies).
     */
    private void finalizeContractNet(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return;
        }

        boolean shouldEvaluate;
        synchronized (contractNetSessions) {
            ContractNetSession session = contractNetSessions.get(conversationId);
            if (session == null) {
                shouldEvaluate = false;
            } else {
                shouldEvaluate = session.tryFinalize();
                if (shouldEvaluate) {
                    contractNetSessions.remove(conversationId);
                }
            }
        }

        if (shouldEvaluate) {
            evaluateAndAssign(conversationId);
        }
    }

    private void handleRefuse(ACLMessage refuse) {
        String conversationId = refuse.getConversationId();
        if (conversationId == null || conversationId.isEmpty()) {
            return;
        }

        boolean allRepliesIn = false;
        synchronized (contractNetSessions) {
            ContractNetSession session = contractNetSessions.get(conversationId);
            if (session != null) {
                allRepliesIn = session.recordResponse(refuse.getSender().getLocalName());
            }
        }

        System.out.println("[" + getLocalName() + "] RECEIVED REFUSE from "
                + refuse.getSender().getLocalName()
                + " for " + conversationId
                + (refuse.getContent() != null ? (" (" + refuse.getContent() + ")") : ""));

        if (allRepliesIn) {
            finalizeContractNet(conversationId);
        }
    }

    private void handlePropose(ACLMessage propose) {
        try {
            String conversationId = propose.getConversationId();
            if (conversationId != null && !conversationId.isEmpty()) {
                boolean allRepliesIn = false;
                synchronized (contractNetSessions) {
                    ContractNetSession session = contractNetSessions.get(conversationId);
                    if (session != null) {
                        allRepliesIn = session.recordResponse(propose.getSender().getLocalName());
                    }
                }
                if (allRepliesIn) {
                    finalizeContractNet(conversationId);
                }
            }

            UnitAvailable unitAvailable = (UnitAvailable) getContentManager().extractContent(propose);
            UnitStatus status = unitAvailable.getStatus();
            AID sender = propose.getSender();
            String eId = propose.getConversationId();
            String baseEId = baseIncidentId(eId);

            System.out.println("[" + getLocalName() + "] RECEIVED PROPOSE from " + status.getUnitId()
                    + " for " + eId);

            if (isIncidentClosed(eId)) {
                System.out.println("[" + getLocalName() + "] Ignoring proposal for closed incident " + eId);
                rejectProposal(propose, "Incident " + eId + " is closed");
                return;
            }

            ProposalCollector collector = activeCollectors.get(eId);
            if (collector == null) {
                System.out.println("[" + getLocalName() + "] Late proposal from " + status.getUnitId()
                        + " - emergency already assigned or timed out");
                return;
            }

            Emergency emergency = emergencyData.get(baseEId);
            if (emergency == null) {
                System.err.println("[" + getLocalName() + "] Missing emergency context for conversation " + eId
                        + " (base=" + baseEId + "), rejecting proposal from " + sender.getLocalName());
                rejectProposal(propose, "Missing emergency context for conversation " + eId);
                return;
            }
            String serviceType = isPoliceRole(eId) ? "CROWD_CONTROL" : emergencyServiceType.get(baseEId);

            double utility = UtilityCalculator.calculateUtility(status, emergency, serviceType);
            boolean validPrimaryResponder = UtilityCalculator.isValidPrimaryResponder(status, emergency);
            if (isPoliceRole(eId) && status.getUnitId() != null && status.getUnitId().contains("police")) {
                validPrimaryResponder = true;
            }

            System.out.println("[" + getLocalName() + "] Utility score for " + status.getUnitId()
                    + ": " + String.format("%.4f", utility)
                    + " (validPrimary=" + validPrimaryResponder + ")");

            collector.addProposal(sender, propose, utility, validPrimaryResponder);

        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error handling PROPOSE: " + e.getMessage());
        }
    }

    private void evaluateAndAssign(String eId) {
        if (isIncidentClosed(eId)) {
            activeCollectors.remove(eId);
            System.out.println("[" + getLocalName() + "] Skipping evaluation for closed incident " + eId);
            return;
        }

        ProposalCollector collector = activeCollectors.remove(eId);
        if (collector == null || collector.isEmpty()) {
            Emergency emergency = emergencyData.get(baseIncidentId(eId));
            if (emergency != null) {
                scheduleRetry(eId, emergency, "no proposals");
            } else {
                markIncidentFailed(eId, "missing emergency data");
            }
            return;
        }

        System.out.println("[" + getLocalName() + "] === EVALUATING " + collector.getCount()
                + " proposals for " + eId + " ===");

        Emergency emergency = emergencyData.get(baseIncidentId(eId));
        List<Map.Entry<AID, ProposalData>> rankedCandidates = collector.getRankedValidPrimaryResponders();
        if (rankedCandidates.isEmpty()) {
            System.out.println("[" + getLocalName() + "] No valid primary responder proposal for " + eId);
            rejectAll(collector, "No valid primary responder for " + (emergency != null ? emergency.getType() : eId));
            if (emergency != null) {
                scheduleRetry(eId, emergency, "no valid primary responder");
            }
            return;
        }
        Map.Entry<AID, ProposalData> best = null;
        for (Map.Entry<AID, ProposalData> candidate : rankedCandidates) {
            String candidateName = candidate.getKey().getLocalName();
            if (tryReserveUnit(candidateName)) {
                best = candidate;
                break;
            }
        }
        if (best == null) {
            System.out.println("[" + getLocalName() + "] No available responder after atomic reservation checks");
            rejectAll(collector, "No available responder (all candidates already busy)");
            if (emergency != null) {
                scheduleRetry(eId, emergency, "all candidates became busy");
            }
            return;
        }

        AID winner = best.getKey();
        double bestUtility = best.getValue().utility;
        String winnerName = winner.getLocalName();

        System.out.println("[" + getLocalName() + "] WINNER: " + winnerName
                + " with utility " + String.format("%.4f", bestUtility));

        assignedUnits.put(eId, winnerName);
        queuedIncidentIds.remove(eId);
        queuedIncidents.remove(eId);
        queueRounds.remove(eId);
        queueSince.remove(eId);

        ACLMessage accept = best.getValue().originalMessage.createReply();
        accept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
        accept.setContent("Assigned to emergency " + eId);
        send(accept);
        notifyLogger("ASSIGNED:" + baseIncidentId(eId) + ":" + winnerName + ":" + (isPoliceRole(eId) ? "POLICE" : "PRIMARY"));
        System.out.println("[" + getLocalName() + "] -> ACCEPT sent to " + winnerName);

        for (Map.Entry<AID, ProposalData> entry : collector.getAll().entrySet()) {
            if (!entry.getKey().equals(winner)) {
                ACLMessage reject = entry.getValue().originalMessage.createReply();
                reject.setPerformative(ACLMessage.REJECT_PROPOSAL);
                reject.setContent("Emergency " + eId + " assigned to " + winnerName);
                send(reject);
                System.out.println("[" + getLocalName() + "] -> REJECT sent to " + entry.getKey().getLocalName());
            }
        }

        System.out.println("[" + getLocalName() + "] === ASSIGNMENT COMPLETE for " + eId + " ===");
        retryCounts.remove(eId);
    }

    private void scheduleRetry(String eId, Emergency emergency, String reason) {
        if (isIncidentClosed(eId)) {
            return;
        }

        int retries = retryCounts.getOrDefault(eId, 0);
        if (retries >= MAX_RETRIES) {
            queueIncident(eId, emergency, reason);
            return;
        }

        retryCounts.put(eId, retries + 1);
        System.out.println("[" + getLocalName() + "] Retrying " + eId + " after " + reason
                + " (" + (retries + 1) + "/" + MAX_RETRIES + ") in " + (RETRY_DELAY / 1000) + "s");
        addBehaviour(new WakerBehaviour(this, RETRY_DELAY) {
            @Override
            protected void onWake() {
                if (isIncidentClosed(eId)) {
                    return;
                }
                startContractNetRound(eId, emergency);
            }
        });
    }

    private boolean isIncidentClosed(String eId) {
        return failedIncidents.contains(eId) || resolvedIncidents.contains(baseIncidentId(eId)) || assignedUnits.containsKey(eId);
    }

    private void queueIncident(String eId, Emergency emergency, String reason) {
        if (isIncidentClosed(eId)) {
            return;
        }

        activeCollectors.remove(eId);
        retryCounts.remove(eId);
        emergencyData.put(eId, emergency);
        emergencyServiceType.put(eId, determineServiceType(emergency));

        if (queuedIncidentIds.add(eId)) {
            queuedIncidents.offer(eId);
            queueSince.putIfAbsent(eId, System.currentTimeMillis());
            System.out.println("[" + getLocalName() + "] *** INCIDENT QUEUED: " + eId
                    + " after " + reason + ". Waiting for available " + emergencyServiceType.get(eId) + " unit. ***");
        } else {
            System.out.println("[" + getLocalName() + "] Incident " + eId + " already queued; reason: " + reason);
        }
    }

    private void retryQueuedIncidentsForUnit(String unitName) {
        if (queuedIncidents.isEmpty()) {
            return;
        }
        String eId = pollQueuedIncidentByPriority(unitName);
        if (eId == null) {
            return;
        }

        if (isIncidentClosed(eId)) {
            return;
        }

        Emergency emergency = emergencyData.get(eId);
        if (emergency == null) {
            markIncidentFailed(eId, "queued incident has no emergency data");
            return;
        }

        int rounds = queueRounds.getOrDefault(eId, 0);
        queueRounds.put(eId, rounds + 1);
        System.out.println("[" + getLocalName() + "] Retrying queued incident " + eId
                + " because " + unitName + " became idle (queue round " + (rounds + 1) + ")");
        startContractNetRound(eId, emergency);
    }

    private void retryNextQueuedIncident() {
        if (queuedIncidents.isEmpty()) {
            return;
        }
        String eId = pollQueuedIncidentByPriority(null);
        if (eId == null) {
            return;
        }

        if (isIncidentClosed(eId)) {
            return;
        }

        if (activeCollectors.containsKey(eId)) {
            queuedIncidents.offer(eId);
            queuedIncidentIds.add(eId);
            return;
        }

        Emergency emergency = emergencyData.get(eId);
        if (emergency == null) {
            markIncidentFailed(eId, "queued incident has no emergency data");
            return;
        }

        int rounds = queueRounds.getOrDefault(eId, 0);
        queueRounds.put(eId, rounds + 1);
        System.out.println("[" + getLocalName() + "] Periodic retry for queued incident " + eId
                + " (queue round " + (rounds + 1) + ")");
        startContractNetRound(eId, emergency);
    }

    private boolean isUnitCompatibleWithIncident(String unitName, Emergency emergency) {
        if (unitName == null || emergency == null) {
            return false;
        }

        String normalizedUnitName = unitName.toLowerCase();
        switch (emergency.getType()) {
            case "FIRE":
            case "STRUCTURAL_COLLAPSE":
                return normalizedUnitName.contains("firetruck");
            case "MEDICAL":
                return normalizedUnitName.contains("ambulance");
            case "BIOHAZARD":
            case "CRYOGENIC_LEAK":
                return normalizedUnitName.contains("bcu") || normalizedUnitName.contains("biohazard");
            default:
                return false;
        }
    }

    private void startContractNetRound(String eId, Emergency emergency) {
        if (isIncidentClosed(eId) || activeCollectors.containsKey(eId)) {
            return;
        }

        retryCounts.remove(eId);
        String serviceType = emergencyServiceType.get(eId);
        if (serviceType == null || serviceType.trim().isEmpty()) {
            serviceType = determineServiceType(emergency);
            emergencyServiceType.put(eId, serviceType);
            System.out.println("[" + getLocalName() + "] Restored missing serviceType for " + eId + " => " + serviceType);
        }

        ProposalCollector collector = new ProposalCollector(eId, serviceType, emergency);
        activeCollectors.put(eId, collector);
        searchAndSendCFP(serviceType, emergency, eId);

        if (emergency.getType().equals("FIRE") || emergency.getType().equals("STRUCTURAL_COLLAPSE")) {
            startPoliceContractNet(eId, emergency);
        }

    }

    private void markIncidentFailed(String eId, String reason) {
        if (!failedIncidents.add(eId)) {
            return;
        }

        activeCollectors.remove(eId);
        retryCounts.remove(eId);
        assignedUnits.remove(eId);
        queuedIncidentIds.remove(eId);
        queuedIncidents.remove(eId);
        queueRounds.remove(eId);
        queueSince.remove(eId);
        policeConversationParents.remove(eId);

        System.out.println("[" + getLocalName() + "] *** INCIDENT FAILED: " + eId
                + " after " + MAX_RETRIES + " retries. Reason: " + reason + " ***");
        notifyFailure(eId, reason);
    }

    private void notifyFailure(String eId, String reason) {
        ACLMessage failure = new ACLMessage(ACLMessage.FAILURE);
        AID logger = findAgentByService("AUDIT");
        if (logger != null) failure.addReceiver(logger);
        failure.setContent("INCIDENT_FAILED:" + eId + ":" + reason);
        send(failure);
    }

    private void rejectProposal(ACLMessage proposal, String reason) {
        ACLMessage reject = proposal.createReply();
        reject.setPerformative(ACLMessage.REJECT_PROPOSAL);
        reject.setContent(reason);
        send(reject);
    }

    private void rejectAll(ProposalCollector collector, String reason) {
        for (Map.Entry<AID, ProposalData> entry : collector.getAll().entrySet()) {
            ACLMessage reject = entry.getValue().originalMessage.createReply();
            reject.setPerformative(ACLMessage.REJECT_PROPOSAL);
            reject.setContent(reason);
            send(reject);
        }
    }

    private void handleUnitStatusInform(ACLMessage inform) {
        String content = inform.getContent();
        if (content != null && content.startsWith("UNIT_IDLE:")) {
            handleUnitIdle(inform);
        } else if (content != null && (content.startsWith("MISSION_ARRIVED:")
                || content.startsWith("MISSION_COMPLETE:")
                || content.startsWith("PERIMETER_SECURED:"))) {
            handleLifecycleInform(inform);
        } else if (content != null && content.startsWith("HOSPITAL_SATURATION:")) {
            handleHospitalSaturation(inform);
        } else {
            handleAbort(inform);
        }
    }

    private void handleHospitalSaturation(ACLMessage inform) {
        String content = inform.getContent();
        String[] parts = content.split(":");
        String hospitalName = parts.length >= 2 ? parts[1] : inform.getSender().getLocalName();
        String availableBeds = parts.length >= 3 ? parts[2] : "?";
        String totalBeds = parts.length >= 4 ? parts[3] : "?";
        String percent = parts.length >= 5 ? parts[4] : "?";

        System.out.println("[" + getLocalName() + "] ALERT: " + hospitalName
                + " bed saturation, available=" + availableBeds + "/" + totalBeds
                + " (" + percent + "%)");
        notifyLogger("HOSPITAL_SATURATION:" + hospitalName + ":" + availableBeds + ":" + totalBeds + ":" + percent);
    }

    private void handleLifecycleInform(ACLMessage inform) {
        String[] parts = inform.getContent().split(":");
        if (parts.length < 3) return;
        String event = parts[0];
        String unitName = parts[1];
        String eId = parts[2];
        notifyLogger(event + ":" + unitName + ":" + eId);

        if ("PERIMETER_SECURED".equals(event)) {
            clearedByPolice.add(eId);
            policeAssignments.put(eId, unitName);
            System.out.println("[" + getLocalName() + "] Police perimeter secured for " + eId + " by " + unitName);
            return;
        }

        if ("MISSION_COMPLETE".equals(event)) {
            System.out.println("[" + getLocalName() + "] Mission complete for " + eId + " by " + unitName);
            resolvedIncidents.add(eId);
            assignedUnits.remove(eId);
            releaseUnit(unitName);
            releasePolicePerimeter(eId);
            notifyLogger("RESOLVED:" + eId + ":" + unitName);
        }
    }

    private void handleUnitIdle(ACLMessage inform) {
        String content = inform.getContent();
        String[] parts = content.split(":");
        String unitName = parts.length >= 2 ? parts[1] : inform.getSender().getLocalName();
        releaseUnit(unitName);
        System.out.println("[" + getLocalName() + "] Unit " + unitName + " is idle; freed from busy list");
        retryQueuedIncidentsForUnit(unitName);
    }

    private void handleAbort(ACLMessage abort) {
        String content = abort.getContent();
        System.out.println("[" + getLocalName() + "] RECEIVED ABORT: " + content);

        // FIX: Handle both UNIT_ABORT:unit:reason:eid and ABORT:reason:eid formats
        String[] parts = content.split(":");
        String unitName = null;
        String reason = null;
        String emergencyId = null;

        if (content.startsWith("UNIT_ABORT:") && parts.length >= 4) {
            unitName = parts[1];
            reason = parts[2];
            emergencyId = parts[3];
        } else if (content.startsWith("ABORT:") && parts.length >= 3) {
            reason = parts[1];
            emergencyId = parts[2];
        }

        if (unitName != null) {
            releaseUnit(unitName);
            System.out.println("[" + getLocalName() + "] Unit " + unitName + " freed from busy list");
        }

        final String abortedEmergencyId = emergencyId;
        if (abortedEmergencyId != null && !abortedEmergencyId.equals("UNKNOWN")) {
            if (failedIncidents.contains(abortedEmergencyId)) {
                System.out.println("[" + getLocalName() + "] Ignoring abort for failed incident " + abortedEmergencyId);
                return;
            }

            System.out.println("[" + getLocalName() + "] Unit " + unitName + " aborted " + abortedEmergencyId
                    + " due to: " + reason);

            Emergency emergency = emergencyData.get(abortedEmergencyId);
            String assignedUnit = assignedUnits.get(abortedEmergencyId);
            boolean abortingAssignedUnit = unitName != null && unitName.equals(assignedUnit);

            if (!abortingAssignedUnit) {
                System.out.println("[" + getLocalName() + "] Ignoring abort for reassignment because "
                        + unitName + " is not assigned to " + abortedEmergencyId);
                return;
            }

            assignedUnits.remove(abortedEmergencyId);
            notifyLogger("ABORTED:" + abortedEmergencyId + ":" + unitName + ":" + reason);
            if (emergency != null) {
                scheduleImmediateRetry(abortedEmergencyId, emergency, "assigned unit aborted: " + reason);
            }
        }
    }

    private void scheduleImmediateRetry(String eId, Emergency emergency, String reason) {
        if (isIncidentClosed(eId)) {
            return;
        }

        int retries = retryCounts.getOrDefault(eId, 0);
        if (retries >= MAX_RETRIES) {
            queueIncident(eId, emergency, reason);
            return;
        }

        retryCounts.put(eId, retries + 1);
        System.out.println("[" + getLocalName() + "] *** DYNAMIC REASSIGNMENT for " + eId
                + " after " + reason + " (" + (retries + 1) + "/" + MAX_RETRIES + ") ***");
        String serviceType = emergencyServiceType.get(eId);
        if (serviceType == null || serviceType.trim().isEmpty()) {
            serviceType = determineServiceType(emergency);
            emergencyServiceType.put(eId, serviceType);
            System.out.println("[" + getLocalName() + "] Restored missing serviceType for " + eId + " => " + serviceType);
        }

        ProposalCollector collector = new ProposalCollector(eId, serviceType, emergency);
        activeCollectors.put(eId, collector);
        searchAndSendCFP(serviceType, emergency, eId);

        if (emergency.getType().equals("FIRE") || emergency.getType().equals("STRUCTURAL_COLLAPSE")) {
            startPoliceContractNet(eId, emergency);
        }

    }

    private void startPoliceContractNet(String eId, Emergency emergency) {
        String policeKey = "POLICE::PERIMETER::" + eId + "::" + System.currentTimeMillis();
        if (activeCollectors.containsKey(policeKey) || assignedUnits.containsKey(policeKey)) return;
        policeConversationParents.put(policeKey, eId);
        ProposalCollector collector = new ProposalCollector(policeKey, "CROWD_CONTROL", emergency);
        activeCollectors.put(policeKey, collector);
        searchAndSendCFPWithConversation("CROWD_CONTROL", emergency, policeKey);
    }

    private void searchAndSendCFPWithConversation(String serviceType, Emergency emergency, String conversationId) {
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType(serviceType);
        template.addServices(sd);
        try {
            DFAgentDescription[] results = DFService.search(this, template);
            if (results.length == 0) {
                System.out.println("[" + getLocalName() + "] No units found for " + serviceType + " (" + conversationId + ")");
                return;
            }
            Set<String> invitedLocals = new LinkedHashSet<>();
            for (DFAgentDescription result : results) {
                String local = result.getName().getLocalName();
                synchronized (assignmentLock) {
                    if (busyUnits.contains(local)) {
                        System.out.println("[" + getLocalName() + "] Skipping CFP to busy unit " + local + " (" + conversationId + ")");
                        continue;
                    }
                }
                sendCFP(result.getName(), emergency, conversationId);
                invitedLocals.add(local);
            }

            startContractNetSession(conversationId, invitedLocals.size());
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }

    private boolean isPoliceRole(String eId) {
        return eId != null && policeConversationParents.containsKey(eId);
    }

    private String baseIncidentId(String eId) {
        if (eId == null) return null;
        return policeConversationParents.getOrDefault(eId, eId);
    }

    private void releasePolicePerimeter(String eId) {
        String policeUnit = policeAssignments.remove(eId);
        if (policeUnit == null) return;
        ACLMessage release = new ACLMessage(ACLMessage.INFORM);
        release.addReceiver(new AID(policeUnit, AID.ISLOCALNAME));
        release.setContent("PERIMETER_RELEASED:" + eId);
        send(release);
        releaseUnit(policeUnit);
        assignedUnits.entrySet().removeIf(entry ->
                policeUnit.equals(entry.getValue()) && isPoliceRole(entry.getKey()) && eId.equals(baseIncidentId(entry.getKey())));
    }

    private boolean tryReserveUnit(String unitName) {
        synchronized (assignmentLock) {
            if (busyUnits.contains(unitName)) {
                return false;
            }
            busyUnits.add(unitName);
            return true;
        }
    }

    private void releaseUnit(String unitName) {
        if (unitName == null) return;
        synchronized (assignmentLock) {
            busyUnits.remove(unitName);
        }
    }

    private String pollQueuedIncidentByPriority(String preferredUnitName) {
        if (queuedIncidents.isEmpty()) return null;

        String bestId = null;
        int bestRound = Integer.MIN_VALUE;
        long bestSince = Long.MAX_VALUE;

        for (String id : queuedIncidents) {
            Emergency emergency = emergencyData.get(id);
            if (emergency == null) continue;
            if (preferredUnitName != null && !isUnitCompatibleWithIncident(preferredUnitName, emergency)) continue;

            int rounds = queueRounds.getOrDefault(id, 0);
            long since = queueSince.getOrDefault(id, Long.MAX_VALUE);
            if (rounds > bestRound || (rounds == bestRound && since < bestSince)) {
                bestRound = rounds;
                bestSince = since;
                bestId = id;
            }
        }

        if (bestId != null) {
            queuedIncidents.remove(bestId);
            queuedIncidentIds.remove(bestId);
        }
        return bestId;
    }

    private void notifyLogger(String content) {
        ACLMessage log = new ACLMessage(ACLMessage.INFORM);
        AID logger = findAgentByService("AUDIT");
        if (logger != null) log.addReceiver(logger);
        log.setContent(content);
        send(log);
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException e) {
            e.printStackTrace();
        }
        System.out.println("[" + getLocalName() + "] DispatcherAgent terminated");
    }

    private static class ProposalData {
        ACLMessage originalMessage;
        double utility;
        boolean validPrimaryResponder;

        ProposalData(ACLMessage msg, double utility, boolean validPrimaryResponder) {
            this.originalMessage = msg;
            this.utility = utility;
            this.validPrimaryResponder = validPrimaryResponder;
        }
    }

    private static class ProposalCollector {
        private String emergencyId;
        private String serviceType;
        private Emergency emergency;
        private Map<AID, ProposalData> proposals = new HashMap<>();

        ProposalCollector(String eId, String serviceType, Emergency emergency) {
            this.emergencyId = eId;
            this.serviceType = serviceType;
            this.emergency = emergency;
        }

        void addProposal(AID sender, ACLMessage msg, double utility, boolean validPrimaryResponder) {
            proposals.put(sender, new ProposalData(msg, utility, validPrimaryResponder));
        }

        boolean isEmpty() {
            return proposals.isEmpty();
        }

        int getCount() {
            return proposals.size();
        }

        List<Map.Entry<AID, ProposalData>> getRankedValidPrimaryResponders() {
            return proposals.entrySet().stream()
                    .filter(e -> e.getValue().validPrimaryResponder)
                    .sorted((a, b) -> Double.compare(b.getValue().utility, a.getValue().utility))
                    .collect(Collectors.toList());
        }

        Map<AID, ProposalData> getAll() {
            return proposals;
        }
    }

    private static final class ContractNetSession {
        private final String conversationId;
        private final int invitedCount;
        private final Set<String> responders = new HashSet<>();
        private boolean finalized = false;

        ContractNetSession(String conversationId, int invitedCount) {
            this.conversationId = conversationId;
            this.invitedCount = invitedCount;
        }

        synchronized boolean tryFinalize() {
            if (finalized) return false;
            finalized = true;
            return true;
        }

        /**
         * @return true if all invited responders have replied (early close), and evaluation should be triggered.
         */
        synchronized boolean recordResponse(String responderLocalName) {
            if (responderLocalName == null || invitedCount <= 0) {
                return false;
            }
            responders.add(responderLocalName);
            return responders.size() >= invitedCount;
        }
    }
}
