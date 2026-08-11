# communicators

장비(디바이스) 통신 드라이버 및 클러스터 구성 library 모음.
외부 coordinator 없이 노드 간 직접 통신(gRPC)만으로 클러스터를 구성하고, 다양한 산업 프로토콜(TCP/UDP/Modbus/HTTP/OPC UA)로 장비 데이터를 수집·제어한다.

## 모듈 구성

```
common ◄─── cluster-starter ◄─── driver-starter ◄─┬── io-db
                                                  ├── io-kafka
                                                  └── io-none
```

| 모듈 | 종류 | 설명 | 문서 |
|---|---|---|---|
| common | library | 공용 struct(Device, Command, Response, Status), enum, 유틸(UtilFunc, LoadBalancer) | - |
| cluster-starter | library | 클러스터 구성: LEADER 선출, heartbeat, shared-object 공유, redirect proxy | [cluster.md](cluster.md) |
| driver-starter | library | 장비 통신 드라이버: 프로토콜 구현, Python 3 스크립트 엔진(GraalPy), REST API, Web UI | [driver.md](driver.md) |
| io-db | application | driver-starter 실행 예시 (Spring Boot, 수집 데이터 로그 출력 — custom output 상속 예시) | - |
| io-kafka | application | driver-starter 실행 예시 (Spring Boot, 수집 데이터 Kafka 출력) | - |
| io-none | application | driver-starter 실행 예시 (Spring Boot, 수집 데이터 미출력 — 테스트/검증용) | - |

- **common**: 모든 모듈이 공유하는 데이터 구조. 버전은 전 모듈 공통(현재 3.8)
- **cluster-starter**: reactor-netty HTTP server(외부 REST/redirect) + gRPC(node 간 내부 통신, port = serverPort + grpcPortOffset) 기반
- **driver-starter**: cluster-starter를 내장해 노드 간 디바이스 로드밸런싱/장애 인계를 지원.
  출력별 구현체(None/File/Kafka/Rest)를 제공하며 `DriverStarter`를 상속해 custom output 구현 가능.
  Web UI 포함 (`driver-starter/ui`, React + Vite → `src/main/resources/static`)
- **io-db / io-kafka / io-none**: Spring Boot로 설정(`application.yml`의 `io.*`)만 주입해 driver를 구동하는 실행 모듈.
  HTTP server는 driver-starter의 reactor-netty가 담당 (`spring.main.web-application-type: none`)

## 기술 스택

- Java 17, Gradle (멀티프로젝트, gradle wrapper 포함 — 기존 pom.xml은 참고용으로 유지)
- reactor-netty (HTTP server/client), gRPC (grpc-netty-shaded, node 간 내부 통신 — Jackson JSON payload), Feign (범용 REST client), Jackson
- GraalPy 24.1.2 (Python 3 스크립트 엔진, `org.graalvm.polyglot`)
- RxJava 3, digitalpetri modbus, Eclipse Milo (OPC UA), kafka-clients
- io 모듈: Spring Boot (설정/실행 컨테이너 용도)

## 빌드

JDK 17 필요. Gradle 멀티프로젝트로 구성되어 루트에서 한 번에 빌드한다
(모듈 간 의존성 순서 common → cluster-starter → driver-starter → io-db/io-kafka/io-none 은 자동 보장).

```bash
gradlew build          # 전체 빌드 (테스트 포함)
gradlew build -x test  # 테스트 제외
```

- 산출물: 각 모듈의 `build/libs/` (io-db/io-kafka/io-none은 Spring Boot `bootJar` — `io-kafka-3.8.jar` 등)
- 특정 모듈만: `gradlew :driver-starter:build`, `gradlew :io-kafka:bootJar`

## 실행 (io 모듈 예시)

```bash
java -jar io-kafka/build/libs/io-kafka-3.8.jar
```

- 설정: `application.yml`의 `io.*` (driver-id, node-index, node-target-urls, quorum, Kafka 주소/topic/포맷 등)
- 구동 후 Web UI: `http://{host}:{server.port}/driver`
- 클러스터 구성 시 노드마다 `io.node-index`를 고유하게 부여하고 `io.node-target-urls`에 전체 노드 URL을 나열

## 문서

- [cluster.md](cluster.md) — cluster-starter 구조, config, API, REST endpoint
- [driver.md](driver.md) — driver-starter 구조, config, Device/Command, 프로토콜별 옵션, Python 스크립트 가이드, REST endpoint
