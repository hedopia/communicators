# driver-starter
- Equipment (device) communication driver library
- Runs on top of [cluster-starter](cluster.md): cluster composition, device information sharing (shared-object), load balancing between nodes
- Protocol handling logic (command parsing/generation) is written in **Python 3 scripts** (GraalPy engine)
- Supported protocols
  - TCP (server/client)
  - UDP (server/client) - multicast / broadcast supported
  - MODBUS TCP (server/client)
  - HTTP (server/client) - SSL, mTLS supported
  - OPC UA (server/client) - security policy/authentication, subscription supported (Eclipse Milo)
  - Dummy (script execution only)
---
## Module structure
```
DriverStarter (abstract)          Entry point. Created with a Builder, embeds ClusterStarter
 ├─ DriverStarterNoneOutput       No output of collected data
 ├─ DriverStarterFileOutput       CSV file output
 ├─ DriverStarterKafkaOutput      Kafka topic output
 └─ DriverStarterRestOutput       REST endpoint output (a custom output can be implemented by inheritance, like the io-db module)

DriverService                     Device connect/disconnect/load balancing, response management
DriverServerRoutes                REST API and Web UI routing (reactor-netty)
DriverCommand                     Command execution engine. Each device has its own independent Python Context
PythonEngine                      GraalPy (Python 3) Context wrapper
DriverProtocol (abstract)         Common protocol logic (state transitions, reconnection, response channels)
 └─ DriverProtocolTcpUdp / TcpClient / TcpServer / UdpClient / UdpServer
    DriverProtocolModbusClient / ModbusServer
    DriverProtocolHttp / HttpClient / HttpServer
    DriverProtocolOpcua / OpcuaClient / OpcuaServer
    DriverProtocolDummy
```
- 1 device = 1 `DriverProtocol` instance = 1 Python Context (script global variables are isolated per device)
- Collected data (Response) and device status (Status) are passed to the Output via `sendResponse`/`sendStatus`
---
## config (DriverStarter.Builder)
- driverId: Driver identifier (included in Response/Status output)
- clusterStarterBuilder: Cluster configuration (see the config in [cluster.md](cluster.md), including the REST server port)
- loadBalancing: Whether devices are distributed across nodes on a `balanced-connect-all` request (if false, the node that received the request connects all of them) (default: true)
- defaultScript: A common script executed once in the Python Context of every device (for defining shared functions) (default: "")
- driverEvents: Registers functions to run when events occur
  - deviceAdded(Device device)
  - deviceDeleted(Device device)
- driverBasePath: REST API base url (default: "/driver")
- clusterEvents: Registers cluster event functions (see cluster.md)
- routes: For adding REST APIs (`java.util.function.Consumer<reactor.netty.http.server.HttpServerRoutes>`)

### Builders per output
``` java
DriverStarterNoneOutput.builder(driverId, clusterStarterBuilder)
DriverStarterFileOutput.builder(responseFile, statusFile, driverId, clusterStarterBuilder)
DriverStarterKafkaOutput.builder(bootstrapAddress, responseTopic, responseFormat, statusTopic, statusFormat, driverId, clusterStarterBuilder)
DriverStarterRestOutput.builder(restOutputTargetUrls, responsePath, responseFormat, statusPath, statusFormat, driverId, clusterStarterBuilder)
```
- responseFormat placeholders: `${deviceId}`, `${tagId}`, `${value}`, `${driverId}`, `${nodeIndex}`, `${receivedTime}`
- statusFormat placeholders: `${deviceId}`, `${status}`, `${driverId}`, `${nodeIndex}`, `${issuedTime}`
---
## Usage example
pom.xml
```xml
<dependency>
    <groupId>com.sds.communicators</groupId>
    <artifactId>driver-starter</artifactId>
    <version>{driver-version}</version>
</dependency>
```
Creating and starting the driver object
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

driver.start();                    // start the reactor-netty server (REST API + Web UI)
// driver.startWithoutHttpServer(); // start without a server; register routes yourself via driver.getRoutes()
// driver.dispose();                // shutdown
```
- Web UI: connect to `http://{host}:{port}/driver` (screens for device connection/command execution/queries)
---
## Device
| Field | Description | default |
|---|---|---|
| id | Device identifier (only alphanumerics and `_` allowed) | (required) |
| group | Devices in the same group are placed on the same node during load balancing | "" |
| connectionUrl | Connection information (see the per-protocol formats below) | tcp-client://127.0.0.1:5000 |
| protocolScript | Protocol script (defines protocolFunc / bufferingFunc) | "" |
| commands | Set of commands (Command) | {} |
| responseTimeout | Response timeout [sec]; exceeding it causes connection-lost (0 or less: unlimited) | 0 |
| maxRetryConnect | Number of retries on connection failure (negative: unlimited) | 5 |
| retryConnectDelay | Reconnection delay [ms] | 5000 |
| socketTimeout | Socket timeout [ms] | 5000 |
| initialCommandDelay | Delay after connecting before executing commands [ms] (minimum 100) | 5000 |
| connectionCommand | If true, connect only when executing a command (connect before the request, disconnect after completion) | false |
| data | Initial data for use in scripts (`protocol.getData/setData`) | {} |

