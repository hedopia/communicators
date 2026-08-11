# driver-starter
- 장비(디바이스) 통신 드라이버 library
- [cluster-starter](cluster.md) 기반으로 동작: 클러스터 구성, 디바이스 정보 공유(shared-object), 노드 간 로드밸런싱 지원
- 프로토콜 처리 로직(명령 파싱/생성)은 **Python 3 스크립트**(GraalPy 엔진)로 작성
- 지원 프로토콜
  - TCP (server/client)
  - UDP (server/client) - multicast / broadcast 지원
  - MODBUS TCP (server/client)
  - HTTP (server/client) - SSL, mTLS 지원
  - OPC UA (server/client) - 보안정책/인증, subscription 지원 (Eclipse Milo)
  - Dummy (스크립트 실행 전용)
---
## 모듈 구조
```
DriverStarter (abstract)          진입점. Builder로 생성, ClusterStarter를 내장
 ├─ DriverStarterNoneOutput       수집 데이터 미출력
 ├─ DriverStarterFileOutput       CSV 파일 출력
 ├─ DriverStarterKafkaOutput      Kafka topic 출력
 └─ DriverStarterRestOutput       REST endpoint 출력 (io-db 모듈처럼 상속하여 custom output 구현 가능)

DriverService                     디바이스 connect/disconnect/로드밸런싱, 응답 관리
DriverServerRoutes                REST API 및 Web UI 라우팅 (reactor-netty)
DriverCommand                     명령 실행 엔진. 디바이스마다 독립 Python Context 보유
PythonEngine                      GraalPy(Python 3) Context wrapper
DriverProtocol (abstract)         프로토콜 공통 로직 (상태 전이, 재접속, 응답 채널)
 └─ DriverProtocolTcpUdp / TcpClient / TcpServer / UdpClient / UdpServer
    DriverProtocolModbusClient / ModbusServer
    DriverProtocolHttp / HttpClient / HttpServer
    DriverProtocolOpcua / OpcuaClient / OpcuaServer
    DriverProtocolDummy
```
- 디바이스 1개 = `DriverProtocol` 인스턴스 1개 = Python Context 1개 (스크립트 전역 변수는 디바이스 단위로 격리)
- 수집 데이터(Response)와 디바이스 상태(Status)는 `sendResponse`/`sendStatus`를 통해 Output으로 전달
---
## config (DriverStarter.Builder)
- driverId: 드라이버 식별자 (Response/Status 출력에 포함)
- clusterStarterBuilder: cluster 설정 ([cluster.md](cluster.md) config 참고, REST server port 포함)
- loadBalancing: `balanced-connect-all` 요청 시 노드 간 디바이스 분배 여부 (false면 요청받은 노드가 전부 연결) (default: true)
- defaultScript: 모든 디바이스의 Python Context에 최초 1회 실행되는 공통 스크립트 (공용 함수 정의 용도) (default: "")
- driverEvents: event 발생 시 수행 함수 등록
  - deviceAdded(Device device)
  - deviceDeleted(Device device)
- driverBasePath: REST API base url (default: "/driver")
- clusterEvents: cluster event 함수 등록 (cluster.md 참고)
- routes: REST API 추가 용도 (`java.util.function.Consumer<reactor.netty.http.server.HttpServerRoutes>`)

### Output 별 builder
``` java
DriverStarterNoneOutput.builder(driverId, clusterStarterBuilder)
DriverStarterFileOutput.builder(responseFile, statusFile, driverId, clusterStarterBuilder)
DriverStarterKafkaOutput.builder(bootstrapAddress, responseTopic, responseFormat, statusTopic, statusFormat, driverId, clusterStarterBuilder)
DriverStarterRestOutput.builder(restOutputTargetUrls, responsePath, responseFormat, statusPath, statusFormat, driverId, clusterStarterBuilder)
```
- responseFormat placeholder: `${deviceId}`, `${tagId}`, `${value}`, `${driverId}`, `${nodeIndex}`, `${receivedTime}`
- statusFormat placeholder: `${deviceId}`, `${status}`, `${driverId}`, `${nodeIndex}`, `${issuedTime}`
---
## 사용 예시
pom.xml
```xml
<dependency>
    <groupId>com.sds.communicators</groupId>
    <artifactId>driver-starter</artifactId>
    <version>{driver-version}</version>
</dependency>
```
driver 객체 생성 및 시작
``` java
var driver = DriverStarterKafkaOutput.builder(
                "127.0.0.1:9092",
                "response-topic",
                "{\"deviceId\":${deviceId},\"tagId\":${tagId},\"value\":${value},\"time\":${receivedTime}}",
                "status-topic",
                "{\"deviceId\":${deviceId},\"status\":${status}}",
                "DRIVER-1",
                ClusterStarter.builder(Set.of("http://127.0.0.1:4001"), 4001, 1))
        .setLoadBalancing(true)
        .setDriverBasePath("/driver")
        .build();

driver.start();                    // reactor-netty server 시작 (REST API + Web UI)
// driver.startWithoutHttpServer(); // server 미포함 시작, driver.getRoutes()로 라우트를 직접 등록
// driver.dispose();                // 종료
```
- Web UI: `http://{host}:{port}/driver` 접속 (디바이스 연결/명령 실행/조회 화면)
---
## Device
| 필드 | 설명 | default |
|---|---|---|
| id | 디바이스 식별자 (영숫자와 `_`만 허용) | (필수) |
| group | 같은 group의 디바이스는 로드밸런싱 시 같은 노드에 배치 | "" |
| connectionUrl | 접속 정보 (아래 프로토콜 별 형식 참고) | tcp-client://127.0.0.1:5000 |
| protocolScript | 프로토콜 스크립트 (protocolFunc / bufferingFunc 정의) | "" |
| commands | 명령(Command) set | {} |
| responseTimeout | 응답 timeout [sec], 초과 시 connection-lost (0 이하: 무제한) | 0 |
| maxRetryConnect | 접속 실패 시 재시도 횟수 (음수: 무한) | 5 |
| retryConnectDelay | 재접속 지연 [ms] | 5000 |
| socketTimeout | socket timeout [ms] | 5000 |
| initialCommandDelay | 접속 후 명령 실행까지 지연 [ms] (최소 100) | 5000 |
| connectionCommand | true면 명령 실행 시에만 접속 (요청 전 connect, 완료 후 disconnect) | false |
| data | 스크립트에서 사용할 초기 데이터 (`protocol.getData/setData`) | {} |

## Command
| 필드 | 설명 | default |
|---|---|---|
| id | 명령 식별자 (영숫자와 `_`만 허용) | UUID |
| order | 실행 순서 (오름차순) | 0 |
| type | 명령 종류 (아래 참고) | READ_REQUEST |
| periodGroup | 주기 실행 주기 [ms] (최소 500), 음수면 비주기(non-periodic) | -1 |
| requestInfo | 요청 정보 (프로토콜 별 형식, requestInfo 함수로 동적 생성 가능) | null |
| afterDelay | 명령 실행 후 지연 [ms] (delay 함수 미정의 시 사용) | 0 |
| commandTimeout | 응답 대기 timeout [ms] (read-request) | 5000 |
| cmdScript | 명령 스크립트 (cmdFunc / requestInfo / delay / control 정의) | null |

