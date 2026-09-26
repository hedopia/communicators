# cluster-starter

`cluster-starter` creates a cluster through direct node-to-node communication without an external coordinator.

- Every node maintains a shared-object map.
- A node may be reachable through one or more network interfaces and URLs.
- If the leader fails, the participating node with the lowest `nodeIndex` is elected.
- Public REST endpoints and redirect proxying use Reactor Netty HTTP.
- Internal node communication uses HTTP calls with Jackson-serialized payloads through one shared JDK `HttpClient` (HTTP/1.1 with keep-alive pooling), served on the same port as the public API.
- Route handlers are blocking and run on a per-request worker thread, so event loops stay free for I/O.

## Architecture

```text
ClusterStarter                  Entry point and lifecycle owner for the HTTP server
ClusterServerRoutes             Public cluster REST API, redirect proxy, and internal node-to-node routes
ClusterService                  Leader/follower transitions, heartbeats, and shared-object synchronization
ClusterInternalClient           Typed client for the internal node-to-node routes
ClusterRedirectFunction         Leader/index dispatch, election, retry, and parallel-execution utilities
ClusterEvents                   Event registration API
support/NodeHttpClient          Shared JDK HttpClient for all node-to-node calls
support/RouteDispatcher         Runs each request's handler on its own worker thread
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

### Node loss and failover

- A node whose heartbeats stop for `leaderLostTimeoutSeconds` leaves the membership (`getCluster()`, quorum) of the node
  that stopped hearing it. That node reports the loss to the leader, but only the leader decides about the lost node's
  data: it ignores the report while it still receives the node's heartbeats (one-way link loss, a follower that
  stalled).
- The leader takes the removed node's shared object out of its own view, runs the `clusterDeleted` handlers with it and
  waits for them to finish. Only then do the followers drop their copy. If the leader fails in between, the new leader
  still holds that copy and runs `clusterDeleted` again.
- The leader also removes, in the same way, any held shared object whose node has not been a member for
  `leaderLostTimeoutSeconds` plus one heartbeat interval. Followers drop copies that the leader does not hold.
- A node that stalls (GC pause, `docker pause`, SIGSTOP) re-arms its lost-node and lost-leader timers while the stall
  is recent (`leaderLostTimeoutSeconds` plus two heartbeat intervals), instead of removing peers whose heartbeats queued
  up meanwhile.
- When a split brain heals, the winning leader keeps the demoted leader's copy of a node's shared object if it has none
  or an older one (for a node it has already removed: one newer than the object its `clusterDeleted` handlers got). It
  answers as soon as it has taken them over; bringing its followers in line (each peer checked with the probe timeout)
  and `splitBrainResolved` follow on another thread, once per heal (a repeated handover carries the same id). The
  demoted leader sends its copies from another thread, one call at a time, and again with a later heartbeat of the
  winner (backing off from one to 16 heartbeat intervals) until the call succeeds or a heartbeat of the winner lists
  each of them at the same or a newer sequence, or it learned that the winner removed it; it drops none of them
  meanwhile, nor replaces them with an older copy. The first call always goes out, and a winner that listed everything
  before any call succeeded is still told once (without the objects), so `splitBrainResolved` fires exactly once per
  heal. The handover is stored under the same lock as offers: an offer made for a heartbeat of the winner handled at
  the same time never replaces it, and two demoting heartbeats handled at once make one handover. Only the winner's
  heartbeats count, and while the winner is heard another leader's heartbeats decide nothing about the handover. If
  the winner dies right after answering, the copies the leader after it lacks are offered to that leader instead: once
  the winner has not been heard for `leaderLostTimeoutSeconds`, or sooner, once it has missed two heartbeats and that
  leader was elected after the winner's last heartbeat (an election is also started by a lookup that finds no leader,
  well before the timeout). Copies whose failover still runs on the demoted leader are handed over too, and the winner
  fails them over itself like any held copy of a node that is not a member. A failover that finished after its leader
  was demoted is a soft removal, not proof that the devices were placed: a failover that places devices through the
  leader finds them registered with the winner while it holds that copy, so it may have placed nothing. Once the
  winner has taken that copy over from the demoted leader's handover (it answered a call carrying it, or such a call
  is on its way to a winner that the demoted leader's heartbeats reach), the demoted leader holds the copy it handed
  over again, as it would once the winner listed it, and does not broadcast that failover: should the winner die
  before its own failover, the leader after it holds that object or is offered it, or the demoted leader fails it
  over itself if it leads next, and the winner's followers keep what they hold of it meanwhile, also a copy fetched
  from the demoted leader while they followed it during the heal. Otherwise (no call carrying it got through: a
  one-way link) the failover is broadcast marked as such, with the sender's index, since an unmarked broadcast would
  make a winner that took the copy over from a follower drop it, leaving those devices nowhere; should an answer
  carrying it come only after that, the demoted leader holds that copy again then. A leader keeps what it holds of that
  object and remembers the sequence: while it leads, it takes that object over from no offer or handover. A follower
  keeps a copy that the sender did not list there (the winner's, pushed to it or fetched from it): should the winner
  die before its own failover, the next leader holds it or is offered it. It drops a copy that the sender listed (one
  from the sender's side of the split brain, which no other leader was known to hold, so that failover could place
  its devices) and offers it to no leader. The demoted leader takes its own failover for a soft removal too, so a copy
  of the winner's that it holds again is offered to the leader after the winner. A node that leads later does not take
  a soft removal it learned as a follower (or in an earlier leadership) for proof: it fails over a copy it holds, or
  takes over one offered at that sequence, after the absence grace, so in a double fault that object may be failed
  over twice. From then on, unless the demoted leader holds that copy again, a handover call carries that sequence
  instead of the copy, also when it only tells the winner without the objects: a winner that the broadcast did not
  reach (a one-way link) still declines that object when a follower that missed it too offers it. An object
  the winner took over before that failover finished (from the handover, or from a follower's offer: a restarted leader
  may take one over while a leader elected meanwhile still fails it over) is failed over twice; so is an object whose
  failover finished while the handover call carrying it was still on its way (or that call failed and a later one
  carried the copy again), since the winner takes it over only afterwards. The driver skips devices already
  registered on another node, and its duplicate check stops a device that runs twice.
- When a follower starts following another leader (a leader change, a heal, a leader that restarted), it first offers
  that leader the copies it holds that the leader lacks or holds older, with the same delivery rule. It keeps copies of
  nodes it still hears, but offers them only once it no longer hears them: a live node supplies its own object, and
  the leader may just not hear it (a follower hearing both leaders of a split brain). A leader heard again within
  `leaderLostTimeoutSeconds` counts as the same one, not as a new leader each time the two alternate. The leader takes
  over only copies of nodes that are not members, each only if newer than what it holds or knows to be removed, and
  then removes (fails over) that node like any held copy of a node that is not a member. It answers with the members
  it declined (a member supplies its own object); the follower keeps those and offers them again later, since the
  member may die before the leader pulled its object (a restarted leader heard it while preparing). With the same
  leader, the leader's lack of a copy still decides. An offer is kept for the leader it goes to while that leader is
  heard, whatever another leader heard alternately with it holds (a follower bridged to the other side of a split
  brain keeps the copy of its own side's leader for the other one, and sends it once that leader is no longer heard).
  Both leaders of a bridge are heard every interval and led before each other's last heartbeat; only a leader elected
  after the last heartbeat of the one an offer goes to, once that one has missed two heartbeats, took over from it,
  and is offered what it lacks right away.
  Handover calls back off per leader, across offers, while this node's heartbeats do not reach that leader; once one
  does again, the next call goes out right away. The leader is called at the url where it answered this node's
  heartbeats with its index, since a restarted leader refuses the status lookup until it has prepared; failures after
  the first one in a row are logged at debug level, and a leader not found at all (this node's heartbeats have not
  reached it yet, and the status lookup finds none) at warn level once per leader.
- Stale offers cannot bring devices back: a follower drops a copy the leader lacks at each leader heartbeat, and every
  node remembers the sequence of each removed object (failed over as leader; from a broadcast, which carries it; or, as
  follower, from dropping its copy of a node it does not hear once the leader that listed that copy no longer does), so
  neither the follower nor a later leader offers or takes over that object again; a failover that finished after its
  leader was demoted counts only as described above. A copy dropped because a leader that never held it lacks it (a
  new or restarted leader, or a split-brain leader heard for a while) is not taken for removed. A node the leader
  hears supplies its own object, never an offer, and
  sequences grow across restarts. An offered copy is failed over only after the absence grace, so a node that is back
  (or was not heard yet) cancels it, and its own object replaces the copy; unless a removal of that node was requested
  within the grace before (by the leader's own timer, or by a follower whose timer expired): the copy is then failed
  over right away, as that request would have done had the copy come a moment earlier. Not when the leader heard
  another leader within the grace, which may be failing that node over too, and whose broadcast makes the leader drop
  the copy it took over (see below). What is left (a leader that failed a node over and died before any follower learned of it) at worst fails
  the same object over twice, and the driver skips devices already registered on another node.
- A node that is leader itself keeps its copies on the broadcast that makes followers drop a removed node's copy (a
  late broadcast of another leader), since the leader decides about the data it holds. It remembers the sequence, and
  from a leader that led all the while (transient dual leaders) it also drops a copy of a node that is not its member
  and is no newer (taken over from an offer or a handover meanwhile), which would otherwise be failed over a second
  time. From a leader that was demoted before its failover finished (the marked broadcast) it keeps that copy and fails
  it over itself, and declines that object at that sequence while it leads, as above.
- `dispose()` stops the HTTP server before the cluster service, and a disposed node ignores heartbeats, lost-node
  timers and removals that are still under way.

### Shared-object consistency

- A node's sequence number starts at `System.currentTimeMillis() * 1000` on every start, so a restarted node never
  reuses a sequence number from its previous run.
- Every few heartbeats, followers and the leader compare content digests of the copies they hold. A copy with the
  same sequence number but different content is fetched again.
- The leader sends every change to the followers in sequence order: a follower applies each change instead of being
  overwritten with the whole object. Each node's changes go to each peer in a lane of their own, which sends everything
  queued while its previous batch was on its way as one request, so neither a slow peer nor another node's changes hold
  it up. The peer applies the batch in order and skips the changes it already has (it got the whole object ahead of
  them); a peer that is further behind gets the whole object once. A peer of an older version, which answers the batch
  route with `404`, gets one change per call (tried again after a minute). More than 256 changes of one node waiting
  for one peer are replaced by one copy of the whole object: a peer that keeps up gets about one change per writer
  waiting on the leader, and a whole copy fires `overwritten` on the follower. A follower's change is not sent back
  to that follower (its urls are known from the index it answers heartbeats with).
- Copies of every node's shared object (`getSharedObjectMap()`, `/shared-object-map`) are taken one node at a time, so
  a large copy does not hold up changes until it is complete.

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
| `quorum` | Minimum active partition size. A value at or below zero selects `maxClusterSize / 2 + 1`, so a two-node cluster then needs both nodes; set `1` if one node must keep running alone. | `0` |
| `leaderLostTimeoutSeconds` | Leader-loss timeout and initial leader-discovery wait | `20` |
| `heartbeatSendingIntervalMillis` | Heartbeat interval in milliseconds | `2000` |
| `clusterEvents` | Cluster event handlers | None |
| `routes` | Additional Reactor Netty HTTP routes | None |
| `clusterBasePath` | Base path for the cluster REST API | `/cluster` |
| `connectTimeoutMillis` | Connection timeout for REST and internal calls | `1000` |
| `readTimeoutMillis` | Read timeout for REST and internal calls. Node-status probes that locate the leader or a node index use `min(max(2 * heartbeatSendingIntervalMillis, 1000), readTimeoutMillis)`. | `60000` |

### Cluster events

`ClusterEvents` supports the following handlers:

- `activated`: quorum has been reached.
- `inactivated`: quorum has been lost.
- `becomeLeader`: this node became leader.
- `becomeFollower`: this node became a follower.
- `clusterAdded(int nodeIndex)`: a node joined.
- `clusterDeleted(int nodeIndex, Map<String, Object> sharedObject)`: the leader removed a lost node; its last shared object is supplied. It runs on the leader only, and the followers keep their copy of the object until every handler has returned (see [Node loss and failover](#node-loss-and-failover)).
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
cluster.mergeSharedObjectIf(ownObject -> ..., Object value, String... path);
cluster.deleteSharedObjectIf(ownObject -> ..., List<List<String>> paths);
cluster.getSharedObject();
cluster.getSharedObjectMap();
cluster.getItem(nodeIndex, path);
cluster.readSharedObject(map -> ...);
```

