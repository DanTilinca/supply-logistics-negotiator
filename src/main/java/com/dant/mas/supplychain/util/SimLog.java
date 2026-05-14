package com.dant.mas.supplychain.util;

import com.dant.mas.supplychain.bus.LogChannel;
import com.dant.mas.supplychain.bus.SimulationEventBus;
import jade.core.Agent;
import jade.core.AID;
import jade.lang.acl.ACLMessage;
import java.util.Iterator;

/** Simulation time scaling and ACL one-line formatting for the event bus. */
public final class SimLog {

  private SimLog() {}

  public static double speed(Agent a) {
    Object[] args = a.getArguments();
    if (args != null && args.length > 1 && args[1] instanceof Number n) {
      double v = n.doubleValue();
      return v > 0.08 ? v : 0.08;
    }
    return 1.0;
  }

  public static long ms(Agent a, long baseMs) {
    return Math.max(80L, (long) (baseMs / speed(a)));
  }

  public static void sent(SimulationEventBus bus, String me, ACLMessage m) {
    if (bus == null) {
      return;
    }
    LogChannel ch = channelForSent(me, m);
    bus.logf(
        ch,
        "%s  ──%s──▶  %s  %s",
        me,
        ACLMessage.getPerformative(m.getPerformative()),
        formatReceivers(m),
        normalizeContent(m.getContent()));
  }

  public static void got(SimulationEventBus bus, String me, ACLMessage m) {
    if (bus == null) {
      return;
    }
    LogChannel ch = channelForGot(me, m);
    String from = m.getSender() != null ? m.getSender().getLocalName() : "?";
    bus.logf(
        ch,
        "%s  ◀──%s──  %-20s  %s",
        me,
        ACLMessage.getPerformative(m.getPerformative()),
        from,
        normalizeContent(m.getContent()));
  }

  private static boolean isCustomerAgent(String localName) {
    return localName != null && localName.startsWith("customer");
  }

  private static LogChannel channelForSent(String me, ACLMessage m) {
    if (isCustomerAgent(me)) {
      return LogChannel.B2C;
    }
    Iterator<?> it = m.getAllReceiver();
    while (it.hasNext()) {
      Object o = it.next();
      if (o instanceof AID aid && isCustomerAgent(aid.getLocalName())) {
        return LogChannel.B2C;
      }
    }
    return LogChannel.B2B;
  }

  private static LogChannel channelForGot(String me, ACLMessage m) {
    if (isCustomerAgent(me)) {
      return LogChannel.B2C;
    }
    if (m.getSender() != null && isCustomerAgent(m.getSender().getLocalName())) {
      return LogChannel.B2C;
    }
    return LogChannel.B2B;
  }

  /** Joins all receiver local names for outbound ACL lines. */
  private static String formatReceivers(ACLMessage m) {
    StringBuilder sb = new StringBuilder();
    Iterator<?> it = m.getAllReceiver();
    while (it.hasNext()) {
      if (sb.length() > 0) {
        sb.append(", ");
      }
      Object o = it.next();
      sb.append(o instanceof AID aid ? aid.getLocalName() : String.valueOf(o));
    }
    return sb.length() == 0 ? "(none)" : sb.toString();
  }

  private static String normalizeContent(String s) {
    if (s == null) {
      return "";
    }
    return s.replace('\n', ' ').trim();
  }
}
