package com.dant.mas.supplychain.runtime;

import com.dant.mas.supplychain.agents.BankAgent;
import com.dant.mas.supplychain.agents.CourierAgent;
import com.dant.mas.supplychain.agents.CustomerAgent;
import com.dant.mas.supplychain.agents.RetailerAgent;
import com.dant.mas.supplychain.agents.SupplierAgent;
import com.dant.mas.supplychain.bus.LogChannel;
import com.dant.mas.supplychain.bus.SimulationEventBus;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;
import java.util.Objects;

/** Boots the JADE main container and the scenario agents. */
public class SupplyChainKernel {

  private final SimulationEventBus bus;
  private AgentContainer main;

  public SupplyChainKernel(SimulationEventBus bus) {
    this.bus = Objects.requireNonNull(bus);
  }

  public synchronized void start(double speed) throws StaleProxyException {
    if (main != null) {
      return;
    }
    Runtime rt = Runtime.instance();
    Profile p = new ProfileImpl(false);
    p.setParameter(Profile.GUI, "false");
    p.setParameter(Profile.MAIN, "true");
    main = rt.createMainContainer(p);
    bus.log("JADE started (main container + directory facilitator).", LogChannel.B2B);

    Object[] base = new Object[] {bus, speed};
    create("bank", BankAgent.class.getName(), base);
    create("retailer", RetailerAgent.class.getName(), base);
    create(
        "supplier-north",
        SupplierAgent.class.getName(),
        new Object[] {bus, speed, "DEPOT-NORTH", "7.85"});
    create(
        "supplier-south",
        SupplierAgent.class.getName(),
        new Object[] {bus, speed, "DEPOT-SOUTH", "8.15"});
    create("courier-express", CourierAgent.class.getName(), new Object[] {bus, speed, "3.2"});
    create("courier-eco", CourierAgent.class.getName(), new Object[] {bus, speed, "2.0"});
    create("customer-1", CustomerAgent.class.getName(), new Object[] {bus, speed, "retailer"});
  }

  private void create(String name, String cls, Object[] args) throws StaleProxyException {
    AgentController c = main.createNewAgent(name, cls, args);
    c.start();
  }

  public synchronized void spawnCustomer(String hint, double speed) throws StaleProxyException {
    if (main == null) {
      throw new IllegalStateException("Not started");
    }
    String safe = hint.replaceAll("[^a-zA-Z0-9\\-_]", "");
    if (safe.isBlank()) {
      safe = "customer";
    }
    create(safe + "-" + Integer.toHexString((int) (Math.random() * 0xffff)), CustomerAgent.class.getName(), new Object[] {bus, speed, "retailer"});
  }

  public synchronized void stop() {
    if (main != null) {
      try {
        main.kill();
      } catch (Exception ignored) {
      }
      main = null;
    }
    bus.log("JADE stopped.", LogChannel.B2B);
  }

  public boolean isRunning() {
    return main != null;
  }
}
