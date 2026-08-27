# cluster-starter

`cluster-starter` creates a cluster through direct node-to-node communication without an external coordinator.

- Every node maintains a shared-object map.
- A node may be reachable through one or more network interfaces and URLs.
- If the leader fails, the participating node with the lowest `nodeIndex` is elected.
- Public REST endpoints and redirect proxying use Reactor Netty HTTP.
- Internal node communication uses unary gRPC calls with Jackson-serialized payloads over plaintext connections.

## Architecture

```text
ClusterStarter          Entry point and lifecycle owner for the HTTP and gRPC servers
ClusterServerRoutes     Public cluster REST API and redirect proxy routes
ClusterService          Leader/follower transitions, heartbeats, and shared-object synchronization
ClusterGrpc             gRPC method descriptors and Jackson marshalling; no protobuf code generation
ClusterGrpcService      Internal gRPC handlers
ClusterGrpcClient       Cached gRPC channels and deadline-aware unary calls
ClusterClient           Generic Feign REST client and load-balanced client
RedirectFunction        Leader/index dispatch, election, retry, and parallel-execution utilities
ClusterEvents           Event registration API
```

## Runtime behavior

1. During startup, the node temporarily serves `GET /index` on its configured HTTP port.
2. It queries `nodeTargetUrls` and automatically identifies the URL that refers to itself. Startup fails if no local URL can be identified.
3. The node starts an internal gRPC server on `serverPort + grpcPortOffset`.
4. After waiting up to `leaderLostTimeoutSeconds` for an existing leader, node `1` starts as leader when no leader is found; other nodes start as followers.
5. Every node broadcasts a heartbeat containing its position and shared-object sequence at `heartbeatSendingIntervalMillis`.
6. When the leader heartbeat is missing for `leaderLostTimeoutSeconds`, the active candidate with the lowest `nodeIndex` becomes the new leader.
7. Shared-object changes are propagated through the leader. Sequence mismatches trigger synchronization.
8. After a communication failure or split-brain recovery, the leader's state overwrites divergent follower state and emits the `overwritten` event.
9. A partition without quorum is marked inactive.

The internal gRPC port must be allowed through the firewall in addition to the public HTTP port.

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
| `connectTimeoutMillis` | REST and gRPC connection timeout | `1000` |
| `readTimeoutMillis` | REST read timeout and gRPC deadline | `60000` |
| `grpcPortOffset` | Offset added to `serverPort` for the internal gRPC listener | `10000` |
| `grpcServices` | Additional `ServerServiceDefinition` instances registered with the internal server | None |

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

cluster.start();        // Starts the HTTP and gRPC servers.
// cluster.start(100);  // Starts with a custom HTTP server thread-pool size.
// cluster.dispose();   // Stops the cluster.
```

The default HTTP server thread-pool size used by `start()` is `200`.

To use an externally managed HTTP server:

```java
cluster.startWithoutHttpServer();

HttpServer.create()
        .port(4001)
        .route(cluster.getRoutes()::accept)
        .bindNow();
```

`startWithoutHttpServer()` still starts the internal gRPC server.

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
cluster.getClient(url, Api.class);
cluster.grpcCall(url, methodDescriptor, request);
cluster.loadBalancedClient(urls, Api.class, api -> { });
```

`toLeaderFuncConfirmed` retries and may initiate an election. `getClient` creates a Feign client. `grpcCall` reuses the internal channel cache and configured deadline.

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

## Internal gRPC API

Heartbeat, node status, leader changes, cluster deletion, and shared-object get/merge/delete/check/overwrite/sync/remove operations use gRPC rather than REST.

- Service: `cluster.ClusterInternal`
- Call type: unary
- Payload: Jackson JSON; no generated protobuf classes
- Listener: `serverPort + grpcPortOffset`
- Transport: plaintext
- Client deadline: `readTimeoutMillis`

`driver-starter` embeds this module and registers its own internal driver service on the same gRPC server. See the [driver guide](driver.md).