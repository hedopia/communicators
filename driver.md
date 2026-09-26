# driver-starter

`driver-starter` is a clustered device communication library built on [cluster-starter](cluster.md).

It provides:

- Cluster-wide device placement, load balancing, and failover
- TCP and UDP client/server drivers
- Modbus TCP client/server drivers
- HTTP client/server drivers with SSL and mTLS options
- OPC UA client/server drivers based on Eclipse Milo 1.1.6
- A script-only dummy driver
- Python 3 command and protocol scripting through GraalPy
- REST APIs and a React management UI

## Architecture

```text
DriverStarter (abstract)          Entry point; embeds ClusterStarter
 +-- DriverStarterNoneOutput      No response/status sink
 +-- DriverStarterFileOutput      CSV file output
 +-- DriverStarterKafkaOutput     Kafka output
 +-- DriverStarterRestOutput      REST output

DriverService                     Connect, disconnect, load balancing, and response state
DriverServerRoutes                REST API and Web UI routes
DriverCommand                     Command scheduler and execution engine
DriverProtocol                    Shared protocol lifecycle and reconnect behavior
 +-- TCP / UDP client and server
 +-- Modbus TCP client and server
 +-- HTTP client and server
 +-- OPC UA client and server
 +-- Dummy

support/PythonEngine              GraalPy context wrapper
support/ModbusTcpSocketTransport  Plain-socket Modbus/TCP client transport
support/OpcuaSecurityStore        OPC UA application identity and PKI trust store
```

Each Device owns one `DriverProtocol` instance and one Python context. Script globals are therefore isolated per Device. Responses and status changes reach the selected output through `sendResponse` and `sendStatus`.

## Dependencies and compatibility

- Java 21, running on GraalVM for JDK 21 so that GraalPy uses its optimizing runtime
- GraalPy 23.1.12.1 (Python 3.10.8). The 23.1 line matches the Truffle compiler that GraalVM for JDK 21 bundles; 24.x would not.
- DigitalPetri Modbus 2.1.6
- Eclipse Milo client and server 1.1.6
- Netty 4.2.17.Final, aligned through the Netty BOM
- `netty-channel-fsm` 1.0.2, shared by Milo 1.1.6 and Modbus 2.1.6

The OPC UA implementation uses the Milo 1.1 endpoint, certificate, namespace, monitored-item, and subscription APIs.

## Driver configuration

Create an output-specific builder and pass a `ClusterStarter.Builder`.

```java
DriverStarterNoneOutput.builder(driverId, clusterStarterBuilder)

DriverStarterFileOutput.builder(
        responseFile,
        statusFile,
        driverId,
        clusterStarterBuilder)

DriverStarterKafkaOutput.builder(
        bootstrapAddress,
        responseTopic,
        responseFormat,
        statusTopic,
        statusFormat,
        driverId,
        clusterStarterBuilder)

DriverStarterRestOutput.builder(
        restOutputTargetUrls,
        responsePath,
        responseFormat,
        statusPath,
        statusFormat,
        driverId,
        clusterStarterBuilder)
```

Common builder values:

| Value | Description | Default |
|---|---|---|
| `driverId` | Identifier included in Response and Status output | Required |
| `clusterStarterBuilder` | Cluster settings, including the HTTP port | Required |
| `loadBalancing` | Distribute Devices across nodes during `balanced-connect-all` | `true` |
| `defaultScript` | Python executed once when each Device context is created | Empty |
| `driverEvents` | `deviceAdded` and `deviceDeleted` handlers | None |
| `driverBasePath` | Driver REST and UI base path | `/driver` |
| `clusterEvents` | Additional cluster event handlers | None |
| `routes` | Additional Reactor Netty routes | None |

Response format placeholders:

```text
${deviceId} ${tagId} ${value} ${driverId} ${nodeIndex} ${receivedTime}
```

Status format placeholders:

```text
${deviceId} ${status} ${driverId} ${nodeIndex} ${issuedTime}
```

### Example

```java
var driver = DriverStarterKafkaOutput.builder(
                "127.0.0.1:9092",
                "response-topic",
                "{\"deviceId\":${deviceId},\"tagId\":${tagId},\"value\":${value}}",
                "status-topic",
                "{\"deviceId\":${deviceId},\"status\":${status}}",
                "DRIVER-1",
                ClusterStarter.builder(
                        Set.of("http://127.0.0.1:4001"),
                        4001,
                        1))
        .setLoadBalancing(true)
        .setDriverBasePath("/driver")
        .build();

driver.start();
// driver.startWithoutHttpServer();
// driver.dispose();
```

Open the management UI at `http://127.0.0.1:4001/driver/`.

## Device model