### CommandType
- READ_REQUEST / WRITE_REQUEST / REQUEST: 일반 명령 (periodGroup ≥ 0 이면 주기 실행, 음수면 수신 데이터 도착 시 실행)
- STARTING_*: 접속 완료 시 1회 실행
- STOPPING_*: 정상 disconnect 시 1회 실행
- READ: 요청 후 응답을 cmdFunc로 파싱 / WRITE: 요청만 전송 / REQUEST: 전송 없이 cmdFunc만 실행

### 디바이스 상태 (StatusCode)
`DISCONNECTED` → `CONNECTING` → `CONNECTED` → (`CONNECTION_FAIL`/`CONNECTION_LOST` 시 자동 재접속) → `DISCONNECTED`, 실패 시 `DISCONNECTION_FAIL`
---
## connectionUrl 및 프로토콜 별 옵션
옵션은 query string으로 전달: `tcp-client://127.0.0.1:5000?endBytes=0x0D0A&bufferTime=200`

공통 옵션
- connectionLostOnException: 명령 실행 예외 시 connection-lost 처리 여부 (server류는 기본 false)

> **예시 표기 규칙**: 아래 각 프로토콜의 "예시" 소단락에 있는 Device JSON은 `POST /driver/balanced-connect-all`(또는 `/connect-all`)의 body에 그대로 사용할 수 있는 형태다.
> 여러 줄 스크립트는 가독성을 위해 JSON과 분리해 표기했으며, `"<script:이름>"` 자리에는 해당 이름의 python 블록 전문을 JSON 문자열로(개행을 `\n`으로 이스케이프하여) 넣으면 된다.

### tcp-client / tcp-server / udp-client / udp-server
- 형식: `tcp-client://{host}:{port}`, `tcp-server://{host}:{port}` (server의 host 생략 가능)
- startBytes / endBytes: 패킷 구분 바이트 (예: `0x0D0A`)
- retainStartEndBytes: 구분 바이트를 수신 데이터에 포함할지 여부 (default: false)
- combineBufferedData: 버퍼링된 패킷을 하나로 합쳐서 전달할지 여부 (default: true)
- bufferTime: 패킷 버퍼링 시간 [ms] (tcp default: endBytes/bufferingFunc 없으면 100, 있으면 0 / udp default: 항상 0)
- multicastGroup: (udp-server) 콤마 구분 multicast 주소 목록
- requestInfo 형식: 전송할 문자열, 또는 `{"message":"...", "host":"...", "port":n}` (udp/tcp-server 대상 지정, host/port 생략 시 tcp-server는 전체 클라이언트에 전송)
  - requestInfo 문자열은 `\xNN` 이스케이프로 임의 바이트를 표현할 수 있다 (예: `"RD\x0D\x0A"` → `52 44 0D 0A`)
  - helper: `protocol.requestInfo(message, host, port)` / `protocol.requestInfo(message, sender)` (sender는 cmdFunc로 전달된 InetSocketAddress)
- cmdFunc 입력: `(received, sender[, receivedTime])` - received는 바이트 값(int) 리스트 (combineBufferedData=false면 패킷별 리스트의 리스트), sender는 InetSocketAddress
  - received의 바이트 값은 Java byte 그대로 **signed(-128~127)** 이다. 0x80 이상 바이트가 올 수 있는 바이너리 데이터는 `bytes(b & 0xFF for b in received)` 처럼 `& 0xFF` 마스킹 후 변환하고, 길이 필드 등 수치 계산에도 `received[i] & 0xFF`를 사용해야 한다 (`bytes(received)`는 음수 값에서 ValueError 발생)

#### 예시 1: endBytes 패킷 분리 + 주기 read 파싱 (tcp-client)
장비에 1초마다 `RD ENV\r\n`을 보내고, `TEMP=23.5;HUM=41.2\r\n` 형태의 응답을 태그로 분해한다.
``` json
[
  {
    "id": "tcp_sensor_1",
    "connectionUrl": "tcp-client://127.0.0.1:5000?endBytes=0x0D0A",
    "commands": [
      {
        "id": "read_env",
        "type": "READ_REQUEST",
        "periodGroup": 1000,
        "commandTimeout": 3000,
        "requestInfo": "RD ENV\\x0D\\x0A",
        "cmdScript": "<script:read_env>"
      }
    ]
  }
]
```
`read_env` 스크립트:
``` python
def cmdFunc(received, sender, receivedTime):
    # received: endBytes(0x0D0A) 기준으로 분리된 바이트(int) 리스트 (구분 바이트는 제외됨)
    text = bytes(received).decode("ascii")        # 예: "TEMP=23.5;HUM=41.2"
    ret = []
    for field in text.split(";"):
        tag, value = field.split("=")
        ret.append((tag, value, receivedTime))
    return ret
```

#### 예시 2: bufferingFunc로 가변 길이 프레임 조립 (tcp-client)
프레임 형식이 `[0x02(STX), LEN, payload(LEN bytes), CHECKSUM]`처럼 길이 필드 기반이라 endBytes로 나눌 수 없는 경우, protocolScript의 `bufferingFunc`로 조립을 제어한다.
``` json
[
  {
    "id": "tcp_framed_1",
    "connectionUrl": "tcp-client://127.0.0.1:5001",
    "protocolScript": "<script:framed_protocol>",
    "commands": [
      {
        "id": "read_frame",
        "type": "READ_REQUEST",
        "periodGroup": 2000,
        "requestInfo": "\\x02\\x01\\x52\\x53",
        "cmdScript": "<script:read_frame>"
      }
    ]
  }
]
```
`framed_protocol` 스크립트:
``` python
def bufferingFunc(buffer):
    # buffer: 지금까지 수신한 패킷(바이트 int 리스트)의 리스트
    data = [b for packet in buffer for b in packet]
    if len(data) < 2:
        return False              # 길이 필드 미수신 -> 계속 버퍼링
    frameLen = (data[1] & 0xFF) + 3   # STX + LEN + payload + CHECKSUM (수신 바이트는 signed이므로 & 0xFF)
    if len(data) < frameLen:
        return False              # 프레임 미완성 -> 계속 버퍼링
    if len(data) == frameLen:
        return True               # 조립 완료 -> 명령으로 전달
    return data[frameLen:]        # 조립 완료 + 초과 수신분은 다음 조립의 시작으로 재투입
```
`read_frame` 스크립트:
``` python
def cmdFunc(received, sender, receivedTime):
    # bufferingFunc가 리스트를 반환한 경우 이번 received에 초과분이 포함될 수 있으므로 프레임 길이만큼만 사용
    length = received[1] & 0xFF
    payload = received[2:2 + length]
    return [("payload", bytes(b & 0xFF for b in payload).hex(), receivedTime)]
```
- bufferingFunc 반환: `True` 조립 완료 / `False` 계속 버퍼링 / `list` 조립 완료 + 반환 바이트를 다음 조립으로 재투입 / `None` 버퍼 폐기