## Command
| Field | Description | default |
|---|---|---|
| id | Command identifier (only alphanumerics and `_` allowed) | UUID |
| order | Execution order (ascending) | 0 |
| type | Command type (see below) | READ_REQUEST |
| periodGroup | Periodic execution interval [ms] (minimum 500); negative means non-periodic | -1 |
| requestInfo | Request information (per-protocol format; can be generated dynamically by a requestInfo function) | null |
| afterDelay | Delay after executing the command [ms] (used when no delay function is defined) | 0 |
| commandTimeout | Response wait timeout [ms] (read-request) | 5000 |
| cmdScript | Command script (defines cmdFunc / requestInfo / delay / control) | null |

### CommandType
- READ_REQUEST / WRITE_REQUEST / REQUEST: normal commands (executed periodically if periodGroup ≥ 0; if negative, executed when received data arrives)
- STARTING_*: executed once when the connection completes
- STOPPING_*: executed once on a normal disconnect
- READ: sends a request and parses the response with cmdFunc / WRITE: only sends a request / REQUEST: executes only cmdFunc without sending anything

### Device status (StatusCode)
`DISCONNECTED` → `CONNECTING` → `CONNECTED` → (automatic reconnection on `CONNECTION_FAIL`/`CONNECTION_LOST`) → `DISCONNECTED`, and `DISCONNECTION_FAIL` on failure
---
## connectionUrl and per-protocol options
Options are passed as a query string: `tcp-client://127.0.0.1:5000?endBytes=0x0D0A&bufferTime=200`

Common options
- connectionLostOnException: whether to treat an exception during command execution as connection-lost (false by default for server-type protocols)

> **Example notation convention**: The Device JSON in the "example" subsections of each protocol below can be used as-is as the body of `POST /driver/balanced-connect-all` (or `/connect-all`).
> Multi-line scripts are shown separately from the JSON for readability; in place of `"<script:name>"`, insert the full python block with that name as a JSON string (escaping newlines as `\n`).

### tcp-client / tcp-server / udp-client / udp-server
- Format: `tcp-client://{host}:{port}`, `tcp-server://{host}:{port}` (host may be omitted for servers)
- startBytes / endBytes: packet delimiter bytes (e.g. `0x0D0A`)
- retainStartEndBytes: whether to include the delimiter bytes in the received data (default: false)
- combineBufferedData: whether to merge buffered packets into one before delivering (default: true)
- bufferTime: packet buffering time [ms] (tcp default: 100 if there is no endBytes/bufferingFunc, 0 if there is / udp default: always 0)
- multicastGroup: (udp-server) comma-separated list of multicast addresses
- requestInfo format: the string to send, or `{"message":"...", "host":"...", "port":n}` (to target a udp/tcp-server peer; if host/port are omitted, tcp-server sends to all clients)
  - A requestInfo string can express arbitrary bytes with `\xNN` escapes (e.g. `"RD\x0D\x0A"` → `52 44 0D 0A`)
  - helper: `protocol.requestInfo(message, host, port)` / `protocol.requestInfo(message, sender)` (sender is the InetSocketAddress passed to cmdFunc)
- cmdFunc input: `(received, sender[, receivedTime])` - received is a list of byte values (int) (a list of per-packet lists if combineBufferedData=false), sender is an InetSocketAddress
  - The byte values in received are Java bytes as-is, i.e. **signed (-128~127)**. For binary data that may contain bytes of 0x80 or higher, mask with `& 0xFF` before converting, as in `bytes(b & 0xFF for b in received)`, and also use `received[i] & 0xFF` for numeric computation such as length fields (`bytes(received)` raises ValueError on negative values)

#### Example 1: endBytes packet splitting + periodic read parsing (tcp-client)
Sends `RD ENV\r\n` to the device every second and decomposes a response of the form `TEMP=23.5;HUM=41.2\r\n` into tags.
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
`read_env` script:
``` python
def cmdFunc(received, sender, receivedTime):
    # received: list of bytes (int) split on endBytes (0x0D0A) (the delimiter bytes are excluded)
    text = bytes(received).decode("ascii")        # e.g. "TEMP=23.5;HUM=41.2"
    ret = []
    for field in text.split(";"):
        tag, value = field.split("=")
        ret.append((tag, value, receivedTime))
    return ret
```

#### Example 2: assembling variable-length frames with bufferingFunc (tcp-client)
When the frame format is length-field based, such as `[0x02(STX), LEN, payload(LEN bytes), CHECKSUM]`, and therefore cannot be split by endBytes, control the assembly with `bufferingFunc` in protocolScript.
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
`framed_protocol` script:
``` python
def bufferingFunc(buffer):
    # buffer: list of packets received so far (each a list of byte ints)
    data = [b for packet in buffer for b in packet]
    if len(data) < 2:
        return False              # length field not received yet -> keep buffering
    frameLen = (data[1] & 0xFF) + 3   # STX + LEN + payload + CHECKSUM (received bytes are signed, hence & 0xFF)
    if len(data) < frameLen:
        return False              # frame incomplete -> keep buffering
    if len(data) == frameLen:
        return True               # assembly complete -> deliver to the command
    return data[frameLen:]        # assembly complete + feed the excess bytes back as the start of the next assembly
```
`read_frame` script:
``` python
def cmdFunc(received, sender, receivedTime):
    # if bufferingFunc returned a list, this received may include the excess, so use only the frame length
    length = received[1] & 0xFF
    payload = received[2:2 + length]
    return [("payload", bytes(b & 0xFF for b in payload).hex(), receivedTime)]
```
- bufferingFunc return values: `True` assembly complete / `False` keep buffering / `list` assembly complete + feed the returned bytes back into the next assembly / `None` discard the buffer

