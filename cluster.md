# cluster-starter
- cluster 구성 library (외부 coordinator 없이 노드 간 직접 통신만으로 동작)
- cluster 내 모든 node는 data-map(shared-object) 공유
- node 별로 1개 이상의 NIC 카드 사용 가능
- LEADER node 장애 시 cluster 내 nodeIndex가 가장 빠른 node를 LEADER로 선출
- 외부 사용자용 REST server는 reactor-netty 기반, **node 간 내부 통신은 gRPC** (unary, Jackson JSON payload, plaintext)
---
## 모듈 구조
```
ClusterStarter          진입점. Builder로 생성, 서버 수명주기(start/dispose) 관리 (HTTP + gRPC 서버)
ClusterServerRoutes     cluster REST API 라우팅 (reactor-netty HttpServerRoutes) — 외부용/redirect
ClusterService          position 전이(LEADER/FOLLOWER), heartbeat, shared-object 동기화
ClusterGrpc             node 간 내부 통신 gRPC 정의 (MethodDescriptor + Jackson marshaller, protobuf codegen 미사용)
ClusterGrpcService      node 간 내부 통신 gRPC 서버 구현 (heartbeat, shared-object 동기화, LEADER 선출/상태 확인)
ClusterGrpcClient       node 간 내부 통신 gRPC client (node URL → host:(serverPort + grpcPortOffset), 채널 캐시)
ClusterClient           범용 REST client (Feign) + load balancing client (driver-starter 등에서 사용)
RedirectFunction        LEADER/특정 node 대상 함수 실행, LEADER 선출, 병렬 실행 유틸
ClusterEvents           cluster event 등록 (builder 패턴)
```
### 동작 개요
- 구동 시 자신의 serverPort에 임시 서버(`GET /index`)를 띄우고 nodeTargetUrls를 조회하여 **자기 자신의 URL을 자동 판별** (판별 실패 시 구동 실패)
- node 간 내부 통신(heartbeat, shared-object 동기화, LEADER 선출/상태 확인)은 **gRPC** 사용 — 각 node는 `serverPort + grpcPortOffset` 포트로 gRPC 서버를 listen (plaintext, 방화벽에서 해당 포트 허용 필요). 외부 사용자용 REST API와 redirect proxy는 HTTP 유지
- `leaderLostTimeoutSeconds` 만큼 대기 후 nodeIndex가 1이면 LEADER, 아니면 FOLLOWER로 시작
- 모든 node는 `heartbeatSendingIntervalMillis` 주기로 전체 node에 heartbeat 전송 (position, shared-object sequence 포함)
- LEADER heartbeat가 `leaderLostTimeoutSeconds` 동안 없으면 후보 중 nodeIndex가 가장 작은 node를 LEADER로 선출
- shared-object 변경은 LEADER를 경유해 전파되며, sequence 불일치 시 자동 동기화. 통신 장애/split brain 복구 시 LEADER 기준으로 덮어쓰기(overwritten event 발생)
- quorum 미달 cluster는 inactivate 처리 (split brain 대응)
---
## config (ClusterStarter.Builder)
- nodeTargetUrls: node 들의 url 주소 set (순서 무관, node 별 1개 이상 url 주소 입력)
- serverPort: 해당 node의 server port 번호
- nodeIndex: node 별 고유 번호 (1 부터 시작)
- quorum: 정족수, split brain 상태일 경우 정족수 이상의 node가 포함된 cluster만 activate (quorum <= 0: maxClusterSize/2+1) (default: 0)
- leaderLostTimeoutSeconds: LEADER로 부터 leaderLostTimeoutSeconds[초] 시간만큼 heartbeat가 안 올 경우 새로운 LEADER 선출, 초기 구동 시 이미 선출된 LEADER node 확인을 위한 대기 시간 (default: 20)
- heartbeatSendingIntervalMillis: heartbeat 전송 주기[ms] (default: 2000)
- clusterEvents: event 발생 시 수행 함수 등록 (`ClusterEvents` builder, 각 event는 id와 함수를 등록)
  - activated: cluster 활성화 (quorum 충족)
  - inactivated: cluster 비활성화 (quorum 미달)
  - becomeLeader / becomeFollower: position 전이
  - clusterAdded(int nodeIndex): node 합류
  - clusterDeleted(int nodeIndex, Map<String, Object> sharedObject): node 이탈 (이탈 node의 shared-object 전달)
  - overwritten(int nodeIndex): 통신장애나 split brain 등이 복구되어 LEADER의 shared-object로 해당 node의 shared-object를 덮어씀
  - splitBrainResolved: split brain 해소