Changes to a node's shared object are propagated through the leader. A follower's change returns once the leader
has applied it. The leader's own change returns once it has been sent to the followers that keep up, or after the
probe timeout (see `readTimeoutMillis`); concurrent changes of the leader share one batch. A follower whose last batch
took longer than the probe timeout (or failed) is not waited for until a batch is answered in time again, though it
still gets every batch. Without a reachable follower the change returns right away. A node that misses a propagation
is resynchronized by the heartbeat sequence check.

`getSharedObject()`, `getSharedObjectMap()` and `getItem()` return detached deep copies, so changing them does not
change the shared object. `readSharedObject(reader)` instead runs the reader on the live maps under the shared-object lock,
for reads that should not copy everything (for example, key sets). The reader must be quick, must not block or call
out, and must not modify the maps or keep references into them. `mergeSharedObjectIf` and `deleteSharedObjectIf`
check a guard on this node's own object atomically with the change. When the guard rejects, they return `false`
and change nothing. The guard has the same restrictions as the reader.

Values must be JSON-serializable. They are normalized through a JSON round trip before they are stored, so the
stored copy is detached from the caller's objects and holds only JSON types (maps, lists, strings, `Integer`/`Long`/
`BigInteger`/`Double`, booleans, null). A value that cannot be serialized throws `IllegalArgumentException` without
changing the shared object.

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