#### 예시 3: protocolFunc 라우팅 + write 명령 (tcp-server)
장비(클라이언트)가 자발적으로 보내는 데이터를 첫 바이트로 구분해, 이벤트 라인(`E...`)은 이벤트 처리 명령 + ACK 회신 명령으로, 상태 라인(`S...`)은 주기 read 명령의 응답으로 라우팅한다.
``` json
[
  {
    "id": "tcp_gw_1",
    "connectionUrl": "tcp-server://:6000?endBytes=0x0A",
    "protocolScript": "<script:gw_protocol>",
    "commands": [
      {
        "id": "read_status",
        "type": "READ_REQUEST",
        "periodGroup": 5000,
        "requestInfo": "{\"message\": \"STATUS\\n\"}",
        "cmdScript": "<script:read_status>"
      },
      { "id": "on_event", "type": "READ_REQUEST", "periodGroup": -1, "cmdScript": "<script:on_event>" },
      { "id": "send_ack", "type": "WRITE_REQUEST", "periodGroup": -1, "requestInfo": "ACK\\x0A" }
    ]
  }
]
```
`gw_protocol` 스크립트:
``` python
def protocolFunc(received, sender, receivedTime):
    kind = chr(received[0])
    if kind == 'E':
        return ["on_event", "send_ack"]   # 반환한 리스트 순서대로 non-periodic 명령 실행
    if kind == 'S':
        return "read_status"              # read_status의 read-request 응답으로 전달
    return None                           # 대기 중인 임의 read-request의 응답으로 전달
```
`read_status` / `on_event` 스크립트:
``` python
def cmdFunc(received, sender, receivedTime):
    return [("status", bytes(received[1:]).decode("ascii"), receivedTime)]
```
``` python
def cmdFunc(received, sender, receivedTime):
    return [("event", bytes(received[1:]).decode("ascii"), receivedTime)]
```
- tcp-server의 주기 명령은 접속된 클라이언트에 전송해야 하므로 requestInfo를 `{"message":...}` 형태(전체 전송) 또는 `{"message":..., "host":..., "port":...}` (특정 클라이언트)로 지정한다.
  non-periodic 명령(수신으로 트리거됨)의 문자열 requestInfo는 데이터를 보낸 클라이언트에게 회신된다 (`send_ack`).
- `{"message":...}` 형태의 requestInfo는 JSON으로 파싱되므로 제어 문자는 `\n` 같은 JSON 이스케이프로 넣거나, `\\xNN`처럼 이중 이스케이프하여 파싱 후 `\xNN`이 남게 한다.

#### 예시 4: udp-server multicast 수신 + 발신자에게 응답
``` json
[
  {
    "id": "udp_recv_1",
    "connectionUrl": "udp-server://:9000?multicastGroup=239.0.0.1",
    "commands": [
      { "id": "on_data", "type": "READ_REQUEST", "periodGroup": -1, "order": 0, "cmdScript": "<script:on_data>" },
      { "id": "reply", "type": "WRITE_REQUEST", "periodGroup": -1, "order": 1, "cmdScript": "<script:reply>" }
    ]
  }
]
```
`on_data` / `reply` 스크립트:
``` python
def cmdFunc(received, sender, receivedTime):
    return [("raw", bytes(received).decode("ascii"), receivedTime)]
```
``` python
def requestInfo(received, sender):
    # 데이터를 보낸 곳(sender)으로 ACK 전송, requestInfo 함수도 수신 인자를 받을 수 있다
    return protocol.requestInfo("ACK", sender)
```
- protocolFunc가 없으므로 데이터 수신 시 **모든 non-periodic 명령**이 order 순으로 실행된다 (`on_data` → `reply`).
- multicastGroup 옵션을 주면 모든 multicast 지원 인터페이스에서 해당 그룹에 join한다 (콤마 구분으로 복수 지정 가능).

### modbus-client / modbus-server
- 형식: `modbus-client://{host}:{port}`
- unitId: 기본 unit id (default: 1)
- combineData: (client) 여러 주소 읽기 결과를 하나의 리스트로 합칠지 여부 (default: true)
- 주소 규약: coil은 `isCoil=true`, 1xxxx: discrete input, 3xxxx: input register, 4xxxx: holding register
- requestInfo 형식 (read): `{"address":40001, "length":10, "unitId":1}` 또는 배열
- requestInfo 형식 (write): `{"address":1, "values":[1,2,3]}` (boolean 배열이면 coil write)
  - write 주소는 4xxxx 규약이 아닌 **1부터 시작하는 raw 주소** (1 → 첫 번째 holding register/coil, 즉 read 주소 40001/1에 대응)
- helper: `protocol.requestInfo(address, length[, unitId][, isCoil])`, `protocol.requestInfo(address, values[, unitId])`
- modbus-server: 수신 요청이 non-periodic 명령으로 전달됨. 입력: `(address, quantity|values, unitId[, receivedTime])`.
  register/coil 값은 shared data에 저장되며 `protocol.read(address, length, unitId[, isCoil])` / `protocol.write(address, values, unitId[, isCoil])`로 접근

#### 예시 (modbus-client): 주기 read + 태그 매핑 + write
holding register 40001~40004를 1초 주기로 읽어 태그로 매핑하고, REST로 트리거하는 write 명령을 함께 정의한다.
``` json
[
  {
    "id": "modbus_plc_1",
    "connectionUrl": "modbus-client://127.0.0.1:502?unitId=1",
    "commands": [
      {
        "id": "read_holding",
        "type": "READ_REQUEST",
        "periodGroup": 1000,
        "requestInfo": "{\"address\":40001, \"length\":4}",
        "cmdScript": "<script:read_holding>"
      },
      {
        "id": "write_speed",
        "type": "WRITE_REQUEST",
        "periodGroup": -1,
        "cmdScript": "<script:write_speed>"
      },
      {
        "id": "write_coil",
        "type": "WRITE_REQUEST",
        "periodGroup": -1,
        "requestInfo": "{\"address\":10, \"values\":[true, false, true]}"
      }
    ]
  }
]
```
`read_holding` 스크립트 (읽기 결과 리스트를 태그로 매핑):
``` python
TAGS = ["temp", "pressure", "speed", "status"]

def cmdFunc(values, receivedTime):
    # values: 40001~40004 값 리스트 (combineData=true 기본이므로 하나의 리스트)
    return [(TAGS[i], str(values[i]), receivedTime) for i in range(len(TAGS))]
```
`write_speed` 스크립트 (REST `POST /driver/execute-command-ids/modbus_plc_1` + `initial-value` header `{"speed": 1500}` + body `["write_speed"]`로 실행):
``` python
def requestInfo(initialValue):
    # write 주소는 1부터 시작하는 raw 주소: 3 = 세 번째 holding register (read 주소 40003에 대응)
    return protocol.requestInfo(3, [int(initialValue["speed"])])
```
- read 주소 규약: `40001~`/`400001~` holding register, `30001~`/`300001~` input register, `10001~`/`100001~` discrete input, coil은 `isCoil=true` + 1부터 시작하는 주소
- write는 `values`가 정수 배열이면 register write(주소 1~65536 raw), boolean 배열이면 coil write로 **값 타입으로 판별**된다 (`write_coil` 참고, isCoil 필드 불필요)
- `combineData=false` 옵션을 주면 requestInfo가 배열(여러 주소 블록)일 때 cmdFunc 입력이 **블록별 리스트의 리스트**로 전달된다:
``` python
# requestInfo: [{"address":40001,"length":2}, {"address":30001,"length":3}]
def cmdFunc(values, receivedTime):
    holding, inputReg = values[0], values[1]   # combineData=false
    ...
```

