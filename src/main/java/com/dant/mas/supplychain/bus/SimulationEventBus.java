package com.dant.mas.supplychain.bus;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** Pub/sub: formatted log lines tagged for the shop vs supply-chain tab. */
public class SimulationEventBus {

  @FunctionalInterface
  public interface Listener {
    void onLog(String line, LogChannel channel);
  }

  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

  private final List<Listener> listeners = new CopyOnWriteArrayList<>();

  public void subscribe(Listener listener) {
    listeners.add(listener);
  }

  public void log(String message, LogChannel channel) {
    String line = LocalTime.now().format(TIME) + "  " + message;
    for (Listener l : listeners) {
      l.onLog(line, channel);
    }
  }

  public void logf(LogChannel channel, String format, Object... args) {
    log(String.format(Locale.US, format, args), channel);
  }
}
