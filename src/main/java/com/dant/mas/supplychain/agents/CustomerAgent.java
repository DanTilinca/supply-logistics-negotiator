package com.dant.mas.supplychain.agents;

import com.dant.mas.supplychain.Protocol;
import com.dant.mas.supplychain.bus.SimulationEventBus;
import com.dant.mas.supplychain.util.SimLog;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class CustomerAgent extends Agent {

  private SimulationEventBus bus;
  private AID shop;
  private final Random rnd = new Random();

  @Override
  protected void setup() {
    Object[] a = getArguments();
    if (a != null && a.length > 0 && a[0] instanceof SimulationEventBus b) {
      bus = b;
    }
    shop = new AID(arg(2, "retailer"), AID.ISLOCALNAME);
    addBehaviour(
        new TickerBehaviour(this, SimLog.ms(this, 2400)) {
          @Override
          protected void onTick() {
            if (rnd.nextDouble() > 0.22) {
              return;
            }
            int want = 12 + rnd.nextInt(39);
            String sku =
                Protocol.RETAIL_CATALOG.get(rnd.nextInt(Protocol.RETAIL_CATALOG.size()));
            String conv = "c-" + UUID.randomUUID().toString().substring(0, 8);
            ACLMessage q = new ACLMessage(ACLMessage.REQUEST);
            q.addReceiver(shop);
            q.setOntology("B2C-CUSTOMER-ORDER");
            q.setConversationId(conv);
            q.setContent(
                Protocol.formatMap(
                    Map.of(
                        Protocol.K_ACTION,
                        "QUOTE",
                        Protocol.K_QTY,
                        Integer.toString(want),
                        Protocol.K_PRODUCT,
                        sku)));
            SimLog.sent(bus, getLocalName(), q);
            send(q);

            MessageTemplate mt =
                MessageTemplate.and(
                    MessageTemplate.MatchOntology("B2C-CUSTOMER-ORDER"),
                    MessageTemplate.and(
                        MessageTemplate.MatchConversationId(conv),
                        MessageTemplate.or(
                            MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                            MessageTemplate.MatchPerformative(ACLMessage.REFUSE))));
            ACLMessage replyMsg = blockingReceive(mt, SimLog.ms(CustomerAgent.this, 4000));
            if (replyMsg == null || replyMsg.getPerformative() != ACLMessage.INFORM) {
              return;
            }
            SimLog.got(bus, getLocalName(), replyMsg);
            if (rnd.nextDouble() < 0.2) {
              return;
            }
            Map<String, String> quote = Protocol.parseMap(replyMsg.getContent());
            int available = Integer.parseInt(quote.getOrDefault(Protocol.K_AVAILABLE, "0"));
            int buyQty = Math.min(want, Math.max(0, available));
            if (buyQty <= 0) {
              return;
            }
            ACLMessage buy = new ACLMessage(ACLMessage.REQUEST);
            buy.addReceiver(shop);
            buy.setOntology("B2C-CUSTOMER-ORDER");
            buy.setConversationId(conv + "-b");
            buy.setContent(
                Protocol.formatMap(
                    Map.of(
                        Protocol.K_ACTION,
                        "BUY",
                        Protocol.K_QTY,
                        Integer.toString(buyQty),
                        Protocol.K_PRODUCT,
                        sku)));
            SimLog.sent(bus, getLocalName(), buy);
            send(buy);
          }
        });
  }

  private String arg(int i, String d) {
    Object[] a = getArguments();
    return a != null && a.length > i && a[i] != null ? String.valueOf(a[i]) : d;
  }
}
