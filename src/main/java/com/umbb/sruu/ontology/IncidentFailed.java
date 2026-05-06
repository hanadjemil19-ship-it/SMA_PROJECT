package com.umbb.sruu.ontology;

import jade.content.Concept;

public class IncidentFailed implements Concept {
    private String emergencyId;
    private String reason;

    public IncidentFailed() {}

    public String getEmergencyId() { return emergencyId; }
    public void setEmergencyId(String emergencyId) { this.emergencyId = emergencyId; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
