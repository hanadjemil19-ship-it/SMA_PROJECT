package com.umbb.sruu.ontology;

import jade.content.Concept;

public class RouteExpired implements Concept {
    private String emergencyId;

    public RouteExpired() {}

    public String getEmergencyId() { return emergencyId; }
    public void setEmergencyId(String emergencyId) { this.emergencyId = emergencyId; }
}
