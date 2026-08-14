# cluster-starter
- Cluster composition library (works with direct node-to-node communication only, without an external coordinator)
- All nodes in the cluster share a data-map (shared-object)
- Each node can use one or more NIC cards
- When the LEADER node fails, the node with the lowest nodeIndex in the cluster is elected as LEADER
- The REST server for external users is based on reactor-netty, while **internal node-to-node communication uses gRPC** (unary, Jackson JSON payload, plaintext)
---
## Module structure
```
ClusterStarter          Entry point. Created with a Builder, manages the server lifecycle (start/dispose) (HTTP + gRPC servers)
ClusterServerRoutes     Cluster REST API routing (reactor-netty HttpServerRoutes) — external/redirect
ClusterService          Position transitions (LEADER/FOLLOWER), heartbeat, shared-object synchronization
ClusterGrpc             gRPC definitions for internal node-to-node communication (MethodDescriptor + Jackson marshaller, no protobuf codegen)
ClusterGrpcService      gRPC server implementation for internal node-to-node communication (heartbeat, shared-object sync, LEADER election/status check)
ClusterGrpcClient       gRPC client for internal node-to-node communication (node URL → host:(serverPort + grpcPortOffset), channel cache)
ClusterClient           General-purpose REST client (Feign) + load balancing client (used by driver-starter, etc.)
RedirectFunction        Executes functions against the LEADER/a specific node, LEADER election, parallel execution utilities
ClusterEvents           Cluster event registration (builder pattern)
```
### Behavior overview
- On startup, a temporary server (`GET /index`) is brought up on its own serverPort and nodeTargetUrls are queried to **automatically determine its own URL** (startup fails if it cannot be determined)
- Internal node-to-node communication (heartbeat, shared-object sync, LEADER election/status check) uses **gRPC** — each node listens for gRPC on port `serverPort + grpcPortOffset` (plaintext; the port must be allowed through the firewall). The REST API for external users and the redirect proxy remain on HTTP
- After waiting for `leaderLostTimeoutSeconds`, the node starts as LEADER if its nodeIndex is 1, otherwise as FOLLOWER
- Every node sends a heartbeat to all nodes at the `heartbeatSendingIntervalMillis` interval (including position and shared-object sequence)
- If no LEADER heartbeat arrives for `leaderLostTimeoutSeconds`, the candidate with the smallest nodeIndex is elected as LEADER
- shared-object changes are propagated through the LEADER, and are synchronized automatically when the sequence does not match. When recovering from a communication failure/split brain, the data is overwritten based on the LEADER (an overwritten event is raised)
- A cluster that does not meet the quorum is inactivated (split brain handling)
---
## config (ClusterStarter.Builder)
- nodeTargetUrls: Set of node URL addresses (order does not matter; enter one or more URL addresses per node)
- serverPort: Server port number of this node
- nodeIndex: Unique number per node (starting from 1)
- quorum: Quorum. In a split brain state, only a cluster containing at least the quorum number of nodes is activated (quorum <= 0: maxClusterSize/2+1) (default: 0)
- leaderLostTimeoutSeconds: If no heartbeat arrives from the LEADER for leaderLostTimeoutSeconds [sec], a new LEADER is elected. Also the wait time on initial startup used to detect an already-elected LEADER node (default: 20)
- heartbeatSendingIntervalMillis: Heartbeat sending interval [ms] (default: 2000)
- clusterEvents: Registers functions to run when events occur (`ClusterEvents` builder; each event registers an id and a function)
  - activated: Cluster activated (quorum satisfied)
  - inactivated: Cluster inactivated (quorum not met)
  - becomeLeader / becomeFollower: Position transition
  - clusterAdded(int nodeIndex): A node joined
  - clusterDeleted(int nodeIndex, Map<String, Object> sharedObject): A node left (the leaving node's shared-object is passed)
  - overwritten(int nodeIndex): A communication failure, split brain, etc. was resolved and this node's shared-object was overwritten with the LEADER's shared-object
  - splitBrainResolved: Split brain resolved
- routes: For adding REST APIs (`java.util.function.Consumer<reactor.netty.http.server.HttpServerRoutes>`)
- clusterBasePath: REST API base url (default: "/cluster")
- connectTimeoutMillis: Client connectTimeout [ms] (common to REST/gRPC) (default: 1000)
- readTimeoutMillis: Client readTimeout [ms] (REST readTimeout / gRPC deadline) (default: 60000)
- grpcPortOffset: gRPC listen port offset for internal node-to-node communication — gRPC port = serverPort + grpcPortOffset (default: 10000)
- grpcServices: List of additional services to register on the gRPC server alongside the internal cluster services (`List<io.grpc.ServerServiceDefinition>`, default: none)
---
## Usage example
pom.xml
```xml
<dependency>
    <groupId>com.sds.communicators</groupId>
    <artifactId>cluster-starter</artifactId>
    <version>{cluster-version}</version>
</dependency>
```
### With its own server
``` java
var cluster = ClusterStarter.builder(
                Set.of("http://127.0.0.1:4001","http://127.0.0.1:4002"),
                4001,
                1)
        .setQuorum(1)
        .setLeaderLostTimeoutSeconds(20)
        .setHeartbeatSendingIntervalMillis(2000)
        .setClusterEvents(new ClusterEvents()
                .becomeLeader("on-leader", () -> log.info("become leader"))
                .clusterDeleted("on-deleted", (nodeIndex, sharedObject) -> log.info("node {} deleted", nodeIndex)))
        .setRoutes(routes -> routes.get("/hello",
                (request, response) -> response.sendString(Mono.just("world"))))
        .setClusterBasePath("/cluster")
        .setConnectTimeoutMillis(1000)
        .setReadTimeoutMillis(60000).build();

cluster.start(); // default server thread pool size=200
// cluster.start(serverThreadPoolSize); // specify thread pool size
// cluster.dispose();                   // shutdown
```
### Without its own server
``` java
cluster.startWithoutHttpServer();
```
Registering a server (example: reactor-netty)
``` java
HttpServer.create()
        .port(4001)
        .route(cluster.getRoutes()::accept)
        .bindNow();
```
---
## Main ClusterStarter APIs
### shared-object
``` java
cluster.mergeSharedObject(Map<String, Object> obj);        // merge into this node's shared-object (propagated via the LEADER)
cluster.mergeSharedObject(Object value, String... path);   // merge at the specified path
cluster.deleteSharedObject(String... path);                // delete a path
cluster.deleteSharedObject(List<List<String>> paths);      // delete multiple paths
cluster.getSharedObject();                                 // this node's shared-object
cluster.getSharedObjectMap();                              // shared-objects of all nodes (by nodeIndex)
cluster.getItem(int nodeIndex, String[] path);             // look up an item of a specific node
```
### Cluster status/control
``` java
cluster.getNodeIndex();       // own nodeIndex
cluster.getPosition();        // own position (LEADER/FOLLOWER)
cluster.getPosition(nodeIndex);
cluster.getCluster();         // set of nodeIndexes currently participating in the cluster
cluster.isActivated();        // whether the quorum is satisfied
cluster.forceToLeader();      // force transition to LEADER
cluster.forceToFollower();    // force transition to FOLLOWER
```
### Executing functions against nodes / clients
``` java
cluster.toLeaderFuncConfirmed(url -> {...}, "name");  // execute against the LEADER node URL (retries until success, electing one if needed)
cluster.toLeaderFunc(url -> {...}, "name");           // execute against the LEADER node URL (returns Throwable on failure)
cluster.toIndexFunc(nodeIndex, url -> {...}, "name"); // execute against a specific node URL
cluster.toAllFunc(url -> {...}, "name");              // execute in parallel against all node URLs
cluster.parallelExecute(collection, item -> {...});   // parallel execution utility
cluster.getClient(url, Api.class);                    // create a Feign client (@RequestLine interface)
cluster.grpcCall(url, methodDescriptor, request);     // unary gRPC call reusing the internal channel cache/deadline
cluster.loadBalancedClient(urls, Api.class, api -> {...}); // round-robin execution over a URL set
```
---
## REST API (base: clusterBasePath, default "/cluster")
### Query/control
| Method | Path | Description |
|---|---|---|
| GET | /node-status | Own node status (nodeIndex, position, activated) |
| GET | /get-node-index | Own nodeIndex |
| GET | /leader-url | LEADER node URL |
| GET | /index-url/{nodeIndex} | URL of a specific node |
| GET | /get-cluster-nodes | List of nodeIndexes participating in the cluster |
| GET | /get-cluster-urls | List of registered node URLs |
| POST | /add-cluster-node | Add a node URL (body: url string) |
| PUT | /set-to-leader | Force transition to LEADER |
| PUT | /set-to-follower | Force transition to FOLLOWER |
| GET | /shared-object-map | Query all shared-objects |
| GET | /shared-object-seq | Query the shared-object sequence |

### redirect (no base path)
| Method | Path | Description |
|---|---|---|
| ANY | /redirect-to-leader/{path} | Proxy the request to the LEADER node |
| ANY | /redirect-to-index/{nodeIndex}/{path} | Proxy the request to a specific node |
- The method, headers, query string, and body are forwarded as-is (e.g. `PUT /redirect-to-leader/driver/reconnect-all`)

### Internal (node-to-node communication) — gRPC
Internal node-to-node communication (heartbeat, cluster-deleted, get/merge/delete/check/overwrite/sync/remove shared-object, shared-object sequence check,
internal node-status/set-to-leader/node-index queries) is performed over **gRPC** rather than REST.
- service: `cluster.ClusterInternal` (unary); payloads are serialized as Jackson JSON (no protobuf codegen)
- listen port: `serverPort + grpcPortOffset` (default offset 10000), plaintext
- Client calls are blocking unary, with deadline = `readTimeoutMillis`
---
## Notes
- driver-starter embeds and uses cluster-starter (see [driver.md](driver.md) — device information sharing, load balancing between nodes)
- Dependencies: reactor-netty, jackson, feign-core, grpc-netty-shaded, grpc-stub, grpc-api, guava, slf4j
