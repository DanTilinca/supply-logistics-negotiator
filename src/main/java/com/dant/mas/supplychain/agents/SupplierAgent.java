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
import java.util.HashMap;
import java.util.Map;

/** Registers on the DF, answers CFP with a simple price, then tells the courier when goods are ready. */
public class SupplierAgent extends Agent {

  private SimulationEventBus bus;
  private String depot;
  private double unitPrice;

  @Override
  protected void setup() {
    Object[] a = getArguments();
    if (a != null && a.length > 0 && a[0] instanceof SimulationEventBus b) {
      bus = b;
    }
    depot = arg(2, "DEPOT-CENTRAL");
    unitPrice = Double.parseDouble(arg(3, "8.5"));

    addBehaviour(
        new OneShotBehaviour(this) {
          @Override
          public void action() {
            try {
              DFAgentDescription dfd = new DFAgentDescription();
              dfd.setName(getAID());
              ServiceDescription sd = new ServiceDescription();
              sd.setType(Protocol.DF_SUPPLIER_TYPE);
              sd.setName(getLocalName());
              dfd.addServices(sd);
              DFService.register(SupplierAgent.this, dfd);
              bus.log(getLocalName() + " registered on DF as supplier.", LogChannel.B2B);
            } catch (FIPAException e) {
              bus.log("DF register failed: " + e.getMessage(), LogChannel.B2B);
            }
          }
        });

    MessageTemplate cfp =
        MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.CFP),
            MessageTemplate.MatchOntology("B2B-PROCUREMENT"));
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
            Map<String, String> req = Protocol.parseMap(m.getContent());
            int qty = Integer.parseInt(req.getOrDefault(Protocol.K_QTY, "0"));
            String product = req.getOrDefault(Protocol.K_PRODUCT, Protocol.PRODUCT_DEFAULT);
            ACLMessage r = m.createReply();
            r.setOntology("B2B-PROCUREMENT");
            r.setConversationId(m.getConversationId());
            if (qty <= 0) {
              r.setPerformative(ACLMessage.REFUSE);
            } else {
              double u =
                  unitPrice
                      * (qty > 150 ? 0.97 : 1)
                      * (1.0 + (Math.abs(product.hashCode()) % 5) * 0.02);
              double total = u * qty;
              r.setPerformative(ACLMessage.PROPOSE);
              Map<String, String> map = new HashMap<>();
              map.put(Protocol.K_PRODUCT, product);
              map.put(Protocol.K_QTY, Integer.toString(qty));
              map.put(Protocol.K_UNIT_PRICE, String.format(java.util.Locale.US, "%.2f", u));
              map.put(Protocol.K_TOTAL, String.format(java.util.Locale.US, "%.2f", total));
              map.put(Protocol.K_DEPOT, depot);
              r.setContent(Protocol.formatMap(map));
            }
            SimLog.sent(bus, getLocalName(), r);
            send(r);
          }
        });

    MessageTemplate bank =
        MessageTemplate.and(
            MessageTemplate.MatchPerformative(ACLMessage.INFORM),
            MessageTemplate.MatchOntology("B2B-ESCROW"));
    addBehaviour(
        new CyclicBehaviour(this) {
          @Override
          public void action() {
            ACLMessage m = receive(bank);
            if (m == null) {
              block();
              return;
            }
            SimLog.got(bus, getLocalName(), m);
            Map<String, String> p = Protocol.parseMap(m.getContent());
            if (!Protocol.A_SHIP_ORDER.equals(p.get(Protocol.K_ACTION))) {
              return;
            }
            String cour = p.get(Protocol.K_COURIER_LOCAL);
            String oid = p.get(Protocol.K_ORDER_ID);
            addBehaviour(
                new WakerBehaviour(myAgent, SimLog.ms(SupplierAgent.this, 800)) {
                  @Override
                  protected void onWake() {
                    ACLMessage ready = new ACLMessage(ACLMessage.INFORM);
                    ready.addReceiver(new jade.core.AID(cour, jade.core.AID.ISLOCALNAME));
                    ready.setOntology("B2B-ESCROW");
                    ready.setContent(
                        Protocol.formatMap(
                            Map.of(
                                Protocol.K_ACTION,
                                Protocol.A_GOODS_READY,
                                Protocol.K_ORDER_ID,
                                oid != null ? oid : "",
                                Protocol.K_DEPOT,
                                depot)));
                    SimLog.sent(bus, getLocalName(), ready);
                    send(ready);
                    bus.log(getLocalName() + " prepared shipment for " + oid + ".", LogChannel.B2B);
                  }
                });
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