#### Example 3: protocolFunc routing + write command (tcp-server)
Data spontaneously sent by the device (client) is distinguished by its first byte: event lines (`E...`) are routed to an event handling command plus an ACK reply command, and status lines (`S...`) are routed as the response of a periodic read command.
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
`gw_protocol` script:
``` python
def protocolFunc(received, sender, receivedTime):
    kind = chr(received[0])
    if kind == 'E':
        return ["on_event", "send_ack"]   # non-periodic commands are executed in the order of the returned list
    if kind == 'S':
        return "read_status"              # delivered as the read-request response of read_status
    return None                           # delivered as the response of any waiting read-request
```
`read_status` / `on_event` scripts:
``` python
def cmdFunc(received, sender, receivedTime):
    return [("status", bytes(received[1:]).decode("ascii"), receivedTime)]
```
``` python
def cmdFunc(received, sender, receivedTime):
    return [("event", bytes(received[1:]).decode("ascii"), receivedTime)]
```
- Periodic commands of a tcp-server must be sent to connected clients, so specify requestInfo in the form `{"message":...}` (send to all) or `{"message":..., "host":..., "port":...}` (a specific client).
  A string requestInfo of a non-periodic command (triggered by reception) is replied to the client that sent the data (`send_ack`).
- A requestInfo of the form `{"message":...}` is parsed as JSON, so control characters must be written as JSON escapes such as `\n`, or double-escaped as `\\xNN` so that `\xNN` remains after parsing.

#### Example 4: udp-server multicast reception + reply to the sender
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
`on_data` / `reply` scripts:
``` python
def cmdFunc(received, sender, receivedTime):
    return [("raw", bytes(received).decode("ascii"), receivedTime)]
```
``` python
def requestInfo(received, sender):
    # send an ACK to the origin of the data (sender); a requestInfo function can also take the received arguments
    return protocol.requestInfo("ACK", sender)
```
- Since there is no protocolFunc, **all non-periodic commands** are executed in order when data is received (`on_data` → `reply`).
- If the multicastGroup option is given, the node joins that group on every multicast-capable interface (multiple groups can be specified, comma-separated).

### modbus-client / modbus-server
- Format: `modbus-client://{host}:{port}`
- unitId: default unit id (default: 1)
- combineData: (client) whether to merge the results of reading multiple addresses into a single list (default: true)
- Address convention: for coils use `isCoil=true`; 1xxxx: discrete input, 3xxxx: input register, 4xxxx: holding register
- requestInfo format (read): `{"address":40001, "length":10, "unitId":1}` or an array
- requestInfo format (write): `{"address":1, "values":[1,2,3]}` (a boolean array means a coil write)
  - Write addresses are **raw addresses starting from 1**, not the 4xxxx convention (1 → the first holding register/coil, i.e. corresponding to read address 40001/1)
- helper: `protocol.requestInfo(address, length[, unitId][, isCoil])`, `protocol.requestInfo(address, values[, unitId])`
- modbus-server: incoming requests are delivered to non-periodic commands. Input: `(address, quantity|values, unitId[, receivedTime])`.
  Register/coil values are stored in shared data and accessed with `protocol.read(address, length, unitId[, isCoil])` / `protocol.write(address, values, unitId[, isCoil])`

#### Example (modbus-client): periodic read + tag mapping + write
Reads holding registers 40001~40004 every second and maps them to tags, together with a write command triggered via REST.
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
`read_holding` script (maps the list of read results to tags):
``` python
TAGS = ["temp", "pressure", "speed", "status"]

def cmdFunc(values, receivedTime):
    # values: list of values for 40001~40004 (a single list since combineData=true by default)
    return [(TAGS[i], str(values[i]), receivedTime) for i in range(len(TAGS))]
```
`write_speed` script (executed via REST `POST /driver/execute-command-ids/modbus_plc_1` + `initial-value` header `{"speed": 1500}` + body `["write_speed"]`):
``` python
def requestInfo(initialValue):
    # write addresses are raw addresses starting from 1: 3 = the third holding register (corresponds to read address 40003)
    return protocol.requestInfo(3, [int(initialValue["speed"])])
```
- Read address convention: `40001~`/`400001~` holding register, `30001~`/`300001~` input register, `10001~`/`100001~` discrete input; for coils use `isCoil=true` + an address starting from 1
- For writes, if `values` is an integer array it is a register write (raw addresses 1~65536), and if it is a boolean array it is a coil write — **determined by the value type** (see `write_coil`; no isCoil field needed)
- With the `combineData=false` option, when requestInfo is an array (multiple address blocks) the cmdFunc input is delivered as **a list of per-block lists**:
``` python
# requestInfo: [{"address":40001,"length":2}, {"address":30001,"length":3}]
def cmdFunc(values, receivedTime):
    holding, inputReg = values[0], values[1]   # combineData=false
    ...
```