`toLeaderFuncConfirmed` retries until the leader accepts the call and may initiate an election. It stops and throws
when a retry cannot succeed (the request cannot be encoded, or the leader answers 4xx other than 408/425/429) and
when the calling thread is interrupted (`CancellationException`, with the interrupt flag kept set).

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
- Transport: HTTP/1.1 over the shared `java.net.http.HttpClient` (`NodeHttpClient`), with keep-alive
  connection pooling
- Timeouts: `connectTimeoutMillis` and `readTimeoutMillis`. Node-status probes, shared-object propagation
  (batches, check/overwrite), the cluster-deleted broadcast, the split-brain winner's sequence check and digest requests
  use the shorter probe timeout described under `readTimeoutMillis`. Propagation only goes to peers whose last
  heartbeat or propagation call succeeded; the others are resynchronized by the heartbeat sequence check.
- Changes are sent in batches (`check-shared-object-changes`); `offer-shared-object` carries a follower's offer to a new
  leader, and answers with the node indexes it declined because they are members. The cluster-deleted broadcast carries
  the sequence of the object failed over (`?seq=`), and `?demoted=true&sender=nodeIndex` from a node that no longer led
  when that failover finished (it sends none when the winner took that object over from its handover); a split-brain
  handover carries its id (`?handover=`), and `?removed=nodeIndex:sequence` for each copy it hands over as removed.
  Nodes of an older version ignore all of them.
  To such a node the marked broadcast is a cluster-deleted broadcast like any other, which it takes for the loss of
  that node: for a node it no longer hears (its own timer removed it already, as it has for a node that died) this
  changes nothing, so a winner of that version keeps its copy, as it should. A heartbeat is answered with the
  receiver's node index (a sender of an older version ignores it, and a receiver of an older version answers nothing).
- Failure mapping: rejected preconditions answer `400` with a plain-text reason; a shared-object change that the leader
  could not synchronize answers `503` and is retried by the sender. Requesting a shared object that the node does not
  hold answers `404`.
- `GET {clusterBasePath}/internal/shared-object-digest` lists the sequence number and a content digest of every
  shared object the node holds; with `?own=true` only that of the node's own object, which the leader asks each member
  for. Anti-entropy uses it and ignores a `404` from a node of an older version (which also ignores the parameter and
  lists every object).

These routes are part of `getRoutes()`. They are reachable by anything that can reach the HTTP port, so restrict that
port to the cluster network if the deployment is not otherwise isolated.

`driver-starter` embeds this module and adds its own internal routes under `{driverBasePath}/internal`. See the [driver guide](driver.md).