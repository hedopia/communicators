import type { CommandDraft, ConnectionDraft, OptionDefinition, ProtocolId } from "./deviceForm";

export type ExecutionContext = "event" | "scheduled" | "rest";
export const isScriptRequest = (type: string) => !type.includes("READ_") && !type.includes("WRITE_");

export function connectionCommandSetting(protocol: ProtocolId, value: boolean) {
  if (protocol.endsWith("-server")) return { value: false, locked: true, hint: "Server protocols always keep the connection open (false)." };
  if (protocol === "http-client") return { value: true, locked: true, hint: "HTTP client always connects per request (true)." };
  return { value, locked: false, hint: "Default: false. Enable to connect only while a command runs." };
}

export function optionPresentation(connection: ConnectionDraft, option: OptionDefinition, protocolScript = "") {
  const { protocol, options } = connection;
  let defaultValue = option.placeholder;
  let hint = option.hint;
  let disabled = false;
  switch (option.key) {
    case "connectionLostOnException": defaultValue = String(!protocol.endsWith("-server")); break;
    case "retainStartEndBytes": defaultValue = "false"; break;
    case "combineBufferedData":
      defaultValue = "true";
      hint = "true: combine packets into one byte list; false: pass a list of packet byte lists.";
      break;
    case "combineData":
      defaultValue = "true";
      hint = "true: flatten read blocks; false: values is a list of lists. This remains one callback argument.";
      break;
    case "useByteArrayBody":
      defaultValue = "false";
      hint = "true: received body is a byte list; false: parse UTF-8 text as JSON when valid, otherwise keep it as a string.";
      break;
    case "startBytes": case "endBytes":
      hint = "Enter literal escape text, e.g. \\x0D\\x0A, not 0x0D0A. URL encoding is automatic.";
      break;
    case "bufferTime":
      defaultValue = protocol.startsWith("udp-") || options.endBytes || /def\s+bufferingFunc\s*\(/.test(protocolScript) ? "0" : "100";
      hint = "Default: UDP 0; TCP 100 only without endBytes and bufferingFunc, otherwise 0. startBytes alone does not change it. Script-defined bufferingFunc is resolved at runtime.";
      break;
    case "trustCert": case "trustPassword":
      hint = "Applied only when cert is configured. trustCert alone does not enable this custom TLS configuration.";
      break;
    case "trustFormat":
      defaultValue = options.trustPassword ? "PKCS12" : "PEM (omit format and password)";
      hint = "Requires cert and trustCert. Omit both trustFormat and trustPassword for PEM; supplying only trustPassword selects PKCS12. An explicit format is a Java keystore type.";
      break;
    case "securityPolicy":
      defaultValue = protocol === "opcua-server" && options.username ? "Basic256Sha256" : "None";
      hint = protocol === "opcua-server" ? "Username authentication requires a secure policy." : "Default: None. Select a secure policy to apply securityMode.";
      break;
    case "securityMode": {
      const policy = options.securityPolicy || (protocol === "opcua-server" && options.username ? "Basic256Sha256" : "None");
      disabled = policy === "None";
      defaultValue = disabled ? "None (policy is None)" : "SignAndEncrypt";
      hint = disabled ? "Inactive: effective mode is None. Any saved value is retained and applies when a secure policy is selected." : "Applies only with a secure policy. Default: SignAndEncrypt.";
      break;
    }
    case "anonymous":
      defaultValue = options.username ? "false" : "true";
      disabled = !options.username;
      hint = disabled ? "Without username, anonymous access is always enabled; any saved value is inactive." : "With username, anonymous access defaults to false.";
      break;
    case "unitId": hint = "Decimal integer from 0 to 255. Default: 1."; break;
  }
  return { defaultValue, hint, disabled };
}

const responsePools: Partial<Record<ProtocolId, string[]>> = {
  "tcp-client": ["received", "sender"], "tcp-server": ["received", "sender"],
  "udp-client": ["received", "sender"], "udp-server": ["received", "sender"],
  "modbus-client": ["values"], "http-client": ["statusCode", "body", "headers"],
  "opcua-client": ["received"],
};
const eventPools: Partial<Record<ProtocolId, string[]>> = {
  "tcp-client": ["received", "sender"], "tcp-server": ["received", "sender"],
  "udp-client": ["received", "sender"], "udp-server": ["received", "sender"],
  "modbus-server": ["address", "quantityOrValues", "unitId"],
  "http-server": ["method", "path", "body", "params", "headers"],
  "opcua-client": ["nodeId", "value"], "opcua-server": ["name", "value"],
};

export function requestExample(protocol: ProtocolId, write: boolean) {
  if (protocol.startsWith("tcp-") || protocol.startsWith("udp-")) return {
    value: protocol.endsWith("-server") ? '{"message":"PING","host":"127.0.0.1","port":5000}' : "PING\\x0D\\x0A",
    helper: protocol.endsWith("-server") ? 'protocol.requestInfo("PONG", sender)' : 'protocol.requestInfo("PING\\x0D\\x0A")',
    hint: "Raw string or JSON message/host/port. Server sender helper is for a receive event; use explicit host/port otherwise. TCP server {\"message\":\"PING\"} broadcasts to connected clients.",
  };
  if (protocol === "modbus-client") return {
    value: write ? '{"address":"40001","values":[123],"unitId":1}' : '{"address":"40001","length":10,"unitId":1}',
    helper: write ? 'protocol.requestInfo("40001", [123], 1)' : 'protocol.requestInfo("40001", 10, 1)',
    hint: "Address is a string with a table prefix and a one-based offset. Arrays of read/write blocks are also supported.",
  };
  if (protocol === "http-client") return {
    value: '{"method":"GET","path":"/status"}',
    helper: 'protocol.requestInfo("GET", "/status", None, None, None)',
    hint: "Use READ_REQUEST for every HTTP method, including POST. body may be a string, object or array; params maps names to string arrays. Optional headers follow the five base arguments as name/value pairs.",
  };
  if (protocol === "http-server") return {
    value: '{"httpStatusCode":200,"body":{"ok":true},"headers":{"Content-Type":["application/json"]}}',
    helper: 'protocol.requestInfo(200, {"ok": True}, "Content-Type", "application/json")',
    hint: "WRITE_REQUEST sends this response only while handling an incoming HTTP request. READ_REQUEST consumes the incoming request without using requestInfo.",
  };
  if (protocol === "opcua-client") return {
    value: write ? '{"ns=2;s=Tag1":123}' : '["ns=2;s=Tag1"]',
    helper: write ? 'protocol.requestInfo({"ns=2;s=Tag1": 123})' : 'protocol.requestInfo("ns=2;s=Tag1")',
    hint: "Read: NodeId string/array. Write: NodeId-to-value object or typed array of {nodeId, value, type}. A read response contains (nodeId, value) pairs; a subscription has separate nodeId and value arguments.",
  };
  if (protocol === "opcua-server") return {
    value: '{"temperature":25}', helper: 'json.dumps({"temperature": 25})  # import json first',
    hint: "WRITE_REQUEST updates local nodes by name. There is no protocol.requestInfo helper for this server.",
  };
  return { value: "", helper: "", hint: protocol === "dummy" ? "Use REQUEST to run a script; dummy has no protocol I/O." : "Use READ_REQUEST for incoming Modbus events, or REQUEST with protocol.read / protocol.write for local data. No requestInfo helper or command I/O is implemented." };
}

export function commandGuidance(connection: ConnectionDraft | undefined, command: CommandDraft, context: ExecutionContext, initialValue = false) {
  const warnings: string[] = [];
  const protocol = connection?.protocol;
  const scriptOnly = isScriptRequest(command.type);
  const write = command.type.includes("WRITE_");
  const eventRead = context === "event" && command.type === "READ_REQUEST";
  const initial = context === "rest" && initialValue ? ["initialValue"] : [];
  const eventPool = protocol ? eventPools[protocol] : undefined;
  let cmdArgs: string[] | null = null;
  let requestArgs: string[] | null = null;
  if (scriptOnly) cmdArgs = [...initial, "receivedTime"];
  else if (protocol) {
    if (!write) {
      const pool = eventRead ? eventPool : responsePools[protocol];
      if (pool) cmdArgs = [...initial, ...pool, "receivedTime"];
    }
    if (!eventRead && (context !== "event" || eventPool)) requestArgs = [...initial, ...(context === "event" ? [...eventPool!, "receivedTime"] : [])];
  }
  if (!protocol) warnings.push("Protocol details are unavailable. Select a device with a known connectionUrl to see protocol-specific arguments.");
  if (context === "event" && protocol && !eventPool) warnings.push("This protocol has no incoming-event command path. Use scheduled/lifecycle or REST execution.");
  if (context === "event" && command.periodGroup >= 0) warnings.push("Automatic receive-event execution selects periodGroup < 0. Explicit protocolFunc routing can select command IDs separately.");
  if (command.type.startsWith("STARTING_") || command.type.startsWith("STOPPING_")) warnings.push("Lifecycle commands with periodGroup < 0 can also be selected by incoming events. Only exact READ_REQUEST directly consumes received values; lifecycle READ types issue a new request.");
  if (!scriptOnly && protocol === "http-client" && write) warnings.push("HTTP client does not support WRITE_REQUEST. Use READ_REQUEST for POST/PUT/DELETE as well as GET.");
  if (!scriptOnly && protocol === "http-server" && !(context === "event" && (write || eventRead))) warnings.push("HTTP server needs an incoming request: event READ_REQUEST reads it; event WRITE_REQUEST replies. Direct reads and REST/lifecycle replies are unsupported.");
  if (!scriptOnly && (protocol === "modbus-server" || protocol === "dummy") && !eventRead) warnings.push("This protocol implements no read/write command I/O. Use REQUEST for local scripting.");
  if (!scriptOnly && protocol === "opcua-server" && !write && !eventRead) warnings.push("OPC UA server does not support direct READ commands; use event READ_REQUEST or REQUEST for local scripting.");
  if (!scriptOnly && !eventRead && !command.requestInfo && !command.cmdScript) warnings.push("Provide static requestInfo or a requestInfo callback before executing protocol I/O.");
  if (!write && !command.cmdScript) warnings.push("READ and REQUEST types require a cmdFunc definition, including event reads.");
  const example = protocol ? requestExample(protocol, write) : { value: "", helper: "", hint: "Protocol request format is unknown." };
  const requestHint = scriptOnly ? "Unused: REQUEST types run cmdFunc without protocol I/O." : eventRead ? "Unused for this event READ_REQUEST: received values go directly to cmdFunc. A periodic READ still requires requestInfo at compilation." : "Static protocol request. If def requestInfo exists, its string result overrides this field; None falls back to this field, or skips when empty. Without a callback, an empty request is an error.";
  const signature = (name: string, args: string[]) => `def ${name}(${args.join(", ")}):`;
  const supportsContext = context !== "event" || !!eventPool;
  const supportsIo = scriptOnly || (eventRead && !!eventPool) || (
    protocol !== "dummy" && protocol !== "modbus-server" &&
    (protocol !== "http-client" || !write) &&
    (protocol !== "http-server" || (context === "event" && write)) &&
    (protocol !== "opcua-server" || write)
  );
  const template = protocol && supportsContext && supportsIo ? [
    ...(requestArgs ? [signature("requestInfo", requestArgs) + "\n    # Return a protocol request string, or use the static field.\n    return None"] : []),
    ...(cmdArgs ? [signature("cmdFunc", cmdArgs) + "\n    # Return [(\"tag\", str(value), receivedTime)] to emit values.\n    return []"] : []),
  ].join("\n\n") : "";
  return { cmdArgs, requestArgs, requestHint, example, warnings, template };
}

export function protocolScriptGuidance(protocol: ProtocolId) {
  if (protocol.startsWith("tcp-") || protocol.startsWith("udp-")) return {
    signature: "def protocolFunc(received, sender, receivedTime):",
    hint: "Return None or a command-ID string to route to a waiting read, or a list/tuple of IDs to run event commands. Without protocolFunc, incoming data also runs non-periodic commands. Optional bufferingFunc(buffer) controls packet framing.",
  };
  if (protocol === "http-server") return {
    signature: "def protocolFunc(method, path, body, params, headers, receivedTime):",
    hint: "Return a list/tuple of command IDs to handle the HTTP request. Other return types are unsupported. Without protocolFunc, non-periodic commands handle requests.",
  };
  return { signature: "# Protocol initialization code", hint: protocol === "dummy" ? "Dummy does not execute protocolScript. Put executable code in cmdScript." : "This protocol executes initialization code but does not call protocolFunc or bufferingFunc. Use command callbacks for received values." };
}