#### Example (modbus-server): handling client writes + managing register values
The device acts as a modbus TCP slave. It fills the initial registers on connection (bind) and outputs values written by clients as Responses.
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
`init_regs` script (managing register values with protocol.write/read):
``` python
def cmdFunc():
    protocol.write(40001, [0] * 10, 1)          # holding registers 40001~40010 = 0 (unitId 1)
    protocol.write(1, [False] * 8, 1, True)     # coils 1~8 = False
    current = protocol.read(40001, 10, 1)       # read back the values
    log.info("initial registers: " + str(list(current)))
    return None
```
`on_client` script (non-periodic - executed on every client read/write request):
``` python
def cmdFunc(address, values, unitId, receivedTime):
    if isinstance(values, int):
        # client read request: values is the quantity (int).
        # if you update values with protocol.write at this point, the updated values are returned in the response
        # (the response is generated from the shared data after the non-periodic commands run)
        return None
    # client write request: values is the list of written values (registers: int, coils: bool)
    return [("REG_%d" % (address + i), str(v), receivedTime) for i, v in enumerate(values)]
```
- Register/coil values are stored in the device shared data (`{unitId: {address: value}}`), so they are shared across cluster nodes.
- **Caution**: the targets of reception-triggered non-periodic execution are **all** commands with `periodGroup < 0` (the default -1), regardless of command type.
  If, like `init_regs`, a STARTING_*/STOPPING_* command has no periodGroup specified, it is re-executed not only once on connection but also **on every client read/write request**, resetting the registers to their initial values each time.
  For STARTING_*/STOPPING_* commands on server-type protocols, specify `"periodGroup": 0` or greater as in the example above
  (STARTING_*/STOPPING_* types are excluded from periodic scheduling by type, so they are not executed periodically even with periodGroup ≥ 0).

### http-client / http-server
- Format: `http-client://{baseUrl}`, `http-server://{host}:{port}` (host may be omitted for servers)
- cert / key / format / password: SSL certificate (PEM: cert+key; otherwise cert+format(default PKCS12)+password)
- trustCert / trustFormat / trustPassword: trust certificate for mTLS (the server requires client authentication)
- useByteArrayBody: deliver the body as a byte list instead of a string (default: false)
- http-client requestInfo format: `{"method":"GET", "path":"/api", "basePath":"...", "body":"...", "params":{...}, "headers":{...}, "proxy":{...}}`
  - helper: `protocol.requestInfo(method, path, basePath, body, params[, proxyType, proxyHost, proxyPort, proxyUsername, proxyPassword], *headers)`
    - **Caution**: if you actually pass `*headers` (7 or more arguments in total), overload ambiguity causes a runtime TypeError (`Multiple applicable overloads found`). If headers are needed, build and return the requestInfo JSON string directly instead of using the helper (see the `post_control` example below)
  - cmdFunc input: `(statusCode, body, headers[, receivedTime])`
- http-server: incoming requests are delivered to non-periodic commands. Input: `(method, path, body, params, headers[, receivedTime])`
  - Specifying the response: return `protocol.requestInfo(statusCode, body, *headers)` as the requestInfo of a write-request (200 OK if unspecified)

#### Example (http-client): periodic GET polling + POST send
Polls a REST API with GET every 5 seconds and parses the JSON response, plus defines a POST command triggered via REST.
All commands of an http-client must be READ_REQUESTs that receive a response (write-request is not supported; POST is also READ_REQUEST + cmdFunc to check the status).
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
`poll_sensors` script (cmdFunc input: `(statusCode, body, headers[, receivedTime])`):
``` python
def cmdFunc(statusCode, body, headers, receivedTime):
    if statusCode != 200:
        raise Exception("polling failed, statusCode=%d" % statusCode)
    # body: the parsed object (dict/list) if JSON, otherwise a string (a byte list if useByteArrayBody=true)
    # headers: {headerName: [value, ...]} dict
    return [("temperature", str(body["temperature"]), receivedTime),
            ("humidity", str(body["humidity"]), receivedTime)]
```
`post_control` script (executed via REST `POST /driver/request-command-ids/http_api_1` + `initial-value` header `{"fan":"on"}` + body `["post_control"]`;
since headers are needed, the requestInfo JSON is built directly instead of using the helper — passing header arguments to the helper causes an overload-ambiguity TypeError):
``` python
import json

def requestInfo(initialValue):
    return json.dumps({"method": "POST", "path": "/api/control",
                       "body": json.dumps(initialValue),
                       "headers": {"Content-Type": ["application/json"]}})

def cmdFunc(initialValue, statusCode, body, headers):
    # when executed with an initial-value header, initialValue is always filled in as the first argument
    if statusCode >= 300:
        raise Exception("control failed, statusCode=%d" % statusCode)
    return None
```
- The `{baseUrl}` of connectionUrl includes the scheme (`http-client://http://127.0.0.1:8080`; when using https/certificates, `http-client://https://...?cert=...`)
- If `basePath` is specified in requestInfo, it is used instead of the baseUrl of connectionUrl. A `proxy` (`{"type":"HTTP","host":...,"port":...}`) can also be specified

