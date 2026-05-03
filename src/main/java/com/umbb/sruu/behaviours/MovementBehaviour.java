package com.umbb.sruu.behaviours;

import com.umbb.sruu.ontology.Location;
import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;

public class MovementBehaviour extends TickerBehaviour {

    private Location currentLocation;
    private Location targetLocation;
    private String agentName;
    private Runnable onArrival;
    private boolean moving = false;
    private volatile boolean arrivalHandled = false;
    private long movementId = 0;

    public MovementBehaviour(Agent agent, long period, Location start, String name) {
        super(agent, period);
        this.currentLocation = start;
        this.agentName = name;
    }

    public synchronized void setTarget(Location target, Runnable onArrivalCallback) {
        this.targetLocation = target;
        this.onArrival = onArrivalCallback;
        this.moving = true;
        this.arrivalHandled = false;
        this.movementId++;
        System.out.println("[" + agentName + "] Moving to " + target);
        if (target != null
                && currentLocation.getX() == target.getX()
                && currentLocation.getY() == target.getY()) {
            // Fire callback immediately to avoid waiting for next tick.
            handleArrival(this.movementId);
        }
    }

    @Override
    protected void onTick() {
        if (!moving || targetLocation == null) return;
        if (arrivalHandled) return;

        // Check if already at destination
        if (currentLocation.getX() == targetLocation.getX() &&
                currentLocation.getY() == targetLocation.getY()) {
            handleArrival(movementId);
            return;
        }

        int dx = targetLocation.getX() - currentLocation.getX();
        int dy = targetLocation.getY() - currentLocation.getY();

        if (dx != 0) {
            currentLocation.setX(currentLocation.getX() + (dx > 0 ? 1 : -1));
        } else if (dy != 0) {
            currentLocation.setY(currentLocation.getY() + (dy > 0 ? 1 : -1));
        }

        // Check after movement
        if (!arrivalHandled && currentLocation.getX() == targetLocation.getX() &&
                currentLocation.getY() == targetLocation.getY()) {
            handleArrival(movementId);
        }
    }

    private synchronized void handleArrival(long completedMovementId) {
        if (completedMovementId != movementId) return;
        if (arrivalHandled) return;
        Location arrivedTarget = targetLocation;
        Runnable arrivalCallback = onArrival;
        arrivalHandled = true;
        moving = false;
        onArrival = null;
        System.out.println("[" + agentName + "] ARRIVED at " + arrivedTarget);

        if (arrivalCallback != null) {
            arrivalCallback.run();
        }

        if (completedMovementId == movementId) {
            targetLocation = null;
        }
    }

    public Location getCurrentLocation() {
        return currentLocation;
    }

    public boolean isMoving() {
        return moving;
    }
}