| Field | Description | Default |
|---|---|---|
| `id` | Device identifier; only letters, numbers, and underscores are accepted | Required |
| `group` | Devices in the same group are placed on the same node during load balancing | Empty |
| `connectionUrl` | Protocol and connection settings | `tcp-client://127.0.0.1:5000` |
| `protocolScript` | Protocol initialization code; TCP/UDP support `protocolFunc` and `bufferingFunc`, HTTP server supports `protocolFunc`; Dummy does not execute it | Empty |
| `commands` | Command set, represented as a JSON array | Empty |
| `responseTimeout` | Seconds without a response before connection loss; zero or negative means unlimited | `0` |
| `maxRetryConnect` | Maximum retries after a connection failure; negative means unlimited | `5` |
| `retryConnectDelay` | Delay between reconnect attempts in milliseconds | `5000` |
| `socketTimeout` | Socket or request timeout in milliseconds | `5000` |
| `initialCommandDelay` | Delay before commands start after connection; values below 100 are raised to 100 | `5000` |
| `connectionCommand` | Connect only while a command is being executed | `false` |
| `data` | Initial script-accessible shared data | Empty object |

Server protocols force `connectionCommand` to `false`. The HTTP client forces it to `true`.

## Command model

| Field | Description | Default |
|---|---|---|
| `id` | Command identifier; only letters, numbers, and underscores are accepted | Generated UUID |
| `order` | Ascending execution order | `0` |
| `type` | Command type | `READ_REQUEST` |
| `periodGroup` | Period in milliseconds; negative means non-periodic and positive values below 500 are raised to 500 | `-1` |
| `requestInfo` | Protocol-specific request data | `null` |
| `afterDelay` | Delay after command execution in milliseconds | `0` |
| `commandTimeout` | Read-response timeout in milliseconds | `5000` |
| `cmdScript` | Python containing command functions | `null` |

Command types:

```text
READ_REQUEST
STARTING_READ_REQUEST
STOPPING_READ_REQUEST
WRITE_REQUEST
STARTING_WRITE_REQUEST
STOPPING_WRITE_REQUEST
REQUEST
STARTING_REQUEST
STOPPING_REQUEST
```

- `READ_REQUEST` sends a request and parses the response with `cmdFunc`.
- `WRITE_REQUEST` sends a request without waiting for a read response.
- `REQUEST` runs `cmdFunc` without protocol I/O.
- `STARTING_*` commands run once after connection.
- `STOPPING_*` commands run once during a normal disconnect.
- A command with `periodGroup >= 0` is scheduled periodically unless it is a starting or stopping type.
- A command with `periodGroup < 0` participates in receive-triggered execution.

For server protocols, give initialization-only starting/stopping commands `periodGroup >= 0`. Otherwise they also match receive-triggered non-periodic execution.

## Device status

Typical transitions are:

```text
DISCONNECTED -> CONNECTING -> CONNECTED
                         +-> CONNECTION_FAIL -> retry
CONNECTED --------------+-> CONNECTION_LOST -> retry
                         +-> DISCONNECTED
```

A disconnect failure is reported as `DISCONNECTION_FAIL`.

## Connection URLs

The scheme selects the protocol. Options are URL-encoded query parameters.

```text
tcp-client://127.0.0.1:5000?endBytes=%5Cx0D%5Cx0A&bufferTime=200
```

The common `connectionLostOnException` option controls whether a command exception marks the connection as lost. Client protocols and Dummy default to `true`; servers default to `false`. An explicit option overrides this default.

### TCP and UDP

Formats:

```text
tcp-client://{host}:{port}
tcp-server://[{host}]:{port}
udp-client://{host}:{port}
udp-server://[{host}]:{port}
```

Options:

| Option | Description | Default |
|---|---|---|
| `startBytes` | Start delimiter bytes | None |
| `endBytes` | End delimiter bytes | None |
| `retainStartEndBytes` | Include delimiter bytes in received data | `false` |
| `combineBufferedData` | Flatten buffered packets into one byte list | `true` |
| `bufferTime` | Buffering period in milliseconds | TCP: `100` without `endBytes` and `bufferingFunc`, otherwise `0`; UDP: `0` |
| `multicastGroup` | Comma-separated multicast groups for `udp-server` | None |

Enter delimiter bytes as literal escape text such as `\x0D\x0A`, encoded as `%5Cx0D%5Cx0A` in a URL. `0x0D0A` is ordinary text, not a byte escape. The UI encodes the option values automatically. `startBytes` alone does not change TCP's default `bufferTime` of 100 ms. Negative explicit buffer times are clamped to zero; unparsable values use the protocol default. With `combineBufferedData=false`, the receive argument is a list of packet byte lists instead of one flat byte list.

A request is either a string or:

```json
{"message":"...", "host":"127.0.0.1", "port":5000}
```

The host and port select a destination for UDP or TCP server output. Without a destination, TCP server output is broadcast to connected clients. A string triggered by received server data is sent back to that sender.

`\xNN` sequences represent arbitrary bytes:

```text
RD\x0D\x0A
```

Helpers:

```python
protocol.requestInfo(message)
protocol.requestInfo(message, host, port)
protocol.requestInfo(message, sender)
```

The one-argument helper returns a JSON string literal for a plain client send or
an event reply. Addressed forms return a JSON object. Both preserve quotes and
backslashes, including the byte-escape sequences in the message.

The receive argument pool is:

```text
received, sender, receivedTime
```

`received` contains signed Java byte values from `-128` through `127`. Mask binary data before converting it:

```python
payload = bytes(value & 0xFF for value in received)
```

`bufferingFunc(buffer)` may return:

- `True`: frame complete
- `False`: continue buffering
- A list: frame complete and reuse the returned bytes for the next frame
- `None`: discard the buffer

Example:

```json
[
  {
    "id": "tcp_sensor_1",
    "connectionUrl": "tcp-client://127.0.0.1:5000?endBytes=%5Cx0D%5Cx0A",
    "commands": [
      {
        "id": "read_env",
        "type": "READ_REQUEST",
        "periodGroup": 1000,
        "requestInfo": "RD ENV\\x0D\\x0A",
        "cmdScript": "def cmdFunc(received, sender, receivedTime):\n    text = bytes(received).decode('ascii')\n    return [('raw', text, receivedTime)]"
      }
    ]
  }
]
```

### Modbus TCP

Formats:

```text
modbus-client://{host}:{port}
modbus-server://[{host}]:{port}
```

Client options:

| Option | Description | Default |
|---|---|---|
| `unitId` | Default unit ID; decimal integer, UI range 0–255 (fractional values such as `1.5` fail initialization) | `1` |
| `combineData` | Combine multiple read blocks into one list | `true` |

Read request:

```json
{"address":"40001", "length":10, "unitId":1}
```

An array of read objects may be supplied. The address is a string whose leading digit selects the
table and whose remaining digits are the one-based offset inside it:

- `0xxxx`: coil
- `1xxxx`: discrete input
- `3xxxx`: input register
- `4xxxx`: holding register

So `"00001"`, `"10001"`, `"30001"`, and `"40001"` all address the first entry of their table. Four
offset digits reach 9999 entries and five reach 65536, so `"40001"` and `"400001"` are equivalent.

Write request:

```json
{"address":"40001", "values":[1,2,3], "unitId":1}
```

Write addresses use the same convention as read addresses. Only coils and holding registers are
writable; a `1xxxx` or `3xxxx` address is rejected as read-only. Holding register values must be
integers. Coil values may be `true`/`false`, or numbers where zero is false and anything else is
true.

Helpers:

```python
protocol.requestInfo(address, length, unitId)
protocol.requestInfo(address, values, unitId)
```

A client read passes `values[, receivedTime]` to `cmdFunc`. With `combineData=false`, multiple blocks are passed as a list of lists.

A Modbus server stores register and coil state in Device data, and uses the same address strings as
the client. Scripts can use:

```python
protocol.read(address, length, unitId)
protocol.write(address, values, unitId)
```

The server may write every table, including `1xxxx` and `3xxxx`, because it owns the data a client
reads. Bit-table values follow the client rule: `true`/`false`, or numbers where zero is false.

A client request triggers non-periodic commands with:

```text
address, quantity-or-values, unitId, receivedTime
```

`address` arrives as the canonical six-character string, so a request for the first holding register
is reported as `"400001"` and the first coil as `"000001"`.

### HTTP

Formats:

```text
http-client://{baseUrl}
http-server://[{host}]:{port}
```

The HTTP client base URL includes its own scheme:

```text
http-client://https://api.example.com/v1
```

Options:

| Option | Description |
|---|---|
| `cert`, `key` | PEM certificate and private key, or a keystore path |
| `format`, `password` | Keystore format and password; the default format is PKCS12 |
| `trustCert`, `trustFormat`, `trustPassword` | Trust material, applied only with `cert`. Omit both `trustFormat` and `trustPassword` for PEM; if only `trustPassword` is supplied, the format defaults to PKCS12 |
| `useByteArrayBody` | Default `false`. `true` passes the body as a byte list; otherwise UTF-8 text is parsed as JSON when valid, or kept as a string |

`trustCert` alone does not configure a custom TLS context. Use `cert` plus `key` for a PEM identity; without `key`, `cert` is a keystore and `format` defaults to PKCS12. An explicit `trustFormat` selects a Java keystore type; for PEM trust material leave both trust format and trust password empty. Paths are opened during initialization even when their TLS options are inactive, so supplied file paths must exist.

HTTP client request:

```json
{
  "method": "GET",
  "path": "/api/items",
  "basePath": "https://override.example",
  "body": "",
  "params": {"site":["A1"]},
  "headers": {"Accept":["application/json"]},
  "proxy": {"type":"HTTP", "host":"127.0.0.1", "port":8080}
}
```