#### Example (http-server): handling incoming requests + returning a response
The device acts as an HTTP server, and for each incoming request the non-periodic commands are executed in order (`handle_req` → `send_resp`).
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
`handle_req` script (cmdFunc input: `(method, path, body, params, headers[, receivedTime])`):
``` python
def cmdFunc(method, path, body, params, headers, receivedTime):
    if method == "POST" and path == "/data":
        # body: a parsed dict/list if JSON; params: {name: [value, ...]} dict
        return [(str(k), str(v), receivedTime) for k, v in body.items()]
    return None
```
`send_resp` script (the requestInfo of a write-request becomes the HTTP response; the value of the last write-request is used):
``` python
import json

def requestInfo(method, path, body, params, headers):
    if method == "POST" and path == "/data":
        return protocol.requestInfo(200, json.dumps({"result": "ok"}),
                                    "Content-Type", "application/json")
    return protocol.requestInfo(404, "not found")
```
- requestInfo JSON format for responses: `{"httpStatusCode":200, "body":"...", "headers":{"name":["value"]}}` (if there is no write-request command or no requestInfo, the response is 200 OK)
- By defining `protocolFunc(method, path, body, params, headers[, receivedTime])` in protocolScript, you can route execution so that only the commands in the returned cmdId list run (e.g. branching per path)

### opcua-client / opcua-server
- Format: `opcua-client://{host}:{port}[/{path}]` (→ `opc.tcp://...`), `opcua-server://{host}:{port}[/{path}]` (host may be omitted for servers)
- opcua-client options
  - securityPolicy: None | Basic128Rsa15 | Basic256 | Basic256Sha256 | Aes128_Sha256_RsaOaep | Aes256_Sha256_RsaPss (default: None)
  - securityMode: Sign | SignAndEncrypt (when securityPolicy != None, default: SignAndEncrypt) - the client certificate is generated automatically as self-signed
  - username / password: authentication information (anonymous if unspecified)
  - subscriptionNodeIds: list of nodeIds to subscribe to automatically on connection (comma-separated)
  - publishingInterval: subscription publishing interval [ms] (default: 1000)
- opcua-client requestInfo format
  - read: an array of nodeId strings `["ns=2;s=Tag1", "ns=0;i=2258"]` (helper: `protocol.requestInfo("ns=2;s=Tag1", ...)`)
    - **Caution**: calling the helper with a **single** nodeId dispatches to the write overload `requestInfo(writeNodes)` rather than the read one (String varargs), causing incorrect behavior. For a single-nodeId read, return the JSON string `'["ns=2;s=Tag1"]'` directly instead of using the helper (two or more nodeIds work fine)
  - write: `{"ns=2;s=Tag1": 42}`, or with an explicit type `[{"nodeId":"ns=2;s=Tag1", "value":42, "type":"Int16"}]`
    (type: Boolean/SByte/Byte/Int16/UInt16/Int32/UInt32/Int64/UInt64/Float/Double/String/DateTime; if unspecified, the JSON type is used as-is)
  - cmdFunc input (read): `(received, receivedTime)` - received is `[[nodeId, value], ...]`
  - Non-periodic commands are executed when subscription data changes; input: `(nodeId, value[, receivedTime])`
  - `protocol.subscribe([nodeIds])`: add subscriptions dynamically from a script
- opcua-server options
  - namespaceUri: namespace URI (default: `urn:sds:communicators:{deviceId}`)
  - username / password: if specified, username authentication is required (the `anonymous=true` option additionally allows anonymous access)
- opcua-server behavior
  - Variable nodes are created under the `{deviceId}` folder (nodeId: `ns=2;s={deviceId}/{name}`)
  - Initial nodes are created automatically from the keys/values of Device.data
  - `protocol.write(name, value)`: create/update a node (from a script), `protocol.read(name)`: read a node value
  - When an OPC UA client writes a node, non-periodic commands are executed; input: `(name, value[, receivedTime])`
  - Nodes can also be updated via a write-request requestInfo of the form `{"name": value, ...}` (read-request is not supported)

#### Example (opcua-client): read + subscription
Uses a 1-second periodic read together with automatic subscription on connection (subscriptionNodeIds). When a subscribed node's value changes, non-periodic commands are executed with the input `(nodeId, value[, receivedTime])`.
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
`read_tags` script (cmdFunc input: `(received[, receivedTime])`, where received is `[[nodeId, value], ...]`):
``` python
def cmdFunc(received, receivedTime):
    ret = []
    for nodeId, value in received:
        tag = nodeId.split("/")[-1] if "/" in nodeId else nodeId
        ret.append((tag, str(value), receivedTime))
    return ret
```
`on_change` script (executed when subscription data changes):
``` python
def cmdFunc(nodeId, value, receivedTime):
    return [(nodeId, str(value), receivedTime)]
```
- Subscriptions can be added dynamically from a script with `protocol.subscribe(["ns=2;s=PLC1/hum"])` (e.g. called from a STARTING_REQUEST command)

#### Example (opcua-client): write (including forced types)
An example that defines only REST-triggered write commands on a device without subscriptions. Use `type` to force the type when the server node's data type must be exact.
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
- Executed as `POST /driver/execute-command-ids/opcua_wr_1` with body `["write_setpoint"]`
- The `{"nodeId": value}` form is converted using the JSON type as-is (integer → Int64/Int32, float → Double, string → String), so if Int16/UInt32 etc. are needed, use the array + `type` form