#### 예시 (modbus-server): 클라이언트 write 수신 처리 + 레지스터 값 관리
디바이스가 modbus TCP slave로 동작한다. 접속(bind) 시 초기 레지스터를 채우고, 클라이언트가 write한 값을 Response로 출력한다.
``` json
[
  {
    "id": "modbus_srv_1",
    "connectionUrl": "modbus-server://:1502",
    "commands": [
      { "id": "init_regs", "type": "STARTING_REQUEST", "periodGroup": 0, "cmdScript": "<script:init_regs>" },
      { "id": "on_client", "type": "READ_REQUEST", "periodGroup": -1, "cmdScript": "<script:on_client>" }
    ]
  }
]
```
`init_regs` 스크립트 (protocol.write/read로 레지스터 값 관리):
``` python
def cmdFunc():
    protocol.write(40001, [0] * 10, 1)          # holding register 40001~40010 = 0 (unitId 1)
    protocol.write(1, [False] * 8, 1, True)     # coil 1~8 = False
    current = protocol.read(40001, 10, 1)       # 값 조회
    log.info("initial registers: " + str(list(current)))
    return None
```
`on_client` 스크립트 (non-periodic - 클라이언트 read/write 요청마다 실행):
``` python
def cmdFunc(address, values, unitId, receivedTime):
    if isinstance(values, int):
        # 클라이언트 read 요청: values는 quantity(int).
        # 이 시점에 protocol.write로 값을 갱신하면 갱신된 값이 응답된다 (응답은 non-periodic 명령 실행 후 shared data 기준으로 생성됨)
        return None
    # 클라이언트 write 요청: values는 쓴 값 리스트 (레지스터: int, coil: bool)
    return [("REG_%d" % (address + i), str(v), receivedTime) for i, v in enumerate(values)]
```
- 레지스터/coil 값은 디바이스 shared data(`{unitId: {address: value}}`)에 저장되므로 클러스터 노드 간에 공유된다.
- **주의**: 수신 트리거 non-periodic 실행 대상은 명령 타입과 무관하게 `periodGroup < 0`(기본값 -1)인 **모든** 명령이다.
  `init_regs`처럼 STARTING_*/STOPPING_* 명령에 periodGroup을 지정하지 않으면 접속 시 1회뿐 아니라 **클라이언트 read/write 요청마다** 함께 재실행되어 레지스터가 매번 초기값으로 리셋된다.
  server류의 STARTING_*/STOPPING_* 명령에는 위 예시처럼 `"periodGroup": 0` 이상을 지정하라
  (STARTING_*/STOPPING_* 타입은 주기 스케줄 대상에서 타입으로 제외되므로 periodGroup ≥ 0이어도 주기 실행되지 않는다).

### http-client / http-server
- 형식: `http-client://{baseUrl}`, `http-server://{host}:{port}` (server의 host 생략 가능)
- cert / key / format / password: SSL 인증서 (PEM: cert+key, 그 외: cert+format(default PKCS12)+password)
- trustCert / trustFormat / trustPassword: mTLS용 trust 인증서 (server는 client 인증 요구)
- useByteArrayBody: body를 문자열 대신 바이트 리스트로 전달 (default: false)
- http-client requestInfo 형식: `{"method":"GET", "path":"/api", "basePath":"...", "body":"...", "params":{...}, "headers":{...}, "proxy":{...}}`
  - helper: `protocol.requestInfo(method, path, basePath, body, params[, proxyType, proxyHost, proxyPort, proxyUsername, proxyPassword], *headers)`
    - **주의**: `*headers`를 실제로 넘기면(총 인자 7개 이상) 오버로드 모호성으로 런타임 TypeError(`Multiple applicable overloads found`)가 발생한다. 헤더가 필요하면 helper 대신 requestInfo JSON 문자열을 직접 구성해 반환하라 (아래 `post_control` 예시 참고)
  - cmdFunc 입력: `(statusCode, body, headers[, receivedTime])`
- http-server: 수신 요청이 non-periodic 명령으로 전달됨. 입력: `(method, path, body, params, headers[, receivedTime])`
  - 응답 지정: write-request의 requestInfo로 `protocol.requestInfo(statusCode, body, *headers)` 반환 (미지정 시 200 OK)

#### 예시 (http-client): GET 주기 폴링 + POST 전송
REST API를 5초 주기로 GET 폴링하여 JSON 응답을 파싱하고, REST로 트리거되는 POST 명령을 정의한다.
http-client의 모든 명령은 응답을 받는 READ_REQUEST여야 한다 (write-request 미지원, POST도 READ_REQUEST + cmdFunc로 상태 확인).
``` json
[
  {
    "id": "http_api_1",
    "connectionUrl": "http-client://http://127.0.0.1:8080",
    "commands": [
      {
        "id": "poll_sensors",
        "type": "READ_REQUEST",
        "periodGroup": 5000,
        "requestInfo": "{\"method\":\"GET\", \"path\":\"/api/sensors\", \"params\":{\"site\":[\"A1\"]}}",
        "cmdScript": "<script:poll_sensors>"
      },
      {
        "id": "post_control",
        "type": "READ_REQUEST",
        "periodGroup": -1,
        "cmdScript": "<script:post_control>"
      }
    ]
  }
]
```
`poll_sensors` 스크립트 (cmdFunc 입력: `(statusCode, body, headers[, receivedTime])`):
``` python
def cmdFunc(statusCode, body, headers, receivedTime):
    if statusCode != 200:
        raise Exception("polling failed, statusCode=%d" % statusCode)
    # body: JSON이면 파싱된 객체(dict/list), 아니면 문자열 (useByteArrayBody=true면 바이트 리스트)
    # headers: {headerName: [value, ...]} dict
    return [("temperature", str(body["temperature"]), receivedTime),
            ("humidity", str(body["humidity"]), receivedTime)]
```
`post_control` 스크립트 (REST `POST /driver/request-command-ids/http_api_1` + `initial-value` header `{"fan":"on"}` + body `["post_control"]`로 실행,
헤더가 필요하므로 helper 대신 requestInfo JSON을 직접 구성한다 — helper에 헤더 인자를 넘기면 오버로드 모호성 TypeError 발생):
``` python
import json

def requestInfo(initialValue):
    return json.dumps({"method": "POST", "path": "/api/control",
                       "body": json.dumps(initialValue),
                       "headers": {"Content-Type": ["application/json"]}})

def cmdFunc(initialValue, statusCode, body, headers):
    # initial-value header와 함께 실행되면 initialValue가 항상 첫 번째 인자로 채워진다
    if statusCode >= 300:
        raise Exception("control failed, statusCode=%d" % statusCode)
    return None
```
- connectionUrl의 `{baseUrl}`은 scheme을 포함한다 (`http-client://http://127.0.0.1:8080`, https/인증서 사용 시 `http-client://https://...?cert=...`)
- requestInfo의 `basePath`를 지정하면 connectionUrl의 baseUrl 대신 사용된다. `proxy`(`{"type":"HTTP","host":...,"port":...}`)도 지정 가능

