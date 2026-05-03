package com.umbb.sruu.ontology;

import jade.content.Predicate;

public class HasEmergency implements Predicate {
    private Emergency emergency;

    public HasEmergency() {}

    public Emergency getEmergency() { return emergency; }
    public void setEmergency(Emergency emergency) { this.emergency = emergency; }
}