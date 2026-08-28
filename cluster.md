# cluster-starter

`cluster-starter` creates a cluster through direct node-to-node communication without an external coordinator.

- Every node maintains a shared-object map.
- A node may be reachable through one or more network interfaces and URLs.
- If the leader fails, the participating node with the lowest `nodeIndex` is elected.
- Public REST endpoints and redirect proxying use Reactor Netty HTTP.
- Internal node communication uses HTTP calls with Jackson-serialized payloads, served on the same port as the public API.
- The server offers h2c next to HTTP/1.1, and internal calls use it, so all node-to-node traffic multiplexes over one connection per peer.
- Route handlers are blocking and run on a per-request worker thread, so event loops stay free for I/O.

## Architecture

```text
ClusterStarter          Entry point and lifecycle owner for the HTTP server
ClusterServerRoutes     Public cluster REST API, redirect proxy, and internal node-to-node routes
ClusterService          Leader/follower transitions, heartbeats, and shared-object synchronization
NodeHttpClient          Shared JDK HttpClient (h2c) for all node-to-node calls
ClusterInternalClient   Typed client for the internal node-to-node routes
RouteDispatcher         Runs each request's handler on its own worker thread
RedirectFunction        Leader/index dispatch, election, retry, and parallel-execution utilities
ClusterEvents           Event registration API
```

## Runtime behavior

1. During startup, the node temporarily serves `GET /index` on its configured HTTP port.
2. It queries `nodeTargetUrls` and automatically identifies the URL that refers to itself. Startup fails if no local URL can be identified.
3. The node serves the internal node-to-node routes under `{clusterBasePath}/internal` on its HTTP port.
4. After waiting up to `leaderLostTimeoutSeconds` for an existing leader, node `1` starts as leader when no leader is found; other nodes start as followers.
5. Every node broadcasts a heartbeat containing its position and shared-object sequence at `heartbeatSendingIntervalMillis`.
6. When the leader heartbeat is missing for `leaderLostTimeoutSeconds`, the active candidate with the lowest `nodeIndex` becomes the new leader.
7. Shared-object changes are propagated through the leader. Sequence mismatches trigger synchronization.
8. After a communication failure or split-brain recovery, the leader's state overwrites divergent follower state and emits the `overwritten` event.
9. A partition without quorum is marked inactive.

Only the HTTP port needs to be reachable between nodes; internal traffic shares it with the public API.

## Configuration

Create a cluster with:

```java
ClusterStarter.builder(nodeTargetUrls, serverPort, nodeIndex)
```

| Builder value | Description | Default |
|---|---|---:|
| `nodeTargetUrls` | URLs for all nodes. A node may have more than one URL. Ordering is irrelevant. | Required |
| `serverPort` | Public HTTP port for this node | Required |
| `nodeIndex` | Unique node number, starting at `1` | Required |
| `quorum` | Minimum active partition size. A value at or below zero selects `maxClusterSize / 2 + 1`. | `0` |
| `leaderLostTimeoutSeconds` | Leader-loss timeout and initial leader-discovery wait | `20` |
| `heartbeatSendingIntervalMillis` | Heartbeat interval in milliseconds | `2000` |
| `clusterEvents` | Cluster event handlers | None |
| `routes` | Additional Reactor Netty HTTP routes | None |
| `clusterBasePath` | Base path for the cluster REST API | `/cluster` |
| `connectTimeoutMillis` | Connection timeout for REST and internal calls | `1000` |
| `readTimeoutMillis` | Read timeout for REST and internal calls | `60000` |

### Cluster events

`ClusterEvents` supports the following handlers:

- `activated`: quorum has been reached.
- `inactivated`: quorum has been lost.
- `becomeLeader`: this node became leader.
- `becomeFollower`: this node became a follower.
- `clusterAdded(int nodeIndex)`: a node joined.
- `clusterDeleted(int nodeIndex, Map<String, Object> sharedObject)`: a node left; its last shared object is supplied.
- `overwritten(int nodeIndex)`: this node's shared object was replaced with the leader's state.
- `splitBrainResolved`: a split-brain condition was resolved.

## Dependency

```xml
<dependency>
    <groupId>com.sds.communicators</groupId>
    <artifactId>cluster-starter</artifactId>
    <version>3.8</version>
</dependency>
```

## Example

```java
var cluster = ClusterStarter.builder(
                Set.of("http://127.0.0.1:4001", "http://127.0.0.1:4002"),
                4001,
                1)
        .setQuorum(1)
        .setLeaderLostTimeoutSeconds(20)
        .setHeartbeatSendingIntervalMillis(2000)
        .setClusterEvents(new ClusterEvents()
                .becomeLeader("on-leader", () -> log.info("became leader"))
                .clusterDeleted(
                        "on-deleted",
                        (nodeIndex, sharedObject) ->
                                log.info("node {} deleted", nodeIndex)))
        .setRoutes(routes -> routes.get(
                "/hello",
                (request, response) -> response.sendString(Mono.just("world"))))
        .setClusterBasePath("/cluster")
        .setConnectTimeoutMillis(1000)
        .setReadTimeoutMillis(60000)
        .build();

cluster.start();        // Starts the HTTP server.
// cluster.dispose();   // Stops the cluster.
```

The HTTP server runs on Reactor Netty's default event loops, which carry I/O only. Handlers are blocking and
never run on an event loop: `getRoutes()` wraps every route so each request is dispatched to its own worker
thread, from an unbounded pool that grows with concurrency and reclaims idle threads. This is the
thread-per-request model of a servlet container, without a fixed ceiling, so there is no pool size to tune.