#### 예시 (http-server): 수신 요청 처리 + 응답 반환
디바이스가 HTTP 서버로 동작하며, 수신 요청마다 non-periodic 명령이 order 순으로 실행된다 (`handle_req` → `send_resp`).
``` json
[
  {
    "id": "http_srv_1",
    "connectionUrl": "http-server://:8081",
    "commands": [
      { "id": "handle_req", "type": "READ_REQUEST", "periodGroup": -1, "order": 0, "cmdScript": "<script:handle_req>" },
      { "id": "send_resp", "type": "WRITE_REQUEST", "periodGroup": -1, "order": 1, "cmdScript": "<script:send_resp>" }
    ]
  }
]
```
`handle_req` 스크립트 (cmdFunc 입력: `(method, path, body, params, headers[, receivedTime])`):
``` python
def cmdFunc(method, path, body, params, headers, receivedTime):
    if method == "POST" and path == "/data":
        # body: JSON이면 파싱된 dict/list, params: {name: [value, ...]} dict
        return [(str(k), str(v), receivedTime) for k, v in body.items()]
    return None
```
`send_resp` 스크립트 (write-request의 requestInfo가 HTTP 응답이 된다, 마지막 write-request의 값 사용):
``` python
import json

def requestInfo(method, path, body, params, headers):
    if method == "POST" and path == "/data":
        return protocol.requestInfo(200, json.dumps({"result": "ok"}),
                                    "Content-Type", "application/json")
    return protocol.requestInfo(404, "not found")
```
- 응답용 requestInfo JSON 형식: `{"httpStatusCode":200, "body":"...", "headers":{"name":["value"]}}` (write-request 명령이 없거나 requestInfo가 없으면 200 OK로 응답)
- protocolScript의 `protocolFunc(method, path, body, params, headers[, receivedTime])`를 정의하면 반환한 cmdId 리스트의 명령만 실행되도록 라우팅할 수 있다 (path별 처리 분기 등)

### opcua-client / opcua-server
- 형식: `opcua-client://{host}:{port}[/{path}]` (→ `opc.tcp://...`), `opcua-server://{host}:{port}[/{path}]` (server의 host 생략 가능)
- opcua-client 옵션
  - securityPolicy: None | Basic128Rsa15 | Basic256 | Basic256Sha256 | Aes128_Sha256_RsaOaep | Aes256_Sha256_RsaPss (default: None)
  - securityMode: Sign | SignAndEncrypt (securityPolicy != None일 때, default: SignAndEncrypt) - client 인증서는 self-signed로 자동 생성
  - username / password: 인증 정보 (미지정 시 anonymous)
  - subscriptionNodeIds: 접속 시 자동 구독할 nodeId 목록 (콤마 구분)
  - publishingInterval: 구독 publishing 주기 [ms] (default: 1000)
- opcua-client requestInfo 형식
  - read: nodeId 문자열 배열 `["ns=2;s=Tag1", "ns=0;i=2258"]` (helper: `protocol.requestInfo("ns=2;s=Tag1", ...)`)
    - **주의**: helper를 nodeId **1개**로 호출하면 read용(String varargs)이 아니라 write용 `requestInfo(writeNodes)` 오버로드로 디스패치되어 오동작한다. 단일 nodeId read는 helper 대신 JSON 문자열 `'["ns=2;s=Tag1"]'`을 직접 반환하라 (nodeId 2개 이상이면 정상)
  - write: `{"ns=2;s=Tag1": 42}` 또는 타입 지정 시 `[{"nodeId":"ns=2;s=Tag1", "value":42, "type":"Int16"}]`
    (type: Boolean/SByte/Byte/Int16/UInt16/Int32/UInt32/Int64/UInt64/Float/Double/String/DateTime, 미지정 시 JSON 타입 그대로)
  - cmdFunc 입력 (read): `(received, receivedTime)` - received는 `[[nodeId, value], ...]`
  - 구독 데이터 변경 시 non-periodic 명령 실행, 입력: `(nodeId, value[, receivedTime])`
  - `protocol.subscribe([nodeIds])`: 스크립트에서 동적 구독 추가
- opcua-server 옵션
  - namespaceUri: namespace URI (default: `urn:sds:communicators:{deviceId}`)
  - username / password: 지정 시 username 인증 요구 (anonymous=true 옵션으로 익명 병행 허용)
- opcua-server 동작
  - `{deviceId}` 폴더 아래에 variable node 생성 (nodeId: `ns=2;s={deviceId}/{name}`)
  - Device.data의 key/value로 초기 node 자동 생성
  - `protocol.write(name, value)`: node 생성/갱신 (스크립트), `protocol.read(name)`: node 값 조회
  - OPC UA client가 node를 write하면 non-periodic 명령 실행, 입력: `(name, value[, receivedTime])`
  - write-request의 requestInfo `{"name": value, ...}`로도 node 갱신 가능 (read-request 미지원)

#### 예시 (opcua-client): read + subscription
1초 주기 read와 접속 시 자동 구독(subscriptionNodeIds)을 함께 사용한다. 구독 노드 값이 변하면 non-periodic 명령이 `(nodeId, value[, receivedTime])` 입력으로 실행된다.
``` json
[
  {
    "id": "opcua_cli_1",
    "connectionUrl": "opcua-client://127.0.0.1:4840/server?securityPolicy=None&subscriptionNodeIds=ns=2;s=PLC1/alarm&publishingInterval=500",
    "commands": [
      {
        "id": "read_tags",
        "type": "READ_REQUEST",
        "periodGroup": 1000,
        "requestInfo": "[\"ns=2;s=PLC1/temp\", \"ns=0;i=2258\"]",
        "cmdScript": "<script:read_tags>"
      },
      { "id": "on_change", "type": "READ_REQUEST", "periodGroup": -1, "cmdScript": "<script:on_change>" }
    ]
  }
]
```
`read_tags` 스크립트 (cmdFunc 입력: `(received[, receivedTime])`, received는 `[[nodeId, value], ...]`):
``` python
def cmdFunc(received, receivedTime):
    ret = []
    for nodeId, value in received:
        tag = nodeId.split("/")[-1] if "/" in nodeId else nodeId
        ret.append((tag, str(value), receivedTime))
    return ret
```
`on_change` 스크립트 (구독 데이터 변경 시 실행):
``` python
def cmdFunc(nodeId, value, receivedTime):
    return [(nodeId, str(value), receivedTime)]
```
- 스크립트에서 `protocol.subscribe(["ns=2;s=PLC1/hum"])`로 동적 구독 추가 가능 (예: STARTING_REQUEST 명령에서 호출)

