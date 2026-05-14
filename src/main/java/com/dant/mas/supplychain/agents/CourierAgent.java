package com.dant.mas.supplychain.agents;

import com.dant.mas.supplychain.Protocol;
import com.dant.mas.supplychain.bus.LogChannel;
import com.dant.mas.supplychain.bus.SimulationEventBus;
import com.dant.mas.supplychain.util.SimLog;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class CourierAgent extends Agent {

  private SimulationEventBus bus;
  private double ratePerKm;
  private final Set<String> waiting = new HashSet<>();

  @Override
  protected void setup() {
    Object[] a = getArguments();
    if (a != null && a.length > 0 && a[0] instanceof SimulationEventBus b) {
      bus = b;
    }
    ratePerKm = Double.parseDouble(arg(2, "2.5"));

    addBehaviour(
        new OneShotBehaviour(this) {
          @Override
          public void action() {
            try {
              DFAgentDescription dfd = new DFAgentDescription();
              dfd.setName(getAID());
              ServiceDescription sd = new ServiceDescription();
              sd.setType(Protocol.DF_COURIER_TYPE);
              sd.setName(getLocalName());
              dfd.addServices(sd);
              DFService.register(CourierAgent.this, dfd);
              bus.log(getLocalName() + " registered on DF as courier.", LogChannel.B2B);
            } catch (FIPAException e) {
              bus.log("DF register failed: " + e.getMessage(), LogChannel.B2B);
            }
          }
        });

    MessageTemplate cfp =
        MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.CFP),
            MessageTemplate.MatchOntology("B2B-LOGISTICS"));
    addBehaviour(
        new CyclicBehaviour(this) {
          @Override
          public void action() {
            ACLMessage m = receive(cfp);
            if (m == null) {
              block();
              return;
            }
            SimLog.got(bus, getLocalName(), m);
            Map<String, String> p = Protocol.parseMap(m.getContent());
            String depot = p.getOrDefault(Protocol.K_DEPOT, "DEPOT-CENTRAL");
            double kg = Double.parseDouble(p.getOrDefault(Protocol.K_WEIGHT_KG, "10"));
            int km =
                switch (depot) {
                  case "DEPOT-NORTH" -> 120;
                  case "DEPOT-SOUTH" -> 200;
                  default -> 80;
                };
            double fee = ratePerKm * km * (1 + kg / 200.0);
            ACLMessage r = m.createReply();
            r.setOntology("B2B-LOGISTICS");
            r.setConversationId(m.getConversationId());
            r.setPerformative(ACLMessage.PROPOSE);
            r.setContent(
                Protocol.formatMap(
                    Map.of(
                        Protocol.K_DELIVERY_FEE,
                        String.format(java.util.Locale.US, "%.2f", fee),
                        Protocol.K_DEPOT,
                        depot)));
            SimLog.sent(bus, getLocalName(), r);
            send(r);
          }
        });

    MessageTemplate esc =
        MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.INFORM),
            MessageTemplate.MatchOntology("B2B-ESCROW"));
    addBehaviour(
        new CyclicBehaviour(this) {
          @Override
          public void action() {
            ACLMessage m = receive(esc);
            if (m == null) {
              block();
              return;
            }
            SimLog.got(bus, getLocalName(), m);
            Map<String, String> p = Protocol.parseMap(m.getContent());
            String act = p.get(Protocol.K_ACTION);
            String oid = p.get(Protocol.K_ORDER_ID);
            if (Protocol.A_AWAIT_ORDER.equals(act) && oid != null) {
              waiting.add(oid);
            } else if (Protocol.A_GOODS_READY.equals(act)
                && oid != null
                && waiting.remove(oid)) {
              addBehaviour(
                  new WakerBehaviour(myAgent, SimLog.ms(CourierAgent.this, 1000)) {
                    @Override
                    protected void onWake() {
                      ACLMessage done = new ACLMessage(ACLMessage.INFORM);
                      done.addReceiver(new jade.core.AID("bank", jade.core.AID.ISLOCALNAME));
                      done.setOntology("B2B-ESCROW");
                      done.setContent(
                          Protocol.formatMap(
                              Map.of(
                                  Protocol.K_ACTION,
                                  Protocol.A_DELIVERY_COMPLETE,
                                  Protocol.K_ORDER_ID,
                                  oid)));
                      SimLog.sent(bus, getLocalName(), done);
                      send(done);
                      bus.log(getLocalName() + " delivered order " + oid + " — informed bank.", LogChannel.B2B);
                    }
                  });
            }
          }
        });
  }

  @Override
  protected void takeDown() {
    try {
      DFService.deregister(this);
    } catch (FIPAException ignored) {
    }
  }

  private String arg(int i, String d) {
    Object[] a = getArguments();
    return a != null && a.length > i && a[i] != null ? String.valueOf(a[i]) : d;
  }
}
