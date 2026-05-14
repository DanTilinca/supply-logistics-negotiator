package com.dant.mas.supplychain.ui;

import com.dant.mas.supplychain.bus.LogChannel;
import com.dant.mas.supplychain.bus.SimulationEventBus;
import com.dant.mas.supplychain.runtime.SupplyChainKernel;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import jade.wrapper.StaleProxyException;
import java.util.concurrent.ConcurrentLinkedQueue;

/** JavaFX UI: controls, dual-tab logs, and JADE lifecycle. */
public class SupplyChainApp extends Application {

  private final SimulationEventBus bus = new SimulationEventBus();
  private SupplyChainKernel kernel;
  private final ListView<String> logViewB2c = new ListView<>();
  private final ListView<String> logViewB2b = new ListView<>();
  private Label hint;

  private final ConcurrentLinkedQueue<String> logQueueB2c = new ConcurrentLinkedQueue<>();
  private final ConcurrentLinkedQueue<String> logQueueB2b = new ConcurrentLinkedQueue<>();

  private AnimationTimer logDrainTimer;
  private long lastDrainB2cNanos;
  private long lastDrainB2bNanos;
  private static final long LOG_DRAIN_INTERVAL_NS = 260_000_000L;

  public static void main(String[] args) {
    launch(args);
  }

  @Override
  public void start(Stage stage) {
    configureLogList(
        logViewB2c, "Customer ↔ retailer messages appear here after you press Start.");
    configureLogList(
        logViewB2b,
        "Retailer ↔ supplier, courier, bank, and platform messages appear here.");

    bus.subscribe(
        (line, channel) -> {
          if (line == null) {
            return;
          }
          if (channel == LogChannel.B2C) {
            logQueueB2c.offer(line);
          } else {
            logQueueB2b.offer(line);
          }
        });
    startLogDrainTimer();

    ComboBox<String> speed = new ComboBox<>();
    speed.getStyleClass().add("combo-box");
    speed.getItems().addAll("1x", "2x", "4x");
    speed.setValue("1x");

    Button start = new Button("Start");
    start.getStyleClass().addAll("button", "btn-primary");
    start.setOnAction(e -> startKernel(speed));

    Button stop = new Button("Stop");
    stop.getStyleClass().addAll("button", "btn-danger");
    stop.setOnAction(e -> stopKernel());

    Button clear = new Button("Clear logs");
    clear.getStyleClass().addAll("button", "btn-secondary");
    clear.setOnAction(
        e -> {
          logQueueB2c.clear();
          logQueueB2b.clear();
          logViewB2c.getItems().clear();
          logViewB2b.getItems().clear();
        });

    TextField name = new TextField();
    name.getStyleClass().add("text-field");
    name.setPromptText("Name for extra customer");
    name.setPrefWidth(200);

    Button add = new Button("Add customer");
    add.getStyleClass().addAll("button", "btn-secondary");
    add.setOnAction(
        e -> {
          if (kernel == null || !kernel.isRunning()) {
            enqueueLog("(Start the simulation first.)", LogChannel.B2C);
            return;
          }
          new Thread(
                  () -> {
                    try {
                      kernel.spawnCustomer(name.getText(), parseSpeed(speed));
                      Platform.runLater(
                          () -> enqueueLog("Added another customer agent.", LogChannel.B2C));
                    } catch (StaleProxyException ex) {
                      Platform.runLater(
                          () ->
                              enqueueLog(
                                  "Could not add customer: " + ex.getMessage(), LogChannel.B2C));
                    }
                  },
                  "spawn")
              .start();
        });

    Label speedLbl = new Label("Speed");
    HBox bar =
        new HBox(
            12,
            start,
            stop,
            clear,
            new Region() {
              {
                HBox.setHgrow(this, Priority.ALWAYS);
              }
            },
            speedLbl,
            speed,
            add,
            name);
    bar.setAlignment(Pos.CENTER_LEFT);
    bar.getStyleClass().add("toolbar");

    hint = new Label("Stopped.");
    hint.getStyleClass().add("hint-label");
    hint.setMaxWidth(Double.MAX_VALUE);

    VBox top = new VBox(10, bar, hint);

    VBox shopWrap = wrapLogCard(logViewB2c);
    VBox chainWrap = wrapLogCard(logViewB2b);

    Tab tabShop = new Tab("Customers ↔ retailer", shopWrap);
    tabShop.setClosable(false);
    Tab tabChain = new Tab("Suppliers, couriers & bank", chainWrap);
    tabChain.setClosable(false);

    TabPane tabs = new TabPane(tabShop, tabChain);
    tabs.getStyleClass().add("log-tab-pane");
    tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
    VBox.setVgrow(tabs, Priority.ALWAYS);

    BorderPane root = new BorderPane();
    root.setTop(top);
    root.setCenter(tabs);

    Scene scene = new Scene(root, 920, 640);
    var css = getClass().getResource("styles.css");
    if (css != null) {
      scene.getStylesheets().add(css.toExternalForm());
    }
    stage.setTitle("Supply Logistics Negotiator");
    stage.setScene(scene);
    stage.setMinWidth(640);
    stage.setMinHeight(480);
    stage.show();

    stage.setOnCloseRequest(
        ev -> {
          if (logDrainTimer != null) {
            logDrainTimer.stop();
          }
          stopKernel();
          Platform.exit();
        });
  }