Virtual threads are deliberately not used for this. GraalPy creates a polyglot context per device while a
connect request is being served, and Truffle rejects that on a virtual thread while its optimizing runtime
is active.

To use an externally managed HTTP server:

```java
cluster.startWithoutHttpServer();

HttpServer.create()
        .port(4001)
        .route(cluster.getRoutes()::accept)
        .bindNow();
```

`startWithoutHttpServer()` starts no server of its own, so the routes returned by `getRoutes()` must be mounted for
the cluster to work: they carry the internal node-to-node API as well as the public one.

Under Spring Boot WebFlux, contribute the routes with a `NettyRouteProvider` bean instead. Spring Boot
applies every provider and then appends its own WebFlux handler as a catch-all, so WebFlux endpoints keep
working alongside the cluster routes (this is how the `io-*` modules are wired):

```java
@Bean
NettyRouteProvider clusterRoutes(ClusterStarter cluster) {
    return routes -> {
        cluster.getRoutes().accept(routes);
        return routes;
    };
}
```

Do not call `route(...)` from a `NettyServerCustomizer` / `WebServerFactoryCustomizer`: those routes take
over the connection and answer every unmatched path with a bare 404, so Spring's own handler never runs.

## Main Java API

### Shared objects

```java
cluster.mergeSharedObject(Map<String, Object> value);
cluster.mergeSharedObject(Object value, String... path);
cluster.deleteSharedObject(String... path);
cluster.deleteSharedObject(List<List<String>> paths);
cluster.getSharedObject();
cluster.getSharedObjectMap();
cluster.getItem(nodeIndex, path);
```

Changes to a node's shared object are propagated through the leader.

### Cluster state and control

```java
cluster.getNodeIndex();
cluster.getPosition();
cluster.getPosition(nodeIndex);
cluster.getCluster();
cluster.isActivated();
cluster.forceToLeader();
cluster.forceToFollower();
```

### Targeted execution and clients

```java
cluster.toLeaderFuncConfirmed(url -> { }, "action-name");
cluster.toLeaderFunc(url -> { }, "action-name");
cluster.toIndexFunc(nodeIndex, url -> { }, "action-name");
cluster.toAllFunc(url -> { }, "action-name");
cluster.parallelExecute(collection, item -> { });
```

`toLeaderFuncConfirmed` retries and may initiate an election.

## REST API

The default base path is `/cluster`.

| Method | Path | Description |
|---|---|---|
| `GET` | `/node-status` | Return this node's `nodeIndex`, position, and activation state |
| `GET` | `/get-node-index` | Return this node's index |
| `GET` | `/leader-url` | Return the current leader URL |
| `GET` | `/index-url/{nodeIndex}` | Return a URL for a specific node |
| `GET` | `/get-cluster-nodes` | Return participating node indexes |
| `GET` | `/get-cluster-urls` | Return registered node URLs |
| `POST` | `/add-cluster-node` | Add a node URL; the request body is the URL string |
| `PUT` | `/set-to-leader` | Force this node to become leader |
| `PUT` | `/set-to-follower` | Force this node to become a follower |
| `GET` | `/shared-object-map` | Return every node's shared object |
| `GET` | `/shared-object-seq` | Return shared-object sequence information |

### Redirect routes

Redirect routes do not use `clusterBasePath`.

| Method | Path | Description |
|---|---|---|
| Any | `/redirect-to-leader/{path}` | Proxy the request to the leader |
| Any | `/redirect-to-index/{nodeIndex}/{path}` | Proxy the request to a specific node |

The HTTP method, headers, query string, and body are preserved. For example:

```text
PUT /redirect-to-leader/driver/reconnect-all
```

## Internal node-to-node API

Heartbeat, node status, leader changes, cluster deletion, and shared-object get/merge/delete/check/overwrite/sync/remove
operations are served under `{clusterBasePath}/internal` on the node's own HTTP port.

- Payload: Jackson JSON
- Transport: h2c. The server is bound with `HttpProtocol.H2C, HttpProtocol.HTTP11`, and the client is a
  `java.net.http.HttpClient` set to `HTTP_2`, which over plaintext negotiates h2c with an HTTP/1.1 `Upgrade`.
  The upgrade happens once per connection; later calls are multiplexed streams, so concurrency no longer costs
  connections. A node still speaking only HTTP/1.1 answers without upgrading and the client falls back, which
  keeps rolling deploys working.
- Timeouts: `connectTimeoutMillis` and `readTimeoutMillis`
- Upgrade body limit: Reactor Netty rejects an h2c upgrade request that carries a body unless
  `h2cMaxContentLength` is raised from its default of `0`, and the first internal call to a node is often a
  POST. `ClusterStarter` sets 64 MB; an application that mounts the routes on its own server must do the
  same (`server.netty.h2c-max-content-length` under Spring Boot). The limit only covers the upgrade request:
  once the connection is HTTP/2 it no longer applies.
- Failure mapping: rejected preconditions answer `400` with a plain-text reason

These routes are part of `getRoutes()`. They are reachable by anything that can reach the HTTP port, so restrict that
port to the cluster network if the deployment is not otherwise isolated.

`driver-starter` embeds this module and adds its own internal routes under `{driverBasePath}/internal`. See the [driver guide](driver.md).