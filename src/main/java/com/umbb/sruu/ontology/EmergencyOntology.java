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
            statusSchema.add("unitName", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            statusSchema.add("state", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            statusSchema.add("position", (ConceptSchema) getSchema("Location"));
            statusSchema.add("workload", (PrimitiveSchema) getSchema(BasicOntology.FLOAT)); // Use float for java double in Jade
            statusSchema.add("water", (PrimitiveSchema) getSchema(BasicOntology.INTEGER));
            add(statusSchema, UnitStatus.class);

            // Hospital schema - NO dependencies
            ConceptSchema hospitalSchema = new ConceptSchema("Hospital");
            hospitalSchema.add("name", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            hospitalSchema.add("location", (ConceptSchema) getSchema("Location"));
            hospitalSchema.add("availableBeds", (PrimitiveSchema) getSchema(BasicOntology.INTEGER));
            hospitalSchema.add("totalBeds", (PrimitiveSchema) getSchema(BasicOntology.INTEGER));
            add(hospitalSchema, Hospital.class);

            // Assignment schema
            ConceptSchema assignmentSchema = new ConceptSchema("Assignment");
            assignmentSchema.add("emergencyId", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            assignmentSchema.add("unitName", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            assignmentSchema.add("role", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            add(assignmentSchema, Assignment.class);

            // MissionComplete schema
            ConceptSchema missionCompleteSchema = new ConceptSchema("MissionComplete");
            missionCompleteSchema.add("emergencyId", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            missionCompleteSchema.add("unitName", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            missionCompleteSchema.add("success", (PrimitiveSchema) getSchema(BasicOntology.BOOLEAN));
            add(missionCompleteSchema, MissionComplete.class);

            // Additional Concept schemas for messages
            ConceptSchema routeExpiredSchema = new ConceptSchema("RouteExpired");
            routeExpiredSchema.add("emergencyId", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            add(routeExpiredSchema, RouteExpired.class);

            ConceptSchema hospitalSaturationSchema = new ConceptSchema("HospitalSaturation");
            hospitalSaturationSchema.add("hospitalName", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            hospitalSaturationSchema.add("availableBeds", (PrimitiveSchema) getSchema(BasicOntology.INTEGER));
            hospitalSaturationSchema.add("totalBeds", (PrimitiveSchema) getSchema(BasicOntology.INTEGER));
            hospitalSaturationSchema.add("percent", (PrimitiveSchema) getSchema(BasicOntology.FLOAT)); // double translates to float in basic ontology usually
            add(hospitalSaturationSchema, HospitalSaturation.class);

            ConceptSchema unitAbortSchema = new ConceptSchema("UnitAbort");
            unitAbortSchema.add("unitName", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            unitAbortSchema.add("reason", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            unitAbortSchema.add("emergencyId", (PrimitiveSchema) getSchema(BasicOntology.STRING), ObjectSchema.OPTIONAL);
            add(unitAbortSchema, UnitAbort.class);

            ConceptSchema incidentFailedSchema = new ConceptSchema("IncidentFailed");
            incidentFailedSchema.add("emergencyId", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            incidentFailedSchema.add("reason", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            add(incidentFailedSchema, IncidentFailed.class);

            ConceptSchema perimeterSecuredSchema = new ConceptSchema("PerimeterSecured");
            perimeterSecuredSchema.add("unitName", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            perimeterSecuredSchema.add("emergencyId", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            add(perimeterSecuredSchema, PerimeterSecured.class);

            ConceptSchema releasePerimeterSchema = new ConceptSchema("ReleasePerimeter");
            releasePerimeterSchema.add("emergencyId", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            add(releasePerimeterSchema, ReleasePerimeter.class);

            ConceptSchema missionArrivedSchema = new ConceptSchema("MissionArrived");
            missionArrivedSchema.add("unitName", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            missionArrivedSchema.add("emergencyId", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            add(missionArrivedSchema, MissionArrived.class);

            ConceptSchema patientHandoffSchema = new ConceptSchema("PatientHandoff");
            patientHandoffSchema.add("unitName", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            patientHandoffSchema.add("emergencyId", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            add(patientHandoffSchema, PatientHandoff.class);

            ConceptSchema missionAbortSchema = new ConceptSchema("MissionAbort");
            missionAbortSchema.add("emergencyId", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            missionAbortSchema.add("reason", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            add(missionAbortSchema, MissionAbort.class);

            ConceptSchema missionRejectSchema = new ConceptSchema("MissionReject");
            missionRejectSchema.add("emergencyId", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            missionRejectSchema.add("reason", (PrimitiveSchema) getSchema(BasicOntology.STRING));
            add(missionRejectSchema, MissionReject.class);

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
            hospitalAssignSchema.add("hospitalAid", (ConceptSchema) getSchema(BasicOntology.AID), ObjectSchema.OPTIONAL);
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