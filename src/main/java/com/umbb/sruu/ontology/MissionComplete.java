package com.umbb.sruu.ontology;

import jade.content.Concept;

public class MissionComplete implements Concept {
    private String emergencyId;
    private String unitName;
    private boolean success;

    public MissionComplete() {}

    public MissionComplete(String emergencyId, String unitName, boolean success) {
        this.emergencyId = emergencyId;
        this.unitName = unitName;
        this.success = success;
    }

    public String getEmergencyId() { return emergencyId; }
    public void setEmergencyId(String emergencyId) { this.emergencyId = emergencyId; }

    public String getUnitName() { return unitName; }
    public void setUnitName(String unitName) { this.unitName = unitName; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
}
