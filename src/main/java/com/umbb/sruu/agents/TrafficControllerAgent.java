package com.umbb.sruu.agents;

import com.umbb.sruu.ontology.EmergencyOntology;
import com.umbb.sruu.ontology.Location;
import com.umbb.sruu.ontology.RouteCleared;
import com.umbb.sruu.ontology.RouteExpired;
import jade.content.lang.sl.SLCodec;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
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

        // Expired routes are now handled by per-route WakerBehaviours.
    }
    // setup(): Initialises the agent, registers in the DF, then adds two behaviours:
    // (1) a CyclicBehaviour that listens for ACL REQUEST messages with the emergency ontology
    //     and processes each route clearance request from the dispatcher;
    // (2) a TickerBehaviour that runs every 2 seconds to evict expired route entries from
    //     the activeRoutes map, keeping the cleared-route registry up to date.

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
    // registerInDF(): Publishes this agent in the JADE DF under service type "TRAFFIC_CONTROL"
    // so that the dispatcher can locate it via a DF lookup when it needs to request a route clearance.

    private void handleRouteRequest(ACLMessage request) {
        try {
            System.out.println("[" + getLocalName() + "] Received route request from "
                    + request.getSender().getLocalName());

            RouteCleared rcRequest = (RouteCleared) request.getContentObject();
            Location from = rcRequest.getFrom();
            Location to = rcRequest.getTo();

            if (from == null) from = new Location(0, 0);
            if (to == null) to = new Location(0, 0);
            int expirationSeconds = rcRequest.getExpirationSeconds() > 0 ? rcRequest.getExpirationSeconds() : 30;
            final String routeKey = from.getX() + "," + from.getY() + "-" + to.getX() + "," + to.getY();
            long expirationTime = System.currentTimeMillis() + (expirationSeconds * 1000);
            activeRoutes.put(routeKey, expirationTime);

            System.out.println("[" + getLocalName() + "] Route cleared: " + routeKey
                    + " for " + expirationSeconds + " seconds");

            // Schedule expiry notification
            addBehaviour(new jade.core.behaviours.WakerBehaviour(this, expirationSeconds * 1000L) {
                @Override
                protected void onWake() {
                    activeRoutes.remove(routeKey);
                    broadcastRouteExpired(routeKey);
                }
            });

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

            // DUAL-MODE for INFORM reply
            reply.setContent("ROUTE_CLEARED:" + routeKey + ":" + expirationSeconds);
            reply.setContentObject(rc);
            send(reply);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    // handleRouteRequest(): Processes a ROUTE:<x1>,<y1>,<x2>,<y2> request from the dispatcher.
    // Parses the from/to coordinates, stores the cleared route with a 30-second expiry in
    // activeRoutes, broadcasts the RouteCleared ontology event to all operational units via
    // broadcastRouteCleared(), and finally sends an INFORM reply back to the dispatcher.

    private void broadcastRouteCleared(Location from, Location to, int expirationSeconds) {
        ACLMessage broadcast = new ACLMessage(ACLMessage.INFORM);
        broadcast.setOntology(EmergencyOntology.getInstance().getName());
        broadcast.setLanguage(new SLCodec().getName());

        String[] targetServices = {"MEDICAL", "FIRE", "RESCUE", "CROWD_CONTROL", "TRAFFIC_CONTROL"};
        for (String type : targetServices) {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType(type);
            template.addServices(sd);
            try {
                DFAgentDescription[] results = DFService.search(this, template);
                for (DFAgentDescription result : results) {
                    broadcast.addReceiver(result.getName());
                }
            } catch (FIPAException e) {
                e.printStackTrace();
            }
        }

        try {
            RouteCleared rc = new RouteCleared();
            rc.setFrom(from);
            rc.setTo(to);
            rc.setExpirationSeconds(expirationSeconds);

            // DUAL-MODE for Broadcast
            broadcast.setContent("ROUTE_CLEARED:from=" + from + " to=" + to + " expires=" + expirationSeconds + "s");
            broadcast.setContentObject(rc);
            send(broadcast);

            System.out.println("[" + getLocalName() + "] Broadcast route clearance to units");

        } catch (Exception e) {
            broadcast.setContent("ROUTE_CLEARED:from=" + from + " to=" + to
                    + " expires=" + expirationSeconds + "s");
            send(broadcast);
        }
    }
    private void broadcastRouteExpired(String routeKey) {
        ACLMessage broadcast = new ACLMessage(ACLMessage.INFORM);
        broadcast.setOntology(EmergencyOntology.getInstance().getName());
        broadcast.setLanguage(new SLCodec().getName());

        String[] targetServices = {"MEDICAL", "FIRE", "RESCUE", "CROWD_CONTROL", "TRAFFIC_CONTROL"};
        for (String type : targetServices) {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType(type);
            template.addServices(sd);
            try {
                DFAgentDescription[] results = DFService.search(this, template);
                for (DFAgentDescription result : results) {
                    broadcast.addReceiver(result.getName());
                }
            } catch (FIPAException e) {
                e.printStackTrace();
            }
        }

        try {
            RouteExpired re = new RouteExpired();
            re.setEmergencyId(routeKey);
            broadcast.setContentObject(re);
            send(broadcast);
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("[" + getLocalName() + "] Broadcast route expiry to units: " + routeKey);
    }
    // broadcastRouteExpired(): Broadcasts an INFORM to all field units telling them a route has expired.

    // broadcastRouteCleared(): Discovers all registered field units (MEDICAL, FIRE, RESCUE,
    // CROWD_CONTROL, TRAFFIC_CONTROL) via the DF and sends each of them a RouteCleared INFORM
    // message so they can optimise their movement paths. If ontology serialisation fails, a
    // plain-text fallback content string is used to ensure the message is still delivered.

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException e) {
            e.printStackTrace();
        }
        System.out.println("[" + getLocalName() + "] TrafficController terminated");
    }
    // takeDown(): Cleanup hook executed when the agent is shut down.
    // Deregisters from the DF so other agents no longer try to route requests to this instance.
}