#### 예시 (opcua-client): write (타입 강제 포함)
구독이 없는 디바이스에 REST 트리거용 write 명령만 정의한 예. 서버 노드의 데이터 타입이 정확해야 할 때 `type`으로 강제한다.
``` json
[
  {
    "id": "opcua_wr_1",
    "connectionUrl": "opcua-client://127.0.0.1:4840/server",
    "commands": [
      {
        "id": "write_setpoint",
        "type": "WRITE_REQUEST",
        "periodGroup": -1,
        "requestInfo": "{\"ns=2;s=PLC1/setpoint\": 42.5}"
      },
      {
        "id": "write_speed_typed",
        "type": "WRITE_REQUEST",
        "periodGroup": -1,
        "requestInfo": "[{\"nodeId\":\"ns=2;s=PLC1/speed\", \"value\":1500, \"type\":\"Int16\"}]"
      }
    ]
  }
]
```
- `POST /driver/execute-command-ids/opcua_wr_1` body `["write_setpoint"]` 형태로 실행
- `{"nodeId": value}` 형태는 JSON 타입 그대로(정수 → Int64/Int32, 실수 → Double, 문자열 → String) 변환되므로, Int16/UInt32 등이 필요하면 배열 + `type` 형태 사용

#### 예시 (opcua-server): 초기 노드 + 노드 갱신 + 클라이언트 write 수신
`Device.data`의 key/value로 초기 variable node가 생성되고(`ns=2;s={deviceId}/{name}`), 주기 명령이 protocol.write/read로 노드를 갱신하며, OPC UA client가 node를 write하면 non-periodic 명령이 실행된다.
``` json
[
  {
    "id": "opcua_srv_1",
    "connectionUrl": "opcua-server://:4840/driver?username=user1&password=pw1&anonymous=true",
    "data": { "temp": 0.0, "hum": 0.0, "mode": "AUTO" },
    "commands": [
      { "id": "update_nodes", "type": "REQUEST", "periodGroup": 1000, "cmdScript": "<script:update_nodes>" },
      { "id": "on_write", "type": "READ_REQUEST", "periodGroup": -1, "cmdScript": "<script:on_write>" },
      {
        "id": "init_status",
        "type": "STARTING_WRITE_REQUEST",
        "periodGroup": 0,
        "requestInfo": "{\"status\": \"READY\"}"
      }
    ]
  }
]
```
`update_nodes` 스크립트 (protocol.write/read로 노드 값 관리, 없는 노드는 생성됨):
``` python
import random

def cmdFunc(receivedTime):
    protocol.write("temp", round(random.uniform(20.0, 30.0), 2))
    protocol.write("hum", round(random.uniform(30.0, 60.0), 2))
    mode = protocol.read("mode")
    return [("mode", str(mode), receivedTime)]
```
`on_write` 스크립트 (OPC UA client가 node write 시 실행, 입력: `(name, value[, receivedTime])`):
``` python
def cmdFunc(name, value, receivedTime):
    log.info("node written by client: " + name + " = " + str(value))
    return [("written_" + name, str(value), receivedTime)]
```
- `init_status`처럼 write-request의 requestInfo `{"name": value, ...}`로도 노드를 생성/갱신할 수 있다.
  단 **STARTING_* 타입을 포함해** periodGroup이 음수(기본값 -1)인 모든 write-request는 클라이언트 write 수신 시마다 함께 실행되므로,
  초기화 용도 명령은 위 예시처럼 `"periodGroup": 0` 이상을 지정해야 한다 (STARTING_*는 타입으로 주기 스케줄에서 제외되므로 주기 실행되지 않음).
  REST 트리거 전용 write는 별도 디바이스로 분리 권장.

### dummy
- 형식: `dummy://` - 실제 접속 없이 스크립트만 실행 (테스트 용도)

#### 예시 (dummy): 주기 REQUEST로 스크립트만 실행
접속 없이 데이터 생성/가공 용도로 사용한다. REQUEST 타입은 전송/수신 없이 cmdFunc만 실행하며, 인자 풀은 `(initialValue?, receivedTime)`이다.
``` json
[
  {
    "id": "dummy_gen_1",
    "connectionUrl": "dummy://",
    "data": { "amplitude": 10.0 },
    "commands": [
      { "id": "gen_data", "type": "REQUEST", "periodGroup": 1000, "cmdScript": "<script:gen_data>" }
    ]
  }
]
```
`gen_data` 스크립트:
``` python
import math, time

count = 0                                     # 전역 변수는 디바이스(Python Context) 단위로 유지된다

def cmdFunc(receivedTime):
    global count
    count += 1
    amp = protocol.getData(["amplitude"])     # Device.data 값 조회
    value = amp * math.sin(count / 10.0)
    # 다른 디바이스의 수집값을 가공하는 용도로도 사용 가능:
    #   resp = protocol.getResponse("tcp_sensor_1")   # {tagId: Response} map
    #   if resp and "TEMP" in resp: ...
    return [("sine", "%.3f" % value, receivedTime),
            ("count", str(count), receivedTime)]
```
---
## 스크립트 작성 (Python 3 / GraalPy)
- 스크립트 엔진은 GraalPy (**Python 3**) 사용
- 디바이스마다 독립된 Python Context에서 실행: `defaultScript` → 각 `cmdScript` → `protocolScript` 순서로 로드
  (protocolScript에서 정의한 이름을 cmdScript의 **모듈 레벨 코드**에서 바로 참조하면 NameError — 함수 본문 안에서의 참조는 호출 시점에 해석되므로 무방)
- 스크립트 전역 객체
  - `log`: SLF4J logger (`log.info("...")`)
  - `protocol`: 해당 디바이스의 DriverProtocol 객체 (아래 API 참고)
  - `UtilFunc`: 공용 유틸 (`com.sds.communicators.common.UtilFunc`)
  - `java`: Java 클래스 접근 (`from java.util import ArrayList` 형태 가능)

### cmdScript 함수 (모두 선택 정의)
``` python
def cmdFunc(received, receivedTime):        # 수신 데이터 파싱, [(tagId, value), (tagId, value, time), ...] 또는 None 반환
    return [("temperature", str(received[0]))]

def requestInfo():                          # requestInfo 동적 생성, str 또는 None(Command.requestInfo 사용) 반환
    return protocol.requestInfo(40001, 10)

def delay():                                # 명령 후 지연[ms] 반환, None이면 Command.afterDelay 사용
    return 1000

def control(commandList, idx, exception):   # 명령 흐름 제어 (2개 또는 3개 인자)
    return None                             # None: 다음 명령, int: 해당 index로 이동, Exception: 예외 발생
```
- 함수 인자는 앞에서부터 선택적으로 선언 가능 (인자 개수에 맞춰 전달됨)
- execute/request-commands REST API에 `initial-value` header를 주면 첫 번째 인자로 전달됨 (JSON이면 파싱된 객체)

