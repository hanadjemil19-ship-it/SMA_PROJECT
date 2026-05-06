package com.umbb.sruu.ontology;

import jade.content.Concept;

public class Assignment implements Concept {
    private String emergencyId;
    private String unitName;
    private String role;

    public Assignment() {}

    public String getEmergencyId() { return emergencyId; }
    public void setEmergencyId(String emergencyId) { this.emergencyId = emergencyId; }

    public String getUnitName() { return unitName; }
    public void setUnitName(String unitName) { this.unitName = unitName; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
}
