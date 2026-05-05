package com.umbb.sruu.utils;

import com.umbb.sruu.ontology.Emergency;
import com.umbb.sruu.ontology.Location;
import com.umbb.sruu.ontology.UnitStatus;

public class UtilityCalculator {

    private static final double W_TYPE_MATCH = 0.50;
    private static final double W_DISTANCE = 0.20;
    private static final double W_WORKLOAD = 0.15;
    private static final double W_SEVERITY = 0.15;

    /**
     * Calculate utility score for a unit responding to an emergency
     * Higher score = better candidate
     */
    public static double calculateUtility(UnitStatus unitStatus, Emergency emergency, String serviceType) {

        // 1. Type match score. This must be based on the actual unit, not only the CFP service.
        double typeMatchScore = getTypeMatchScore(unitStatus.getUnitId(), emergency.getType(), serviceType);

        // 2. Distance score (inverse - closer is better)
        double distance = euclideanDistance(unitStatus.getCurrentLocation(), emergency.getLocation());
        double distanceScore = 1.0 / (1.0 + distance);  // Normalized 0-1

        // 3. Workload score (inverse - less busy is better)
        double workloadScore = 1.0 / (unitStatus.getWorkload() + 1.0);
        double severityScore = Math.max(1, Math.min(5, emergency.getSeverity())) / 5.0;

        // Weighted sum
        double utility = (W_TYPE_MATCH * typeMatchScore)
                + (W_DISTANCE * distanceScore)
                + (W_WORKLOAD * workloadScore)
                + (W_SEVERITY * severityScore);

        return utility;
    }
    private static double euclideanDistance(Location loc1, Location loc2) {
        int dx = loc1.getX() - loc2.getX();
        int dy = loc1.getY() - loc2.getY();
        return Math.sqrt(dx * dx + dy * dy);
    }

    public static boolean isValidPrimaryResponder(UnitStatus unitStatus, Emergency emergency) {
        String unitId = unitStatus.getUnitId() == null ? "" : unitStatus.getUnitId().toLowerCase();
        String emergencyType = emergency.getType();

        if (emergencyType.equals("FIRE")) return unitId.contains("firetruck");
        if (emergencyType.equals("STRUCTURAL_COLLAPSE")) return unitId.contains("firetruck");
        if (emergencyType.equals("MEDICAL")) return unitId.contains("ambulance");
        // FIX: BIOHAZARD/CRYOGENIC_LEAK must accept BCU as valid primary responder
        if (emergencyType.equals("BIOHAZARD") || emergencyType.equals("CRYOGENIC_LEAK")) return unitId.contains("bcu"); // FIX

        return false;
    }

    private static double getTypeMatchScore(String unitId, String emergencyType, String serviceType) {
        String normalizedUnitId = unitId == null ? "" : unitId.toLowerCase();

        // Primary capability = 1.0. These are the units allowed to resolve the incident.
        if (emergencyType.equals("MEDICAL") && normalizedUnitId.contains("ambulance")) return 1.0;
        if (emergencyType.equals("FIRE") && normalizedUnitId.contains("firetruck")) return 1.0;
        if (emergencyType.equals("STRUCTURAL_COLLAPSE") && normalizedUnitId.contains("firetruck")) return 1.0;
        // if ((emergencyType.equals("BIOHAZARD") || emergencyType.equals("CRYOGENIC_LEAK")) && normalizedUnitId.contains("bcu")) return 1.0;

        // Secondary responders can be useful, but must not beat primary responders for assignment.
        if (serviceType.equals("CROWD_CONTROL")
                && normalizedUnitId.contains("police")
                && (emergencyType.equals("FIRE") || emergencyType.equals("STRUCTURAL_COLLAPSE"))) {
            return 0.5;
        }

        return 0.0;
    }
    /**
     * For report: Print the formula
     */
    public static void printFormula() {
        System.out.println("=== UTILITY FUNCTION ===");
        System.out.println("U(unit, incident) = 0.5*type + 0.2*(1/(1+d)) + 0.15*(1/(w+1)) + 0.15*(severity/5)");
        System.out.println("Weights: type=" + W_TYPE_MATCH + ", distance=" + W_DISTANCE + ", workload=" + W_WORKLOAD + ", severity=" + W_SEVERITY);
        System.out.println(explainWeightDominanceScenarios());
        System.out.println("========================");
    }

    public static String explainWeightDominanceScenarios() {
        StringBuilder sb = new StringBuilder();
        sb.append("Weight rationale scenarios:\n");
        sb.append("S1 FIRE: firetruck(d=12,w=0,s=4,type=1.0) vs ambulance(d=2,w=0,s=4,type=0.0)\n");
        double s1Firetruck = score(1.0, 12, 0, 4);
        double s1Amb = score(0.0, 2, 0, 4);
        sb.append(String.format(" -> firetruck=%.4f, ambulance=%.4f (type-match dominates distance)\n", s1Firetruck, s1Amb));

        // S2 removed (BIOHAZARD/BCU not in project specification)
        return sb.toString();
    }

    private static double score(double type, double distance, double workload, double severity) {
        return (W_TYPE_MATCH * type)
                + (W_DISTANCE * (1.0 / (1.0 + distance)))
                + (W_WORKLOAD * (1.0 / (1.0 + workload)))
                + (W_SEVERITY * (Math.max(1.0, Math.min(5.0, severity)) / 5.0));
    }
}