#### Example (opcua-server): initial nodes + node updates + receiving client writes
Initial variable nodes are created from the keys/values of `Device.data` (`ns=2;s={deviceId}/{name}`), a periodic command updates the nodes with protocol.write/read, and non-periodic commands are executed when an OPC UA client writes a node.
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
`update_nodes` script (managing node values with protocol.write/read; missing nodes are created):
``` python
import random

def cmdFunc(receivedTime):
    protocol.write("temp", round(random.uniform(20.0, 30.0), 2))
    protocol.write("hum", round(random.uniform(30.0, 60.0), 2))
    mode = protocol.read("mode")
    return [("mode", str(mode), receivedTime)]
```
`on_write` script (executed when an OPC UA client writes a node; input: `(name, value[, receivedTime])`):
``` python
def cmdFunc(name, value, receivedTime):
    log.info("node written by client: " + name + " = " + str(value))
    return [("written_" + name, str(value), receivedTime)]
```
- As with `init_status`, nodes can also be created/updated via a write-request requestInfo of the form `{"name": value, ...}`.
  However, **including STARTING_* types**, every write-request whose periodGroup is negative (the default -1) is also executed on each client write,
  so initialization commands must specify `"periodGroup": 0` or greater as in the example above (STARTING_* is excluded from periodic scheduling by type, so it is not executed periodically).
  It is recommended to separate REST-triggered-only writes into a dedicated device.

### dummy
- Format: `dummy://` - runs scripts only, without an actual connection (for testing)

#### Example (dummy): running scripts only with a periodic REQUEST
Used for generating/processing data without a connection. The REQUEST type executes only cmdFunc without sending/receiving, and its argument pool is `(initialValue?, receivedTime)`.
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
`gen_data` script:
``` python
import math, time

count = 0                                     # global variables are retained per device (Python Context)

def cmdFunc(receivedTime):
    global count
    count += 1
    amp = protocol.getData(["amplitude"])     # read a Device.data value
    value = amp * math.sin(count / 10.0)
    # can also be used to process values collected by other devices:
    #   resp = protocol.getResponse("tcp_sensor_1")   # {tagId: Response} map
    #   if resp and "TEMP" in resp: ...
    return [("sine", "%.3f" % value, receivedTime),
            ("count", str(count), receivedTime)]
```
---
## Writing scripts (Python 3 / GraalPy)
- The script engine is GraalPy (**Python 3**)
- Scripts run in an independent Python Context per device, loaded in the order `defaultScript` → each `cmdScript` → `protocolScript`
  (referencing a name defined in protocolScript directly from **module-level code** in a cmdScript raises NameError — references inside a function body are fine, since they are resolved at call time)
- Script global objects
  - `log`: SLF4J logger (`log.info("...")`)
  - `protocol`: the DriverProtocol object of this device (see the API below)
  - `UtilFunc`: shared utilities (`com.sds.communicators.common.UtilFunc`)
  - `java`: Java class access (forms such as `from java.util import ArrayList` are possible)

### cmdScript functions (all optional)
``` python
def cmdFunc(received, receivedTime):        # parse received data; returns [(tagId, value), (tagId, value, time), ...] or None
    return [("temperature", str(received[0]))]

def requestInfo():                          # dynamically generate requestInfo; returns str or None (use Command.requestInfo)
    return protocol.requestInfo(40001, 10)

def delay():                                # returns the delay after the command [ms]; None means Command.afterDelay is used
    return 1000

def control(commandList, idx, exception):   # control the command flow (2 or 3 arguments)
    return None                             # None: next command, int: jump to that index, Exception: raise the exception
```
- Function arguments can be declared selectively from the front (they are passed according to the number of arguments)
- If an `initial-value` header is given to the execute/request-commands REST API, it is passed as the first argument (parsed into an object if JSON)

### Argument-filling rules (common)
When calling a `cmdFunc`/`requestInfo` function, an **argument pool** is built in the order below depending on the execution situation, and the function is filled from the front of the pool with as many arguments as it declares.

| Pool order | Value | Inclusion condition |
|---|---|---|
| 1 | initialValue | When there is an `initial-value` header on an execute/request-commands(-ids) REST call, or an initialValue from the script's `protocol.executeCommands(deviceId, initialValue, commands)` |
| 2 ~ | received... (per-protocol received arguments, see the table below) | When executed from received data (read-request response parsing, non-periodic commands) |
| last | receivedTime (epoch ms) | When executed from received data and for REQUEST type execution (not present in the requestInfo of periodic read/write, nor at command start time) |

- If the function declares **more** arguments than the pool size, it is an error (`invalid function, function arguments count: ...`)
- receivedTime is passed as the last argument **only when** the function's argument count equals the full pool size — that is, arguments are omitted from the back
- If initialValue is in the pool, it is **always filled as the first argument** (functions of commands to be executed together with initial-value must declare their first argument as initialValue)

Per-protocol received arguments (received...):