HTTP client commands must be read-request commands, including POST requests. Responses pass:

```text
statusCode, body, headers, receivedTime
```

The helper is:

```python
protocol.requestInfo(
    method,
    path,
    basePath,
    body,
    params,
    proxyType,
    proxyHost,
    proxyPort,
    proxyUsername,
    proxyPassword
)
```

The first five arguments are `method, path, basePath, body, params`. They may be
followed by header name/value string pairs:

```python
protocol.requestInfo("POST", "/items", None, {"items": ["a", "b"]}, None,
                     "Content-Type", "application/json")
```

For a proxy, precede the header pairs with either `proxyHost, proxyPort` or all
five proxy fields shown above. The short form uses an integer (or `None`) port;
header values are strings. These forms use one GraalPy entry point and do not
require Java overload selection.

For both HTTP request and response helpers, dictionaries, lists, booleans, and
numbers become structured JSON bodies. Quotes, backslashes, and Unicode are
preserved on the wire. String bodies retain the existing byte-escape syntax
(`\xNN`, `\r`, `\n`, etc.). Direct request JSON also accepts structured `body`
values. Set the appropriate Content-Type header explicitly.

HTTP server requests trigger non-periodic commands with:

```text
method, path, body, params, headers, receivedTime
```

A write request supplies the response:

```json
{"httpStatusCode":200, "body":"ok", "headers":{"Content-Type":["text/plain"]}}
```

Response helper:

```python
protocol.requestInfo(200, "ok", "Content-Type", "text/plain")
```

Without a response write command, the server returns `200 OK`. An HTTP server `protocolFunc` may return a list or tuple of command IDs to route the request.

### OPC UA

Formats:

```text
opcua-client://{host}:{port}[/{path}]
opcua-server://[{host}]:{port}[/{path}]
```

The driver converts client URLs to `opc.tcp://...`.

#### OPC UA client options

| Option | Description | Default |
|---|---|---|
| `securityPolicy` | `None`, `Basic128Rsa15`, `Basic256`, `Basic256Sha256`, `Aes128_Sha256_RsaOaep`, or `Aes256_Sha256_RsaPss` | `None` |
| `securityMode` | `Sign` or `SignAndEncrypt` when the policy is not `None`; ignored with `None` policy | `None` with `None` policy; otherwise `SignAndEncrypt` |
| `username`, `password` | Username authentication; otherwise anonymous | Anonymous |
| `subscriptionNodeIds` | Comma-separated NodeIds subscribed after connection | None |
| `publishingInterval` | Subscription publishing interval in milliseconds | `1000` |
| `pkiDir` | Directory containing the persistent application identity, trust list, and rejected certificates | `pki/opcua/client/{deviceId}` |
| `keyStorePassword` | Password for `identity.pfx`; prefer the system property or environment variable below instead of storing it in JSON | See below |

For secured connections, the client uses Milo's `DefaultClientCertificateValidator`. An unknown or untrusted server certificate is rejected instead of being accepted by the insecure default validator. The client certificate and private key are stored in `identity.pfx` and reused across reconnects and process restarts, so their fingerprint remains stable.

The keystore password is resolved in this order: `keyStorePassword`, the `communicators.opcua.key-store-password` system property, the `COMMUNICATORS_OPCUA_KEY_STORE_PASSWORD` environment variable, and finally an automatically generated value in `identity.password` under `pkiDir`. Keep the PKI directory private and backed up. Deleting `identity.pfx`, or losing/changing its password, creates a new identity or prevents the existing identity from loading.

On the first connection to an untrusted peer, the certificate is placed under `{pkiDir}/rejected` and the connection fails. Verify its fingerprint through a trusted channel, copy the approved certificate into `{pkiDir}/trust/trusted/certs`, and reconnect. Do not approve a rejected certificate without verifying it.

Endpoint discovery and connect waits are bounded by `socketTimeout`.

Read request:

```json
["ns=2;s=PLC1/temp", "ns=0;i=2258"]
```

The read response passes this value to `cmdFunc`:

```text
[[nodeId, value], ...], receivedTime
```

A single `protocol.requestInfo("nodeId")` call produces a one-element read array.
Multiple NodeId arguments also produce a read array. Passing a dictionary builds
a write request and preserves arrays, numbers, booleans, nulls, and string escapes.

Write requests:

```json
{"ns=2;s=PLC1/setpoint":42.5}
```

or, when an exact OPC UA scalar type is required:

```json
[
  {"nodeId":"ns=2;s=PLC1/speed", "value":1500, "type":"Int16"}
]
```

Supported explicit types include Boolean, SByte, Byte, Int16, UInt16, Int32, UInt32, Int64, UInt64, Float, Double, String, and DateTime.

Subscription changes trigger non-periodic commands with:

```text
nodeId, value, receivedTime
```

