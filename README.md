# Communicators

Communicators is a collection of device communication drivers and clustering libraries. It forms a cluster through direct node-to-node HTTP communication without an external coordinator, and collects or controls device data through industrial protocols such as TCP, UDP, Modbus TCP, HTTP, and OPC UA.

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
- `cluster-starter` uses Reactor Netty for both the public HTTP API and internal node-to-node calls, which are served under `{clusterBasePath}/internal` on the same port.
- `driver-starter` embeds `cluster-starter` and provides device load balancing and failover. Output implementations are available for no output, files, Kafka, and REST; applications can also extend `DriverStarter` to implement a custom output.
- `driver-starter/ui` is a React and Vite application. Its production build is written to `driver-starter/src/main/resources/static`.
- The `io-*` modules run Spring Boot WebFlux (web application type `reactive`), so Spring Boot owns the Reactor Netty HTTP server. The driver is started with `startWithoutHttpServer()`, and its cluster, driver, and web UI routes are contributed through a `NettyRouteProvider` bean; Spring Boot appends the WebFlux handler after those routes as a catch-all, so WebFlux endpoints can be added alongside them. Each module also replaces the auto-configured `ReactorResourceFactory` to give the server a 200-thread worker pool, because the driver routes block.

## Technology stack

- Java 21 and a Gradle multi-project build
- Reactor Netty for HTTP servers and clients
- Feign, Jackson, and RxJava 3
- GraalPy 23.1.12.1 for Python 3 device scripts (Python 3.10.8), on the 23.1 line that GraalVM for JDK 21 provides
- DigitalPetri Modbus 2.1.6
- Eclipse Milo 1.1.6 for OPC UA client and server support
- Kafka clients and Spring Boot in the executable example modules
- React 19, TypeScript, Axios, and Vite for the management UI

Gradle is the only build system; there are no Maven POM files.

## Build

GraalVM for JDK 21 is required (verified with GraalVM CE 21.0.2). A plain JDK 21 also builds and runs, but GraalPy then falls back to its interpreter: runtime compilation of guest code needs the GraalVM runtime. The Gradle wrapper is not committed, so build with a local Gradle 9 installation (verified with 9.7.0). In IntelliJ IDEA, open Settings -> Build, Execution, Deployment -> Build Tools -> Gradle and set Distribution to `Specific Version` so the IDE downloads one.

Run the build from the repository root; Gradle resolves module order automatically.

```bash
gradle build
gradle build -x test
```

Useful module-specific commands:

```text
gradle :driver-starter:build
gradle :io-kafka:bootJar
```

Build artifacts are written to each module's `build/libs` directory. The `io-db`, `io-kafka`, and `io-none` modules produce executable Spring Boot JARs (`<module>-3.8.jar`) next to a non-executable `<module>-3.8-plain.jar`.

The management UI is committed in built form under `driver-starter/src/main/resources/static`, so a Gradle build alone is enough. Rebuild it with `npm run build` in `driver-starter/ui` only after changing the UI sources.

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
3. Allow the HTTP port through the firewall; internal node-to-node traffic uses the same port.

## Documentation

- [Cluster guide](cluster.md): cluster architecture, configuration, lifecycle, Java API, and REST API
- [Driver guide](driver.md): devices, commands, protocol options, Python scripting, REST API, and the Web UI
- [UI development guide](driver-starter/ui/README.md): frontend architecture, scripts, proxying, and production output