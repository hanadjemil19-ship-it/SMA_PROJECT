package com.umbb.sruu.ontology;

import jade.content.Concept;

public class UnitAbort implements Concept {
    private String unitName;
    private String reason;
    private String emergencyId;
    private boolean reassign;

    public UnitAbort() {}

    public String getUnitName() { return unitName; }
    public void setUnitName(String unitName) { this.unitName = unitName; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getEmergencyId() { return emergencyId; }
    public void setEmergencyId(String emergencyId) { this.emergencyId = emergencyId; }

    public boolean isReassign() { return reassign; }
    public void setReassign(boolean reassign) { this.reassign = reassign; }
}
