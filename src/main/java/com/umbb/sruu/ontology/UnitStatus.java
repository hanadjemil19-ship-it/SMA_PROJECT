package com.umbb.sruu.ontology;

import jade.content.Concept;

public class UnitStatus implements Concept {
    private String unitId;
    private String state;
    private Location currentLocation;
    private int workload;

    public UnitStatus() {}

    public String getUnitId() { return unitId; }
    public void setUnitId(String unitId) { this.unitId = unitId; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public Location getCurrentLocation() { return currentLocation; }
    public void setCurrentLocation(Location currentLocation) { this.currentLocation = currentLocation; }

    public int getWorkload() { return workload; }
    public void setWorkload(int workload) { this.workload = workload; }
}