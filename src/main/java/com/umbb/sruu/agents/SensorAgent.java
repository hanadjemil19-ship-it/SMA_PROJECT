package com.umbb.sruu.agents;

import com.umbb.sruu.ontology.Emergency;
import com.umbb.sruu.ontology.EmergencyOntology;
import com.umbb.sruu.ontology.HasEmergency;
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
    }

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

    private void generateEmergency() {
        incidentCounter++;

        String globalId = getLocalName().toUpperCase() + "-EMERGENCY-" + incidentCounter;

        Emergency emergency = new Emergency();
        emergency.setId(globalId);

        // MODIFIED: extend incident types (keep existing behavior unchanged)
        String[] types = {"FIRE", "MEDICAL", "STRUCTURAL_COLLAPSE", "BIOHAZARD", "CRYOGENIC_LEAK"}; // NEW
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

    private void sendEmergencyAlert(Emergency emergency) {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        AID dispatcher = findAgentByService("COORDINATION");
        if (dispatcher != null) msg.addReceiver(dispatcher);
        msg.setLanguage(new SLCodec().getName());
        msg.setOntology(EmergencyOntology.getInstance().getName());

        HasEmergency he = new HasEmergency();
        he.setEmergency(emergency);

        try {
            getContentManager().fillContent(msg, he);
            send(msg);
            System.out.println("[" + getLocalName() + "] Sent INFORM to dispatcher");
        } catch (Exception e) {
            System.err.println("[" + getLocalName() + "] Error sending message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private AID findAgentByService(String serviceType) {
        return com.umbb.sruu.utils.AgentUtils.findAgentByService(this, serviceType);
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException e) {
            e.printStackTrace();
        }
        System.out.println("[" + getLocalName() + "] SensorAgent terminated");
    }
}
