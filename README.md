# Communicators

Communicators is a collection of device communication drivers and clustering libraries. It forms a cluster through direct node-to-node gRPC communication without an external coordinator, and collects or controls device data through industrial protocols such as TCP, UDP, Modbus TCP, HTTP, and OPC UA.

## Modules

```text
common <--- cluster-starter <--- driver-starter <--+-- io-db
                                                   +-- io-kafka
                                                   +-- io-none
```

| Module | Type | Purpose | Documentation |
|---|---|---|---|
| `common` | Library | Shared structures (`Device`, `Command`, `Response`, and `Status`), enums, and utilities | — |
| `cluster-starter` | Library | Leader election, heartbeat, shared-object replication, and redirect proxying | [Cluster guide](cluster.md) |
| `driver-starter` | Library | Protocol drivers, GraalPy scripts, REST API, and the management UI | [Driver guide](driver.md) |
| `io-db` | Application | Spring Boot example with a custom output implementation that logs collected data | — |
| `io-kafka` | Application | Spring Boot example that publishes collected data to Kafka | — |
| `io-none` | Application | Spring Boot example without an output sink, intended for testing and validation | — |

All Java modules currently use project version `3.8`.

### Module relationships

- `common` contains the data model shared by every module.
- `cluster-starter` uses Reactor Netty for the public HTTP API and gRPC for internal node communication. The default gRPC port is `serverPort + 10000`.
- `driver-starter` embeds `cluster-starter` and provides device load balancing and failover. Output implementations are available for no output, files, Kafka, and REST; applications can also extend `DriverStarter` to implement a custom output.
- `driver-starter/ui` is a React and Vite application. Its production build is written to `driver-starter/src/main/resources/static`.
- The `io-*` modules use Spring Boot as a configuration and process container. Their Spring web application type is `none`; each module creates and owns its own Reactor Netty HTTP server, binding the cluster and driver routes from `driverStarter.getRoutes()` after starting the driver with `startWithoutHttpServer()`.

## Technology stack

- Java 17 and a Gradle multi-project build
- Reactor Netty for HTTP servers and clients
- gRPC with Jackson-serialized payloads for internal node calls
- Feign, Jackson, and RxJava 3
- GraalPy 24.1.2 for Python 3 device scripts
- DigitalPetri Modbus 2.1.6
- Eclipse Milo 1.1.6 for OPC UA client and server support
- Kafka clients and Spring Boot in the executable example modules
- React 19, TypeScript, Axios, and Vite for the management UI

The Gradle build is authoritative. Maven POM files are retained for consumers and reference builds.

## Build

JDK 17 is required. Run the build from the repository root; Gradle resolves module order automatically.

Windows:

```powershell
.\gradlew.bat build
.\gradlew.bat build -x test
```

Linux or macOS:

```bash
./gradlew build
./gradlew build -x test
```

Useful module-specific commands:

```text
gradlew :driver-starter:build
gradlew :io-kafka:bootJar
```

Build artifacts are written to each module's `build/libs` directory. The `io-db`, `io-kafka`, and `io-none` modules produce executable Spring Boot JARs.

## Run an example application

```bash
java -jar io-kafka/build/libs/io-kafka-3.8.jar
```

Configure the application through the `io.*` properties in its `application.yml`. Important settings include the driver ID, node index, node target URLs, quorum, REST base paths, and output-specific settings such as Kafka topics.

After startup, open the management UI at:

```text
http://{host}:{server.port}/driver/
```

For a multi-node cluster:

1. Assign a unique `io.node-index` to every node.
2. List all node HTTP URLs in `io.node-target-urls`.
3. Allow both the HTTP port and the internal gRPC port (`server.port + grpc-port-offset`) through the firewall.

## Documentation

- [Cluster guide](cluster.md): cluster architecture, configuration, lifecycle, Java API, and REST API
- [Driver guide](driver.md): devices, commands, protocol options, Python scripting, REST API, and the Web UI
- [UI development guide](driver-starter/ui/README.md): frontend architecture, scripts, proxying, and production output