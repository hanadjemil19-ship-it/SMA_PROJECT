package com.umbb.sruu.ontology;

import jade.content.Concept;

public class MissionArrived implements Concept {
    private String unitName;
    private String emergencyId;

    public MissionArrived() {}

    public MissionArrived(String unitName, String emergencyId) {
        this.unitName = unitName;
        this.emergencyId = emergencyId;
    }

    public String getUnitName() { return unitName; }
    public void setUnitName(String unitName) { this.unitName = unitName; }

    public String getEmergencyId() { return emergencyId; }
    public void setEmergencyId(String emergencyId) { this.emergencyId = emergencyId; }
}
