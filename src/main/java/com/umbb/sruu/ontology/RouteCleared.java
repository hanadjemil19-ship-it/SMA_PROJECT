package com.umbb.sruu.ontology;

import jade.content.Predicate;

public class RouteCleared implements Predicate {
    private Location from;
    private Location to;
    private int expirationSeconds;

    public RouteCleared() {}

    public Location getFrom() { return from; }
    public void setFrom(Location from) { this.from = from; }

    public Location getTo() { return to; }
    public void setTo(Location to) { this.to = to; }

    public int getExpirationSeconds() { return expirationSeconds; }
    public void setExpirationSeconds(int expirationSeconds) { this.expirationSeconds = expirationSeconds; }
}