### 인자 채움 규칙 (공통)
`cmdFunc`/`requestInfo` 함수 호출 시 실행 상황에 따라 아래 순서의 **인자 풀**이 만들어지고, 함수가 선언한 인자 개수만큼 풀의 앞에서부터 채워진다.

| 풀 순서 | 값 | 포함 조건 |
|---|---|---|
| 1 | initialValue | execute/request-commands(-ids) REST 호출의 `initial-value` header, 또는 스크립트 `protocol.executeCommands(deviceId, initialValue, commands)`의 initialValue가 있을 때 |
| 2 ~ | received... (프로토콜 별 수신 인자, 아래 표) | 수신 데이터로 실행될 때 (read-request 응답 파싱, non-periodic 명령) |
| 마지막 | receivedTime (epoch ms) | 수신 데이터로 실행될 때와 REQUEST 타입 실행 시 (주기 read/write의 requestInfo·명령 시작 시점에는 없음) |

- 함수 인자 개수가 풀 크기보다 **크면 오류** (`invalid function, function arguments count: ...`)
- receivedTime은 함수 인자 개수가 풀 전체 크기와 **같을 때만** 마지막 인자로 전달된다 - 즉 인자는 뒤에서부터 생략된다
- initialValue가 풀에 있으면 **항상 첫 번째 인자**로 채워진다 (initial-value와 함께 실행될 명령의 함수는 첫 인자를 initialValue로 선언해야 함)

프로토콜 별 수신 인자 (received...):

| 실행 상황 | 수신 인자 |
|---|---|
| tcp/udp 수신 | `received`(바이트 int 리스트, combineBufferedData=false면 패킷별 리스트의 리스트), `sender`(InetSocketAddress) |
| modbus-client read 응답 | `values`(레지스터/코일 값 리스트, combineData=false면 주소 블록별 리스트의 리스트) |
| modbus-server 클라이언트 요청 | `address`(int), `quantity`(int, read 요청) 또는 `values`(리스트, write 요청), `unitId`(int) |
| http-client 응답 | `statusCode`(int), `body`(JSON 파싱 객체 또는 str), `headers`(dict[str, list[str]]) |
| http-server 수신 요청 | `method`(str), `path`(str), `body`(JSON 파싱 객체 또는 str), `params`(dict[str, list[str]]), `headers`(dict[str, list[str]]) |
| opcua-client read 응답 | `received` = `[[nodeId, value], ...]` |
| opcua-client 구독 데이터 변경 | `nodeId`(str), `value` |
| opcua-server 클라이언트 write | `name`(str), `value` |
| REQUEST 타입 / dummy | (없음) |

예 - http-server 수신 요청 처리 (풀 = `method, path, body, params, headers, receivedTime` 6개):
``` python
def cmdFunc(method, path): ...                                     # 앞 2개만 사용
def cmdFunc(method, path, body, params, headers): ...              # receivedTime 없이
def cmdFunc(method, path, body, params, headers, receivedTime): ...  # 전체
```

### 반환 규약
| 함수 | 반환 | 처리 |
|---|---|---|
| cmdFunc | `[(tagId, value), (tagId, value, time), ...]` | Response로 출력. value는 str로 변환, time은 epoch ms(int), 2-tuple이면 receivedTime 사용 |
| cmdFunc | `None` 또는 빈 리스트 | 출력 없음 |
| cmdFunc | 그 외 타입 | 오류 (ScriptException) |
| requestInfo | str | 해당 문자열을 requestInfo로 사용 |
| requestInfo | None | `Command.requestInfo` 사용, 그것도 비어있으면 해당 명령 skip |
| delay | int | 명령 후 지연 [ms] |
| delay | None | `Command.afterDelay` 사용 |
| control | None | 다음 명령으로 진행 |
| control | int | 해당 index의 명령으로 이동 (음수/리스트 크기 이상이면 명령 시퀀스 종료) |
| control | Exception 객체 | 해당 예외 발생 |

- control 함수는 인자를 정확히 2개(`commandList, idx`) 또는 3개(`commandList, idx, exception`)로 선언해야 한다.
  `commandList`는 실행 대상 cmdId 리스트, `idx`는 현재 index, `exception`은 직전 명령 실행 중 발생한 예외(정상 시 None).
- control이 정의된 명령은 실행 중 예외가 발생해도 control 반환값으로 흐름을 이어갈 수 있다 (재시도/건너뛰기 구현 용도).
``` python
def control(commandList, idx, exception):
    if exception is not None:
        log.warn("command failed: " + str(exception))
        return idx          # 같은 명령 재시도
    return None             # 정상이면 다음 명령
```
- 명령 실행 중 예외 발생 시 `connectionLostOnException=true`(client류 기본값)이면 connection-lost로 전이되어 재접속한다 (스크립트 반환 형식 오류 등 ScriptException은 제외)

### initial-value header 사용 예
```
POST /driver/execute-command-ids/{deviceId}
initial-value: {"speed": 1500}        <- JSON이면 파싱된 객체(dict/list), 아니면 문자열로 전달
body: ["write_speed"]
```
``` python
def requestInfo(initialValue):        # 인자 풀 = (initialValue,) - 수신 실행이 아니므로 receivedTime 없음
    return protocol.requestInfo(3, [int(initialValue["speed"])])
```

### 전역 객체 사용 예
``` python
log.info("device script loaded")                   # SLF4J logger (ScriptLogger 카테고리로 출력)

protocol.setData({"threshold": 42})                # 디바이스 shared data 저장 (클러스터 공유)
threshold = protocol.getData(["threshold"])        # 조회 (Device.data 초기값 포함)

arr = UtilFunc.stringToByteArray("AB\\x0D\\x0A")   # 공용 유틸 (com.sds.communicators.common.UtilFunc)

from java.util import ArrayList                    # java 패키지 접근
from java.lang import System
now = System.currentTimeMillis()
```

### protocolScript 함수
``` python
def protocolFunc(received, receivedTime):   # (tcp/udp/http-server) 수신 데이터 라우팅
    return None            # None: read-request 응답으로 전달
                            # str: 해당 cmdId의 read-request 응답으로 전달
                            # list/tuple: 해당 cmdId 목록의 non-periodic 명령 실행

def bufferingFunc(buffer):                  # (tcp/udp) 패킷 조립 제어, buffer는 수신 패킷(리스트)의 리스트
    return True             # True: 조립 완료 → 처리, False: 계속 버퍼링
                            # list: 조립 완료 + 나머지 바이트 반환, None: 버퍼 폐기
```
- protocolFunc의 인자도 "인자 채움 규칙"을 따른다 (initialValue 없이 수신 인자 + receivedTime).
  tcp/udp는 `(received, sender[, receivedTime])`, http-server는 `(method, path, body, params, headers[, receivedTime])`
- http-server의 protocolFunc는 list/tuple(cmdId 목록) 반환만 지원 (None/str 라우팅은 tcp/udp 전용)

