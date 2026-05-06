package com.umbb.sruu.ontology;

import jade.content.Concept;

public class UnitStatus implements Concept {
    private String unitName;
    private String state;
    private Location position;
    private double workload;

    public UnitStatus() {}

    public String getUnitName() { return unitName; }
    public void setUnitName(String unitName) { this.unitName = unitName; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public Location getPosition() { return position; }
    public void setPosition(Location position) { this.position = position; }

    public double getWorkload() { return workload; }
    public void setWorkload(double workload) { this.workload = workload; }

    private int water;
    public int getWater() { return water; }
    public void setWater(int water) { this.water = water; }
}