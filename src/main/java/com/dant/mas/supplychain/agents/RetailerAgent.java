package com.dant.mas.supplychain.agents;

import com.dant.mas.supplychain.Protocol;
import com.dant.mas.supplychain.bus.LogChannel;
import com.dant.mas.supplychain.bus.SimulationEventBus;
import com.dant.mas.supplychain.util.SimLog;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.Behaviour;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.core.behaviours.SequentialBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Sells multiple SKUs to customers; when a SKU is low, restocks that SKU via suppliers (CFP),
 * couriers (CFP), and bank escrow. Agent names match {@link
 * com.dant.mas.supplychain.runtime.SupplyChainKernel}.
 */
public class RetailerAgent extends Agent {

  public static final int CRITICAL_STOCK = 30;
  public static final int REORDER = 100;

  private static final String[] SUPPLIERS = {"supplier-north", "supplier-south"};
  private static final String[] COURIERS = {"courier-eco", "courier-express"};

  private SimulationEventBus bus;
  private final Map<String, Integer> inventoryByProduct = new ConcurrentHashMap<>();
  private volatile double cash = 4000;
  private volatile boolean busy;
  private volatile String pendingOrder;

  private String convSup;
  private String convCour;
  private final List<ACLMessage> buf = new ArrayList<>();
  private ACLMessage chosenSupplier;

  @Override
  protected void setup() {
    if (getArguments() != null && getArguments().length > 0 && getArguments()[0] instanceof SimulationEventBus b) {
      bus = b;
    }
    for (String sku : Protocol.RETAIL_CATALOG) {
      inventoryByProduct.put(sku, 68 + (Math.abs(sku.hashCode()) % 28));
    }
    addBehaviour(new SalesLoop());
    addBehaviour(new EscrowDoneLoop());
    addBehaviour(
        new TickerBehaviour(this, 3000) {
          @Override
          protected void onTick() {
            if (pendingOrder != null || busy) {
              return;
            }
            for (String sku : Protocol.RETAIL_CATALOG) {
              if (inventoryByProduct.getOrDefault(sku, 0) < CRITICAL_STOCK) {
                runRestock(sku);
                return;
              }
            }
          }
        });
  }

