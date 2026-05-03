package com.umbb.sruu.ontology;

import jade.content.Concept;

public class Emergency implements Concept {
    private String type;
    private int severity;
    private Location location;
    private String id;

    public Emergency() {}

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public int getSeverity() { return severity; }
    public void setSeverity(int severity) { this.severity = severity; }

    public Location getLocation() { return location; }
    public void setLocation(Location location) { this.location = location; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    @Override
    public String toString() {
        return "Emergency[" + id + ", " + type + ", severity=" + severity + ", at=" + location + "]";
    }
}