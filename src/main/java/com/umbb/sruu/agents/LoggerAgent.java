package com.umbb.sruu.agents;

import com.umbb.sruu.ontology.*;
import jade.content.lang.sl.SLCodec;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;


import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class LoggerAgent extends Agent {

    private PrintWriter logWriter;
    private long totalResponseTime = 0;
    private long startTime = System.currentTimeMillis();
    private DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Map<String, Long> emergencyStartTimes = new HashMap<>();
    private Map<String, String> incidentTypes = new HashMap<>();
    private Map<String, List<Long>> responseTimesByType = new HashMap<>();
    
    private Set<String> detectedIncidents = new HashSet<>();
    private Set<String> resolvedIncidents = new HashSet<>();
    private Set<String> failedIncidents = new HashSet<>();
    
    private int assignedUnitsCount = 0; // Count of primary units assigned
    private int missionAbortsCount = 0; // Count of MissionAbort received
    private int resolvedCount = 0; // Count of MissionComplete received with success=true

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

        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive();
                if (msg != null) {
                    processMessage(msg);
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
        } catch (jade.domain.FIPAException e) { e.printStackTrace(); }
    }

    private void processMessage(ACLMessage msg) {
        // Always log the raw message for the audit trail
        logToAuditFile(msg);

        Object content = null;
        try {
            content = msg.getContentObject();
        } catch (Exception e) {
            String text = msg.getContent();
            if (text != null && !text.isEmpty()) {
                // handle string-based logging if needed
            }
        }

        if (content == null) return;

        if (content instanceof Emergency e) {
            String eId = e.getId();
            incidentTypes.put(eId, e.getType());
            if (detectedIncidents.add(eId)) {
                emergencyStartTimes.put(eId, System.currentTimeMillis());
                System.out.println("[LOGGER] EMERGENCY DETECTED: " + eId);
            }
        } else if (content instanceof Assignment a) {
            if ("PRIMARY".equals(a.getRole())) {
                assignedUnitsCount++;
            }
            System.out.println("[LOGGER] UNIT ASSIGNED: " + a.getUnitName() + " (" + a.getRole() + ") to " + a.getEmergencyId());
        } else if (content instanceof MissionComplete mc) {
            String eId = mc.getEmergencyId();
            if (mc.isSuccess()) {
                resolvedCount++;
                if (resolvedIncidents.add(eId)) {
                    System.out.println("[LOGGER] MISSION COMPLETE: " + eId);
                    Long start = emergencyStartTimes.get(eId);
                    if (start != null) {
                        long responseTime = System.currentTimeMillis() - start;
                        totalResponseTime += responseTime;
                        recordResponseTime(eId, responseTime);
                    }
                }
            }
        } else if (content instanceof MissionAbort ma) {
            missionAbortsCount++;
            System.out.println("[LOGGER] MISSION ABORTED: " + ma.getEmergencyId() + " reason: " + ma.getReason());
        } else if (content instanceof IncidentFailed f) {
            failedIncidents.add(f.getEmergencyId());
            System.out.println("[LOGGER] INCIDENT FAILED: " + f.getEmergencyId());
        } else if (content instanceof MissionArrived ma) {
            System.out.println("[LOGGER] UNIT ARRIVED: " + ma.getUnitName() + " at " + ma.getEmergencyId());
        } else if (content instanceof PerimeterSecured ps) {
            System.out.println("[LOGGER] PERIMETER SECURED: " + ps.getEmergencyId());
        } else if (content instanceof UnitStatus) {
            // Tracking unit status
        }
    }

    private void logToAuditFile(ACLMessage msg) {
        String timestamp = LocalDateTime.now().format(formatter);
        String performative = ACLMessage.getPerformative(msg.getPerformative());
        String sender = msg.getSender().getLocalName();
        String ontology = msg.getOntology() != null ? msg.getOntology() : "none";
        
        String auditContent = "[no-content]";
        try {
            Object obj = msg.getContentObject();
            if (obj != null) {
                auditContent = serializeObjectToReadable(obj);
            } else if (msg.getContent() != null) {
                auditContent = msg.getContent();
            }
        } catch (Exception e) {
            auditContent = msg.getContent() != null ? msg.getContent() : "[error-reading-content]";
        }

        // Clean up any non-printable characters that might have leaked from binary content
        auditContent = auditContent.replaceAll("[^\\p{Print}]", "?");

        String logEntry = String.format("{\"ts\":\"%s\",\"perf\":\"%s\",\"from\":\"%s\",\"ontology\":\"%s\",\"content\":\"%s\"}",
                timestamp, performative, sender, ontology, auditContent);

        if (logWriter != null) {
            logWriter.println(logEntry);
            logWriter.flush();
        }
    }

    private String serializeObjectToReadable(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof Emergency e) {
            return String.format("EMERGENCY(id=%s, type=%s, sev=%d)", e.getId(), e.getType(), e.getSeverity());
        } else if (obj instanceof Assignment a) {
            return String.format("ASSIGNMENT(id=%s, unit=%s, role=%s)", a.getEmergencyId(), a.getUnitName(), a.getRole());
        } else if (obj instanceof UnitStatus s) {
            return String.format("STATUS(unit=%s, state=%s, water=%d%%)", s.getUnitName(), s.getState(), s.getWater());
        } else if (obj instanceof MissionComplete mc) {
            return String.format("COMPLETE(id=%s, unit=%s, success=%b)", mc.getEmergencyId(), mc.getUnitName(), mc.isSuccess());
        } else if (obj instanceof MissionAbort ma) {
            return String.format("ABORT(id=%s, reason=%s)", ma.getEmergencyId(), ma.getReason());
        } else if (obj instanceof PerimeterSecured ps) {
            return String.format("PERIMETER_SECURED(id=%s, unit=%s)", ps.getEmergencyId(), ps.getUnitName());
        } else if (obj instanceof RouteCleared rc) {
            return String.format("ROUTE_CLEARED(from=%s, to=%s)", rc.getFrom(), rc.getTo());
        }
        return obj.toString();
    }

    private void recordResponseTime(String eId, long responseTime) {
        String type = incidentTypes.getOrDefault(eId, "UNKNOWN");
        responseTimesByType.computeIfAbsent(type, key -> new ArrayList<>()).add(responseTime);
    }

    public void generateReport() {
        long elapsed = System.currentTimeMillis() - startTime;
        int total = detectedIncidents.size();
        int resolved = resolvedCount;
        int aborted = missionAbortsCount;
        int failed = failedIncidents.size();
        int unresolved = total - resolved - failed;
        
        int responseCount = responseTimesByType.values().stream().mapToInt(List::size).sum();
        double avgResponse = responseCount > 0 ? (double) totalResponseTime / responseCount : 0;

        String reportJson = String.format("{\"type\":\"REPORT\",\"stats\":{\"durationSec\":%d,\"total\":%d,\"assigned\":%d,\"resolved\":%d,\"aborted\":%d,\"failed\":%d,\"unresolved\":%d,\"avgResponseMs\":%.2f}}",
                elapsed / 1000, total, assignedUnitsCount, resolved, aborted, failed, unresolved, avgResponse);

        System.out.println(reportJson);

        if (logWriter != null) {
            logWriter.println("================ REPORT ================");
            logWriter.println(reportJson);
            logWriter.println(buildTable3Markdown());
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
        } catch (IOException e) {
            System.err.println("[LOGGER] Failed to export Table 3 metrics: " + e.getMessage());
        }
    }

    @Override
    protected void takeDown() {
        generateReport();
        try {
            jade.domain.DFService.deregister(this);
        } catch (jade.domain.FIPAException e) {}
        if (logWriter != null) {
            logWriter.close();
        }
        System.out.println("[" + getLocalName() + "] LoggerAgent terminated");
    }
}