| Execution situation | Received arguments |
|---|---|
| tcp/udp reception | `received` (list of byte ints; a list of per-packet lists if combineBufferedData=false), `sender` (InetSocketAddress) |
| modbus-client read response | `values` (list of register/coil values; a list of per-address-block lists if combineData=false) |
| modbus-server client request | `address` (int), `quantity` (int, read request) or `values` (list, write request), `unitId` (int) |
| http-client response | `statusCode` (int), `body` (parsed JSON object or str), `headers` (dict[str, list[str]]) |
| http-server incoming request | `method` (str), `path` (str), `body` (parsed JSON object or str), `params` (dict[str, list[str]]), `headers` (dict[str, list[str]]) |
| opcua-client read response | `received` = `[[nodeId, value], ...]` |
| opcua-client subscription data change | `nodeId` (str), `value` |
| opcua-server client write | `name` (str), `value` |
| REQUEST type / dummy | (none) |

Example - handling an http-server incoming request (pool = `method, path, body, params, headers, receivedTime`, 6 items):
``` python
def cmdFunc(method, path): ...                                     # use only the first 2
def cmdFunc(method, path, body, params, headers): ...              # without receivedTime
def cmdFunc(method, path, body, params, headers, receivedTime): ...  # all of them
```

### Return conventions
| Function | Return | Handling |
|---|---|---|
| cmdFunc | `[(tagId, value), (tagId, value, time), ...]` | Output as Responses. value is converted to str, time is epoch ms (int); for a 2-tuple, receivedTime is used |
| cmdFunc | `None` or an empty list | No output |
| cmdFunc | Any other type | Error (ScriptException) |
| requestInfo | str | Use that string as the requestInfo |
| requestInfo | None | Use `Command.requestInfo`; if that is also empty, skip the command |
| delay | int | Delay after the command [ms] |
| delay | None | Use `Command.afterDelay` |
| control | None | Proceed to the next command |
| control | int | Jump to the command at that index (a negative value or one beyond the list size ends the command sequence) |
| control | Exception object | Raise that exception |

- A control function must declare exactly 2 (`commandList, idx`) or 3 (`commandList, idx, exception`) arguments.
  `commandList` is the list of cmdIds to execute, `idx` is the current index, and `exception` is the exception raised during the previous command execution (None when normal).
- For a command with control defined, the flow can continue based on control's return value even if an exception occurs during execution (useful for implementing retries/skips).
``` python
def control(commandList, idx, exception):
    if exception is not None:
        log.warn("command failed: " + str(exception))
        return idx          # retry the same command
    return None             # proceed to the next command if normal
```
- If an exception occurs during command execution and `connectionLostOnException=true` (the default for client-type protocols), the device transitions to connection-lost and reconnects (excluding ScriptExceptions such as an invalid script return format)

### Example of using the initial-value header
```
POST /driver/execute-command-ids/{deviceId}
initial-value: {"speed": 1500}        <- passed as a parsed object (dict/list) if JSON, otherwise as a string
body: ["write_speed"]
```
``` python
def requestInfo(initialValue):        # argument pool = (initialValue,) - no receivedTime since this is not a reception-driven execution
    return protocol.requestInfo(3, [int(initialValue["speed"])])
```

### Examples of using the global objects
``` python
log.info("device script loaded")                   # SLF4J logger (output under the ScriptLogger category)

protocol.setData({"threshold": 42})                # store device shared data (shared across the cluster)
threshold = protocol.getData(["threshold"])        # read it back (includes the Device.data initial values)

arr = UtilFunc.stringToByteArray("AB\\x0D\\x0A")   # shared utilities (com.sds.communicators.common.UtilFunc)

from java.util import ArrayList                    # access to java packages
from java.lang import System
now = System.currentTimeMillis()
```

### protocolScript functions
``` python
def protocolFunc(received, receivedTime):   # (tcp/udp/http-server) route received data
    return None            # None: deliver as a read-request response
                            # str: deliver as the read-request response of that cmdId
                            # list/tuple: execute the non-periodic commands of that cmdId list

def bufferingFunc(buffer):                  # (tcp/udp) control packet assembly; buffer is a list of received packets (lists)
    return True             # True: assembly complete → process, False: keep buffering
                            # list: assembly complete + return the remaining bytes, None: discard the buffer
```
- The arguments of protocolFunc also follow the "argument-filling rules" (received arguments + receivedTime, without initialValue).
  For tcp/udp it is `(received, sender[, receivedTime])`, and for http-server `(method, path, body, params, headers[, receivedTime])`
- The protocolFunc of an http-server supports returning list/tuple (a cmdId list) only (None/str routing is tcp/udp only)