Add subscriptions dynamically with:

```python
protocol.subscribe(["ns=2;s=PLC1/hum"])
```

#### OPC UA server options

| Option | Description | Default |
|---|---|---|
| `namespaceUri` | Namespace URI | `urn:sds:communicators:{deviceId}` |
| `securityPolicy` | Endpoint security policy; the same values as the client are supported | `Basic256Sha256` with username; otherwise `None` |
| `securityMode` | `Sign` or `SignAndEncrypt` when the policy is not `None`; ignored with `None` policy | `None` with `None` policy; otherwise `SignAndEncrypt` |
| `username`, `password` | Optional username authentication | None |
| `anonymous` | Also allow anonymous access when a username is configured | `false` with username; otherwise `true` |
| `pkiDir` | Directory containing the persistent server identity, trust list, and rejected client certificates | `pki/opcua/server/{deviceId}` |
| `keyStorePassword` | Password for `identity.pfx`; uses the same fallback order as the client | See client options |

When a username is configured without an explicit policy, the server automatically uses `Basic256Sha256` with `SignAndEncrypt`. Configuring username authentication with `SecurityPolicy.None` is rejected during initialization because it would expose the credentials. Anonymous-only servers retain the previous `None` default for compatibility; set a secure policy explicitly when anonymous access must also be protected.

Without a username, `anonymous=false` is inactive: anonymous access remains enabled. In the UI, security mode and anonymous controls show these dependencies while preserving any previously configured inactive value.

The server exposes a folder for the Device and creates variables from `Device.data`:

```text
ns=2;s={deviceId}/{name}
```

Scripts can create, update, and read nodes:

```python
protocol.write("temperature", 23.5)
value = protocol.read("temperature")
```

A client write triggers non-periodic commands with:

```text
name, value, receivedTime
```

A write-request `requestInfo` object also updates nodes:

```json
{"status":"READY"}
```

The server uses the same file-backed PKI layout and trust-enrollment workflow as the client. Its application certificate is persistent, and `DefaultServerCertificateValidator` validates client application certificates on secured endpoints.

### Dummy

Format:

```text
dummy://
```

Dummy Devices perform no network I/O. Use `REQUEST` commands to generate or transform data.

```json
[
  {
    "id": "dummy_1",
    "connectionUrl": "dummy://",
    "data": {"amplitude":10.0},
    "commands": [
      {
        "id": "generate",
        "type": "REQUEST",
        "periodGroup": 1000,
        "cmdScript": "count = 0\n\ndef cmdFunc(receivedTime):\n    global count\n    count += 1\n    return [('count', str(count), receivedTime)]"
      }
    ]
  }
]
```

## Python scripting

GraalPy runs Python 3. Scripts load in this order for each Device:

```text
defaultScript -> each cmdScript -> protocolScript
```

A name declared only by `protocolScript` is not available to module-level code in `cmdScript`, because the command script is loaded first. Referring to it inside a function body is valid because lookup occurs when the function runs.

Available globals:

- `log`: SLF4J logger
- `protocol`: this Device's `DriverProtocol`
- `UtilFunc`: `com.sds.communicators.common.UtilFunc`
- `java`: Java package access

### Command functions

`cmdFunc` is required for every READ and REQUEST type. WRITE types do not call it. A static `Command.requestInfo` or a Python `requestInfo` callback is required at compilation for WRITE types and READ types with `periodGroup >= 0`. Direct protocol I/O also needs a request at execution time, even when a negative period group allowed compilation without one. `delay` and `control` are optional.

This Modbus client example is for periodic/lifecycle or REST execution **without** `initial-value`:

```python
def cmdFunc(received, receivedTime):
    return [("temperature", str(received[0]), receivedTime)]

def requestInfo():
    return protocol.requestInfo("40001", 10)

def delay():
    return 1000

def control(commandList, idx, exception):
    return None
```

### Argument pool

The runtime builds an argument pool and supplies as many leading values as the Python function declares.

| Position | Value | Included when |
|---|---|---|
| First | `initialValue` | A REST `initial-value` header or `protocol.executeCommands` value is present |
| Middle | Protocol-specific received values | Incoming event or the response to a protocol read |
| Last | `receivedTime` in epoch milliseconds | Incoming event, protocol read response, or `REQUEST` execution |

Rules:

- Declaring more parameters than the pool contains is an error.
- `initialValue`, when present, is always first.
- `receivedTime` is supplied only when the function declares the complete pool; trailing values are omitted first.
- Binding is positional, regardless of parameter names. For TCP, `def cmdFunc(received, receivedTime)` receives the sender in its second parameter. Use `def cmdFunc(received, sender, receivedTime)` to receive the timestamp.
- Python default parameter values do not increase the available pool. Use fixed positional parameters; do not rely on names or `*args` to select values.

Protocol receive pools:

