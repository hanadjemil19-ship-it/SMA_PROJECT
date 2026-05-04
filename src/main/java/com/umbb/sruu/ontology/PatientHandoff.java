package com.umbb.sruu.ontology;

import jade.content.Predicate;

public class PatientHandoff implements Predicate {
    private String emergencyId;
    private String ambulanceId;

    public String getEmergencyId() {
        return emergencyId;
    }

    public void setEmergencyId(String emergencyId) {
        this.emergencyId = emergencyId;
    }

    public String getAmbulanceId() {
        return ambulanceId;
    }

    public void setAmbulanceId(String ambulanceId) {
        this.ambulanceId = ambulanceId;
    }
}
