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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

public class DispatcherAgent extends Agent {

    // Contract-Net proposal collection window (PROPOSE / REFUSE)
    private static final long PROPOSAL_TIMEOUT = 6000;
    private static final long MIN_PROPOSAL_WAIT = 500;
    private static final long RETRY_DELAY = 5000;
    private static final long QUEUE_RETRY_PERIOD = 10000;

    private final ConcurrentHashMap<String, Emergency> emergencyData = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> emergencyServiceType = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, ProposalCollector> activeCollectors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> retryCounts = new ConcurrentHashMap<>();
    private static final int MAX_RETRIES = 3;
    private static final int MAX_QUEUE_ROUNDS = 5;
    private final ConcurrentHashMap<String, String> assignedUnits = new ConcurrentHashMap<>();
    private final Set<String> failedIncidents = ConcurrentHashMap.newKeySet();
    private final Set<String> resolvedIncidents = ConcurrentHashMap.newKeySet();
    private final Queue<String> queuedIncidents = new ConcurrentLinkedQueue<>();
    private final Set<String> queuedIncidentIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Integer> queueRounds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> queueSince = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> policeAssignments = new ConcurrentHashMap<>();
    private final Set<String> clearedByPolice = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, String> policeConversationParents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> policeConversationByIncident = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ContractNetSession> contractNetSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, IncidentStatus> incidentStatus = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<IncidentEvent>> incidentEvents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RetryContext> retryContextByIncident = new ConcurrentHashMap<>();