| Situation | Pool before `receivedTime` |
|---|---|
| TCP/UDP | `received`, `sender` |
| Modbus client read | `values` |
| Modbus server request | `address`, `quantity` or `values`, `unitId` |
| HTTP client response | `statusCode`, `body`, `headers` |
| HTTP server request | `method`, `path`, `body`, `params`, `headers` |
| OPC UA client read | `received` |
| OPC UA subscription | `nodeId`, `value` |
| OPC UA server write | `name`, `value` |
| Any REQUEST type (including Dummy) | No receive values; `cmdFunc` receives the current time, preceded by `initialValue` when supplied |

### Execution path and request functions

These are three different interfaces:

| Interface | Purpose |
|---|---|
| `Command.requestInfo` field | Static string in the protocol's request format (raw text or serialized JSON) |
| `def requestInfo(...)` in `cmdScript` | Callback that constructs a request; returns a string, or `None` to use the static field / skip if empty |
| `protocol.requestInfo(...)` | Protocol-specific Java helper that serializes a request string; it neither executes I/O nor shares the callback's argument list |

The callback must not return a Python dict or list directly. Use a supported helper or `json.dumps(...)`.

| Execution | `requestInfo` callback pool | `cmdFunc` pool |
|---|---|---|
| Periodic / starting / stopping protocol I/O | Empty: `def requestInfo():` | Protocol read-response pool above, including time; WRITE does not call `cmdFunc` |
| REST protocol I/O, no `initial-value` | Empty | Protocol read-response pool including time |
| REST protocol I/O, with `initial-value` | `initialValue` only | `initialValue` followed by protocol read-response pool including time |
| Incoming event, exact `READ_REQUEST` | Not called; static request is also unused | Incoming event pool including time |
| Incoming event, WRITE or lifecycle READ | Incoming event pool including time | WRITE: not called; lifecycle READ: subsequent protocol read-response pool including time |
| REQUEST / STARTING_REQUEST / STOPPING_REQUEST | Not called; static request is unused | `receivedTime`, or `initialValue, receivedTime` when supplied; incoming values are not passed |

Only exact `READ_REQUEST` consumes an event without sending a request. Automatic non-periodic execution selects all commands with `periodGroup < 0`, including STARTING/STOPPING types. A lifecycle READ selected by an event issues a new request; its callback arguments therefore differ between lifecycle and event execution. TCP/UDP and HTTP server `protocolFunc` can explicitly select command IDs. Review every path on which a registered command may run; an event-only signature is not generally valid when invoked through REST.

Protocol restrictions:

- HTTP client uses READ types for all methods, including POST/PUT/DELETE; WRITE types are unsupported.
- HTTP server event READ consumes an incoming request; event WRITE sends a response. REST/periodic/lifecycle replies have no incoming response channel and fail. Direct READ calls are unsupported.
- Modbus server event READ observes requests. Its direct read/write command I/O is a no-op; use REQUEST scripts with `protocol.read` / `protocol.write` for local data.
- OPC UA client READ response arguments are `received, receivedTime`, where `received` contains node/value pairs. Subscription event arguments are `nodeId, value, receivedTime`.
- OPC UA server event READ observes writes; WRITE updates local nodes by name. Direct READ is unsupported. It has no `protocol.requestInfo` helper: use a JSON string or `json.dumps` for writes.
- Dummy has no protocol I/O or receive-event source. Use REQUEST scripts.

The UI shows signatures for these execution paths, marks inactive options, and keeps existing scripts when the preview or protocol changes. The Commands tab uses the selected device's protocol and actual `initial-value` field; the Devices tab provides a preview selector and an initial-value toggle. Inserting a skeleton fills only an empty script and does not validate Python code or make one signature valid for every path.

### Return contracts

| Function | Return | Behavior |
|---|---|---|
| `cmdFunc` | `[(tagId, value), (tagId, value, time), ...]` | Emit Responses |
| `cmdFunc` | `None` or an empty list | Emit nothing |
| `requestInfo` | String | Use the returned protocol request |
| `requestInfo` | `None` | Fall back to `Command.requestInfo`; skip if that is also empty |
| `delay` | Integer | Delay in milliseconds |
| `delay` | `None` | Use `Command.afterDelay` |
| `control` | `None` | Continue to the next command |
| `control` | Integer | Jump to that command-list index |
| `control` | Exception | Raise the exception |

A two-element Response tuple uses the current received time. Values are converted to strings. `control` must declare exactly two or three parameters.

Example using `initial-value`:

```text
POST /driver/execute-command-ids/modbus_plc_1
initial-value: %7B%22speed%22%3A1500%7D        <- URL-encoded {"speed":1500}
Body: ["write_speed"]
```

The `initial-value` header value must be URL-encoded (UTF-8); the server decodes it before use.
This is what allows values containing non-Latin-1 text (for example Korean), which raw HTTP
headers cannot carry.

