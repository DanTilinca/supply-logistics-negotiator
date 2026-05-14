# Supply logistics negotiator

A **multi-agent supply-chain simulation** using [JADE](https://jade.tilab.com/) (FIPA-ACL, Directory Facilitator) and a **JavaFX** desktop UI. Customers request quotes and purchase multiple product lines from a **retailer**. When inventory falls below a threshold, the retailer **restocks** by soliciting **proposals** from suppliers and couriers, then coordinates payment through a **bank escrow** until delivery is confirmed.

---

## Overview

The system models two channels of interaction:

- **Business-to-consumer (B2C):** customers exchange `REQUEST` / `INFORM` messages with the retailer under the `B2C-CUSTOMER-ORDER` ontology (quote and buy flows).
- **Business-to-business (B2B):** the retailer issues **call-for-proposal (CFP)** messages to suppliers and couriers, selects the cheapest **PROPOSE** responses, and opens **escrow** with the bank (`B2B-ESCROW`). The bank instructs the supplier to ship and the courier to await pickup; the courier’s delivery confirmation triggers settlement.

The UI presents **two log tabs** so shop-floor traffic and procurement or logistics traffic stay easy to follow.

---

## Architecture

| Component | Role |
|-----------|------|
| **Agents** (`com.dant.mas.supplychain.agents`) | `CustomerAgent`, `RetailerAgent`, `SupplierAgent`, `CourierAgent`, `BankAgent` — JADE behaviours, ACL performatives, and ontologies listed above. |
| **Protocol** (`Protocol.java`) | Message payload keys, action constants, retail product catalog, and `key=value;…` encoding via `parseMap` / `formatMap`. |
| **Kernel** (`SupplyChainKernel`) | Creates the JADE main container (no built-in GUI profile) and starts named agent instances. |
| **Event bus** (`SimulationEventBus`, `LogChannel`) | Publishes timestamped log lines to the UI, tagged **B2C** or **B2B**. |
| **SimLog** | Renders ACL lines for the bus (including multiple receivers on one outbound line). |
| **UI** (`SupplyChainApp`, `styles.css`) | JavaFX application: toolbar, dual-tab log views, throttled list updates for readability. |

Agents emit structured logs through `SimLog` and the bus; the UI subscribes once and drains two queues on the JavaFX application thread.

---

## Requirements

| Item | Version / notes |
|------|------------------|
| **JDK** | **25** (`maven.compiler.release` in `pom.xml`). To build with another JDK, change `<release>` and align OpenJFX versions if necessary. |
| **Maven** | Not required on `PATH`; the repository ships the **Maven Wrapper** (`./mvnw`, `mvnw.cmd`). |

---

## JADE library (bundled layout)

This project does **not** resolve JADE from a remote Maven repository. The JAR is expected at the following path (standard local-repository layout):

```text
lib/m2/com/tilab/jade/jade/4.6.0/jade-4.6.0.jar
```

The version must match `jade.version` in `pom.xml` (currently **4.6.0**). Download the matching binary from the [JADE distribution](https://jade.tilab.com/), create the directory tree if needed, and place the file there. Maven then resolves `com.tilab.jade:jade` from the `file:${project.basedir}/lib/m2` repository declared in `pom.xml`.

---

## Build

Unix-like shells:

```bash
./mvnw clean compile
```

Windows:

```bat
mvnw.cmd clean compile
```

Run tests:

```bash
./mvnw test
```

---

## Run

Main class: `com.dant.mas.supplychain.ui.SupplyChainApp`.

**Using the Exec plugin**

```bash
./mvnw exec:java
```

```bat
mvnw.cmd exec:java
```

**Using the JavaFX Maven plugin**

```bash
./mvnw javafx:run
```

### Using the UI

1. Select simulation **speed** (1×, 2×, or 4×).
2. Click **Start** to launch the JADE container and agents.
3. Inspect logs in **Customers ↔ retailer** and **Suppliers, couriers & bank**.
4. Click **Stop** to shut down the container.
5. **Clear logs** removes all lines from both tabs.
6. **Add customer** (after start) spawns an additional customer agent; supply a label in the text field.

---

## Repository layout

```text
pom.xml                    Project model, dependencies, plugins
mvnw, mvnw.cmd, .mvn/      Maven Wrapper
lib/m2/…/jade-4.6.0.jar    JADE binary (see above)
src/main/java/             Java sources (agents, kernel, protocol, bus, UI)
src/main/resources/      JavaFX CSS
```

---

## Implementation notes

- **FIPA-style ACL** content uses semicolon-separated `key=value` pairs; performatives include `REQUEST`, `CFP`, `PROPOSE`, `INFORM`, `REFUSE`, and others as required by each flow.
- **Directory Facilitator:** suppliers and couriers register services; retailer procurement in this demo still targets **fixed local agent names** for predictability.
- **OpenJFX** artifacts are resolved from Maven Central like any normal dependency.

---

## Troubleshooting

| Symptom | Things to check |
|---------|-------------------|
| Build cannot resolve JADE | Confirm `jade-4.6.0.jar` exists under `lib/m2/com/tilab/jade/jade/4.6.0/` and matches `jade.version`. |
| Build or launch errors | Run commands from the **repository root** so `file:${project.basedir}/lib/m2` resolves. |
| Wrong Java version | `java -version` should report JDK **25** unless you have intentionally changed `pom.xml`. |