- routes: REST API 추가 용도 (`java.util.function.Consumer<reactor.netty.http.server.HttpServerRoutes>`)
- clusterBasePath: REST API base url (default: "/cluster")
- connectTimeoutMillis: client connectTimeout[ms] (REST/gRPC 공통) (default: 1000)
- readTimeoutMillis: client readTimeout[ms] (REST readTimeout / gRPC deadline) (default: 60000)
- grpcPortOffset: node 간 내부 통신용 gRPC listen port offset — gRPC port = serverPort + grpcPortOffset (default: 10000)
- grpcServices: cluster 내부 서비스와 함께 gRPC 서버에 추가 등록할 서비스 목록 (`List<io.grpc.ServerServiceDefinition>`, default: 없음)
---
## 사용 예시
pom.xml
```xml
<dependency>
    <groupId>com.sds.communicators</groupId>
    <artifactId>cluster-starter</artifactId>
    <version>{cluster-version}</version>
</dependency>
```
### 자체 server 포함
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
// cluster.start(serverThreadPoolSize); // thread pool size 지정
// cluster.dispose();                   // 종료
```
### 자체 server 미포함
``` java
cluster.startWithoutHttpServer();
```
server 등록 (예시: reactor-netty)
``` java
HttpServer.create()
        .port(4001)
        .route(cluster.getRoutes()::accept)
        .bindNow();
```
---
## ClusterStarter 주요 API
### shared-object
``` java
cluster.mergeSharedObject(Map<String, Object> obj);        // 자기 node의 shared-object에 병합 (LEADER 경유 전파)
cluster.mergeSharedObject(Object value, String... path);   // 경로 지정 병합
cluster.deleteSharedObject(String... path);                // 경로 삭제
cluster.deleteSharedObject(List<List<String>> paths);      // 다중 경로 삭제
cluster.getSharedObject();                                 // 자기 node의 shared-object
cluster.getSharedObjectMap();                              // 전체 node의 shared-object (nodeIndex별)
cluster.getItem(int nodeIndex, String[] path);             // 특정 node의 항목 조회
```
### cluster 상태/제어
``` java
cluster.getNodeIndex();       // 자기 nodeIndex
cluster.getPosition();        // 자기 position (LEADER/FOLLOWER)
cluster.getPosition(nodeIndex);
cluster.getCluster();         // 현재 cluster에 참여 중인 nodeIndex set
cluster.isActivated();        // quorum 충족 여부
cluster.forceToLeader();      // 강제 LEADER 전환
cluster.forceToFollower();    // 강제 FOLLOWER 전환
```
### node 대상 함수 실행 / client
``` java
cluster.toLeaderFuncConfirmed(url -> {...}, "name");  // LEADER node URL로 실행 (성공할 때까지 재시도, 필요 시 선출)
cluster.toLeaderFunc(url -> {...}, "name");           // LEADER node URL로 실행 (실패 시 Throwable 반환)
cluster.toIndexFunc(nodeIndex, url -> {...}, "name"); // 특정 node URL로 실행
cluster.toAllFunc(url -> {...}, "name");              // 전체 node URL로 병렬 실행
cluster.parallelExecute(collection, item -> {...});   // 병렬 실행 유틸
cluster.getClient(url, Api.class);                    // Feign client 생성 (@RequestLine 인터페이스)
cluster.grpcCall(url, methodDescriptor, request);     // 내부 채널 캐시/deadline을 재사용하는 unary gRPC 호출
cluster.loadBalancedClient(urls, Api.class, api -> {...}); // URL set에 round-robin 실행
```
---
## REST API (base: clusterBasePath, default "/cluster")
### 조회/제어
| Method | Path | 설명 |
|---|---|---|
| GET | /node-status | 자기 node 상태 (nodeIndex, position, activated) |
| GET | /get-node-index | 자기 nodeIndex |
| GET | /leader-url | LEADER node URL |
| GET | /index-url/{nodeIndex} | 특정 node URL |
| GET | /get-cluster-nodes | cluster 참여 nodeIndex 목록 |
| GET | /get-cluster-urls | 등록된 node URL 목록 |
| POST | /add-cluster-node | node URL 추가 (body: url 문자열) |
| PUT | /set-to-leader | 강제 LEADER 전환 |
| PUT | /set-to-follower | 강제 FOLLOWER 전환 |
| GET | /shared-object-map | 전체 shared-object 조회 |
| GET | /shared-object-seq | shared-object sequence 조회 |

### redirect (base path 없음)
| Method | Path | 설명 |
|---|---|---|
| ANY | /redirect-to-leader/{path} | 요청을 LEADER node로 proxy |
| ANY | /redirect-to-index/{nodeIndex}/{path} | 요청을 특정 node로 proxy |
- method, header, query string, body 그대로 전달됨 (예: `PUT /redirect-to-leader/driver/reconnect-all`)

### 내부용 (node 간 통신) — gRPC
node 간 내부 통신(heartbeat, cluster-deleted, get/merge/delete/check/overwrite/sync/remove shared-object, shared-object sequence 확인,
node-status/set-to-leader/node-index 내부 조회)은 REST가 아닌 **gRPC**로 수행된다.
- service: `cluster.ClusterInternal` (unary), payload는 Jackson JSON 직렬화 (protobuf codegen 미사용)
- listen port: `serverPort + grpcPortOffset` (default offset 10000), plaintext
- client 호출은 blocking unary, deadline = `readTimeoutMillis`
---
## 참고
- driver-starter가 cluster-starter를 내장하여 사용 ([driver.md](driver.md) 참고 — 디바이스 정보 공유, 노드 간 로드밸런싱)
- 의존성: reactor-netty, jackson, feign-core, grpc-netty-shaded, grpc-stub, grpc-api, guava, slf4j
