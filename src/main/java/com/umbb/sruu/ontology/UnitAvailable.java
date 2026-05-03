package com.umbb.sruu.ontology;

import jade.content.Predicate;

public class UnitAvailable implements Predicate {
    private UnitStatus status;

    public UnitAvailable() {}

    public UnitStatus getStatus() {
        return status;
    }

    public void setStatus(UnitStatus status) {
        this.status = status;
    }
}