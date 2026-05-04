package com.umbb.sruu.ontology;

import jade.content.onto.*;
import jade.content.schema.*;

public class EmergencyOntology extends Ontology {
    public static final String ONTOLOGY_NAME = "Emergency-Ontology";

    private static Ontology instance = new EmergencyOntology();
    public static Ontology getInstance() { return instance; }

    private EmergencyOntology() {
        super(ONTOLOGY_NAME, jade.domain.FIPAAgentManagement.FIPAManagementOntology.getInstance());

        try {
            // ========== FIRST: Register all basic schemas (no dependencies) ==========

            // Location schema - NO dependencies
            ConceptSchema locationSchema = new ConceptSchema("Location");
            locationSchema.add("x", (PrimitiveSchema) getSchema(BasicOntology.INTEGER));
            locationSchema.add("y", (PrimitiveSchema) getSchema(BasicOntology.INTEGER));
            add(locationSchema, Location.class);

            // ========== SECOND: Register schemas that depend on Location ==========

            // Emergency schema - DEPENDS on Location
            ConceptSchema emergencySchema = new ConceptSchema("Emergency");
            emergencySchema.add("type", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            emergencySchema.add("severity", (PrimitiveSchema) getSchema(BasicOntology.INTEGER));
            emergencySchema.add("location", (ConceptSchema) getSchema("Location"));  // Now Location exists!
            emergencySchema.add("id", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            add(emergencySchema, Emergency.class);

            // UnitStatus schema - DEPENDS on Location
            ConceptSchema statusSchema = new ConceptSchema("UnitStatus");
            statusSchema.add("unitId", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            statusSchema.add("state", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            statusSchema.add("currentLocation", (ConceptSchema) getSchema("Location"));
            statusSchema.add("workload", (PrimitiveSchema) getSchema(BasicOntology.INTEGER));
            add(statusSchema, UnitStatus.class);

            // Hospital schema - NO dependencies
            ConceptSchema hospitalSchema = new ConceptSchema("Hospital");
            hospitalSchema.add("name", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            hospitalSchema.add("location", (ConceptSchema) getSchema("Location"));
            hospitalSchema.add("availableBeds", (PrimitiveSchema) getSchema(BasicOntology.INTEGER));
            hospitalSchema.add("totalBeds", (PrimitiveSchema) getSchema(BasicOntology.INTEGER));
            add(hospitalSchema, Hospital.class);
            // ========== THIRD: Register predicates ==========

            // HasEmergency - DEPENDS on Emergency
            PredicateSchema hasEmergencySchema = new PredicateSchema("HasEmergency");
            hasEmergencySchema.add("emergency", (ConceptSchema) getSchema("Emergency"));
            add(hasEmergencySchema, HasEmergency.class);

            // UnitAvailable - DEPENDS on UnitStatus
            PredicateSchema unitAvailableSchema = new PredicateSchema("UnitAvailable");
            unitAvailableSchema.add("status", (ConceptSchema) getSchema("UnitStatus"));
            add(unitAvailableSchema, UnitAvailable.class);

            // RouteCleared - DEPENDS on Location
            PredicateSchema routeClearedSchema = new PredicateSchema("RouteCleared");
            routeClearedSchema.add("from", (ConceptSchema) getSchema("Location"));
            routeClearedSchema.add("to", (ConceptSchema) getSchema("Location"));
            routeClearedSchema.add("expirationSeconds", (PrimitiveSchema) getSchema(BasicOntology.INTEGER));
            add(routeClearedSchema, RouteCleared.class);
            // HospitalAssignment predicate
            PredicateSchema hospitalAssignSchema = new PredicateSchema("HospitalAssignment");
            hospitalAssignSchema.add("hospital", (ConceptSchema) getSchema("Hospital"));
            hospitalAssignSchema.add("emergencyId", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            hospitalAssignSchema.add("hospitalAid", (ConceptSchema) getSchema(jade.domain.FIPAAgentManagement.FIPAManagementOntology.AID), ObjectSchema.OPTIONAL);
            add(hospitalAssignSchema, HospitalAssignment.class);
            // HospitalRequest predicate
            PredicateSchema hospitalRequestSchema = new PredicateSchema("HospitalRequest");
            hospitalRequestSchema.add("emergencyId", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            hospitalRequestSchema.add("emergencyLocation", (ConceptSchema) getSchema("Location"));
            add(hospitalRequestSchema, HospitalRequest.class);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}