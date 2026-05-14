package com.dant.mas.supplychain.agents;

import com.dant.mas.supplychain.Protocol;
import com.dant.mas.supplychain.bus.LogChannel;
import com.dant.mas.supplychain.bus.SimulationEventBus;
import com.dant.mas.supplychain.util.SimLog;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BankAgent extends Agent {

  private SimulationEventBus bus;
  private final Map<String, Escrow> open = new ConcurrentHashMap<>();

  @Override
  protected void setup() {
    if (getArguments() != null && getArguments().length > 0 && getArguments()[0] instanceof SimulationEventBus b) {
      bus = b;
    }
    bus.log("Bank ready — holds retailer money until the courier confirms delivery.", LogChannel.B2B);
    addBehaviour(
        new CyclicBehaviour(this) {
          @Override
          public void action() {
            ACLMessage m = receive();
            if (m == null) {
              block();
              return;
            }
            SimLog.got(bus, getLocalName(), m);
            if (m.getPerformative() == ACLMessage.REQUEST) {
              openEscrow(m);
            } else if (m.getPerformative() == ACLMessage.INFORM) {
              deliveryDone(m);
            }
          }
        });
  }

  private void openEscrow(ACLMessage m) {
    Map<String, String> p = Protocol.parseMap(m.getContent());
    if (!Protocol.A_OPEN_ESCROW.equals(p.get(Protocol.K_ACTION))) {
      return;
    }
    String id = p.get(Protocol.K_ORDER_ID);
    try {
      var sup = new jade.core.AID(p.get(Protocol.K_SUPPLIER_LOCAL), jade.core.AID.ISLOCALNAME);
      var cour = new jade.core.AID(p.get(Protocol.K_COURIER_LOCAL), jade.core.AID.ISLOCALNAME);
      double g = Double.parseDouble(p.get(Protocol.K_TOTAL_GOODS));
      double d = Double.parseDouble(p.get(Protocol.K_DELIVERY_AMOUNT));
      int qty = Integer.parseInt(p.get(Protocol.K_QTY));
      String product =
          p.getOrDefault(Protocol.K_PRODUCT, Protocol.PRODUCT_DEFAULT);
      open.put(id, new Escrow(m.getSender(), sup, cour, g, d, qty, product));

      ACLMessage ok = m.createReply();
      ok.setPerformative(ACLMessage.AGREE);
      ok.setOntology("B2B-ESCROW");
      ok.setContent(Protocol.formatMap(Map.of(Protocol.K_ORDER_ID, id)));
      SimLog.sent(bus, getLocalName(), ok);
      send(ok);

      ACLMessage toS = new ACLMessage(ACLMessage.INFORM);
      toS.addReceiver(sup);
      toS.setOntology("B2B-ESCROW");
      toS.setContent(
          Protocol.formatMap(
              Map.of(
                  Protocol.K_ACTION,
                  Protocol.A_SHIP_ORDER,
                  Protocol.K_ORDER_ID,
                  id,
                  Protocol.K_QTY,
                  Integer.toString(qty),
                  Protocol.K_PRODUCT,
                  product,
                  Protocol.K_COURIER_LOCAL,
                  cour.getLocalName())));
      SimLog.sent(bus, getLocalName(), toS);
      send(toS);

      ACLMessage toC = new ACLMessage(ACLMessage.INFORM);
      toC.addReceiver(cour);
      toC.setOntology("B2B-ESCROW");
      toC.setContent(
          Protocol.formatMap(
              Map.of(
                  Protocol.K_ACTION,
                  Protocol.A_AWAIT_ORDER,
                  Protocol.K_ORDER_ID,
                  id,
                  Protocol.K_SUPPLIER_LOCAL,
                  sup.getLocalName())));
      SimLog.sent(bus, getLocalName(), toC);
      send(toC);

      bus.logf(LogChannel.B2B, "Escrow %s: told supplier to ship and courier to wait for pickup.", id);
    } catch (Exception e) {
      ACLMessage r = m.createReply();
      r.setPerformative(ACLMessage.REFUSE);
      r.setContent("bad request");
      send(r);
    }
  }

  private void deliveryDone(ACLMessage m) {
    Map<String, String> p = Protocol.parseMap(m.getContent());
    if (!Protocol.A_DELIVERY_COMPLETE.equals(p.get(Protocol.K_ACTION))) {
      return;
    }
    String id = p.get(Protocol.K_ORDER_ID);
    Escrow e = open.remove(id);
    if (e == null) {
      return;
    }
    pay(e.supplier, id, e.goods);
    pay(e.courier, id, e.delivery);
    ACLMessage done = new ACLMessage(ACLMessage.INFORM);
    done.addReceiver(e.retailer);
    done.setOntology("B2B-ESCROW");
    done.setContent(
        Protocol.formatMap(
            Map.of(
                Protocol.K_ACTION,
                Protocol.A_ESCROW_CLOSED,
                Protocol.K_ORDER_ID,
                id,
                Protocol.K_QTY,
                Integer.toString(e.qty),
                Protocol.K_PRODUCT,
                e.product,
                Protocol.K_TOTAL,
                String.format(java.util.Locale.US, "%.2f", e.goods + e.delivery))));
    SimLog.sent(bus, getLocalName(), done);
    send(done);
    bus.logf(
        LogChannel.B2B,
        "Courier confirmed delivery for %s — released payments (supplier + courier).", id);
  }

  private void pay(jade.core.AID to, String id, double amount) {
    ACLMessage x = new ACLMessage(ACLMessage.INFORM);
    x.addReceiver(to);
    x.setOntology("B2B-ESCROW");
    x.setContent(
        Protocol.formatMap(
            Map.of(
                Protocol.K_ACTION,
                Protocol.A_PAYMENT_RELEASED,
                Protocol.K_ORDER_ID,
                id,
                Protocol.K_TOTAL,
                String.format(java.util.Locale.US, "%.2f", amount))));
    SimLog.sent(bus, getLocalName(), x);
    send(x);
  }

  private record Escrow(
      jade.core.AID retailer,
      jade.core.AID supplier,
      jade.core.AID courier,
      double goods,
      double delivery,
      int qty,
      String product) {}
}
