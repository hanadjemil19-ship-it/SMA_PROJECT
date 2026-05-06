package com.umbb.sruu.ontology;

import jade.content.Concept;

public class ReleasePerimeter implements Concept {
    private String emergencyId;

    public ReleasePerimeter() {}

    public String getEmergencyId() { return emergencyId; }
    public void setEmergencyId(String emergencyId) { this.emergencyId = emergencyId; }
}