    // Track unit reservations: pending (during CNP) and busy (assigned/in-mission).
    private final Set<String> busyUnits = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingUnits = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, String> pendingUnitOwners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> pendingByConversation = new ConcurrentHashMap<>();
    private static final int MESSAGE_CACHE_LIMIT = 100;
    private final Set<String> recentMessageIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedDeque<String> recentMessageOrder = new ConcurrentLinkedDeque<>();
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
                            || content.equals("IDLE")
                            || content.startsWith("IDLE:")
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
            appendIncidentEvent(eId, new IncidentEvent.Detected(System.currentTimeMillis(), emergency.getType()));
            incidentStatus.putIfAbsent(eId, IncidentStatus.PENDING);
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
            // Invariant: perimeter assignment must be conditional on successful primary assignment.
            // Police CNP is started only after a primary unit is assigned (see evaluateAndAssign()).

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
            // case "BIOHAZARD":
            // case "CRYOGENIC_LEAK":
            //     return "HAZMAT"; // REMOVED - not in project specification
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
                startContractNetSession(conversationId, 0);
                return;
            }

            System.out.println("[" + getLocalName() + "] Found " + results.length + " unit(s) for " + serviceType);

            Set<String> invitedLocals = new LinkedHashSet<>();
            for (DFAgentDescription result : results) {
                AID unitAID = result.getName();
                String local = unitAID.getLocalName();
                synchronized (assignmentLock) {
                    if (busyUnits.contains(local) || pendingUnits.contains(local)) {
                        System.out.println("[" + getLocalName() + "] Skipping CFP to unavailable unit " + local);
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
            scheduleFinalizeRespectingMinWait(conversationId);
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
                    scheduleFinalizeRespectingMinWait(conversationId);
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
            boolean evaluateLateImmediately = false;
            if (collector == null) {
                if (!activeCollectors.containsKey(baseEId)
                        && emergencyData.containsKey(baseEId)
                        && !assignedUnits.containsKey(baseEId)
                        && !failedIncidents.contains(baseEId)) {
                    System.out.println("[" + getLocalName() + "] Late proposal from " + status.getUnitId()
                            + " accepted for unassigned incident " + baseEId + " (mini-collector)");
                    Emergency lateEmergency = emergencyData.get(baseEId);
                    String lateServiceType = emergencyServiceType.get(baseEId);
                    if (lateServiceType == null || lateServiceType.trim().isEmpty()) {
                        lateServiceType = determineServiceType(lateEmergency);
                        emergencyServiceType.put(baseEId, lateServiceType);
                    }
                    ProposalCollector miniCollector = new ProposalCollector(baseEId, lateServiceType, lateEmergency);
                    activeCollectors.put(baseEId, miniCollector);
                    collector = miniCollector;
                    eId = baseEId;
                    evaluateLateImmediately = true;
                } else {
                System.out.println("[" + getLocalName() + "] Late proposal from " + status.getUnitId()
                        + " - emergency already assigned or timed out");
                return;
                }
            }

            Emergency emergency = emergencyData.get(baseEId);
            if (emergency == null) {
                System.err.println("[" + getLocalName() + "] Missing emergency context for conversation " + eId
                        + " (base=" + baseEId + "), rejecting proposal from " + sender.getLocalName());
                rejectProposal(propose, "Missing emergency context for conversation " + eId);
                return;
            }
            String serviceType = isPoliceRole(eId) ? "CROWD_CONTROL" : emergencyServiceType.get(baseEId);
            String proposerName = sender.getLocalName();
            if (!tryReservePendingUnit(proposerName, eId)) {
                System.out.println("[" + getLocalName() + "] Ignoring proposal from " + proposerName
                        + " for " + eId + " because unit is already pending/busy in another incident");
                rejectProposal(propose, "Unit already reserved by another incident");
                return;
            }

            double utility = UtilityCalculator.calculateUtility(status, emergency, serviceType);
            boolean validPrimaryResponder = UtilityCalculator.isValidPrimaryResponder(status, emergency);
            if (isPoliceRole(eId) && status.getUnitId() != null) {
                String normalized = status.getUnitId().toLowerCase(Locale.ROOT);
                validPrimaryResponder = normalized.contains("police") || normalized.contains("firetruck");
            }

            System.out.println("[" + getLocalName() + "] Utility score for " + status.getUnitId()
                    + ": " + String.format("%.4f", utility)
                    + " (validPrimary=" + validPrimaryResponder + ")");

            collector.addProposal(sender, propose, utility, validPrimaryResponder);
            if (evaluateLateImmediately) {
                evaluateAndAssign(eId);
            }

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
            releaseAllPendingForConversation(eId);
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
            releaseAllPendingForConversation(eId);
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
            if (promotePendingToBusy(candidateName, eId)) {
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
            releaseAllPendingForConversation(eId);
            return;
        }

        AID winner = best.getKey();
        double bestUtility = best.getValue().utility;
        String winnerName = winner.getLocalName();

        System.out.println("[" + getLocalName() + "] WINNER: " + winnerName
                + " with utility " + String.format("%.4f", bestUtility));

        assignedUnits.put(eId, winnerName);
        appendIncidentEvent(baseIncidentId(eId), new IncidentEvent.Assigned(System.currentTimeMillis(), winnerName));
        compareAndSetIncidentStatus(baseIncidentId(eId), IncidentStatus.PENDING, IncidentStatus.ASSIGNED);
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
                releasePendingUnit(entry.getKey().getLocalName(), eId);
                ACLMessage reject = entry.getValue().originalMessage.createReply();
                reject.setPerformative(ACLMessage.REJECT_PROPOSAL);
                reject.setContent("Emergency " + eId + " assigned to " + winnerName);
                send(reject);
                System.out.println("[" + getLocalName() + "] -> REJECT sent to " + entry.getKey().getLocalName());
            }
        }

        System.out.println("[" + getLocalName() + "] === ASSIGNMENT COMPLETE for " + eId + " ===");
        retryCounts.remove(eId);
        pendingByConversation.remove(eId);

        // Invariant: perimeter assignment must never outlive primary assignment.
        // Start police perimeter CNP ONLY after a primary winner exists.
        if (!isPoliceRole(eId)) {
            maybeStartPolicePerimeterAfterPrimaryAssigned(baseIncidentId(eId));
        }
    }

    private void maybeStartPolicePerimeterAfterPrimaryAssigned(String baseIncidentId) {
        if (baseIncidentId == null) {
            return;
        }
        Emergency emergency = emergencyData.get(baseIncidentId);
        if (emergency == null || emergency.getType() == null) {
            return;
        }
        String type = emergency.getType();
        if (!type.equals("FIRE") && !type.equals("STRUCTURAL_COLLAPSE")) {
            return;
        }
        // Only run perimeter CFP if we still have a primary assignment winner.
        if (!assignedUnits.containsKey(baseIncidentId)) {
            return;
        }
        System.out.println("[" + getLocalName() + "] Primary assigned for " + baseIncidentId
                + " -> starting conditional police perimeter Contract Net");
        startPoliceContractNet(baseIncidentId, emergency);
    }

    private void scheduleRetry(String eId, Emergency emergency, String reason) {
        if (isIncidentClosed(eId)) {
            return;
        }

        String baseEId = baseIncidentId(eId);
        RetryContext ctx = retryContextByIncident.computeIfAbsent(baseEId, key -> new RetryContext());
        long now = System.currentTimeMillis();
        boolean shouldRetry = ctx.shouldRetryNow(now, reason, snapshotAvailableUnitsForService(baseEId, emergency));
        if (!shouldRetry) {
            System.out.println("[" + getLocalName() + "] Retry suppressed for " + baseEId
                    + " (reason=" + reason + ", lastFailure=" + ctx.lastFailureReason + ", backoffMs=" + ctx.currentBackoffMs + ")");
            return;
        }

        int retries = retryCounts.getOrDefault(eId, 0);
        if (retries >= MAX_RETRIES) {
            markIncidentFailed(baseEId, "max retries exceeded: " + reason);
            return;
        }

        retryCounts.put(eId, retries + 1);
        long delay = ctx.currentBackoffMs;
        System.out.println("[" + getLocalName() + "] Retrying " + baseEId + " after " + reason
                + " (" + (retries + 1) + "/" + MAX_RETRIES + ") in " + (delay / 1000) + "s");
        addBehaviour(new WakerBehaviour(this, delay) {
            @Override
            protected void onWake() {
                if (isIncidentClosed(eId)) {
                    return;
                }
                startContractNetRound(eId, emergency);
            }
        });
    }

    private Set<String> snapshotAvailableUnitsForService(String incidentId, Emergency emergency) {
        String baseEId = baseIncidentId(incidentId);
        String serviceType = emergencyServiceType.get(baseEId);
        if (serviceType == null || serviceType.trim().isEmpty()) {
            serviceType = determineServiceType(emergency);
        }
        // We cannot reliably ask agents for internal state here; snapshot "available" from DF minus busy/pending.
        Set<String> available = new LinkedHashSet<>();
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType(serviceType);
        template.addServices(sd);
        try {
            DFAgentDescription[] results = DFService.search(this, template);
            for (DFAgentDescription result : results) {
                String local = result.getName().getLocalName();
                synchronized (assignmentLock) {
                    if (busyUnits.contains(local) || pendingUnits.contains(local)) {
                        continue;
                    }
                }
                available.add(local);
            }
        } catch (FIPAException e) {
            // Treat DF failure as empty availability; retry gating will then rely on backoff.
        }
        return available;
    }

    private boolean isIncidentClosed(String eId) {
        IncidentStatus status = incidentStatus.get(baseIncidentId(eId));
        return status == IncidentStatus.RESOLVED
                || status == IncidentStatus.FAILED
                || status == IncidentStatus.ABORTED
                || failedIncidents.contains(eId)
                || resolvedIncidents.contains(baseIncidentId(eId))
                || assignedUnits.containsKey(eId);
    }

    private void queueIncident(String eId, Emergency emergency, String reason) {
        if (isIncidentClosed(eId)) {
            return;
        }

        activeCollectors.remove(eId);
        retryCounts.remove(eId);
        emergencyData.put(eId, emergency);
        emergencyServiceType.put(eId, determineServiceType(emergency));

        String baseEId = baseIncidentId(eId);
        emergencyData.put(baseEId, emergency);
        emergencyServiceType.put(baseEId, determineServiceType(emergency));

        if (queuedIncidentIds.add(eId)) {
            queuedIncidents.offer(eId);
            queueSince.putIfAbsent(eId, System.currentTimeMillis());
            System.out.println("[" + getLocalName() + "] *** INCIDENT QUEUED: " + eId
                    + " after " + reason + ". Waiting for available " + emergencyServiceType.get(eId)
                    + " unit. QueueDepth=" + queuedIncidents.size() + " ***");
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
        if (rounds >= MAX_QUEUE_ROUNDS) {
            markIncidentFailed(eId, "exceeded maximum queue rounds");
            return;
        }
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
        if (rounds >= MAX_QUEUE_ROUNDS) {
            markIncidentFailed(eId, "exceeded maximum queue rounds");
            return;
        }
        queueRounds.put(eId, rounds + 1);
        System.out.println("[" + getLocalName() + "] Periodic retry for queued incident " + eId
                + " (queue round " + (rounds + 1) + ")");
        startContractNetRound(eId, emergency);
    }

    private boolean isUnitCompatibleWithIncident(String unitName, Emergency emergency) {
        if (unitName == null || emergency == null) {
            return false;
        }

        String normalizedUnitName = unitName.toLowerCase(Locale.ROOT);
        switch (emergency.getType()) {
            case "FIRE":
            case "STRUCTURAL_COLLAPSE":
                return normalizedUnitName.contains("firetruck") || normalizedUnitName.contains("police");
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

        String baseEId = baseIncidentId(eId);
        Emergency emergencyContext = emergencyData.get(baseEId);
        if (emergencyContext != null) {
            emergency = emergencyContext;
        }
        if (emergency == null) {
            markIncidentFailed(eId, "missing emergency data");
            return;
        }

        retryCounts.remove(eId);
        String serviceType = emergencyServiceType.get(baseEId);
        if (serviceType == null || serviceType.trim().isEmpty()) {
            serviceType = determineServiceType(emergency);
            emergencyServiceType.put(baseEId, serviceType);
            System.out.println("[" + getLocalName() + "] Restored missing serviceType for " + baseEId + " => " + serviceType);
        }

        ProposalCollector collector = new ProposalCollector(eId, serviceType, emergency);
        activeCollectors.put(eId, collector);
        searchAndSendCFP(serviceType, emergency, eId);
        // Invariant: do not start perimeter CFP until primary is successfully assigned.

    }

    private void markIncidentFailed(String eId, String reason) {
        if (!failedIncidents.add(eId)) {
            return;
        }
        String baseEId = baseIncidentId(eId);
        appendIncidentEvent(baseEId, new IncidentEvent.Failed(System.currentTimeMillis(), reason));
        incidentStatus.put(baseEId, IncidentStatus.FAILED);

        activeCollectors.remove(eId);
        retryCounts.remove(eId);
        retryContextByIncident.remove(baseEId);
        assignedUnits.remove(eId);
        queuedIncidentIds.remove(eId);
        queuedIncidents.remove(eId);
        queueRounds.remove(eId);
        queueSince.remove(eId);
        policeConversationParents.remove(eId);
        policeConversationByIncident.remove(baseEId);
        releaseAllPendingForConversation(eId);
        releasePolicePerimeter(baseEId);

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
        if (content != null && (content.startsWith("UNIT_IDLE:")
                || content.equals("IDLE")
                || content.startsWith("IDLE:"))) {
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

        if ("MISSION_ARRIVED".equals(event)) {
            appendIncidentEvent(eId, new IncidentEvent.Arrived(System.currentTimeMillis(), unitName));
            compareAndSetIncidentStatus(eId, IncidentStatus.ASSIGNED, IncidentStatus.ACTIVE);
        }

        if ("MISSION_COMPLETE".equals(event)) {
            System.out.println("[" + getLocalName() + "] Mission complete for " + eId + " by " + unitName);
            appendIncidentEvent(eId, new IncidentEvent.Resolved(System.currentTimeMillis(), unitName));
            compareAndSetIncidentStatus(eId, IncidentStatus.ASSIGNED, IncidentStatus.RESOLVED);
            compareAndSetIncidentStatus(eId, IncidentStatus.ACTIVE, IncidentStatus.RESOLVED);
            resolvedIncidents.add(eId);
            assignedUnits.remove(eId);
            forceReleaseUnit(unitName);
            releasePolicePerimeter(eId);
            notifyLogger("RESOLVED:" + eId + ":" + unitName);
        }
    }

    private void handleUnitIdle(ACLMessage inform) {
        if (isDuplicateStatusMessage(inform)) {
            return;
        }
        String content = inform.getContent();
        String unitName = inform.getSender().getLocalName();
        if (content != null) {
            String[] parts = content.split(":");
            if (parts.length >= 2) {
                unitName = parts[1];
            }
        }
        boolean released = forceReleaseUnit(unitName);
        if (released) {
            System.out.println("[" + getLocalName() + "] Unit " + unitName + " is idle; freed from busy list");
        } else {
            System.out.println("[" + getLocalName() + "] Duplicate IDLE for " + unitName + " ignored (already free)");
        }
        retryQueuedIncidentsForUnit(unitName);
    }

    private void handleAbort(ACLMessage abort) {
        if (isDuplicateStatusMessage(abort)) {
            return;
        }
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
            forceReleaseUnit(unitName);
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
            appendIncidentEvent(abortedEmergencyId, new IncidentEvent.Aborted(System.currentTimeMillis(), unitName, reason));
            compareAndSetIncidentStatus(abortedEmergencyId, IncidentStatus.ASSIGNED, IncidentStatus.ABORTED);
            compareAndSetIncidentStatus(abortedEmergencyId, IncidentStatus.ACTIVE, IncidentStatus.ABORTED);
            if (policeAssignments.containsKey(abortedEmergencyId)) {
                System.out.println("[" + getLocalName() + "] Releasing perimeter due to primary abort on " + abortedEmergencyId);
                releasePolicePerimeter(abortedEmergencyId);
            }
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
        // Invariant: do not start perimeter CFP until primary is successfully assigned.

    }

    private void startPoliceContractNet(String eId, Emergency emergency) {
        String baseEId = baseIncidentId(eId);
        String policeKey = policeConversationByIncident.computeIfAbsent(
                baseEId, key -> "POLICE::PERIMETER::" + key
        );
        if (activeCollectors.containsKey(policeKey) || assignedUnits.containsKey(policeKey)) return;
        policeConversationParents.put(policeKey, baseEId);
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
                if ("CROWD_CONTROL".equals(serviceType)) {
                    int fallbackInvited = inviteFallbackRespondersForPerimeter(emergency, conversationId);
                    startContractNetSession(conversationId, fallbackInvited);
                } else {
                    startContractNetSession(conversationId, 0);
                }
                return;
            }
            Set<String> invitedLocals = new LinkedHashSet<>();
            for (DFAgentDescription result : results) {
                String local = result.getName().getLocalName();
                synchronized (assignmentLock) {
                    if (busyUnits.contains(local) || pendingUnits.contains(local)) {
                        System.out.println("[" + getLocalName() + "] Skipping CFP to unavailable unit " + local + " (" + conversationId + ")");
                        continue;
                    }
                }
                sendCFP(result.getName(), emergency, conversationId);
                invitedLocals.add(local);
            }
            if ("CROWD_CONTROL".equals(serviceType) && invitedLocals.isEmpty()) {
                int fallbackInvited = inviteFallbackRespondersForPerimeter(emergency, conversationId);
                startContractNetSession(conversationId, fallbackInvited);
                return;
            }

            startContractNetSession(conversationId, invitedLocals.size());
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }

    private int inviteFallbackRespondersForPerimeter(Emergency emergency, String conversationId) {
        int invited = 0;
        invited += inviteByServiceType("FIRE", emergency, conversationId);
        invited += inviteByServiceType("RESCUE", emergency, conversationId);
        if (invited > 0) {
            System.out.println("[" + getLocalName() + "] Using cross-type fallback for perimeter: invited "
                    + invited + " FIRE/RESCUE unit(s)");
        }
        return invited;
    }

    private int inviteByServiceType(String serviceType, Emergency emergency, String conversationId) {
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType(serviceType);
        template.addServices(sd);
        int invited = 0;
        try {
            DFAgentDescription[] results = DFService.search(this, template);
            for (DFAgentDescription result : results) {
                String local = result.getName().getLocalName();
                synchronized (assignmentLock) {
                    if (busyUnits.contains(local) || pendingUnits.contains(local)) {
                        continue;
                    }
                }
                sendCFP(result.getName(), emergency, conversationId);
                invited++;
            }
        } catch (FIPAException e) {
            e.printStackTrace();
        }
        return invited;
    }

    private boolean isPoliceRole(String eId) {
        return eId != null && policeConversationParents.containsKey(eId);
    }

    private String baseIncidentId(String eId) {
        if (eId == null) return null;
        String current = eId;
        while (current.startsWith("POLICE::PERIMETER::")) {
            String prefix = "POLICE::PERIMETER::";
            String remaining = current.substring(prefix.length());
            int suffixIdx = remaining.lastIndexOf("::");
            if (suffixIdx <= 0) {
                current = remaining;
                break;
            }
            current = remaining.substring(0, suffixIdx);
        }
        return current;
    }

    private void scheduleFinalizeRespectingMinWait(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return;
        }
        long remaining = 0;
        synchronized (contractNetSessions) {
            ContractNetSession session = contractNetSessions.get(conversationId);
            if (session != null) {
                long elapsed = System.currentTimeMillis() - session.startedAtMillis;
                remaining = Math.max(0, MIN_PROPOSAL_WAIT - elapsed);
            }
        }
        if (remaining == 0) {
            finalizeContractNet(conversationId);
            return;
        }
        addBehaviour(new WakerBehaviour(this, remaining) {
            @Override
            protected void onWake() {
                finalizeContractNet(conversationId);
            }
        });
    }

    private void releasePolicePerimeter(String eId) {
        String policeUnit = policeAssignments.remove(eId);
        if (policeUnit == null) return;
        ACLMessage release = new ACLMessage(ACLMessage.INFORM);
        release.addReceiver(new AID(policeUnit, AID.ISLOCALNAME));
        // Invariant: perimeter must be releasable even while police is EN_ROUTE.
        release.setContent("RELEASE_PERIMETER:" + eId);
        send(release);
        forceReleaseUnit(policeUnit);
        assignedUnits.entrySet().removeIf(entry ->
                policeUnit.equals(entry.getValue()) && isPoliceRole(entry.getKey()) && eId.equals(baseIncidentId(entry.getKey())));
        policeConversationByIncident.remove(eId);
    }

    // Visible for tests
    void testSeedEmergency(String incidentId, Emergency emergency) {
        emergencyData.put(incidentId, emergency);
        emergencyServiceType.put(incidentId, determineServiceType(emergency));
        incidentStatus.putIfAbsent(incidentId, IncidentStatus.PENDING);
    }

    // Visible for tests
    boolean testHasPoliceConversation(String incidentId) {
        return policeConversationByIncident.containsKey(incidentId);
    }

    // Visible for tests
    void testMarkPrimaryAssigned(String incidentId, String unit) {
        assignedUnits.put(incidentId, unit);
    }

    // Visible for tests
    void testMaybeStartPerimeter(String incidentId) {
        maybeStartPolicePerimeterAfterPrimaryAssigned(incidentId);
    }

    private boolean tryReservePendingUnit(String unitName, String conversationId) {
        synchronized (assignmentLock) {
            if (unitName == null || conversationId == null) {
                return false;
            }
            if (busyUnits.contains(unitName)) {
                return false;
            }
            String owner = pendingUnitOwners.get(unitName);
            if (owner != null && !owner.equals(conversationId)) {
                return false;
            }
            pendingUnits.add(unitName);
            pendingUnitOwners.put(unitName, conversationId);
            pendingByConversation.computeIfAbsent(conversationId, key -> new HashSet<>()).add(unitName);
            return true;
        }
    }

    private boolean promotePendingToBusy(String unitName, String conversationId) {
        synchronized (assignmentLock) {
            if (unitName == null || conversationId == null) {
                return false;
            }
            if (busyUnits.contains(unitName)) {
                return false;
            }
            String owner = pendingUnitOwners.get(unitName);
            if (owner != null && !owner.equals(conversationId)) {
                return false;
            }
            pendingUnits.remove(unitName);
            pendingUnitOwners.remove(unitName);
            Set<String> reserved = pendingByConversation.get(conversationId);
            if (reserved != null) {
                reserved.remove(unitName);
                if (reserved.isEmpty()) {
                    pendingByConversation.remove(conversationId);
                }
            }
            busyUnits.add(unitName);
            return true;
        }
    }

    private void releasePendingUnit(String unitName, String conversationId) {
        synchronized (assignmentLock) {
            if (unitName == null) {
                return;
            }
            String owner = pendingUnitOwners.get(unitName);
            if (owner == null || (conversationId != null && !owner.equals(conversationId))) {
                return;
            }
            pendingUnits.remove(unitName);
            pendingUnitOwners.remove(unitName);
            Set<String> reserved = pendingByConversation.get(owner);
            if (reserved != null) {
                reserved.remove(unitName);
                if (reserved.isEmpty()) {
                    pendingByConversation.remove(owner);
                }
            }
        }
    }

    private void releaseAllPendingForConversation(String conversationId) {
        synchronized (assignmentLock) {
            Set<String> units = pendingByConversation.remove(conversationId);
            if (units == null) {
                return;
            }
            for (String unit : units) {
                String owner = pendingUnitOwners.get(unit);
                if (conversationId.equals(owner)) {
                    pendingUnitOwners.remove(unit);
                    pendingUnits.remove(unit);
                }
            }
        }
    }

    private boolean forceReleaseUnit(String unitName) {
        if (unitName == null) return false;
        synchronized (assignmentLock) {
            boolean removed = busyUnits.remove(unitName);
            String owner = pendingUnitOwners.remove(unitName);
            if (owner != null) {
                pendingUnits.remove(unitName);
                Set<String> reserved = pendingByConversation.get(owner);
                if (reserved != null) {
                    reserved.remove(unitName);
                    if (reserved.isEmpty()) {
                        pendingByConversation.remove(owner);
                    }
                }
                removed = true;
            }
            return removed;
        }
    }

    private String pollQueuedIncidentByPriority(String preferredUnitName) {
        if (queuedIncidents.isEmpty()) return null;

        String bestId = null;
        int bestHazmatPriority = Integer.MIN_VALUE;
        int bestSeverity = Integer.MIN_VALUE;
        int bestRound = Integer.MIN_VALUE;
        long bestSince = Long.MAX_VALUE;

        for (String id : queuedIncidents) {
            Emergency emergency = emergencyData.get(id);
            if (emergency == null) continue;
            if (preferredUnitName != null && !isUnitCompatibleWithIncident(preferredUnitName, emergency)) continue;

            int severity = emergency.getSeverity();
            int hazmatPriority = isHazmatEmergency(emergency) ? 1 : 0;
            int rounds = queueRounds.getOrDefault(id, 0);
            long since = queueSince.getOrDefault(id, Long.MAX_VALUE);
            if (hazmatPriority > bestHazmatPriority
                    || (hazmatPriority == bestHazmatPriority && severity > bestSeverity)
                    || (hazmatPriority == bestHazmatPriority && severity == bestSeverity && rounds > bestRound)
                    || (hazmatPriority == bestHazmatPriority && severity == bestSeverity && rounds == bestRound && since < bestSince)) {
                bestHazmatPriority = hazmatPriority;
                bestSeverity = severity;
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

    private boolean isHazmatEmergency(Emergency emergency) {
        if (emergency == null || emergency.getType() == null) {
            return false;
        }
        String type = emergency.getType().toUpperCase(Locale.ROOT);
        return "BIOHAZARD".equals(type) || "CRYOGENIC_LEAK".equals(type);
    }

    private boolean compareAndSetIncidentStatus(String incidentId, IncidentStatus expected, IncidentStatus next) {
        if (incidentId == null) {
            return false;
        }
        return incidentStatus.compute(incidentId, (key, current) -> {
            if (current == null) {
                return expected == null ? next : null;
            }
            return current == expected ? next : current;
        }) == next;
    }

    private void appendIncidentEvent(String incidentId, IncidentEvent event) {
        if (incidentId == null || event == null) {
            return;
        }
        incidentEvents.computeIfAbsent(incidentId, key -> new ConcurrentLinkedQueue<>()).add(event);
    }

    private boolean isDuplicateStatusMessage(ACLMessage msg) {
        String sender = msg.getSender() != null ? msg.getSender().getLocalName() : "unknown";
        String content = msg.getContent() != null ? msg.getContent() : "";
        String key = sender + "|" + content;
        synchronized (recentMessageOrder) {
            if (recentMessageIds.contains(key)) {
                return true;
            }
            recentMessageIds.add(key);
            recentMessageOrder.addLast(key);
            while (recentMessageOrder.size() > MESSAGE_CACHE_LIMIT) {
                String evicted = recentMessageOrder.pollFirst();
                if (evicted != null) {
                    recentMessageIds.remove(evicted);
                }
            }
            return false;
        }
    }

    // Visible for tests
    boolean testTransitionStatus(String incidentId, String expected, String next) {
        IncidentStatus expectedStatus = expected == null ? null : IncidentStatus.valueOf(expected);
        IncidentStatus nextStatus = IncidentStatus.valueOf(next);
        return compareAndSetIncidentStatus(incidentId, expectedStatus, nextStatus);
    }

    // Visible for tests
    String testCurrentStatus(String incidentId) {
        IncidentStatus status = incidentStatus.get(incidentId);
        return status == null ? null : status.name();
    }

    // Visible for tests
    int testEventCount(String incidentId) {
        Queue<IncidentEvent> events = incidentEvents.get(incidentId);
        return events == null ? 0 : events.size();
    }

    // Visible for tests
    void testAppendDetected(String incidentId, String type) {
        appendIncidentEvent(incidentId, new IncidentEvent.Detected(System.currentTimeMillis(), type));
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

    private enum IncidentStatus {
        PENDING, ASSIGNED, ACTIVE, RESOLVED, ABORTED, FAILED
    }

    private sealed interface IncidentEvent permits IncidentEvent.Detected, IncidentEvent.Assigned,
            IncidentEvent.Arrived, IncidentEvent.Aborted, IncidentEvent.Resolved, IncidentEvent.Failed {
        long ts();

        record Detected(long ts, String type) implements IncidentEvent {}
        record Assigned(long ts, String unitName) implements IncidentEvent {}
        record Arrived(long ts, String unitName) implements IncidentEvent {}
        record Aborted(long ts, String unitName, String reason) implements IncidentEvent {}
        record Resolved(long ts, String unitName) implements IncidentEvent {}
        record Failed(long ts, String reason) implements IncidentEvent {}
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
        private final long startedAtMillis;
        private final Set<String> responders = new HashSet<>();
        private boolean finalized = false;

        ContractNetSession(String conversationId, int invitedCount) {
            this.conversationId = conversationId;
            this.invitedCount = invitedCount;
            this.startedAtMillis = System.currentTimeMillis();
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

    /**
     * Per-incident retry memory to avoid blind, identical retries when nothing changed.
     * Invariant: we only retry if something improved OR exponential backoff elapsed.
     */
    private static final class RetryContext {
        private long lastRetryTimeMs = 0;
        private String lastFailureReason = null;
        private Set<String> availableUnitsAtLastRetry = Set.of();
        private long currentBackoffMs = RETRY_DELAY; // 5s, 10s, 20s, max 60s

        synchronized boolean shouldRetryNow(long nowMs, String failureReason, Set<String> availableNow) {
            if (availableNow == null) {
                availableNow = Set.of();
            }
            boolean unitBecameAvailable = availableUnitsAtLastRetry == null
                    || (!availableNow.isEmpty() && !availableNow.equals(availableUnitsAtLastRetry));
            boolean backoffElapsed = lastRetryTimeMs == 0 || (nowMs - lastRetryTimeMs) >= currentBackoffMs;

            // Update context only if we are going to schedule a retry.
            if (unitBecameAvailable || backoffElapsed) {
                lastRetryTimeMs = nowMs;
                lastFailureReason = failureReason;
                availableUnitsAtLastRetry = Set.copyOf(availableNow);
                currentBackoffMs = Math.min(60_000, currentBackoffMs * 2);
                return true;
            }
            lastFailureReason = failureReason;
            return false;
        }
    }

    // Visible for tests
    boolean testRetryGate(String incidentId, long nowMs, String reason, Set<String> availableNow) {
        RetryContext ctx = retryContextByIncident.computeIfAbsent(incidentId, key -> new RetryContext());
        return ctx.shouldRetryNow(nowMs, reason, availableNow);
    }

    // Visible for tests
    void testMarkFailed(String incidentId, String reason) {
        markIncidentFailed(incidentId, reason);
    }

    // Visible for tests
    boolean testIsFailed(String incidentId) {
        return failedIncidents.contains(incidentId) || incidentStatus.get(incidentId) == IncidentStatus.FAILED;
    }
}