  private void runRestock(final String restockSku) {
    busy = true;
    SequentialBehaviour seq = new SequentialBehaviour(this) {
      @Override
      public int onEnd() {
        busy = false;
        return super.onEnd();
      }
    };

    seq.addSubBehaviour(
        new OneShotBehaviour(this) {
          @Override
          public void action() {
            convSup = "sup-" + UUID.randomUUID().toString().substring(0, 8);
            buf.clear();
            ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
            for (String n : SUPPLIERS) {
              cfp.addReceiver(new AID(n, AID.ISLOCALNAME));
            }
            cfp.setConversationId(convSup);
            cfp.setOntology("B2B-PROCUREMENT");
            cfp.setContent(
                Protocol.formatMap(
                    Map.of(
                        Protocol.K_PRODUCT,
                        restockSku,
                        Protocol.K_QTY,
                        Integer.toString(REORDER))));
            SimLog.sent(bus, getLocalName(), cfp);
            send(cfp);
            bus.logf(LogChannel.B2B, "Retailer restocking %s — sent CFP to suppliers.", restockSku);
          }
        });
    seq.addSubBehaviour(new CollectPhase(2200, () -> convSup));

    seq.addSubBehaviour(
        new OneShotBehaviour(this) {
          @Override
          public void action() {
            if (buf.isEmpty()) {
              bus.log("No supplier bids — skipping restock.", LogChannel.B2B);
              convCour = null;
              chosenSupplier = null;
              return;
            }
            chosenSupplier =
                buf.stream()
                    .min(
                        Comparator.comparingDouble(
                            m -> parseTotal(Protocol.parseMap(m.getContent()))))
                    .orElse(null);
            if (chosenSupplier == null) {
              return;
            }
            String depot =
                Protocol.parseMap(chosenSupplier.getContent())
                    .getOrDefault(Protocol.K_DEPOT, "DEPOT-CENTRAL");
            convCour = "cour-" + UUID.randomUUID().toString().substring(0, 8);
            buf.clear();
            ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
            for (String n : COURIERS) {
              cfp.addReceiver(new AID(n, AID.ISLOCALNAME));
            }
            cfp.setConversationId(convCour);
            cfp.setOntology("B2B-LOGISTICS");
            cfp.setContent(
                Protocol.formatMap(
                    Map.of(
                        Protocol.K_DEPOT,
                        depot,
                        Protocol.K_WEIGHT_KG,
                        String.format(
                            java.util.Locale.US,
                            "%.1f",
                            REORDER * weightKgPerUnit(restockSku)),
                        Protocol.K_SPEED,
                        "STANDARD")));
            SimLog.sent(bus, getLocalName(), cfp);
            send(cfp);
            bus.log("Cheapest supplier chosen — asking couriers for delivery price.", LogChannel.B2B);
          }
        });
    seq.addSubBehaviour(new CollectPhase(2200, () -> convCour));

    seq.addSubBehaviour(
        new OneShotBehaviour(this) {
          @Override
          public void action() {
            if (chosenSupplier == null || buf.isEmpty()) {
              return;
            }
            ACLMessage bestCour =
                buf.stream()
                    .min(
                        Comparator.comparingDouble(
                            m ->
                                Double.parseDouble(
                                    Protocol.parseMap(m.getContent())
                                        .getOrDefault(Protocol.K_DELIVERY_FEE, "999"))))
                    .orElse(null);
            if (bestCour == null) {
              return;
            }
            Map<String, String> sp = Protocol.parseMap(chosenSupplier.getContent());
            Map<String, String> cp = Protocol.parseMap(bestCour.getContent());
            double goods = Double.parseDouble(sp.getOrDefault(Protocol.K_TOTAL, "0"));
            double ship = Double.parseDouble(cp.getOrDefault(Protocol.K_DELIVERY_FEE, "0"));
            if (cash < goods + ship) {
              bus.log("Not enough cash for this restock.", LogChannel.B2B);
              buf.clear();
              chosenSupplier = null;
              return;
            }
            cash -= goods + ship;
            String order = "o" + UUID.randomUUID().toString().substring(0, 6);
            pendingOrder = order;
            ACLMessage pay = new ACLMessage(ACLMessage.REQUEST);
            pay.addReceiver(new AID("bank", AID.ISLOCALNAME));
            pay.setOntology("B2B-ESCROW");
            pay.setConversationId("esc-" + order);
            pay.setContent(
                Protocol.formatMap(
                    Map.ofEntries(
                        Map.entry(Protocol.K_ACTION, Protocol.A_OPEN_ESCROW),
                        Map.entry(Protocol.K_ORDER_ID, order),
                        Map.entry(
                            Protocol.K_SUPPLIER_LOCAL, chosenSupplier.getSender().getLocalName()),
                        Map.entry(
                            Protocol.K_COURIER_LOCAL, bestCour.getSender().getLocalName()),
                        Map.entry(Protocol.K_TOTAL_GOODS, String.format(java.util.Locale.US, "%.2f", goods)),
                        Map.entry(Protocol.K_DELIVERY_AMOUNT, String.format(java.util.Locale.US, "%.2f", ship)),
                        Map.entry(Protocol.K_QTY, Integer.toString(REORDER)),
                        Map.entry(Protocol.K_PRODUCT, restockSku))));
            SimLog.sent(bus, getLocalName(), pay);
            send(pay);
            buf.clear();
            chosenSupplier = null;
            bus.logf(
                LogChannel.B2B,
                "Opened escrow order %s (goods %.2f + ship %.2f). Bank pays after courier confirms.",
                order, goods, ship);
          }
        });

    addBehaviour(seq);
  }

  private static double weightKgPerUnit(String product) {
    return 0.28 + (Math.abs(product.hashCode()) % 7) * 0.03;
  }

  private static double retailUnitPrice(String product) {
    return 18.0 + (Math.abs(product.hashCode()) % 11) * 0.65;
  }

  private static double parseTotal(Map<String, String> m) {
    try {
      return Double.parseDouble(m.getOrDefault(Protocol.K_TOTAL, "999999"));
    } catch (NumberFormatException e) {
      return 999999;
    }
  }

  private class CollectPhase extends Behaviour {
    private final long windowMs;
    private final Supplier<String> convIds;
    private String convId;
    private final List<ACLMessage> got = new ArrayList<>();
    private long deadline;

    CollectPhase(long windowMs, Supplier<String> convIds) {
      super(RetailerAgent.this);
      this.windowMs = windowMs;
      this.convIds = convIds;
    }

    @Override
    public void onStart() {
      got.clear();
      convId = convIds.get();
      if (convId == null || convId.isBlank()) {
        deadline = System.currentTimeMillis();
      } else {
        deadline = System.currentTimeMillis() + windowMs;
      }
    }

    @Override
    public void action() {
      long now = System.currentTimeMillis();
      if (now >= deadline) {
        return;
      }
      if (convId == null || convId.isBlank()) {
        return;
      }
      MessageTemplate mt =
          MessageTemplate.and(
              MessageTemplate.MatchConversationId(convId),
              MessageTemplate.or(
                  MessageTemplate.MatchPerformative(ACLMessage.PROPOSE),
                  MessageTemplate.MatchPerformative(ACLMessage.REFUSE)));
      ACLMessage m = receive(mt);
      if (m != null && m.getPerformative() == ACLMessage.PROPOSE) {
        SimLog.got(bus, getLocalName(), m);
        got.add(m);
      } else {
        block(Math.min(150, Math.max(1, deadline - now)));
      }
    }

    @Override
    public boolean done() {
      return System.currentTimeMillis() >= deadline;
    }

    @Override
    public int onEnd() {
      buf.clear();
      buf.addAll(got);
      return super.onEnd();
    }
  }