```python
def requestInfo(initialValue):
    return protocol.requestInfo("40003", [int(initialValue["speed"])])
```

### Protocol script functions

```python
def protocolFunc(received, sender, receivedTime):
    return None

def bufferingFunc(buffer):
    return True
```

For TCP and UDP, `protocolFunc` may return:

- `None`: route to a waiting read request
- A command ID string: route to that read request
- A list or tuple of command IDs: run those non-periodic commands

For HTTP server, only a list or tuple of command IDs is supported.

HTTP server's full signature is `def protocolFunc(method, path, body, params, headers, receivedTime):`. Other non-Dummy protocols execute `protocolScript` as initialization code but do not invoke `protocolFunc` or `bufferingFunc`. Dummy does not execute `protocolScript`. Use command callbacks for Modbus/OPC UA receive events.

### Main protocol object methods

```python
protocol.setData({"key": value})
protocol.getData(["key", "child"])
protocol.deleteData(["key"])
protocol.getResponse()
protocol.getDeviceStatus()
protocol.executeCommands(deviceId, initialValue, commands)
protocol.setConnectionLost()
protocol.setDisconnected()
protocol.getDeviceIdMap()
protocol.getClusterNodes()
```

Device data is stored in the cluster shared object. `setData` and `deleteData` accept JSON values only: dicts with
string, number, or bool keys, lists and tuples, strings, ints, floats, bools, and `None`. A float keeps its type
(`25.0` is stored as `25.0`), and enums and beans returned by the `protocol` API are stored in their JSON form.
Anything else, such as `datetime`, `Decimal`, `set`, NaN, or Infinity, raises an error in the calling script and
leaves the data unchanged.

`setData` and `deleteData` change only the data of a Device that is registered on this node. A script that still runs
after its Device was disconnected gets the error `device <id> is not registered on this node`, and nothing is stored.
Module-level `protocolScript` code runs while the Device is being connected, before it is registered, and may call
`setData` there. The Device's configured `data` (on failover, the data carried over from the lost node) is then merged
over what it wrote, so those writes act as defaults. If the connect fails at that stage, the written data is removed.
With an empty path, `setData` accepts only a dict, because a Device's data must stay an object. `getData` returns a
copy, so changing the returned value does not change the stored data.

## REST API

The default base path is `/driver`.

| Method | Path | Description |
|---|---|---|
| `GET` | `/` | Serve the Web UI |
| `POST` | `/balanced-connect-all` | Connect a Device array and distribute it according to load-balancing settings |
| `POST` | `/connect-all` | Connect a Device array on the selected node through leader coordination |
| `DELETE` | `/disconnect` | Disconnect Device IDs supplied as a JSON array |
| `DELETE` | `/disconnect-all` | Disconnect every Device |
| `PUT` | `/reconnect-all` | Reconnect every Device |
| `GET` | `/device-status` | Return all Device statuses |
| `GET` | `/device-status/{deviceId}` | Return one Device status |
| `GET` | `/device-id-map` | Return `nodeIndex -> deviceId[]` |
| `GET` | `/devices` | Return cluster-stored Device settings as a JSON array |
| `GET` | `/response` | Return all collected Responses |
| `GET` | `/response/{deviceId}` | Return one Device's Responses |
| `POST` | `/execute-commands/{deviceId}` | Run supplied Commands and emit Responses |
| `POST` | `/request-commands/{deviceId}` | Run supplied Commands and return results without output emission |
| `POST` | `/execute-command-ids/{deviceId}` | Run registered command IDs and emit Responses |
| `POST` | `/request-command-ids/{deviceId}` | Run registered command IDs without output emission |

While the cluster is not activated (quorum not reached), `connect-all` and `balanced-connect-all` refuse every Device
with `connect failed, cluster is not activated (quorum not reached)`, and the node does not take over the Devices of
lost nodes.

A Device found on two member nodes stays on the node with the lower `nodeIndex`. This happens, for example, after a
split brain heals, or when a node stalls past the lost-node timeout and keeps its Devices while the leader fails them
over. The higher node disconnects its copy without reconnecting it, but only after the lower node confirms through
`device-status` that it runs the Device. Each node runs this check every five heartbeat intervals and when a peer's
shared object is replaced. It also runs it two heartbeat intervals after a node joins, after it steps down to follower,
and after it resolves a split brain. Each run checks every member, and only one runs at a time: a check requested while
one is running makes that one run exactly once more afterwards. A Device the node is still connecting is left for a
later check. The lower node must confirm within the cluster's probe timeout (two heartbeat intervals, at least one
second, never longer than the read timeout). Each lower node is asked on its own, so a slow one does not delay the yield
of a Device duplicated with another.