  private static void configureLogList(ListView<String> lv, String placeholder) {
    lv.getStyleClass().add("log-list");
    lv.setFixedCellSize(-1);
    lv.setPlaceholder(new Label(placeholder));
    lv.setCellFactory(LogLineCell::new);
  }

  private static VBox wrapLogCard(ListView<String> lv) {
    VBox wrap = new VBox(lv);
    wrap.getStyleClass().add("log-card");
    VBox.setVgrow(lv, Priority.ALWAYS);
    return wrap;
  }

  private static final class LogLineCell extends ListCell<String> {
    LogLineCell(ListView<String> parent) {
      setWrapText(true);
      prefWidthProperty().bind(parent.widthProperty().subtract(36));
    }

    @Override
    protected void updateItem(String item, boolean empty) {
      super.updateItem(item, empty);
      if (empty || item == null) {
        setText(null);
        getStyleClass()
            .removeAll(
                "log-line-default",
                "log-line-acl",
                "log-line-bank",
                "log-line-retailer",
                "log-line-warn",
                "log-line-error");
        return;
      }
      setText(item);
      getStyleClass()
          .removeAll(
              "log-line-default",
              "log-line-acl",
              "log-line-bank",
              "log-line-retailer",
              "log-line-warn",
              "log-line-error");
      getStyleClass().add(styleClassForLine(item));
    }

    private static String styleClassForLine(String item) {
      String u = item.toUpperCase(java.util.Locale.ROOT);
      if (u.contains("ERROR")) {
        return "log-line-error";
      }
      if (item.contains("──") || item.contains("◀──")) {
        return "log-line-acl";
      }
      if (item.contains("Bank")
          || item.contains("escrow")
          || item.contains("Escrow")
          || item.contains("ESCROW")) {
        return "log-line-bank";
      }
      if (item.contains("Retailer")) {
        return "log-line-retailer";
      }
      if (item.startsWith("(") || u.contains("WARN")) {
        return "log-line-warn";
      }
      return "log-line-default";
    }
  }

  private void enqueueLog(String line, LogChannel channel) {
    if (line == null) {
      return;
    }
    if (channel == LogChannel.B2C) {
      logQueueB2c.offer(line);
    } else {
      logQueueB2b.offer(line);
    }
  }

  private void startLogDrainTimer() {
    lastDrainB2cNanos = 0;
    lastDrainB2bNanos = 0;
    logDrainTimer =
        new AnimationTimer() {
          @Override
          public void handle(long nowNanos) {
            if (nowNanos - lastDrainB2cNanos >= LOG_DRAIN_INTERVAL_NS) {
              lastDrainB2cNanos = nowNanos;
              String c = logQueueB2c.poll();
              if (c != null) {
                appendToListView(logViewB2c, c);
              }
            }
            if (nowNanos - lastDrainB2bNanos >= LOG_DRAIN_INTERVAL_NS) {
              lastDrainB2bNanos = nowNanos;
              String b = logQueueB2b.poll();
              if (b != null) {
                appendToListView(logViewB2b, b);
              }
            }
          }
        };
    logDrainTimer.start();
  }

  private void appendToListView(ListView<String> view, String line) {
    view.getItems().add(line);
    if (view.getItems().size() > 450) {
      view.getItems().remove(0, 120);
    }
    view.scrollTo(view.getItems().size() - 1);
  }

  private void startKernel(ComboBox<String> speedBox) {
    if (kernel != null && kernel.isRunning()) {
      enqueueLog("Already running.", LogChannel.B2B);
      return;
    }
    double sp = parseSpeed(speedBox);
    kernel = new SupplyChainKernel(bus);
    new Thread(
            () -> {
              try {
                kernel.start(sp);
                Platform.runLater(() -> hint.setText("Running."));
              } catch (Exception ex) {
                Platform.runLater(
                    () -> {
                      enqueueLog("ERROR: " + ex.getMessage(), LogChannel.B2B);
                      kernel = null;
                    });
              }
            },
            "jade-boot")
        .start();
  }

  private static double parseSpeed(ComboBox<String> speedBox) {
    try {
      return Double.parseDouble(speedBox.getValue().replace("x", ""));
    } catch (Exception e) {
      return 1.0;
    }
  }

  private void stopKernel() {
    if (kernel != null) {
      kernel.stop();
      kernel = null;
    }
    hint.setText("Stopped.");
  }
}
