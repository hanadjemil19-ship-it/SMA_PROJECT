package com.umbb.sruu.agents;

import com.umbb.sruu.ontology.Emergency;
import com.umbb.sruu.ontology.EmergencyOntology;

import com.umbb.sruu.ontology.Location;
import jade.content.lang.sl.SLCodec;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;

import java.util.Random;

public class SensorAgent extends Agent {

    private Random random = new Random();
    private int incidentCounter = 0;
    private Location sensorLocation;

    @Override
    protected void setup() {
        System.out.println("[" + getLocalName() + "] SensorAgent started");

        getContentManager().registerLanguage(new SLCodec());
        getContentManager().registerOntology(EmergencyOntology.getInstance());

        // Different location based on agent name
        if (getLocalName().equals("sensor-1")) {
            sensorLocation = new Location(10, 10);  // North area
            System.out.println("[" + getLocalName() + "] Position: North area (10,10)");
        } else {
            sensorLocation = new Location(40, 40);  // South area
            System.out.println("[" + getLocalName() + "] Position: South area (40,40)");
        }

        registerInDF();

        // Different tick rate to create simultaneous incidents
        long tickRate = getLocalName().equals("sensor-1") ? 10000 : 12000;

        addBehaviour(new TickerBehaviour(this, tickRate) {
            @Override
            protected void onTick() {
                generateEmergency();
            }
        });

        // Forced MEDICAL emergency after 10 seconds as requested
        if (getLocalName().equals("sensor-1")) {
            addBehaviour(new jade.core.behaviours.WakerBehaviour(this, 10000) {
                @Override
                protected void onWake() {
                    generateForcedMedicalEmergency();
                }
            });
        }
    }
    // setup(): Initialises the sensor, sets its fixed grid position based on its name
    // (sensor-1 = north, others = south), registers in the DF, then starts a periodic
    // TickerBehaviour that fires generateEmergency() every 10 or 12 seconds to simulate
    // spontaneous incident detection in the city grid.

    private void registerInDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());

        ServiceDescription sd = new ServiceDescription();
        sd.setType("sensor");
        sd.setName("emergency-sensor-" + getLocalName());
        dfd.addServices(sd);

        try {
            DFService.register(this, dfd);
            System.out.println("[" + getLocalName() + "] Registered in DF as 'sensor'");
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }
    // registerInDF(): Publishes this sensor in the JADE Directory Facilitator (DF) under
    // service type "sensor". This allows other agents (e.g., the dispatcher) to discover
    // and monitor all active sensors in the system.

    private void generateEmergency() {
        incidentCounter++;

        String globalId = getLocalName().toUpperCase() + "-EMERGENCY-" + incidentCounter;

        Emergency emergency = new Emergency();
        emergency.setId(globalId);

        // MODIFIED: extend incident types (keep existing behavior unchanged)
        String[] types = {"FIRE", "MEDICAL", "STRUCTURAL_COLLAPSE"};
        emergency.setType(types[random.nextInt(types.length)]);

        emergency.setSeverity(random.nextInt(5) + 1);

        // Generate location near sensor
        int offsetX = random.nextInt(20) - 10;  // -10 to +10
        int offsetY = random.nextInt(20) - 10;

        int x = Math.max(0, Math.min(49, sensorLocation.getX() + offsetX));
        int y = Math.max(0, Math.min(49, sensorLocation.getY() + offsetY));

        Location loc = new Location(x, y);
        emergency.setLocation(loc);

        System.out.println("[" + getLocalName() + "] DETECTED: " + emergency);

        sendEmergencyAlert(emergency);
    }

    private void generateForcedMedicalEmergency() {
        incidentCounter++;
        String globalId = getLocalName().toUpperCase() + "-MEDICAL-FORCED-" + incidentCounter;
        
        Emergency emergency = new Emergency();
        emergency.setId(globalId);
        emergency.setType("MEDICAL");
        emergency.setSeverity(3);
        emergency.setLocation(new Location(15, 15));

        System.out.println("[" + getLocalName() + "] FORCED MEDICAL DETECTION: " + emergency);
        sendEmergencyAlert(emergency);
    }
    // generateEmergency(): Creates a new Emergency object with a unique ID, a randomly
    // chosen type (FIRE, MEDICAL, STRUCTURAL_COLLAPSE), a random severity (1–5), and a
    // location within ±10 cells of this sensor's fixed position (clamped to the 0–49 grid).
    // After building the event it delegates to sendEmergencyAlert() to notify the dispatcher.

    private void sendEmergencyAlert(Emergency emergency) {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        AID dispatcher = findAgentByService("COORDINATION");
        if (dispatcher != null) msg.addReceiver(dispatcher);
        msg.setLanguage(new SLCodec().getName());
        msg.setOntology(EmergencyOntology.getInstance().getName());
        msg.setContent("DETECTED:" + emergency.getId() + ":" + emergency.getType());

        try {
            msg.setContentObject(emergency);
            send(msg);
            System.out.println("[" + getLocalName() + "] Sent INFORM to dispatcher");
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error sending message: " + e.getMessage());
            e.printStackTrace();
        }
    }
    // sendEmergencyAlert(): Sets the Emergency object directly via setContentObject(),
    // bypassing FIPA SL predicate wrapping, and sends it to the DispatcherAgent.

    private AID findAgentByService(String serviceType) {
        return com.umbb.sruu.utils.AgentUtils.findAgentByService(this, serviceType);
    }
    // findAgentByService(): Utility wrapper that queries the DF for the first agent offering
    // the given service type and returns its AID, or null if none is found.

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException e) {
            e.printStackTrace();
        }
        System.out.println("[" + getLocalName() + "] SensorAgent terminated");
    }
    // takeDown(): Cleanup hook called when the agent is killed or the platform shuts down.
    // Deregisters this sensor from the DF so stale entries do not pollute future lookups.
}