### protocol 객체 주요 API
``` python
protocol.setData({"key": value})                # 디바이스 shared data 병합
protocol.getData(["key", "subKey"])             # 데이터 조회
protocol.deleteData(["key"])                    # 데이터 삭제
protocol.getResponse()                          # 수집 데이터 조회 (전체/디바이스/노드별 overload)
protocol.getDeviceStatus()                      # 디바이스 상태 조회
protocol.executeCommands(deviceId, initialValue, commands)   # 다른 디바이스 명령 실행
protocol.setConnectionLost()                    # 강제 connection-lost
protocol.setDisconnected()                      # 강제 disconnect
protocol.getDeviceIdMap()                       # 노드별 디바이스 목록
protocol.getClusterNodes()                      # 클러스터 노드 목록
```
---
## REST API (base: driverBasePath, default "/driver")
| Method | Path | 설명 |
|---|---|---|
| GET | / | Web UI |
| POST | /balanced-connect-all | 디바이스 연결 (로드밸런싱, body: Device 배열) |
| POST | /connect-all | 현재 노드에 디바이스 연결 (LEADER 경유) |
| DELETE | /disconnect | 디바이스 연결 해제 (body: deviceId 배열) |
| DELETE | /disconnect-all | 전체 연결 해제 |
| PUT | /reconnect-all | 전체 재연결 |
| GET | /device-status | 전체 디바이스 상태 |
| GET | /device-status/{deviceId} | 디바이스 상태 |
| GET | /device-id-map | 노드별 디바이스 목록 |
| GET | /response | 전체 수집 데이터 |
| GET | /response/{deviceId} | 디바이스 수집 데이터 |
| POST | /execute-commands/{deviceId} | 명령 실행 + 응답 출력 (body: Command 배열, header: initial-value) |
| POST | /request-commands/{deviceId} | 명령 실행, 응답 미출력 (결과만 반환) |
| POST | /execute-command-ids/{deviceId} | 등록된 명령 실행 + 응답 출력 (body: cmdId 배열) |
| POST | /request-command-ids/{deviceId} | 등록된 명령 실행, 응답 미출력 |
- 노드 간 연결 위임(FOLLOWER→LEADER 위임, LEADER→노드별 분배)은 REST가 아닌 gRPC(`driver.DriverInternal` 서비스, cluster gRPC 서버 포트 `serverPort + grpcPortOffset` 공유)로 수행된다
- cluster REST API(`/cluster/*`, `/redirect-to-leader/*`, `/redirect-to-index/*`)는 [cluster.md](cluster.md) 참고
---
## Web UI

`http://{host}:{port}{driverBasePath}/` (기본 `/driver/`)로 접속하면 React 기반 관리 화면이 열린다.
어느 노드에 접속해도 클러스터 전체를 조회/관리할 수 있다 (노드별 조회는 내부적으로 `/redirect-to-index/{n}/...` 경유).
Devices / Nodes / Responses 3개 탭으로 구성된다.

### Devices 탭 (연결/해제 관리)
- **디바이스 테이블**: 클러스터 전체 디바이스를 deviceId / 소속 노드(nodeIndex) / 상태 / 액션 컬럼으로 표시
  - `GET /driver/device-id-map`으로 노드별 디바이스 목록을 얻고, 노드마다 `GET /redirect-to-index/{n}/driver/device-status`로 상태를 조회 (노드 응답 불가 시 `NODE_UNREACHABLE` 표시)
  - 행별 **disconnect** 버튼 (`DELETE /driver/disconnect`, body: `["deviceId"]`), 상단 **disconnect-all** / **reconnect-all** 버튼
- **디바이스 추가(connect)**: Device JSON 배열을 textarea에 입력 후 connect (`POST /driver/balanced-connect-all`).
  결과는 디바이스별 성공/실패 표로 표시 (예: `dummy1 → connected`)
- **파일 가져오기 (import file)**: JSON 파일(Device 배열)을 읽어 textarea에 로드한 뒤 connect 가능
- **파일 내보내기 (export file)**: 클러스터에 등록된 전체 디바이스 설정을 `devices.json`으로 다운로드.
  데이터 소스는 `GET /driver/devices` — shared-object에 저장된 디바이스 설정(map 중 `id`/`connectionUrl` 키를 가진 항목)을 JSON 배열로 반환하며,
  내보낸 파일을 그대로 가져오기 → connect에 사용할 수 있다 (round-trip 호환)
- 자동 새로고침(5초) 토글

### Nodes 탭 (노드 상태/관리)
- **노드 테이블**: `GET /cluster/get-cluster-nodes`의 nodeIndex 목록 기준으로,
  노드별 `GET /redirect-to-index/{n}/cluster/node-status`의 position(LEADER 강조 표시)/activated와 디바이스 수(device-id-map 기준)를 표시
- 노드별 액션: **set-to-leader** / **set-to-follower** (`PUT /redirect-to-index/{n}/cluster/set-to-leader` 등)
- 상단 요약: leader-url (`GET /cluster/leader-url`), 접속 중인 노드의 node-status
- 자동 새로고침(5초) 토글

### Responses 탭 (수집 데이터)
- `GET /driver/response` 결과를 deviceId / tagId / value / receivedTime 테이블로 표시 (receivedTime은 epoch ms → 로컬 시간 변환)
- 디바이스 필터 드롭다운, 자동 새로고침(5초) 토글

### 파일 형식 (가져오기/내보내기)
Device JSON 배열 — `POST /driver/balanced-connect-all` body와 동일한 형식:
``` json
[
  {
    "id": "device1",
    "connectionUrl": "dummy://",
    "commands": []
  }
]
```

### 개발 방법
- 소스: `driver-starter/ui` (React 19 + Vite + axios, 외부 UI 라이브러리 없음)
  - `src/App.tsx`(탭 셸), `src/DevicesTab.tsx` / `src/NodesTab.tsx` / `src/ResponsesTab.tsx`(탭별 컴포넌트),
    `src/client.ts`(axios 인스턴스·경로 헬퍼), `src/api.ts`(REST 호출 함수), `src/types.ts`, `src/hooks.ts`(자동 새로고침)
- 개발 서버: `npm run dev` — vite dev 서버가 `/driver`, `/cluster`, `/redirect-to-index`, `/redirect-to-leader`를 `http://localhost:4001`로 proxy 하므로, 4001 포트로 driver 노드를 띄워두고 개발
- 빌드: `npm run build` (tsc 타입체크 포함) → `driver-starter/src/main/resources/static`에 산출 (이후 driver-starter 재빌드 시 jar에 포함)
- base path 치환: `index.html`의 `__APP_BASE_PATH__` placeholder는 빌드 결과에 그대로 유지되고,
  서버(DriverServerRoutes)가 서빙 시점에 driverBasePath로 치환한다 (vite plugin은 dev 모드에서만 `/driver`로 치환).
  asset 경로는 상대 경로(`./assets/...`)라 driverBasePath가 어떤 값이어도 동작한다
---
## 참고
- io-db / io-kafka 모듈: driver-starter를 사용하는 Spring Boot 실행 예시 (custom output 상속 구현 및 설정 참고)
- Web UI 소스: `driver-starter/ui` (React + Vite, 빌드 결과물은 `src/main/resources/static`에 포함)
