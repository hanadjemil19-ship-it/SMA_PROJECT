package com.umbb.sruu.agents;

import jade.core.Agent;
import jade.core.behaviours.SequentialBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;

/**
 * SRUU - Bootstrap Agent (FIXED)
 * Launches all system agents with delays to ensure proper DF registration.
 * Uses WakerBehaviour between launches (compliant with no busy-waiting).
 */
public class BootstrapAgent extends Agent {

    private static final long LAUNCH_DELAY = 800; // ms between agent launches

    @Override
    protected void setup() {
        System.out.println("[" + getLocalName() + "] BootstrapAgent started");

        SequentialBehaviour startupSequence = new SequentialBehaviour(this);

        // Launch each agent with a WakerBehaviour delay between them
        // This gives each agent time to register in the DF before the next starts
        addLaunchStep(startupSequence, "logger", "com.umbb.sruu.agents.LoggerAgent");
        addLaunchStep(startupSequence, "dispatcher", "com.umbb.sruu.agents.DispatcherAgent");
        addLaunchStep(startupSequence, "traffic-controller", "com.umbb.sruu.agents.TrafficControllerAgent");
        for (int i = 1; i <= 3; i++) {
            addLaunchStep(startupSequence, "police-" + i, "com.umbb.sruu.agents.PoliceAgent");
        }
        for (int i = 1; i <= 3; i++) {
            addLaunchStep(startupSequence, "firetruck-" + i, "com.umbb.sruu.agents.FireTruckAgent");
        }
        // BCU agents removed - not in project specification
        // for (int i = 1; i <= 3; i++) {
        //     addLaunchStep(startupSequence, "bcu-" + i, "com.umbb.sruu.agents.BiohazardContainmentUnitAgent");
        // }

        addLaunchStep(startupSequence, "hospital-1", "com.umbb.sruu.agents.HospitalAgent");
        addLaunchStep(startupSequence, "hospital-2", "com.umbb.sruu.agents.HospitalAgent");
        addLaunchStep(startupSequence, "hospital-3", "com.umbb.sruu.agents.HospitalAgent");
        addLaunchStep(startupSequence, "medical-coordinator", "com.umbb.sruu.agents.MedicalCoordinatorAgent");
        for (int i = 1; i <= 3; i++) {
            addLaunchStep(startupSequence, "ambulance-" + i, "com.umbb.sruu.agents.AmbulanceAgent");
        }
        addLaunchStep(startupSequence, "sensor-1", "com.umbb.sruu.agents.SensorAgent");
        addLaunchStep(startupSequence, "sensor-2", "com.umbb.sruu.agents.SensorAgent");

        startupSequence.addSubBehaviour(new WakerBehaviour(this, LAUNCH_DELAY) {
            @Override
            protected void onWake() {
                System.out.println("========================================");
                System.out.println("  All agents launched!");
                System.out.println("  3 Police + 3 Ambulances + 2 Sensors active");
                System.out.println("========================================");
            }
        });

        addBehaviour(startupSequence);
    }

    /**
     * Adds a launch step: create agent + wait delay before next step
     */
    private void addLaunchStep(SequentialBehaviour seq, String agentName, String className) {
        // Step 1: Launch the agent
        seq.addSubBehaviour(new jade.core.behaviours.OneShotBehaviour(this) {
            @Override
            public void action() {
                try {
                    AgentContainer container = myAgent.getContainerController();
                    AgentController agent = container.createNewAgent(agentName, className, null);
                    agent.start();
                    System.out.println("[" + getLocalName() + "] Launched " + agentName);
                } catch (Exception e) {
                    System.err.println("[" + getLocalName() + "] FAILED to launch " + agentName + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }
        });

        // Step 2: Wait for agent to initialize and register in DF
        seq.addSubBehaviour(new WakerBehaviour(this, LAUNCH_DELAY) {
            @Override
            protected void onWake() {
                // Small delay ensures DF registration completes
            }
        });
    }
}