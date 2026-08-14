# communicators

A collection of equipment (device) communication driver and cluster libraries.
It forms a cluster using only direct node-to-node communication (gRPC) without an external coordinator, and collects/controls device data over various industrial protocols (TCP/UDP/Modbus/HTTP/OPC UA).

## Modules

```
common ◄─── cluster-starter ◄─── driver-starter ◄─┬── io-db
                                                  ├── io-kafka
                                                  └── io-none
```

| Module | Type | Description | Document |
|---|---|---|---|
| common | library | Shared structs (Device, Command, Response, Status), enums, utilities (UtilFunc, LoadBalancer) | - |
| cluster-starter | library | Cluster composition: LEADER election, heartbeat, shared-object sharing, redirect proxy | [cluster.md](cluster.md) |
| driver-starter | library | Device communication driver: protocol implementations, Python 3 script engine (GraalPy), REST API, Web UI | [driver.md](driver.md) |
| io-db | application | driver-starter execution example (Spring Boot, logs collected data — example of inheriting a custom output) | - |
| io-kafka | application | driver-starter execution example (Spring Boot, sends collected data to Kafka) | - |
| io-none | application | driver-starter execution example (Spring Boot, no output of collected data — for testing/verification) | - |

- **common**: Data structures shared by all modules. The version is common across all modules (currently 3.8)
- **cluster-starter**: Based on a reactor-netty HTTP server (external REST/redirect) + gRPC (internal node-to-node communication, port = serverPort + grpcPortOffset)
- **driver-starter**: Embeds cluster-starter to support device load balancing / failover between nodes.
  Provides per-output implementations (None/File/Kafka/Rest), and a custom output can be implemented by extending `DriverStarter`.
  Includes a Web UI (`driver-starter/ui`, React + Vite → `src/main/resources/static`)
- **io-db / io-kafka / io-none**: Executable modules that run the driver by injecting configuration only (`io.*` in `application.yml`) via Spring Boot.
  The HTTP server is handled by driver-starter's reactor-netty (`spring.main.web-application-type: none`)

## Tech stack

- Java 17, Gradle (multi-project, gradle wrapper included — the existing pom.xml is kept for reference)
- reactor-netty (HTTP server/client), gRPC (grpc-netty-shaded, internal node-to-node communication — Jackson JSON payload), Feign (general-purpose REST client), Jackson
- GraalPy 24.1.2 (Python 3 script engine, `org.graalvm.polyglot`)
- RxJava 3, digitalpetri modbus, Eclipse Milo (OPC UA), kafka-clients
- io modules: Spring Boot (used as a configuration/execution container)

## Build

JDK 17 required. The project is a Gradle multi-project and is built all at once from the root
(the inter-module dependency order common → cluster-starter → driver-starter → io-db/io-kafka/io-none is guaranteed automatically).

```bash
gradlew build          # full build (including tests)
gradlew build -x test  # excluding tests
```

- Artifacts: `build/libs/` of each module (io-db/io-kafka/io-none produce a Spring Boot `bootJar` — e.g. `io-kafka-3.8.jar`)
- A specific module only: `gradlew :driver-starter:build`, `gradlew :io-kafka:bootJar`

## Run (io module example)

```bash
java -jar io-kafka/build/libs/io-kafka-3.8.jar
```

- Configuration: `io.*` in `application.yml` (driver-id, node-index, node-target-urls, quorum, Kafka address/topic/format, etc.)
- Web UI after startup: `http://{host}:{server.port}/driver`
- When forming a cluster, assign a unique `io.node-index` to each node and list the URLs of all nodes in `io.node-target-urls`

## Documents

- [cluster.md](cluster.md) — cluster-starter structure, config, API, REST endpoints
- [driver.md](driver.md) — driver-starter structure, config, Device/Command, per-protocol options, Python script guide, REST endpoints
