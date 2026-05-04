package com.umbb.sruu.agents;

import com.umbb.sruu.ontology.EmergencyOntology;
import jade.content.lang.sl.SLCodec;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LoggerAgent extends Agent {

    private PrintWriter logWriter;
    private int incidentCount = 0;
    private int resolvedCount = 0;
    private int refusedCount = 0;
    private int assignedCount = 0;
    private int unresolvedCount = 0;
    private int abortedCount = 0;
    private int failedCount = 0;
    private long totalResponseTime = 0;
    private long startTime = System.currentTimeMillis();
    private DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Track emergency lifecycle
    private Map<String, Long> emergencyStartTimes = new HashMap<>();
    private Map<String, Long> arrivalTimes = new HashMap<>();
    private Map<String, String> incidentTypes = new HashMap<>();
    private Map<String, List<Long>> responseTimesByType = new HashMap<>();
    private Set<String> detectedIncidents = new HashSet<>();
    private Set<String> resolvedIncidents = new HashSet<>();
    private Set<String> assignedIncidents = new HashSet<>();
    private Set<String> abortedIncidents = new HashSet<>();
    private Set<String> failedIncidents = new HashSet<>();

    @Override
    protected void setup() {
        System.out.println("[" + getLocalName() + "] LoggerAgent started - AUDIT MODE");

        getContentManager().registerLanguage(new SLCodec());
        getContentManager().registerOntology(EmergencyOntology.getInstance());

        registerInDF();

        String filename = "emergency_log_" + System.currentTimeMillis() + ".txt";
        try {
            logWriter = new PrintWriter(new FileWriter(filename));
            logWriter.println("========================================");
            logWriter.println("SRUU - EMERGENCY RESPONSE SYSTEM LOG");
            logWriter.println("Session Start: " + LocalDateTime.now().format(formatter));
            logWriter.println("========================================");
            logWriter.println();
            logWriter.flush();
            System.out.println("[" + getLocalName() + "] Logging to file: " + filename);
        } catch (IOException e) {
            System.err.println("[" + getLocalName() + "] ERROR opening log file: " + e.getMessage());
        }

        // FIX: Listen for specific message types instead of all messages
        // Behaviour 1: Detect new incidents from sensors
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                        MessageTemplate.and(
                                MessageTemplate.MatchOntology(EmergencyOntology.getInstance().getName()),
                                MessageTemplate.not(MessageTemplate.MatchSender(new jade.core.AID("traffic-controller", jade.core.AID.ISLOCALNAME)))
                        )
                );
                ACLMessage msg = receive(mt);
                if (msg != null) {
                    logMessage(msg);
                    trackNewIncident(msg);
                } else {
                    block();
                }
            }
        });

        // Behaviour 2: Track ACCEPT_PROPOSAL (assignments)
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.ACCEPT_PROPOSAL);
                ACLMessage msg = receive(mt);
                if (msg != null) {
                    logMessage(msg);
                    trackAssignment(msg);
                } else {
                    block();
                }
            }
        });

        // Behaviour 3: Track REJECT_PROPOSAL and REFUSE
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.or(
                        MessageTemplate.MatchPerformative(ACLMessage.REJECT_PROPOSAL),
                        MessageTemplate.MatchPerformative(ACLMessage.REFUSE)
                );
                ACLMessage msg = receive(mt);
                if (msg != null) {
                    logMessage(msg);
                    trackRefusal(msg);
                } else {
                    block();
                }
            }
        });

        // Behaviour 4: Track ABORT and FAILURE messages
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.or(
                        MessageTemplate.MatchPerformative(ACLMessage.FAILURE),
                        MessageTemplate.and(
                                MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                                MessageTemplate.MatchContent("UNIT_ABORT:*")
                        )
                );
                ACLMessage msg = receive(mt);
                if (msg != null) {
                    logMessage(msg);
                    trackAbortOrFailure(msg);
                } else {
                    block();
                }
            }
        });

        // Behaviour 5: Log everything else (low priority)
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive();
                if (msg != null) {
                    logMessage(msg);
                    trackLifecycle(msg);
                } else {
                    block();
                }
            }
        });
    }

    private void registerInDF() {
        jade.domain.FIPAAgentManagement.DFAgentDescription dfd = new jade.domain.FIPAAgentManagement.DFAgentDescription();
        dfd.setName(getAID());
        jade.domain.FIPAAgentManagement.ServiceDescription sd = new jade.domain.FIPAAgentManagement.ServiceDescription();
        sd.setType("AUDIT");
        sd.setName("logger");
        dfd.addServices(sd);
        try {
            jade.domain.DFService.register(this, dfd);
            System.out.println("[" + getLocalName() + "] Registered in DF as AUDIT");
        } catch (jade.domain.FIPAException e) {
            e.printStackTrace();
        }
    }

    private void logMessage(ACLMessage msg) {
        String timestamp = LocalDateTime.now().format(formatter);
        String performative = ACLMessage.getPerformative(msg.getPerformative());
        String sender = msg.getSender().getLocalName();
        String receiver = msg.getAllReceiver().hasNext() ?
                msg.getAllReceiver().next().toString() : "broadcast";
        String ontology = msg.getOntology() != null ? msg.getOntology() : "none";

        String content;
        try {
            content = msg.getContent() != null ? msg.getContent() : "[ontology object]";
            if (content.length() > 80) {
                content = content.substring(0, 77) + "...";
            }
        } catch (Exception e) {
            content = "[content error]";
        }

        String logEntry = String.format("[%s] %-18s | From: %-15s | To: %-20s | Ontology: %-20s | %s",
                timestamp, performative, sender, receiver, ontology, content);

        System.out.println("[LOG] " + logEntry);

        if (logWriter != null) {
            logWriter.println(logEntry);
            logWriter.flush();
        }
    }

    private void trackNewIncident(ACLMessage msg) {
        String sender = msg.getSender().getLocalName();
        if (!sender.startsWith("sensor")) return;

        String eId = msg.getConversationId();
        if (eId == null || eId.isEmpty()) {
            try {
                com.umbb.sruu.ontology.HasEmergency he = (com.umbb.sruu.ontology.HasEmergency)
                        getContentManager().extractContent(msg);
                eId = he.getEmergency().getId();
                incidentTypes.put(eId, he.getEmergency().getType());
            } catch (Exception ex) {
                eId = "EMERGENCY-" + System.currentTimeMillis();
            }
        }

        if (!detectedIncidents.contains(eId)) {
            detectedIncidents.add(eId);
            incidentCount++;
            emergencyStartTimes.put(eId, System.currentTimeMillis());
            System.out.println("[LOGGER] *** NEW INCIDENT DETECTED: " + eId + " (Total: " + incidentCount + ") ***");
        }
    }

    private void trackAssignment(ACLMessage msg) {
        String eId = msg.getConversationId();
        if (eId == null || eId.isEmpty()) return;

        if (!assignedIncidents.contains(eId)) {
            assignedIncidents.add(eId);
            assignedCount++;

            if (emergencyStartTimes.containsKey(eId)) {
                long responseTime = System.currentTimeMillis() - emergencyStartTimes.get(eId);
                System.out.println("[LOGGER] *** INCIDENT ASSIGNED: " + eId + " to " + msg.getAllReceiver().next()
                        + " (Response time: " + responseTime + "ms) ***");
            }
        }
    }

    private void trackRefusal(ACLMessage msg) {
        refusedCount++;
    }

    private void trackAbortOrFailure(ACLMessage msg) {
        String content = msg.getContent();
        if (content != null && content.startsWith("UNIT_ABORT:")) {
            String[] parts = content.split(":");
            if (parts.length >= 4) {
                String eId = parts[3];
                if (!eId.equals("UNKNOWN") && !abortedIncidents.contains(eId)) {
                    abortedIncidents.add(eId);
                    abortedCount++;
                    System.out.println("[LOGGER] *** INCIDENT ABORTED: " + eId + " by " + parts[1] + " ***");
                }
            }
        } else if (content != null && content.startsWith("INCIDENT_FAILED:")) {
            String[] parts = content.split(":", 3);
            if (parts.length >= 2) {
                String eId = parts[1];
                if (!failedIncidents.contains(eId)) {
                    failedIncidents.add(eId);
                    failedCount++;
                    System.out.println("[LOGGER] *** INCIDENT FAILED: " + eId + " ***");
                }
            }
        }
    }

    private void trackLifecycle(ACLMessage msg) {
        String content = msg.getContent();
        if (content == null) return;

        if (content.startsWith("DETECTED:")) {
            String[] parts = content.split(":");
            if (parts.length >= 2) {
                String eId = parts[1];
                if (parts.length >= 3) {
                    incidentTypes.put(eId, parts[2]);
                }
                if (detectedIncidents.add(eId)) {
                    incidentCount++;
                    emergencyStartTimes.put(eId, System.currentTimeMillis());
                    System.out.println("[LOGGER] DETECTED -> " + eId);
                }
            }
        } else if (content.startsWith("ASSIGNED:")) {
            String[] parts = content.split(":");
            if (parts.length >= 3) {
                String eId = parts[1];
                if (assignedIncidents.add(eId)) {
                    assignedCount++;
                }
                System.out.println("[LOGGER] ASSIGNED -> " + eId + " to " + parts[2]);
            }
        } else if (content.startsWith("MISSION_ARRIVED:")) {
            String[] parts = content.split(":");
            if (parts.length >= 3) {
                String eId = parts[2];
                if (!arrivalTimes.containsKey(eId)) {
                    long now = System.currentTimeMillis();
                    arrivalTimes.put(eId, now);
                    Long detected = emergencyStartTimes.get(eId);
                    if (detected != null) {
                        long responseTime = now - detected;
                        totalResponseTime += responseTime;
                        recordResponseTime(eId, responseTime);
                    }
                }
                System.out.println("[LOGGER] ARRIVED -> " + eId + " by " + parts[1]);
            }
        } else if (content.startsWith("RESOLVED:") || content.startsWith("MISSION_COMPLETE:")) {
            String[] parts = content.split(":");
            String eId = content.startsWith("RESOLVED:") && parts.length >= 2 ? parts[1]
                    : (parts.length >= 3 ? parts[2] : null);
            if (eId != null && resolvedIncidents.add(eId)) {
                resolvedCount++;
                System.out.println("[LOGGER] RESOLVED -> " + eId);
            }
        } else if (content.startsWith("ABORTED:")) {
            String[] parts = content.split(":");
            if (parts.length >= 2 && abortedIncidents.add(parts[1])) {
                abortedCount++;
                System.out.println("[LOGGER] ABORTED -> " + parts[1]);
            }
        }
    }

    private void recordResponseTime(String eId, long responseTime) {
        String type = incidentTypes.getOrDefault(eId, "UNKNOWN");
        responseTimesByType.computeIfAbsent(type, key -> new ArrayList<>()).add(responseTime);
    }

    private String buildResponseTimeGraph() {
        if (responseTimesByType.isEmpty()) {
            return "Response Time by Incident Type: no arrivals recorded\n";
        }

        long maxAverage = 1;
        Map<String, Long> averages = new HashMap<>();
        for (Map.Entry<String, List<Long>> entry : responseTimesByType.entrySet()) {
            long avg = Math.round(entry.getValue().stream().mapToLong(Long::longValue).average().orElse(0));
            averages.put(entry.getKey(), avg);
            maxAverage = Math.max(maxAverage, avg);
        }

        final long maxAverageForGraph = maxAverage;
        StringBuilder graph = new StringBuilder();
        graph.append("Response Time by Incident Type (min/avg/max)\n");
        responseTimesByType.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    List<Long> values = entry.getValue();
                    long min = values.stream().mapToLong(Long::longValue).min().orElse(0);
                    long max = values.stream().mapToLong(Long::longValue).max().orElse(0);
                    long avg = averages.get(entry.getKey());
                    int barLength = Math.max(1, (int) Math.round((avg * 24.0) / maxAverageForGraph));
                    graph.append(String.format("%-20s %7d/%7d/%7d ms | %s%n",
                            entry.getKey(), min, avg, max, "#".repeat(barLength)));
                });
        return graph.toString();
    }

    public void generateReport() {
        long elapsed = System.currentTimeMillis() - startTime;
        int responseCount = responseTimesByType.values().stream().mapToInt(List::size).sum();
        double avgResponse = responseCount > 0 ? (double) totalResponseTime / responseCount : 0;

        // Calculate unresolved: detected but never assigned and not aborted
        unresolvedCount = 0;
        for (String eId : detectedIncidents) {
            if (!assignedIncidents.contains(eId) && !abortedIncidents.contains(eId) && !failedIncidents.contains(eId)) {
                unresolvedCount++;
            }
        }

        String report = "\n" +
                "========================================\n" +
                "SRUU FINAL REPORT\n" +
                "========================================\n" +
                "Session Duration: " + (elapsed / 1000) + " seconds\n" +
                "Total Incidents:  " + incidentCount + "\n" +
                "Units Assigned:   " + assignedCount + "\n" +
                "Resolved:         " + resolvedCount + "\n" +
                "Refused (busy):   " + refusedCount + "\n" +
                "Aborted:          " + abortedCount + "\n" +
                "Failed:           " + failedCount + "\n" +
                "Unresolved:       " + unresolvedCount + "\n" +
                "Avg Response Time: " + String.format("%.2f", avgResponse) + " ms\n" +
                buildTable3Markdown() +
                buildResponseTimeGraph() +
                "========================================\n";

        System.out.println(report);

        if (logWriter != null) {
            logWriter.println(report);
            logWriter.flush();
        }
        exportTable3Csv();
    }

    private String buildTable3Markdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("Table 3 - Response metrics by incident type\n");
        sb.append(String.format("%-20s | %6s | %8s | %8s | %8s%n", "Type", "Count", "Min(ms)", "Avg(ms)", "Max(ms)"));
        sb.append("---------------------+--------+----------+----------+----------\n");
        responseTimesByType.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    List<Long> values = entry.getValue();
                    long min = values.stream().mapToLong(Long::longValue).min().orElse(0);
                    long max = values.stream().mapToLong(Long::longValue).max().orElse(0);
                    long avg = Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0));
                    sb.append(String.format("%-20s | %6d | %8d | %8d | %8d%n",
                            entry.getKey(), values.size(), min, avg, max));
                });
        return sb.toString();
    }

    private void exportTable3Csv() {
        Path csvPath = Paths.get("table3_metrics_" + System.currentTimeMillis() + ".csv");
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(csvPath))) {
            writer.println("incident_type,count,min_response_ms,avg_response_ms,max_response_ms");
            responseTimesByType.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        List<Long> values = entry.getValue();
                        long min = values.stream().mapToLong(Long::longValue).min().orElse(0);
                        long max = values.stream().mapToLong(Long::longValue).max().orElse(0);
                        long avg = Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0));
                        writer.println(String.format("%s,%d,%d,%d,%d",
                                entry.getKey(), values.size(), min, avg, max));
                    });
            System.out.println("[LOGGER] Exported Table 3 metrics to " + csvPath.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("[LOGGER] Failed to export Table 3 metrics: " + e.getMessage());
        }
    }

    @Override
    protected void takeDown() {
        generateReport();
        try {
            jade.domain.DFService.deregister(this);
        } catch (jade.domain.FIPAException e) {
            e.printStackTrace();
        }
        if (logWriter != null) {
            logWriter.close();
        }
        System.out.println("[" + getLocalName() + "] LoggerAgent terminated");
    }
}