  private class SalesLoop extends CyclicBehaviour {
    SalesLoop() {
      super(RetailerAgent.this);
    }

    @Override
    public void action() {
      MessageTemplate mt =
          MessageTemplate.and(
              MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
              MessageTemplate.MatchOntology("B2C-CUSTOMER-ORDER"));
      ACLMessage m = receive(mt);
      if (m == null) {
        block();
        return;
      }
      SimLog.got(bus, getLocalName(), m);
      Map<String, String> p = Protocol.parseMap(m.getContent());
      String act = p.get(Protocol.K_ACTION);
      ACLMessage r = m.createReply();
      r.setOntology("B2C-CUSTOMER-ORDER");
      r.setConversationId(m.getConversationId());
      String prod = p.get(Protocol.K_PRODUCT);
      if (!Protocol.isRetailProduct(prod)) {
        r.setPerformative(ACLMessage.NOT_UNDERSTOOD);
        SimLog.sent(bus, getLocalName(), r);
        send(r);
        return;
      }
      if ("QUOTE".equalsIgnoreCase(act)) {
        int q = Integer.parseInt(p.getOrDefault(Protocol.K_QTY, "1"));
        int avail = inventoryByProduct.getOrDefault(prod, 0);
        double unit = retailUnitPrice(prod);
        r.setPerformative(ACLMessage.INFORM);
        r.setContent(
            Protocol.formatMap(
                Map.of(
                    Protocol.K_ACTION,
                    "QUOTE_RESULT",
                    Protocol.K_QTY,
                    Integer.toString(q),
                    Protocol.K_AVAILABLE,
                    String.valueOf(avail),
                    Protocol.K_UNIT_PRICE,
                    String.format(java.util.Locale.US, "%.2f", unit),
                    Protocol.K_PRODUCT,
                    prod)));
      } else if ("BUY".equalsIgnoreCase(act)) {
        int q = Integer.parseInt(p.getOrDefault(Protocol.K_QTY, "1"));
        int have = inventoryByProduct.getOrDefault(prod, 0);
        if (q > 0 && have >= q) {
          int after = have - q;
          inventoryByProduct.put(prod, after);
          double unit = retailUnitPrice(prod);
          cash += q * unit;
          r.setPerformative(ACLMessage.INFORM);
          r.setContent(
              Protocol.formatMap(
                  Map.of(
                      Protocol.K_ACTION,
                      "SOLD",
                      Protocol.K_QTY,
                      Integer.toString(q),
                      Protocol.K_TOTAL,
                      String.format(java.util.Locale.US, "%.2f", q * unit),
                      Protocol.K_PRODUCT,
                      prod)));
          bus.logf(
              LogChannel.B2C,
              "Sold %d × %s to %s (%s stock now %d).",
              q,
              prod,
              m.getSender().getLocalName(),
              prod,
              inventoryByProduct.getOrDefault(prod, 0));
          if (have >= CRITICAL_STOCK && after < CRITICAL_STOCK) {
            bus.logf(
                LogChannel.B2C,
                "%s stock low (%d units, below %d) — will restock from suppliers shortly.",
                prod,
                after,
                CRITICAL_STOCK);
          }
        } else {
          r.setPerformative(ACLMessage.REFUSE);
          r.setContent("no stock");
        }
      } else {
        r.setPerformative(ACLMessage.NOT_UNDERSTOOD);
      }
      SimLog.sent(bus, getLocalName(), r);
      send(r);
    }
  }

  private class EscrowDoneLoop extends CyclicBehaviour {
    EscrowDoneLoop() {
      super(RetailerAgent.this);
    }

    @Override
    public void action() {
      MessageTemplate mt =
          MessageTemplate.and(
              MessageTemplate.MatchPerformative(ACLMessage.INFORM),
              MessageTemplate.MatchOntology("B2B-ESCROW"));
      ACLMessage m = receive(mt);
      if (m == null) {
        block();
        return;
      }
      SimLog.got(bus, getLocalName(), m);
      Map<String, String> p = Protocol.parseMap(m.getContent());
      if (!Protocol.A_ESCROW_CLOSED.equals(p.get(Protocol.K_ACTION))) {
        return;
      }
      String oid = p.get(Protocol.K_ORDER_ID);
      if (pendingOrder != null && pendingOrder.equals(oid)) {
        int q = Integer.parseInt(p.getOrDefault(Protocol.K_QTY, "0"));
        String prodSku = p.get(Protocol.K_PRODUCT);
        if (prodSku == null || prodSku.isBlank()) {
          prodSku = Protocol.PRODUCT_DEFAULT;
        }
        inventoryByProduct.merge(prodSku, q, Integer::sum);
        pendingOrder = null;
        bus.logf(
            LogChannel.B2B,
            "Restock finished — added %d × %s (stock now %d).",
            q,
            prodSku,
            inventoryByProduct.getOrDefault(prodSku, 0));
      }
    }
  }
}
