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
    private final ConcurrentHashMap<String, UnitStatus> unitStatusMap = new ConcurrentHashMap<>();
    private int abortedCount = 0;
    private int unresolvedCount = 0;
    private int incidentCount = 0;
    private int resolvedCount = 0;

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
                                        findAgentByService("TRAFFIC_CONTROL")))));
                ACLMessage msg = receive(mt);
                if (msg != null) {
                    handleInformMessage(msg);
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
                        MessageTemplate.MatchOntology(EmergencyOntology.getInstance().getName()));
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
                // Contract Net responses are often plain-text (e.g., LOW_WATER); do not require
                // ontology match here.
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.REFUSE);
                ACLMessage msg = receive(mt);
                if (msg != null) {
                    handleRefuse(msg);
                } else {
                    block();
                }
            }
        });

                // Old 4th behaviour for string-based INFORM matching has been completely removed
                // since all INFORM messages now use setContentObject and EmergencyOntology
                // and are caught by the 1st behaviour (now handleInformMessage).


        addBehaviour(new TickerBehaviour(this, QUEUE_RETRY_PERIOD) {
            @Override
            protected void onTick() {
                retryNextQueuedIncident();
            }
        });
    }
    // setup(): Registers in the DF, prints the utility formula, then installs five
    // behaviours:
    // 1. INFORM + emergency ontology → dispatches new emergencies
    // (handleEmergencyAlert)
    // 2. PROPOSE → collects unit bids (handlePropose)
    // 3. REFUSE → records refusals (handleRefuse)
    // 4. INFORM with status keywords → handles unit lifecycle events
    // (handleUnitStatusInform)
    // 5. TickerBehaviour (10 s) → periodically retries queued incidents

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
    // registerInDF(): Publishes this agent in the DF under service type
    // "COORDINATION" so
    // sensors, ambulances, fire trucks, and police units can all find the
    // dispatcher via
    // a standard DF lookup when they need to send lifecycle or alert messages.

    private AID findAgentByService(String serviceType) {
        return com.umbb.sruu.utils.AgentUtils.findAgentByService(this, serviceType);
    }
    // findAgentByService(): Delegates to AgentUtils for a DF lookup by service type
    // and
    // returns the AID of the first matching agent, or null if none is registered.

    private void handleInformMessage(ACLMessage msg) {
        Object contentObj = null;
        try {
            contentObj = msg.getContentObject();
        } catch (Exception e) {
            String content = msg.getContent();
            if (content != null && !content.isEmpty()) {
                System.out.println("[" + getLocalName() + "] Note: Plain text from " + msg.getSender().getLocalName() + " - fallback to string parsing");
                // The existing logic already expects objects, so if we get a string here, 
                // we'd normally need to parse it. For now, we follow the requested pattern.
            }
        }

        try {
            if (contentObj == null) {
                // If object extraction failed, we might still proceed if we had string parsing logic.
            }

            if (contentObj instanceof Emergency emergency) {
                handleEmergencyAlert(emergency, msg);
                return;
            }

            if (contentObj instanceof UnitStatus unitStatus) {
                updateUnitTracking(unitStatus, msg);
                return;
            }

            if (contentObj instanceof MissionArrived missionArrived) {
                handleLifecycleArrived(missionArrived);
                return;
            }

            if (contentObj instanceof MissionComplete missionComplete) {
                handleLifecycleComplete(missionComplete);
                return;
            }

            if (contentObj instanceof PerimeterSecured perimeterSecured) {
                handlePerimeterSecured(perimeterSecured);
                return;
            }

            if (contentObj instanceof HospitalSaturation saturation) {
                handleHospitalSaturationObj(saturation);
                return;
            }

            if (contentObj instanceof UnitAbort unitAbort) {
                handleAbortObj(unitAbort, msg);
                return;
            }

            if (contentObj instanceof MissionAbort missionAbort) {
                handleMissionAbort(missionAbort, msg);
                return;
            }

            if (contentObj instanceof RouteCleared routeCleared) {
                System.out.println("[" + getLocalName() + "] RouteCleared received from " + msg.getSender().getLocalName());
                notifyLogger(routeCleared);
                return;
            }

            System.out.println("[" + getLocalName() + "] Unknown ontology content received from " 
                    + msg.getSender().getLocalName() + ": "
                    + (contentObj != null ? contentObj.getClass().getSimpleName() : "null") + " (ignored)");

        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error in handleInformMessage: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void updateUnitTracking(UnitStatus status, ACLMessage msg) {
        String unitName = status.getUnitName();
        if (unitName == null || unitName.isEmpty()) {
            unitName = msg.getSender().getLocalName();
            status.setUnitName(unitName);
        }
        
        unitStatusMap.put(unitName, status);
        System.out.println("[" + getLocalName() + "] STATUS UPDATE from " + unitName 
            + ": state=" + status.getState() + ", water=" + status.getWater() + "%, pos=" + status.getPosition());
            
        if ("IDLE".equals(status.getState())) {
            handleUnitIdle(unitName, msg);
        }
        
        notifyLogger(status);
    }

    private void handleEmergencyAlert(Emergency emergency, ACLMessage msg) {
        System.out.println("[" + getLocalName() + "] *** NEW EMERGENCY: " + emergency);
        System.out.println("[" + getLocalName() + "] From: " + msg.getSender().getLocalName());

        String eId = emergency.getId();
        incidentCount++;
        appendIncidentEvent(eId, new IncidentEvent.Detected(System.currentTimeMillis(), emergency.getType()));
        incidentStatus.putIfAbsent(eId, IncidentStatus.PENDING);
        
        notifyLogger(emergency);

        if (failedIncidents.contains(eId)) {
            System.out.println("[" + getLocalName() + "] Ignoring emergency " + eId
                    + " because it is already marked FAILED");
            return;
        }
        if (assignedUnits.containsKey(eId) || activeCollectors.containsKey(eId)
                || queuedIncidentIds.contains(eId)) {
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
    }
    // handleEmergencyAlert(): Processes an INFORM message carrying a HasEmergency
    // or RouteCleared
    // ontology predicate. For HasEmergency it deduplicates the alert, stores the
    // emergency,
    // determines the required service type, requests traffic clearance, and starts
    // a Contract-Net
    // CFP round. For RouteCleared it logs the route and returns. Unknown predicates
    // are ignored.

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
            case "MEDICAL":
                return "MEDICAL";
            default:
                System.out.println("[" + getLocalName() + "] Unknown emergency type '" + emergency.getType()
                        + "' -> fallback service type RESCUE");
                return "RESCUE";
        }
    }
    // determineServiceType(): Maps the emergency type string to a DF service type:
    // FIRE → "FIRE"
    // STRUCTURAL_COLLAPSE → "RESCUE"
    // MEDICAL → "MEDICAL"
    // anything else → "RESCUE" (safe fallback, logged as a warning)

    private void requestTrafficClearance(Emergency emergency) {
        ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
        AID tc = findAgentByService("TRAFFIC_CONTROL");
        if (tc != null)
            request.addReceiver(tc);
        request.setOntology(EmergencyOntology.getInstance().getName());
        request.setLanguage(new SLCodec().getName());
        Location emergencyLoc = emergency.getLocation();
        try {
            RouteCleared rc = new RouteCleared();
            rc.setFrom(new Location(0, 0));
            rc.setTo(emergencyLoc);
            rc.setExpirationSeconds(30); // Requested duration
            request.setContentObject(rc);
            send(request);
            System.out.println("[" + getLocalName() + "] Requested traffic clearance to " + emergencyLoc);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    // requestTrafficClearance(): Sends a ROUTE request to the
    // TrafficControllerAgent so it
    // can clear a path from the origin (0,0) to the emergency location and notify
    // field units.

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
    // searchAndSendCFP(): Queries the DF for all agents of the required service
    // type, skips
    // units already busy or pending, sends a CFP to each available unit, and opens
    // a
    // ContractNetSession to track responses. If no units are found, opens a
    // zero-invited
    // session which immediately triggers a retry/queue path.


    // sendCFP(AID, Emergency): Convenience overload that uses the emergency's own
    // ID as the
    // conversation ID, delegating to the full three-argument version.

    private void sendCFP(AID unit, Emergency emergency, String conversationId) {
        ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
        cfp.addReceiver(unit);
        cfp.setLanguage(new SLCodec().getName());
        cfp.setOntology(EmergencyOntology.getInstance().getName());
        cfp.setConversationId(conversationId);

        try {
            cfp.setContentObject(emergency);
            send(cfp);
            System.out.println("[" + getLocalName() + "] Sent CFP to " + unit.getLocalName());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    // sendCFP(AID, Emergency, String): Builds a CFP ACL message wrapping the
    // emergency inside
    // a HasEmergency ontology predicate, sets the given conversationId, and sends
    // it to the
    // specified unit. Logs the send on success; prints stack trace on serialisation
    // failure.

    /**
     * Tracks a Contract Net round for a given conversation id.
     * We wait up to {@link #PROPOSAL_TIMEOUT}ms OR until all invited responders
     * reply (PROPOSE/REFUSE).
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
    // startContractNetSession(): Opens a ContractNetSession for the given
    // conversationId,
    // recording how many units were invited. Schedules a WakerBehaviour for
    // PROPOSAL_TIMEOUT
    // (6 s) that calls finalizeContractNet() if not already finalized. If no units
    // were
    // invited (invitedCount ≤ 0) it finalizes immediately, triggering a
    // retry/queue.

    /**
     * Idempotent finalize: evaluates winner once per conversation id (timeout or
     * early all-replies).
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
    // finalizeContractNet(): Idempotent — uses ContractNetSession.tryFinalize() to
    // guarantee
    // evaluateAndAssign() is called exactly once per conversation, whether
    // triggered by the
    // timeout WakerBehaviour or by early completion when all invited units have
    // replied.

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
    // handleRefuse(): Records the refusing unit's response in the active
    // ContractNetSession.
    // If all invited units have now replied (allRepliesIn), it schedules
    // finalization while
    // respecting the MIN_PROPOSAL_WAIT (500 ms) window for any remaining late
    // proposals.

    private void handlePropose(ACLMessage propose) {
        Object contentObj = null;
        try {
            contentObj = propose.getContentObject();
        } catch (Exception e) {
            String content = propose.getContent();
            if (content != null && content.startsWith("PROPOSE:")) {
                 // handle legacy string propose if needed
            }
        }

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
            
            UnitStatus status = (UnitStatus) contentObj;
            if (status == null) return;
            
            AID sender = propose.getSender();
            String eId = propose.getConversationId();
            String baseEId = baseIncidentId(eId);

            System.out.println("[" + getLocalName() + "] RECEIVED PROPOSE from " + status.getUnitName()
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
                    System.out.println("[" + getLocalName() + "] Late proposal from " + status.getUnitName()
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
                    System.out.println("[" + getLocalName() + "] Late proposal from " + status.getUnitName()
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
            if (isPoliceRole(eId) && status.getUnitName() != null) {
                String normalized = status.getUnitName().toLowerCase(java.util.Locale.ROOT);
                validPrimaryResponder = normalized.contains("police") || normalized.contains("firetruck");
            }

            System.out.println("[" + getLocalName() + "] Utility score for " + status.getUnitName()
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
    // handlePropose(): Receives a PROPOSE from a field unit. Records the reply in
    // the session
    // for early-close detection. Validates the emergency context, atomically
    // reserves the
    // proposing unit as "pending", computes its utility score, adds it to the
    // ProposalCollector,
    // and for late proposals (no active collector) it builds a mini-collector and
    // evaluates
    // immediately. Rejects proposals for closed incidents outright.

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
        
        Assignment assignment = new Assignment();
        assignment.setEmergencyId(eId);
        assignment.setUnitName(winnerName);
        assignment.setRole(isPoliceRole(eId) ? "POLICE" : "PRIMARY");

        try {
            accept.setContent("ASSIGNMENT:" + eId + ":" + winnerName);
            accept.setContentObject(assignment);
            send(accept);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        
        notifyLogger(assignment);
        System.out.println("[" + getLocalName() + "] -> ACCEPT sent to " + winnerName);

        for (Map.Entry<AID, ProposalData> entry : collector.getAll().entrySet()) {
            if (!entry.getKey().equals(winner)) {
                releasePendingUnit(entry.getKey().getLocalName(), eId);
                ACLMessage reject = entry.getValue().originalMessage.createReply();
                reject.setPerformative(ACLMessage.REJECT_PROPOSAL);
                
                // DUAL-MODE for REJECT
                String reason = "Emergency " + eId + " assigned to " + winnerName;
                reject.setContent(reason);
                try {
                    Assignment rejAssignment = new Assignment();
                    rejAssignment.setEmergencyId(eId);
                    rejAssignment.setUnitName(entry.getKey().getLocalName());
                    rejAssignment.setRole("REJECTED");
                    reject.setContentObject(rejAssignment);
                } catch (Exception e) {}
                
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
    // evaluateAndAssign(): Ranks all valid primary-responder proposals by utility
    // score,
    // atomically promotes the best available candidate from pending to busy, sends
    // ACCEPT_PROPOSAL to the winner and REJECT_PROPOSAL to all others, updates
    // incident
    // status to ASSIGNED, and (for non-police roles) triggers the police perimeter
    // CNP.
    // Falls back to scheduleRetry() when no valid proposals exist.

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
    // maybeStartPolicePerimeterAfterPrimaryAssigned(): Guards the police perimeter
    // CFP:
    // only starts it when the incident is a FIRE or STRUCTURAL_COLLAPSE AND a
    // primary unit
    // has already been successfully assigned. This prevents a perimeter from being
    // set up
    // before the main responder is confirmed.

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
                    + " (reason=" + reason + ", lastFailure=" + ctx.lastFailureReason + ", backoffMs="
                    + ctx.currentBackoffMs + ")");
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
    // scheduleRetry(): Uses RetryContext's exponential-backoff logic (starting at
    // RETRY_DELAY=5 s,
    // doubling up to 60 s) to decide whether a retry is warranted. Suppresses
    // identical retries
    // when nothing changed (same failure reason, no new units became available).
    // After MAX_RETRIES
    // the incident is marked FAILED.

    private Set<String> snapshotAvailableUnitsForService(String incidentId, Emergency emergency) {
        String baseEId = baseIncidentId(incidentId);
        String serviceType = emergencyServiceType.get(baseEId);
        if (serviceType == null || serviceType.trim().isEmpty()) {
            serviceType = determineServiceType(emergency);
        }
        // We cannot reliably ask agents for internal state here; snapshot "available"
        // from DF minus busy/pending.
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
            // Treat DF failure as empty availability; retry gating will then rely on
            // backoff.
        }
        return available;
    }
    // snapshotAvailableUnitsForService(): Queries the DF for all agents of the
    // required service
    // type and returns those not currently in busyUnits or pendingUnits. Used by
    // RetryContext to
    // detect whether new units became available since the last retry, enabling
    // smarter retry gating.

    private boolean isIncidentClosed(String eId) {
        IncidentStatus status = incidentStatus.get(baseIncidentId(eId));
        return status == IncidentStatus.RESOLVED
                || status == IncidentStatus.FAILED
                || status == IncidentStatus.ABORTED
                || failedIncidents.contains(eId)
                || resolvedIncidents.contains(baseIncidentId(eId))
                || assignedUnits.containsKey(eId);
    }
    // isIncidentClosed(): Returns true if the incident is in a terminal state
    // (RESOLVED, FAILED,
    // ABORTED), is in the failedIncidents set, is in resolvedIncidents, or already
    // has an assigned
    // unit — used as a universal guard to prevent processing stale messages or
    // duplicate actions.

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
    // queueIncident(): Moves an incident into the waiting queue when all units are
    // busy.
    // Stores emergency data for both the raw ID and base ID, adds to
    // queuedIncidents (FIFO)
    // with a timestamp for priority scoring, and logs the queue depth.
    // De-duplicates by checking
    // queuedIncidentIds before adding.

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
    // retryQueuedIncidentsForUnit(): Triggered when a specific unit becomes idle.
    // Dequeues the
    // highest-priority compatible incident (matching unit type) and starts a new
    // CNP round for it.
    // Respects MAX_QUEUE_ROUNDS to prevent infinite retrying of hopeless incidents.

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
    // retryNextQueuedIncident(): Called by the periodic TickerBehaviour (every 10
    // s) to dequeue
    // and retry the highest-priority waiting incident regardless of which unit just
    // freed up.
    // Skips incidents that already have an active collector (CFP round in progress)
    // by re-queuing
    // them. Respects MAX_QUEUE_ROUNDS.

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
            default:
                return false;
        }
    }
    // isUnitCompatibleWithIncident(): Checks whether a unit's name prefix matches
    // the service
    // type required by the incident (firetrucks/police for FIRE or
    // STRUCTURAL_COLLAPSE,
    // ambulances for MEDICAL). Used when dequeuing to avoid sending CFPs to
    // incompatible units.

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
            System.out.println(
                    "[" + getLocalName() + "] Restored missing serviceType for " + baseEId + " => " + serviceType);
        }

        ProposalCollector collector = new ProposalCollector(eId, serviceType, emergency);
        activeCollectors.put(eId, collector);
        searchAndSendCFP(serviceType, emergency, eId);
        // Invariant: do not start perimeter CFP until primary is successfully assigned.

    }
    // startContractNetRound(): Starts a full new CNP round for an existing
    // incident.
    // Restores missing serviceType from emergencyServiceType or falls back to
    // determineServiceType().
    // Creates a fresh ProposalCollector, registers it in activeCollectors, and
    // sends CFPs.
    // Skips incidents that are closed or already have an active collector.

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
    // markIncidentFailed(): Atomically marks the incident as FAILED (guarded by
    // failedIncidents
    // set for idempotency). Removes all associated state (collector, retries, queue
    // entries,
    // police conversation) and sends a FAILURE notification to the logger.

    private void notifyFailure(String eId, String reason) {
        ACLMessage failure = new ACLMessage(ACLMessage.FAILURE);
        failure.setOntology(EmergencyOntology.getInstance().getName());
        failure.setLanguage(new SLCodec().getName());
        AID logger = findAgentByService("AUDIT");
        if (logger != null)
            failure.addReceiver(logger);
        try {
            IncidentFailed failed = new IncidentFailed();
            failed.setEmergencyId(eId);
            failed.setReason(reason);
            failure.setContent("INCIDENT_FAILED:" + eId + ":" + reason);
            failure.setContentObject(failed);
            send(failure);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    // notifyFailure(): Sends an INCIDENT_FAILED:<id>:<reason> FAILURE message to
    // the logger
    // so the final report correctly counts this incident as failed.

    private void rejectProposal(ACLMessage proposal, String reason) {
        ACLMessage reject = proposal.createReply();
        reject.setPerformative(ACLMessage.REJECT_PROPOSAL);
        reject.setContent(reason);
        send(reject);
    }
    // rejectProposal(): Creates a REJECT_PROPOSAL reply for a single proposal
    // message and sends
    // it immediately, releasing the unit so it can accept other assignments.

    private void rejectAll(ProposalCollector collector, String reason) {
        for (Map.Entry<AID, ProposalData> entry : collector.getAll().entrySet()) {
            ACLMessage reject = entry.getValue().originalMessage.createReply();
            reject.setPerformative(ACLMessage.REJECT_PROPOSAL);
            reject.setContent(reason);
            send(reject);
        }
    }
    // rejectAll(): Sends REJECT_PROPOSAL to every unit in the given
    // ProposalCollector so all
    // non-winners are freed and can participate in future CFP rounds.

    private void handleHospitalSaturationObj(HospitalSaturation saturation) {
        String hospitalName = saturation.getHospitalName();
        int availableBeds = saturation.getAvailableBeds();
        int totalBeds = saturation.getTotalBeds();
        double percent = saturation.getPercent();

        System.out.println("[" + getLocalName() + "] ALERT: " + hospitalName
                + " bed saturation, available=" + availableBeds + "/" + totalBeds
                + " (" + percent + "%)");
        notifyLogger(saturation);
    }

    private void handlePerimeterSecured(PerimeterSecured event) {
        String eId = event.getEmergencyId();
        String unitName = event.getUnitName();
        notifyLogger(event);
        clearedByPolice.add(eId);
        policeAssignments.put(eId, unitName);
        System.out.println("[" + getLocalName() + "] Police perimeter secured for " + eId + " by " + unitName);
    }

    private void handleLifecycleArrived(MissionArrived event) {
        String eId = event.getEmergencyId();
        String unitName = event.getUnitName();
        notifyLogger(event);
        appendIncidentEvent(eId, new IncidentEvent.Arrived(System.currentTimeMillis(), unitName));
        compareAndSetIncidentStatus(eId, IncidentStatus.ASSIGNED, IncidentStatus.ACTIVE);
    }

    private void handleLifecycleComplete(MissionComplete event) {
        String eId = event.getEmergencyId();
        String unitName = event.getUnitName();
        notifyLogger(event);
        System.out.println("[" + getLocalName() + "] Mission complete for " + eId + " by " + unitName);
        appendIncidentEvent(eId, new IncidentEvent.Resolved(System.currentTimeMillis(), unitName));
        compareAndSetIncidentStatus(eId, IncidentStatus.ASSIGNED, IncidentStatus.RESOLVED);
        compareAndSetIncidentStatus(eId, IncidentStatus.ACTIVE, IncidentStatus.RESOLVED);
        resolvedIncidents.add(eId);
        resolvedCount++;
        assignedUnits.remove(eId);
        forceReleaseUnit(unitName);
        releasePolicePerimeter(eId);
    }

    private void handleUnitIdle(String unitName, ACLMessage msg) {
        if (isDuplicateStatusMessage(msg)) {
            return;
        }
        if (unitName == null || unitName.isEmpty()) {
            unitName = msg.getSender().getLocalName();
        }
        boolean released = forceReleaseUnit(unitName);
        if (released) {
            System.out.println("[" + getLocalName() + "] Unit " + unitName + " is idle; freed from busy list");
        } else {
            System.out.println("[" + getLocalName() + "] Duplicate IDLE for " + unitName + " ignored (already free)");
        }
        retryQueuedIncidentsForUnit(unitName);
    }
    private void handleAbortObj(UnitAbort unitAbort, ACLMessage msg) {
        if (isDuplicateStatusMessage(msg)) {
            return;
        }

        String unitName = unitAbort.getUnitName();
        String reason = unitAbort.getReason();
        String emergencyId = unitAbort.getEmergencyId();
        boolean shouldReassign = unitAbort.isReassign();

        if (unitName == null || unitName.isEmpty()) {
            unitName = msg.getSender().getLocalName();
        }

        System.out.println("[" + getLocalName() + "] RECEIVED ABORT for unit " + unitName);
        notifyLogger(unitAbort);

        forceReleaseUnit(unitName);
        System.out.println("[" + getLocalName() + "] Unit " + unitName + " aborted. Reason: " + reason);

        if (emergencyId != null && !emergencyId.equals("UNKNOWN")) {
            if (failedIncidents.contains(emergencyId)) {
                System.out.println("[" + getLocalName() + "] Ignoring abort for failed incident " + emergencyId);
                return;
            }

            System.out.println("[" + getLocalName() + "] Unit " + unitName + " aborted " + emergencyId
                    + " due to: " + reason);

            Emergency emergency = emergencyData.get(emergencyId);
            String assignedUnit = assignedUnits.get(emergencyId);
            boolean abortingAssignedUnit = unitName.equals(assignedUnit);

            if (!abortingAssignedUnit) {
                System.out.println("[" + getLocalName() + "] Ignoring abort for reassignment because "
                        + unitName + " is not assigned to " + emergencyId);
                return;
            }

            assignedUnits.remove(emergencyId);
            appendIncidentEvent(emergencyId,
                    new IncidentEvent.Aborted(System.currentTimeMillis(), unitName, reason));
            compareAndSetIncidentStatus(emergencyId, IncidentStatus.ASSIGNED, IncidentStatus.ABORTED);
            compareAndSetIncidentStatus(emergencyId, IncidentStatus.ACTIVE, IncidentStatus.ABORTED);
            if (policeAssignments.containsKey(emergencyId)) {
                System.out.println(
                        "[" + getLocalName() + "] Releasing perimeter due to primary abort on " + emergencyId);
                releasePolicePerimeter(emergencyId);
            }
            notifyLogger(unitAbort);
            if (shouldReassign && emergency != null) {
                scheduleImmediateRetry(emergencyId, emergency, "assigned unit aborted: " + reason);
            }
        }
        retryQueuedIncidentsForUnit(unitName);
    }

    private void handleMissionAbort(MissionAbort ma, ACLMessage msg) {
        String eId = ma.getEmergencyId();
        String reason = ma.getReason();
        String sender = msg.getSender().getLocalName();

        System.out.println("[" + getLocalName() + "] *** MISSION ABORTED by " + sender + " for " + eId + " reason: " + reason + " ***");
        
        notifyLogger(ma);
        
        // Mark emergency as unresolved in our tracking
        forceReleaseUnit(sender);
        
        if (eId != null && !eId.equals("UNKNOWN")) {
            assignedUnits.remove(eId);
            appendIncidentEvent(eId, new IncidentEvent.Aborted(System.currentTimeMillis(), sender, reason));
            
            // Update stats
            abortedCount++;
            unresolvedCount++;

            // Re-run Contract Net
            Emergency emergency = emergencyData.get(eId);
            if (emergency != null) {
                System.out.println("[" + getLocalName() + "] Triggering DYNAMIC RE-ASSIGNMENT for " + eId);
                // Clear previous collection state to allow fresh CNP
                activeCollectors.remove(eId);
                startContractNetRound(eId, emergency);
            } else {
                System.err.println("[" + getLocalName() + "] Cannot reassign " + eId + ": Emergency data not found");
                notifyFailure(eId, "REASSIGNMENT_FAILED_NO_DATA");
            }
        }
        retryQueuedIncidentsForUnit(sender);
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
            System.out
                    .println("[" + getLocalName() + "] Restored missing serviceType for " + eId + " => " + serviceType);
        }

        ProposalCollector collector = new ProposalCollector(eId, serviceType, emergency);
        activeCollectors.put(eId, collector);
        searchAndSendCFP(serviceType, emergency, eId);
        // Invariant: do not start perimeter CFP until primary is successfully assigned.

    }
    // scheduleImmediateRetry(): Called after a unit aborts a mission (ABORT
    // format). Unlike
    // scheduleRetry() this does not use exponential backoff — it starts a new CNP
    // round
    // immediately. Falls back to queueIncident() if MAX_RETRIES is reached.

    private void startPoliceContractNet(String eId, Emergency emergency) {
        String baseEId = baseIncidentId(eId);
        String policeKey = policeConversationByIncident.computeIfAbsent(
                baseEId, key -> "POLICE::PERIMETER::" + key);
        if (activeCollectors.containsKey(policeKey) || assignedUnits.containsKey(policeKey))
            return;
        policeConversationParents.put(policeKey, baseEId);
        ProposalCollector collector = new ProposalCollector(policeKey, "CROWD_CONTROL", emergency);
        activeCollectors.put(policeKey, collector);
        searchAndSendCFPWithConversation("CROWD_CONTROL", emergency, policeKey);
    }
    // startPoliceContractNet(): Starts a dedicated CNP round for crowd-control /
    // perimeter.
    // Uses a "POLICE::PERIMETER::<baseId>" conversation key so police proposals can
    // be
    // distinguished from primary-responder proposals. Guards against duplicate
    // sessions.

    private void searchAndSendCFPWithConversation(String serviceType, Emergency emergency, String conversationId) {
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType(serviceType);
        template.addServices(sd);
        try {
            DFAgentDescription[] results = DFService.search(this, template);
            if (results.length == 0) {
                System.out.println(
                        "[" + getLocalName() + "] No units found for " + serviceType + " (" + conversationId + ")");
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
                        System.out.println("[" + getLocalName() + "] Skipping CFP to unavailable unit " + local + " ("
                                + conversationId + ")");
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
    // searchAndSendCFPWithConversation(): Like searchAndSendCFP() but uses a custom
    // conversationId
    // (used for police perimeter rounds). Falls back to
    // inviteFallbackRespondersForPerimeter() when
    // no CROWD_CONTROL units are available, enabling fire trucks to cover the
    // perimeter role.

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
    // inviteFallbackRespondersForPerimeter(): When no police units are available,
    // invites FIRE
    // and RESCUE units to serve as perimeter responders. Returns the total number
    // of units
    // invited so the session knows how many replies to expect.

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
    // inviteByServiceType(): Queries the DF for a specific service type, sends a
    // CFP to each
    // available (not busy/pending) unit under the given conversationId, and returns
    // the count
    // of units invited. Used as a building block for both primary and fallback
    // perimeter CFPs.

    private boolean isPoliceRole(String eId) {
        return eId != null && policeConversationParents.containsKey(eId);
    }
    // isPoliceRole(): Returns true if the given conversation/incident ID belongs to
    // a police
    // perimeter round (i.e. it is a key in policeConversationParents). Used to skip
    // the
    // police perimeter trigger when evaluating police proposals.

    private String baseIncidentId(String eId) {
        if (eId == null)
            return null;
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
    // baseIncidentId(): Strips "POLICE::PERIMETER::" prefixes iteratively to
    // recover the
    // original sensor-generated incident ID. This normalisation lets all
    // incident-state maps
    // be keyed consistently regardless of whether the lookup comes from a primary
    // or perimeter round.

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
    // scheduleFinalizeRespectingMinWait(): Called when all expected replies arrive
    // before the
    // PROPOSAL_TIMEOUT. If at least MIN_PROPOSAL_WAIT (500 ms) has elapsed since
    // the session
    // started, finalizes immediately; otherwise schedules a WakerBehaviour for the
    // remaining wait
    // to give any in-flight late proposals a chance to arrive.

    private void releasePolicePerimeter(String eId) {
        String policeUnit = policeAssignments.remove(eId);
        if (policeUnit == null)
            return;
        ACLMessage release = new ACLMessage(ACLMessage.INFORM);
        release.setOntology(EmergencyOntology.getInstance().getName());
        release.setLanguage(new SLCodec().getName());
        release.addReceiver(new AID(policeUnit, AID.ISLOCALNAME));
        // Invariant: perimeter must be releasable even while police is EN_ROUTE.
        try {
            ReleasePerimeter rp = new ReleasePerimeter();
            rp.setEmergencyId(eId);
            release.setContentObject(rp);
            send(release);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        forceReleaseUnit(policeUnit);
        assignedUnits.entrySet().removeIf(entry -> policeUnit.equals(entry.getValue()) && isPoliceRole(entry.getKey())
                && eId.equals(baseIncidentId(entry.getKey())));
        policeConversationByIncident.remove(eId);
    }
    // releasePolicePerimeter(): Sends RELEASE_PERIMETER:<id> to the assigned police
    // unit,
    // forcibly frees it from busyUnits, removes all police-role assignment entries
    // for this
    // incident, and clears the conversation tracking entry. Safe to call even while
    // the
    // police unit is still EN_ROUTE (enforced by comment invariant).



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
    // tryReservePendingUnit(): Atomically adds a unit to pendingUnits under the
    // given conversationId
    // (under assignmentLock). Returns false if the unit is already busy, or already
    // reserved
    // for a different conversation, preventing double-reservation races.

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
    // promotePendingToBusy(): Atomically moves a unit from the pending reservation
    // to the busy set
    // (under assignmentLock). Returns false if the unit is already busy or owned by
    // a different
    // conversation. This is the final commitment step when a CNP winner is
    // confirmed.

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
    // releasePendingUnit(): Removes a specific unit from its pending reservation
    // (under assignmentLock).
    // Only releases if the unit is owned by the given conversationId, preventing
    // one conversation
    // from inadvertently releasing a unit reserved for another.

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
    // releaseAllPendingForConversation(): Bulk-releases all units reserved for a
    // conversation
    // (under assignmentLock). Called when an incident fails or is closed so no
    // units are left
    // stranded in the pending set for a conversation that will never complete.

    private boolean forceReleaseUnit(String unitName) {
        if (unitName == null)
            return false;
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
    // forceReleaseUnit(): Unconditionally removes a unit from both busyUnits and
    // pendingUnits
    // (under assignmentLock), also cleaning up the owning conversation entry.
    // Returns true if
    // any removal occurred. Used for IDLE and ABORT messages to guarantee units are
    // never
    // permanently stuck in the busy list.

    private String pollQueuedIncidentByPriority(String preferredUnitName) {
        if (queuedIncidents.isEmpty())
            return null;

        String bestId = null;
        int bestSeverity = Integer.MIN_VALUE;
        int bestRound = Integer.MIN_VALUE;
        long bestSince = Long.MAX_VALUE;

        for (String id : queuedIncidents) {
            Emergency emergency = emergencyData.get(id);
            if (emergency == null)
                continue;
            if (preferredUnitName != null && !isUnitCompatibleWithIncident(preferredUnitName, emergency))
                continue;

            int severity = emergency.getSeverity();
            int rounds = queueRounds.getOrDefault(id, 0);
            long since = queueSince.getOrDefault(id, Long.MAX_VALUE);
            if (severity > bestSeverity
                    || (severity == bestSeverity && rounds > bestRound)
                    || (severity == bestSeverity && rounds == bestRound && since < bestSince)) {
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
    // pollQueuedIncidentByPriority(): Scans the waiting queue and selects the
    // incident with the
    // highest priority score: severity first, then most queue rounds (longest
    // waiting), then
    // earliest enqueue time. Optionally filters by unit compatibility when
    // preferredUnitName is
    // set. Removes the winner from the queue before returning.

    private void notifyLogger(Object contentObj) {
        ACLMessage log = new ACLMessage(ACLMessage.INFORM);
        log.setOntology(EmergencyOntology.getInstance().getName());
        log.setLanguage(new SLCodec().getName());
        AID logger = findAgentByService("AUDIT");
        if (logger != null)
            log.addReceiver(logger);
        try {
            log.setContent("LOG_EVENT:" + contentObj.getClass().getSimpleName());
            log.setContentObject((java.io.Serializable) contentObj);
            send(log);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    // notifyLogger(): Sends a plain-text INFORM message to the LoggerAgent (looked
    // up via the
    // AUDIT service) so lifecycle events are recorded in the audit log and session
    // report.

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
    // compareAndSetIncidentStatus(): Thread-safe CAS using
    // ConcurrentHashMap.compute(). Transitions
    // the incident from `expected` to `next` only if the current value matches;
    // otherwise leaves it
    // unchanged. Returns true when the transition actually occurred.

    private void appendIncidentEvent(String incidentId, IncidentEvent event) {
        if (incidentId == null || event == null) {
            return;
        }
        incidentEvents.computeIfAbsent(incidentId, key -> new ConcurrentLinkedQueue<>()).add(event);
    }
    // appendIncidentEvent(): Appends a typed IncidentEvent (Detected, Assigned,
    // Arrived, Aborted,
    // Resolved, Failed) to the per-incident event log, creating the queue on first
    // use.
    // The ConcurrentLinkedQueue is thread-safe for concurrent appends.

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
    // isDuplicateStatusMessage(): Uses a bounded LRU cache (MESSAGE_CACHE_LIMIT
    // entries) keyed by
    // "sender|content" to detect and suppress duplicate IDLE or ABORT messages.
    // Prevents the
    // dispatcher from double-releasing units when the same status is delivered more
    // than once.

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

    // Visible for tests
    void testSeedEmergency(String eId, Emergency emergency) {
        emergencyData.put(eId, emergency);
        emergencyServiceType.put(eId, determineServiceType(emergency));
        incidentStatus.putIfAbsent(eId, IncidentStatus.PENDING);
    }

    // Visible for tests
    void testMaybeStartPerimeter(String eId) {
        maybeStartPolicePerimeterAfterPrimaryAssigned(eId);
    }

    // Visible for tests
    boolean testHasPoliceConversation(String eId) {
        return policeConversationByIncident.containsKey(eId);
    }

    // Visible for tests
    void testMarkPrimaryAssigned(String eId, String unit) {
        assignedUnits.put(eId, unit);
    }

    // Visible for tests
    boolean testRetryGate(String eId, long now, String reason, Set<String> units) {
        RetryContext ctx = retryContextByIncident.computeIfAbsent(eId, k -> new RetryContext());
        return ctx.shouldRetryNow(now, reason, units);
    }

    // Visible for tests
    void testMarkFailed(String eId, String reason) {
        markIncidentFailed(eId, reason);
    }

    // Visible for tests
    boolean testIsFailed(String eId) {
        return failedIncidents.contains(eId) || incidentStatus.get(eId) == IncidentStatus.FAILED;
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
    // takeDown(): Deregisters from the DF on shutdown so field units and sensors
    // can no longer
    // look up the dispatcher after it has been destroyed.

    private enum IncidentStatus {
        PENDING, ASSIGNED, ACTIVE, RESOLVED, ABORTED, FAILED
    }

    private sealed interface IncidentEvent permits IncidentEvent.Detected, IncidentEvent.Assigned,
            IncidentEvent.Arrived, IncidentEvent.Aborted, IncidentEvent.Resolved, IncidentEvent.Failed {
        long ts();

        record Detected(long ts, String type) implements IncidentEvent {
        }

        record Assigned(long ts, String unitName) implements IncidentEvent {
        }

        record Arrived(long ts, String unitName) implements IncidentEvent {
        }

        record Aborted(long ts, String unitName, String reason) implements IncidentEvent {
        }

        record Resolved(long ts, String unitName) implements IncidentEvent {
        }

        record Failed(long ts, String reason) implements IncidentEvent {
        }
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
        private Map<AID, ProposalData> proposals = new HashMap<>();

        ProposalCollector(String eId, String serviceType, Emergency emergency) {
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
        private final int invitedCount;
        private final long startedAtMillis;
        private final Set<String> responders = new HashSet<>();
        private boolean finalized = false;

        ContractNetSession(String conversationId, int invitedCount) {
            this.invitedCount = invitedCount;
            this.startedAtMillis = System.currentTimeMillis();
        }

        synchronized boolean tryFinalize() {
            if (finalized)
                return false;
            finalized = true;
            return true;
        }

        /**
         * @return true if all invited responders have replied (early close), and
         *         evaluation should be triggered.
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
     * Per-incident retry memory to avoid blind, identical retries when nothing
     * changed.
     * Invariant: we only retry if something improved OR exponential backoff
     * elapsed.
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


}
