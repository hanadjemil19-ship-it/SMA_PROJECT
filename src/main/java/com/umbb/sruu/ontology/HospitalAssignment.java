package com.umbb.sruu.ontology;

import jade.content.Predicate;

public class HospitalAssignment implements Predicate {
    private Hospital hospital;
    private String emergencyId;
    private jade.core.AID hospitalAid;

    public HospitalAssignment() {}

    public Hospital getHospital() { return hospital; }
    public void setHospital(Hospital hospital) { this.hospital = hospital; }

    public String getEmergencyId() { return emergencyId; }
    public void setEmergencyId(String emergencyId) { this.emergencyId = emergencyId; }

    public jade.core.AID getHospitalAid() { return hospitalAid; }
    public void setHospitalAid(jade.core.AID hospitalAid) { this.hospitalAid = hospitalAid; }
}