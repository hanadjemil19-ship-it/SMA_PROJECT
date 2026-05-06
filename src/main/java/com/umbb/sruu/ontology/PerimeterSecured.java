package com.umbb.sruu.ontology;

import jade.content.Concept;

public class PerimeterSecured implements Concept {
    private String unitName;
    private String emergencyId;

    public PerimeterSecured() {}

    public String getUnitName() { return unitName; }
    public void setUnitName(String unitName) { this.unitName = unitName; }

    public String getEmergencyId() { return emergencyId; }
    public void setEmergencyId(String emergencyId) { this.emergencyId = emergencyId; }
}
