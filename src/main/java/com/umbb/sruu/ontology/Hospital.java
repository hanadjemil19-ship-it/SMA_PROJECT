package com.umbb.sruu.ontology;

import jade.content.Concept;

public class Hospital implements Concept {
    private String name;
    private Location location;
    private int availableBeds;
    private int totalBeds;

    public Hospital() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Location getLocation() { return location; }
    public void setLocation(Location location) { this.location = location; }

    public int getAvailableBeds() { return availableBeds; }
    public void setAvailableBeds(int availableBeds) { this.availableBeds = availableBeds; }

    public int getTotalBeds() { return totalBeds; }
    public void setTotalBeds(int totalBeds) { this.totalBeds = totalBeds; }

    @Override
    public String toString() {
        return "Hospital[" + name + ", beds=" + availableBeds + "/" + totalBeds + ", at=" + location + "]";
    }
}