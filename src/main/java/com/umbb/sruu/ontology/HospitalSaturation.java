package com.umbb.sruu.ontology;

import jade.content.Concept;

public class HospitalSaturation implements Concept {
    private String hospitalName;
    private int availableBeds;
    private int totalBeds;
    private double percent;

    public HospitalSaturation() {}

    public String getHospitalName() { return hospitalName; }
    public void setHospitalName(String hospitalName) { this.hospitalName = hospitalName; }

    public int getAvailableBeds() { return availableBeds; }
    public void setAvailableBeds(int availableBeds) { this.availableBeds = availableBeds; }

    public int getTotalBeds() { return totalBeds; }
    public void setTotalBeds(int totalBeds) { this.totalBeds = totalBeds; }

    public double getPercent() { return percent; }
    public void setPercent(double percent) { this.percent = percent; }
}
