package com.umbb.sruu.ontology;

import jade.content.Predicate;

public class HospitalRequest implements Predicate {
    private String emergencyId;
    private Location emergencyLocation;

    public HospitalRequest() {}

    public String getEmergencyId() { return emergencyId; }
    public void setEmergencyId(String emergencyId) { this.emergencyId = emergencyId; }

    public Location getEmergencyLocation() { return emergencyLocation; }
    public void setEmergencyLocation(Location emergencyLocation) { this.emergencyLocation = emergencyLocation; }
}
