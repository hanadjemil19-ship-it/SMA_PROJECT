package com.umbb.sruu.agents;

import com.umbb.sruu.ontology.EmergencyOntology;
import com.umbb.sruu.ontology.Location;
import com.umbb.sruu.ontology.RouteCleared;
import jade.content.lang.sl.SLCodec;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.HashMap;
import java.util.Map;

public class TrafficControllerAgent extends Agent {

    private Map<String, Long> activeRoutes = new HashMap<>();

    @Override
    protected void setup() {
        System.out.println("[" + getLocalName() + "] TrafficController started");

        getContentManager().registerLanguage(new SLCodec());
        getContentManager().registerOntology(EmergencyOntology.getInstance());

        registerInDF();

        // Listen for route requests from Dispatcher
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                        MessageTemplate.MatchOntology(EmergencyOntology.getInstance().getName())
                );

                ACLMessage msg = receive(mt);

                if (msg != null) {
                    handleRouteRequest(msg);
                } else {
                    block();
                }
            }
        });

        // Check for expired routes every 2 seconds
        addBehaviour(new TickerBehaviour(this, 2000) {
            @Override
            protected void onTick() {
                long now = System.currentTimeMillis();
                activeRoutes.entrySet().removeIf(entry -> entry.getValue() < now);
            }
        });
    }

    private void registerInDF() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());

        ServiceDescription sd = new ServiceDescription();
        sd.setType("TRAFFIC_CONTROL");
        sd.setName("traffic-controller");
        dfd.addServices(sd);

        try {
            DFService.register(this, dfd);
            System.out.println("[" + getLocalName() + "] Registered in DF as TRAFFIC_CONTROL");
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }

    private void handleRouteRequest(ACLMessage request) {
        try {
            System.out.println("[" + getLocalName() + "] Received route request from "
                    + request.getSender().getLocalName());

            String content = request.getContent();
            Location from = new Location(0, 0);
            Location to = new Location(0, 0);

            if (content != null && content.startsWith("ROUTE:")) {
                String[] parts = content.substring(6).split(",");
                if (parts.length == 4) {
                    from = new Location(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                    to = new Location(Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                }
            }

            int expirationSeconds = 30;
            String routeKey = from.getX() + "," + from.getY() + "-" + to.getX() + "," + to.getY();
            long expirationTime = System.currentTimeMillis() + (expirationSeconds * 1000);
            activeRoutes.put(routeKey, expirationTime);

            System.out.println("[" + getLocalName() + "] Route cleared: " + routeKey
                    + " for " + expirationSeconds + " seconds");

            // Broadcast to units
            broadcastRouteCleared(from, to, expirationSeconds);

            // Reply to dispatcher with ontology
            ACLMessage reply = request.createReply();
            reply.setPerformative(ACLMessage.INFORM);
            reply.setOntology(EmergencyOntology.getInstance().getName());
            reply.setLanguage(new SLCodec().getName());

            RouteCleared rc = new RouteCleared();
            rc.setFrom(from);
            rc.setTo(to);
            rc.setExpirationSeconds(expirationSeconds);

            getContentManager().fillContent(reply, rc);
            send(reply);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void broadcastRouteCleared(Location from, Location to, int expirationSeconds) {
        ACLMessage broadcast = new ACLMessage(ACLMessage.INFORM);
        broadcast.setOntology(EmergencyOntology.getInstance().getName());
        broadcast.setLanguage(new SLCodec().getName());

        String[] unitNames = {
                "ambulance-1", "ambulance-2", "ambulance-3",
                "firetruck-1", "firetruck-2", "firetruck-3",
                "police-1", "police-2", "police-3",
                "bcu-1"
        };
        for (String name : unitNames) {
            broadcast.addReceiver(new AID(name, AID.ISLOCALNAME));
        }

        try {
            RouteCleared rc = new RouteCleared();
            rc.setFrom(from);
            rc.setTo(to);
            rc.setExpirationSeconds(expirationSeconds);

            getContentManager().fillContent(broadcast, rc);
            send(broadcast);

            System.out.println("[" + getLocalName() + "] Broadcast route clearance to units");

        } catch (Exception e) {
            broadcast.setContent("ROUTE_CLEARED:from=" + from + " to=" + to
                    + " expires=" + expirationSeconds + "s");
            send(broadcast);
        }
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException e) {
            e.printStackTrace();
        }
        System.out.println("[" + getLocalName() + "] TrafficController terminated");
    }
}
