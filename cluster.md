# cluster-starter
- cluster 구성 library
- cluster 내 모든 node는 data-map(shared-object) 공유
- node 별로 1개 이상의 NIC 카드 사용 가능
- LEADER node 장애시 cluster내 nodeIndex가 가장 빠른 node를 LEADER로 선출
---
### config
- nodeTargetUrls: node 들의 url 주소 set (순서 무관, node 별 1개 이상 url 주소 입력)
- serverPort: 해당 node의 server port 번호
- nodeIndex: node 별 고유 번호 (1 부터 시작)
- quorum: 정족수, spit brain 상태일 경우 정족수 이상의 node가 포함된 cluster만 activate (quorum <= 0: maxClusterSize/2+1) (default: 0)
- leaderLostTimeoutSeconds: LEADER로 부터 leaderLostTimeoutSeconds[초] 시간만큼 Heartbeat가 안 올 경우 새로운 LEADER 선출, 초기 구동 시 이미 선출된 LEADER node 확인을 위한 대기 시간 (default: 20)
- heartbeatSendingIntervalMillis: heartbeat 전송 주기[ms] (default: 2000)
- clusterEvents: event 발생 시 수행 함수 등록
  - activated
  - inactivated
  - becomeLeader
  - becomeFollower
  - clusterAdded(int nodeIndex)
  - clusterDeleted(int nodeIndex, Map<String, Object> sharedObject)
  - overwritten(int nodeIndex) -> 통신장애나 split brain 등이 복구돼서 LEADER의 sharedObject와 해당 node의 sharedObject가 불일치 할 경우 해당 node의 sharedObject로 덮어쓰기
  - splitBrainResolved
- routerFunctionBuilder: REST API 추가 용도
- clusterBasePath: REST API base url (default: "/cluster")
- connectTimeoutMillis: REST API client connectTimeout[ms] (default: 1000)
- readTimeoutMillis: REST API client readTimeout[ms] (default: 60000)
---
### 자체 server 포함 예시 코드
pom.xml
```xml
<dependency>
    <groupId>com.sds.communicators</groupId>
    <artifactId>cluster-starter</artifactId>
    <version>{cluster-version}</version>
</dependency>
```
cluster 객체 생성 및 시작
``` java
var cluster = ClusterStarter.builder(
                Set.of("http://127.0.0.1:4001","http://127.0.0.1:4002"),
                4001,
                1)
        .setQuorum(1)
        .setLeaderLostTimeoutSeconds(20)
        .setHeartbeatSendingIntervalMillis(2000)
        .setClusterBasePath("/cluster")
        .setConnectTimeoutMillis(1000)
        .setReadTimeoutMillis(60000).build();

cluster.start(); // default server thread pool size=200
// int serverThreadPoolSize = 200; // server thread pool size 설정
// cluster.start(serverThreadPoolSize);
```
---
### 자체 server 미포함 예시 코드
cluster 객체 생성 및 시작
``` java
var cluster = ClusterStarter.builder(
                Set.of("http://127.0.0.1:4001","http://127.0.0.1:4002"),
                4001,
                1)
        .setQuorum(1)
        .setLeaderLostTimeoutSeconds(20)
        .setHeartbeatSendingIntervalMillis(2000)
        .setClusterBasePath("/cluster")
        .setConnectTimeoutMillis(1000)
        .setReadTimeoutMillis(60000).build();

cluster.startWithoutHttpServer();
```
server 등록 (예시: spring webflux) 
``` java
@Bean
public RouterFunction<ServerResponse> routerFunction() throws Throwable {
    return cluster.getRouterFunction().build();
}
```