### Main APIs of the protocol object
``` python
protocol.setData({"key": value})                # merge device shared data
protocol.getData(["key", "subKey"])             # read data
protocol.deleteData(["key"])                    # delete data
protocol.getResponse()                          # read collected data (overloads for all/per-device/per-node)
protocol.getDeviceStatus()                      # read device status
protocol.executeCommands(deviceId, initialValue, commands)   # execute commands on another device
protocol.setConnectionLost()                    # force connection-lost
protocol.setDisconnected()                      # force disconnect
protocol.getDeviceIdMap()                       # device list per node
protocol.getClusterNodes()                      # list of cluster nodes
```
---
## REST API (base: driverBasePath, default "/driver")
| Method | Path | Description |
|---|---|---|
| GET | / | Web UI |
| POST | /balanced-connect-all | Connect devices (load balanced, body: Device array) |
| POST | /connect-all | Connect devices on the current node (via the LEADER) |
| DELETE | /disconnect | Disconnect devices (body: deviceId array) |
| DELETE | /disconnect-all | Disconnect all |
| PUT | /reconnect-all | Reconnect all |
| GET | /device-status | Status of all devices |
| GET | /device-status/{deviceId} | Device status |
| GET | /device-id-map | Device list per node |
| GET | /response | All collected data |
| GET | /response/{deviceId} | Collected data of a device |
| POST | /execute-commands/{deviceId} | Execute commands + output responses (body: Command array, header: initial-value) |
| POST | /request-commands/{deviceId} | Execute commands without outputting responses (returns the results only) |
| POST | /execute-command-ids/{deviceId} | Execute registered commands + output responses (body: cmdId array) |
| POST | /request-command-ids/{deviceId} | Execute registered commands without outputting responses |
- Connection delegation between nodes (FOLLOWER→LEADER delegation, LEADER→per-node distribution) is performed over gRPC (the `driver.DriverInternal` service, sharing the cluster gRPC server port `serverPort + grpcPortOffset`) rather than REST
- For the cluster REST API (`/cluster/*`, `/redirect-to-leader/*`, `/redirect-to-index/*`), see [cluster.md](cluster.md)
---
## Web UI

Connecting to `http://{host}:{port}{driverBasePath}/` (by default `/driver/`) opens a React-based management screen.
The entire cluster can be viewed/managed from any node you connect to (per-node queries internally go through `/redirect-to-index/{n}/...`).
It consists of 3 tabs: Devices / Nodes / Responses.

### Devices tab (connection/disconnection management)
- **Device table**: shows all devices in the cluster with deviceId / owning node (nodeIndex) / status / action columns
  - Obtains the device list per node with `GET /driver/device-id-map`, and queries the status of each node with `GET /redirect-to-index/{n}/driver/device-status` (shows `NODE_UNREACHABLE` if a node does not respond)
  - Per-row **disconnect** button (`DELETE /driver/disconnect`, body: `["deviceId"]`), and **disconnect-all** / **reconnect-all** buttons at the top
- **Adding devices (connect)**: enter a Device JSON array in the textarea and connect (`POST /driver/balanced-connect-all`).
  The result is shown as a per-device success/failure table (e.g. `dummy1 → connected`)
- **Import file**: reads a JSON file (Device array) into the textarea, from which you can then connect
- **Export file**: downloads the configuration of all devices registered in the cluster as `devices.json`.
  The data source is `GET /driver/devices` — it returns the device configurations stored in the shared-object (entries of the map that have `id`/`connectionUrl` keys) as a JSON array,
  and the exported file can be used as-is for import → connect (round-trip compatible)
- Auto-refresh (5 s) toggle

### Nodes tab (node status/management)
- **Node table**: based on the nodeIndex list from `GET /cluster/get-cluster-nodes`,
  shows the position (LEADER highlighted)/activated from `GET /redirect-to-index/{n}/cluster/node-status` per node, along with the device count (based on device-id-map)
- Per-node actions: **set-to-leader** / **set-to-follower** (`PUT /redirect-to-index/{n}/cluster/set-to-leader`, etc.)
- Summary at the top: leader-url (`GET /cluster/leader-url`), and the node-status of the node you are connected to
- Auto-refresh (5 s) toggle

### Responses tab (collected data)
- Displays the result of `GET /driver/response` in a deviceId / tagId / value / receivedTime table (receivedTime is converted from epoch ms to local time)
- Device filter dropdown, auto-refresh (5 s) toggle

### File format (import/export)
A Device JSON array — the same format as the body of `POST /driver/balanced-connect-all`:
``` json
[
  {
    "id": "device1",
    "connectionUrl": "dummy://",
    "commands": []
  }
]
```

### Development
- Source: `driver-starter/ui` (React 19 + Vite + axios, no external UI libraries)
  - `src/App.tsx` (tab shell), `src/DevicesTab.tsx` / `src/NodesTab.tsx` / `src/ResponsesTab.tsx` (per-tab components),
    `src/client.ts` (axios instance and path helpers), `src/api.ts` (REST call functions), `src/types.ts`, `src/hooks.ts` (auto refresh)
- Dev server: `npm run dev` — the vite dev server proxies `/driver`, `/cluster`, `/redirect-to-index`, and `/redirect-to-leader` to `http://localhost:4001`, so run a driver node on port 4001 while developing
- Build: `npm run build` (includes tsc type checking) → output to `driver-starter/src/main/resources/static` (included in the jar on the next driver-starter build)
- Base path substitution: the `__APP_BASE_PATH__` placeholder in `index.html` is kept as-is in the build output,
  and the server (DriverServerRoutes) substitutes driverBasePath at serving time (the vite plugin substitutes `/driver` in dev mode only).
  Asset paths are relative (`./assets/...`), so they work with any driverBasePath value
---
## Notes
- io-db / io-kafka modules: Spring Boot execution examples using driver-starter (reference for implementing a custom output by inheritance and for configuration)
- Web UI source: `driver-starter/ui` (React + Vite; the build output is included in `src/main/resources/static`)
