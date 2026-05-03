package com.umbb.sruu;

import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;

/**
 * SRUU - Main Entry Point (FULLY COMPLIANT)
 * No Thread.sleep() anywhere - uses JADE SequentialBehaviour for startup
 */
public class Main {
    public static void main(String[] args) {
        Runtime runtime = Runtime.instance();

        Profile profile = new ProfileImpl();
        profile.setParameter(Profile.GUI, "true");
        profile.setParameter(Profile.MAIN_HOST, "localhost");
        profile.setParameter(Profile.MAIN_PORT, "1099");

        AgentContainer mainContainer = runtime.createMainContainer(profile);

        System.out.println("========================================");
        System.out.println("  SRUU - Emergency Response System");
        System.out.println("  Starting...");
        System.out.println("========================================");

        try {
            // BootstrapAgent is in com.umbb.sruu.agents package
            AgentController bootstrap = mainContainer.createNewAgent(
                    "bootstrap",
                    "com.umbb.sruu.agents.BootstrapAgent",
                    null
            );
            bootstrap.start();

            // Keep alive
            System.out.println("Press ENTER to stop...");
            System.in.read();

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                runtime.shutDown();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