If a lower node does not confirm within the probe timeout, the higher node keeps its copy and asks that node again right
away, on a task of its own outside the check, with twice that timeout. Each further timeout in a row doubles the time
allowed, up to the read timeout. While such a request runs, the checks never wait on that node, so no check waits for it
longer than one probe timeout. When the node answers, or the request times out, the check runs again: it uses the answer,
or asks once more with the longer time. A lower node that answers within the probe timeout again is asked by the check
itself from then on; one that answers later keeps its longer time. Only the first timeout in a row is logged as a
warning, the ones after it at debug level.

Once such a request has run for a probe timeout, each check also probes the node: it asks it once more within the probe
timeout, also on a task of its own, with at most one probe per node at a time. Whichever answers first is used. So a
node that recovers while the longer request is stuck in it confirms within about one check interval, not only when that
request answers or times out. An answer to a probe resets the longer time, and the longer request is cancelled, so its
late answer is never used. A probe that fails changes nothing and is logged at debug level only. The longer request
still runs, so a node that stays slow still confirms.

A missing or invalid Device ID is refused with `connect failed, invalid device-id`. A node that already runs a Device
keeps it and answers `connect failed, device is already connected on this node`.

When a node loses quorum, it waits two heartbeat intervals and disconnects all its Devices only if it is still not
activated, so a short stall (for example, a GC pause) does not drop them. For each Device it disconnects, it remembers
the current setting from its own shared object, including the data its scripts wrote since the connect. When the node
is activated again, it waits two heartbeat intervals and reconnects those Devices through the balanced connect, except
the ones that another node registered meanwhile. A Device that gets no result, or is refused because the node lost
quorum again, is kept for the next activation.

When the leader removes a lost node, it waits four heartbeat intervals and then connects that node's Devices on the
remaining nodes, if it still has quorum. The wait lets a node that was only partitioned away, whose own timers can fire
up to one interval later, start disconnecting its Devices first. It skips entries of the lost node's shared object
that are not Device settings and drops Device data that is not an object, and it still fails over every other Device. `device-id-map` and `devices` list Device settings only, which are entries with `id` and
`connectionUrl`.

If the lost node is a member again when the wait ends, for example because the partition healed meanwhile, the leader
asks it through `device-status`, within the same probe timeout, and leaves out of the failover the Devices it confirms to
run. A restarted node that does not run them, or a node that does not answer in time, still gets them failed over.

The four command endpoints accept an optional `initial-value` header, whose value must be URL-encoded (UTF-8). The command-ID endpoints accept a JSON string array; the command endpoints accept a JSON Command array.

The command endpoints execute the supplied definitions, even if an ID matches a
registered command. They do not replace that registration. The command-ID
endpoints, periodic execution, and event execution continue to use the registered
definitions. Python module-level globals still share the Device's script context.

Follower-to-leader delegation and leader-to-node connection distribution use the internal routes under `{driverBasePath}/internal` on the node's HTTP port. Those calls, and the cross-node command, status and response calls, go through the `NodeHttpClient` that `cluster-starter` owns, so they share its connection pool to each peer.

## Web UI

Open:

```text
http://{host}:{port}{driverBasePath}/
```

The UI contains Devices, Nodes, and Responses tabs.

### Devices

The upper section shows cluster-wide Device placement and status. It provides:

- Manual and five-second automatic refresh
- Per-Device disconnect
- Disconnect all
- Reconnect all
- Export connected settings

The structured Device builder provides:

- Device add, duplicate, and remove actions
- Protocol selection before connection details
- Separate fields for all Device properties
- Protocol-specific connection options
- Custom URL query options
- Python editors for `protocolScript` and every Command's `cmdScript`
- Command add, edit, duplicate, and remove actions
- Separate fields for every Command property
- Generated JSON preview
- Connect through `POST /driver/balanced-connect-all`
- Import from a Device JSON array
- Save the current draft to `devices.json`
- Load connected settings into the form
- Export connected settings returned by `GET /driver/devices`

Imported Device fields that are not part of the current Java model are retained when the form is saved again. Unrecognized connection URL query parameters appear as custom options and are also retained. Switching to a different protocol resets options associated with the previous protocol.

### Nodes

The Nodes tab displays cluster membership, leader/follower position, activation state, and Device counts. It can force a node to leader or follower through redirect routes.

### Responses

The Responses tab displays `deviceId`, `tagId`, `value`, and local-formatted `receivedTime`. It supports Device filtering and five-second automatic refresh.

## UI development

The frontend source is in `driver-starter/ui`. See its [development guide](driver-starter/ui/README.md).

```bash
cd driver-starter/ui
npm install
npm run lint
npm run dev
npm run build
```

The development proxy targets `http://localhost:4001`. Production output is written to `driver-starter/src/main/resources/static` and packaged into the driver JAR.

The production HTML retains `__APP_BASE_PATH__`; `DriverServerRoutes` replaces it with `driverBasePath` when serving the application. Relative asset URLs allow custom base paths.
