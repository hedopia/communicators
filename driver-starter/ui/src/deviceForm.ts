import type { Command, CommandType, Device } from "./types";

export const COMMAND_TYPES: CommandType[] = [
  "READ_REQUEST",
  "STARTING_READ_REQUEST",
  "STOPPING_READ_REQUEST",
  "WRITE_REQUEST",
  "STARTING_WRITE_REQUEST",
  "STOPPING_WRITE_REQUEST",
  "REQUEST",
  "STARTING_REQUEST",
  "STOPPING_REQUEST",
];

export type ProtocolId =
  | "tcp-client"
  | "tcp-server"
  | "udp-client"
  | "udp-server"
  | "modbus-client"
  | "modbus-server"
  | "http-client"
  | "http-server"
  | "opcua-client"
  | "opcua-server"
  | "dummy";

export type TargetKind = "none" | "host-port" | "base-url" | "opc";
export type OptionKind = "text" | "password" | "number" | "boolean" | "select";

export interface OptionDefinition {
  key: string;
  label: string;
  kind: OptionKind;
  placeholder?: string;
  hint?: string;
  choices?: string[];
}

export interface ProtocolDefinition {
  id: ProtocolId;
  label: string;
  target: TargetKind;
  defaultHost: string;
  defaultPort: string;
  defaultPath?: string;
  hint: string;
  options: OptionDefinition[];
}

const connectionLostOption: OptionDefinition = {
  key: "connectionLostOnException",
  label: "예외 발생 시 연결 끊김 처리",
  kind: "boolean",
  hint: "미설정 시 프로토콜 기본값을 사용합니다.",
};

const streamOptions: OptionDefinition[] = [
  {
    key: "startBytes",
    label: "시작 바이트",
    kind: "text",
    placeholder: "\\x02",
  },
  {
    key: "endBytes",
    label: "종료 바이트",
    kind: "text",
    placeholder: "\\x0D\\x0A",
  },
  {
    key: "retainStartEndBytes",
    label: "시작/종료 바이트 유지",
    kind: "boolean",
  },
  {
    key: "combineBufferedData",
    label: "버퍼 데이터 결합",
    kind: "boolean",
  },
  {
    key: "bufferTime",
    label: "버퍼 시간 (ms)",
    kind: "number",
    placeholder: "100",
  },
];

const httpOptions: OptionDefinition[] = [
  { key: "cert", label: "인증서/키스토어 경로", kind: "text" },
  { key: "format", label: "키스토어 형식", kind: "text", placeholder: "PKCS12" },
  { key: "password", label: "키/키스토어 비밀번호", kind: "password" },
  { key: "key", label: "PEM 개인키 경로", kind: "text" },
  { key: "trustCert", label: "Trust 인증서/키스토어 경로", kind: "text" },
  { key: "trustFormat", label: "Trust 키스토어 형식", kind: "text", placeholder: "PKCS12" },
  { key: "trustPassword", label: "Trust 키스토어 비밀번호", kind: "password" },
  { key: "useByteArrayBody", label: "Body를 byte[]로 처리", kind: "boolean" },
];

const opcuaSecurityPolicies = [
  "None",
  "Basic128Rsa15",
  "Basic256",
  "Basic256Sha256",
  "Aes128_Sha256_RsaOaep",
  "Aes256_Sha256_RsaPss",
];

const opcuaPkiOptions: OptionDefinition[] = [
  {
    key: "pkiDir",
    label: "PKI 디렉터리",
    kind: "text",
    hint: "영구 identity.pfx, trust list, rejected 인증서를 저장합니다.",
  },
  {
    key: "keyStorePassword",
    label: "Identity 키스토어 비밀번호",
    kind: "password",
    hint: "비워 두면 system property, 환경 변수 또는 PKI 디렉터리의 자동 생성 비밀번호를 사용합니다.",
  },
];

const opcuaClientOptions: OptionDefinition[] = [
  {
    key: "securityPolicy",
    label: "Security policy",
    kind: "select",
    choices: opcuaSecurityPolicies,
    placeholder: "None (기본값)",
  },
  {
    key: "securityMode",
    label: "Security mode",
    kind: "select",
    choices: ["Sign", "SignAndEncrypt"],
    placeholder: "SignAndEncrypt (기본값)",
  },
  { key: "username", label: "사용자 이름", kind: "text" },
  { key: "password", label: "비밀번호", kind: "password" },
  {
    key: "subscriptionNodeIds",
    label: "구독 NodeId",
    kind: "text",
    placeholder: "ns=2;s=PLC1/temp,ns=2;s=PLC1/alarm",
    hint: "여러 NodeId는 쉼표로 구분합니다.",
  },
  {
    key: "publishingInterval",
    label: "Publishing interval (ms)",
    kind: "number",
    placeholder: "1000",
  },
  ...opcuaPkiOptions,
];

const opcuaServerOptions: OptionDefinition[] = [
  {
    key: "namespaceUri",
    label: "Namespace URI",
    kind: "text",
    placeholder: "urn:sds:communicators:{deviceId}",
  },
  {
    key: "securityPolicy",
    label: "Security policy",
    kind: "select",
    choices: opcuaSecurityPolicies,
    placeholder: "username 사용 시 Basic256Sha256, 그 외 None",
  },
  {
    key: "securityMode",
    label: "Security mode",
    kind: "select",
    choices: ["Sign", "SignAndEncrypt"],
    placeholder: "SignAndEncrypt (기본값)",
  },
  { key: "username", label: "사용자 이름", kind: "text" },
  { key: "password", label: "비밀번호", kind: "password" },
  {
    key: "anonymous",
    label: "익명 접속 병행 허용",
    kind: "boolean",
    hint: "username을 지정한 경우에만 적용됩니다.",
  },
  ...opcuaPkiOptions,
];

const withCommon = (options: OptionDefinition[] = []) => [
  ...options,
  connectionLostOption,
];

export const PROTOCOLS: ProtocolDefinition[] = [
  {
    id: "tcp-client",
    label: "TCP Client",
    target: "host-port",
    defaultHost: "127.0.0.1",
    defaultPort: "5000",
    hint: "TCP 서버에 연결해 byte/string 요청과 응답을 처리합니다.",
    options: withCommon(streamOptions),
  },
  {
    id: "tcp-server",
    label: "TCP Server",
    target: "host-port",
    defaultHost: "",
    defaultPort: "5000",
    hint: "지정 포트에서 TCP client 연결을 수신합니다. host는 비워둘 수 있습니다.",
    options: withCommon(streamOptions),
  },
  {
    id: "udp-client",
    label: "UDP Client",
    target: "host-port",
    defaultHost: "127.0.0.1",
    defaultPort: "5000",
    hint: "지정 host/port로 UDP datagram을 송수신합니다.",
    options: withCommon(streamOptions),
  },
  {
    id: "udp-server",
    label: "UDP Server",
    target: "host-port",
    defaultHost: "",
    defaultPort: "5000",
    hint: "UDP datagram을 수신하며 multicast group도 설정할 수 있습니다.",
    options: withCommon([
      ...streamOptions,
      {
        key: "multicastGroup",
        label: "Multicast group",
        kind: "text",
        placeholder: "239.0.0.1,239.0.0.2",
      },
    ]),
  },
  {
    id: "modbus-client",
    label: "Modbus TCP Client",
    target: "host-port",
    defaultHost: "127.0.0.1",
    defaultPort: "502",
    hint: "Modbus TCP slave/server에 연결합니다.",
    options: withCommon([
      { key: "unitId", label: "기본 Unit ID", kind: "number", placeholder: "1" },
      { key: "combineData", label: "여러 블록 데이터 결합", kind: "boolean" },
    ]),
  },
  {
    id: "modbus-server",
    label: "Modbus TCP Server",
    target: "host-port",
    defaultHost: "",
    defaultPort: "502",
    hint: "Device data를 Modbus address space로 제공하는 server입니다.",
    options: withCommon(),
  },
  {
    id: "http-client",
    label: "HTTP Client",
    target: "base-url",
    defaultHost: "",
    defaultPort: "",
    hint: "전체 base URL을 입력합니다. 예: https://api.example.com/v1",
    options: withCommon(httpOptions),
  },
  {
    id: "http-server",
    label: "HTTP Server",
    target: "host-port",
    defaultHost: "",
    defaultPort: "8080",
    hint: "지정 포트에서 HTTP 요청을 수신합니다.",
    options: withCommon(httpOptions),
  },
  {
    id: "opcua-client",
    label: "OPC UA Client",
    target: "opc",
    defaultHost: "127.0.0.1",
    defaultPort: "4840",
    defaultPath: "",
    hint: "OPC UA endpoint에 연결하고 read/write/subscription을 수행합니다.",
    options: withCommon(opcuaClientOptions),
  },
  {
    id: "opcua-server",
    label: "OPC UA Server",
    target: "opc",
    defaultHost: "",
    defaultPort: "4840",
    defaultPath: "",
    hint: "Device data를 OPC UA variable node로 노출합니다.",
    options: withCommon(opcuaServerOptions),
  },
  {
    id: "dummy",
    label: "Dummy",
    target: "none",
    defaultHost: "",
    defaultPort: "",
    hint: "외부 연결 없이 script와 command 동작을 시험합니다.",
    options: withCommon(),
  },
];

export const protocolDefinition = (protocol: ProtocolId) =>
  PROTOCOLS.find((definition) => definition.id === protocol) ?? PROTOCOLS[0];

export interface KeyValueDraft {
  key: string;
  name: string;
  value: string;
}

export interface ConnectionDraft {
  protocol: ProtocolId;
  host: string;
  port: string;
  path: string;
  baseUrl: string;
  options: Record<string, string>;
  customOptions: KeyValueDraft[];
}

export interface CommandDraft {
  key: string;
  id: string;
  order: number;
  type: CommandType;
  periodGroup: number;
  requestInfo: string;
  afterDelay: number;
  commandTimeout: number;
  cmdScript: string;
}

export interface DeviceDraft {
  key: string;
  id: string;
  group: string;
  responseTimeout: number;
  maxRetryConnect: number;
  retryConnectDelay: number;
  socketTimeout: number;
  initialCommandDelay: number;
  connectionCommand: boolean;
  dataText: string;
  protocolScript: string;
  commands: CommandDraft[];
  connection: ConnectionDraft;
  extra: Record<string, unknown>;
}

let keySequence = 0;
const nextKey = (prefix: string) =>
  prefix + "-" + Date.now() + "-" + keySequence++;

export function createConnectionDraft(protocol: ProtocolId): ConnectionDraft {
  const definition = protocolDefinition(protocol);
  return {
    protocol,
    host: definition.defaultHost,
    port: definition.defaultPort,
    path: definition.defaultPath ?? "",
    baseUrl: protocol === "http-client" ? "http://127.0.0.1:8080" : "",
    options: {},
    customOptions: [],
  };
}

export function createCommandDraft(index = 1): CommandDraft {
  return {
    key: nextKey("command"),
    id: "command_" + index,
    order: 0,
    type: "READ_REQUEST",
    periodGroup: -1,
    requestInfo: "",
    afterDelay: 0,
    commandTimeout: 5000,
    cmdScript: "",
  };
}

export function createDeviceDraft(index = 1): DeviceDraft {
  return {
    key: nextKey("device"),
    id: "device" + index,
    group: "",
    responseTimeout: 0,
    maxRetryConnect: 5,
    retryConnectDelay: 5000,
    socketTimeout: 5000,
    initialCommandDelay: 5000,
    connectionCommand: false,
    dataText: "{}",
    protocolScript: "",
    commands: [],
    connection: createConnectionDraft("tcp-client"),
    extra: {},
  };
}

function parseHostPort(value: string, defaultHost: string, defaultPort: string) {
  if (value.startsWith("[")) {
    const bracket = value.indexOf("]");
    if (bracket >= 0) {
      const host = value.slice(0, bracket + 1);
      const port = value.slice(bracket + 1).replace(/^:/, "");
      return { host, port: port || defaultPort };
    }
  }
  const colon = value.lastIndexOf(":");
  if (colon < 0) return { host: value || defaultHost, port: defaultPort };
  return {
    host: value.slice(0, colon),
    port: value.slice(colon + 1) || defaultPort,
  };
}

export function parseConnectionUrl(connectionUrl = "tcp-client://127.0.0.1:5000") {
  const separator = connectionUrl.indexOf("://");
  if (separator < 0)
    throw new Error("connectionUrl 형식이 올바르지 않습니다: " + connectionUrl);

  const protocol = connectionUrl.slice(0, separator) as ProtocolId;
  if (!PROTOCOLS.some((definition) => definition.id === protocol)) {
    throw new Error("지원하지 않는 protocol입니다: " + protocol);
  }

  const definition = protocolDefinition(protocol);
  const remainder = connectionUrl.slice(separator + 3);
  const queryIndex = remainder.indexOf("?");
  let target = queryIndex >= 0 ? remainder.slice(0, queryIndex) : remainder;
  const query = queryIndex >= 0 ? remainder.slice(queryIndex + 1) : "";
  const connection = createConnectionDraft(protocol);

  if (definition.target === "base-url") {
    connection.baseUrl = target;
  } else if (definition.target === "host-port" || definition.target === "opc") {
    if (definition.target === "opc") {
      const pathIndex = target.indexOf("/");
      if (pathIndex >= 0) {
        connection.path = target.slice(pathIndex);
        target = target.slice(0, pathIndex);
      }
    }
    const parsed = parseHostPort(target, definition.defaultHost, definition.defaultPort);
    connection.host = parsed.host;
    connection.port = parsed.port;
  }

  const knownOptions = new Set(definition.options.map((option) => option.key));
  new URLSearchParams(query).forEach((value, name) => {
    if (knownOptions.has(name)) {
      connection.options[name] = value;
    } else {
      connection.customOptions.push({ key: nextKey("option"), name, value });
    }
  });
  return connection;
}

export function buildConnectionUrl(connection: ConnectionDraft) {
  const definition = protocolDefinition(connection.protocol);
  let target = "";
  if (definition.target === "base-url") {
    target = connection.baseUrl.trim();
  } else if (definition.target === "host-port" || definition.target === "opc") {
    target = connection.host.trim() + ":" + connection.port.trim();
    if (definition.target === "opc" && connection.path.trim()) {
      const path = connection.path.trim();
      target += path.startsWith("/") ? path : "/" + path;
    }
  }

  const query = new URLSearchParams();
  for (const [name, value] of Object.entries(connection.options)) {
    if (value !== "") query.set(name, value);
  }
  for (const option of connection.customOptions) {
    if (option.name.trim()) query.set(option.name.trim(), option.value);
  }

  const queryString = query.toString();
  return (
    connection.protocol +
    "://" +
    target +
    (queryString ? "?" + queryString : "")
  );
}

const numberOr = (value: unknown, fallback: number) =>
  typeof value === "number" && Number.isFinite(value) ? value : fallback;

function commandToDraft(command: Command, index: number): CommandDraft {
  const type = COMMAND_TYPES.includes(command.type as CommandType)
    ? (command.type as CommandType)
    : "READ_REQUEST";
  return {
    key: nextKey("command"),
    id: command.id || "command_" + (index + 1),
    order: numberOr(command.order, 0),
    type,
    periodGroup: numberOr(command.periodGroup, -1),
    requestInfo: command.requestInfo ?? "",
    afterDelay: numberOr(command.afterDelay, 0),
    commandTimeout: numberOr(command.commandTimeout, 5000),
    cmdScript: command.cmdScript ?? "",
  };
}

export function deviceToDraft(device: Device, index: number): DeviceDraft {
  const {
    id,
    group,
    responseTimeout,
    maxRetryConnect,
    retryConnectDelay,
    socketTimeout,
    initialCommandDelay,
    connectionUrl,
    protocolScript,
    commands,
    connectionCommand,
    data,
    ...extra
  } = device;

  return {
    key: nextKey("device"),
    id: id || "device" + (index + 1),
    group: group ?? "",
    responseTimeout: numberOr(responseTimeout, 0),
    maxRetryConnect: numberOr(maxRetryConnect, 5),
    retryConnectDelay: numberOr(retryConnectDelay, 5000),
    socketTimeout: numberOr(socketTimeout, 5000),
    initialCommandDelay: numberOr(initialCommandDelay, 5000),
    connectionCommand: connectionCommand ?? false,
    dataText: JSON.stringify(data ?? {}, null, 2),
    protocolScript: protocolScript ?? "",
    commands: Array.isArray(commands)
      ? commands.map((command, commandIndex) => commandToDraft(command, commandIndex))
      : [],
    connection: parseConnectionUrl(connectionUrl),
    extra,
  };
}

function parseData(draft: DeviceDraft) {
  const value = JSON.parse(draft.dataText || "{}") as unknown;
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("[" + draft.id + "] data는 JSON object여야 합니다.");
  }
  return value as Record<string, unknown>;
}

export function validateDrafts(drafts: DeviceDraft[]) {
  const errors: string[] = [];
  if (drafts.length === 0) return ["연결할 Device를 하나 이상 추가하세요."];

  const deviceIds = new Set<string>();
  for (let deviceIndex = 0; deviceIndex < drafts.length; deviceIndex++) {
    const draft = drafts[deviceIndex];
    const prefix = "Device " + (deviceIndex + 1);
    if (!draft.id.trim()) {
      errors.push(prefix + ": deviceId를 입력하세요.");
    } else if (!/^[a-zA-Z0-9_]+$/.test(draft.id)) {
      errors.push(prefix + ": deviceId는 영문, 숫자, 밑줄만 사용할 수 있습니다.");
    } else if (deviceIds.has(draft.id)) {
      errors.push(prefix + ": 중복 deviceId입니다 (" + draft.id + ").");
    }
    deviceIds.add(draft.id);

    const definition = protocolDefinition(draft.connection.protocol);
    const clientProtocols: ProtocolId[] = [
      "tcp-client",
      "udp-client",
      "modbus-client",
      "opcua-client",
    ];
    if (
      clientProtocols.includes(draft.connection.protocol) &&
      !draft.connection.host.trim()
    ) {
      errors.push(prefix + ": client host를 입력하세요.");
    }
    if (definition.target === "host-port" || definition.target === "opc") {
      const port = Number(draft.connection.port);
      if (!Number.isInteger(port) || port < 1 || port > 65535) {
        errors.push(prefix + ": port는 1~65535 범위의 정수여야 합니다.");
      }
    }
    if (definition.target === "base-url" && !draft.connection.baseUrl.trim()) {
      errors.push(prefix + ": HTTP base URL을 입력하세요.");
    }
    if (
      draft.connection.protocol === "opcua-server" &&
      draft.connection.options.username?.trim() &&
      draft.connection.options.securityPolicy === "None"
    ) {
      errors.push(prefix + ": OPC UA username 인증에는 보안 Security policy가 필요합니다.");
    }

    const optionNames = new Set(definition.options.map((option) => option.key));
    for (const option of draft.connection.customOptions) {
      if (!option.name.trim()) {
        errors.push(prefix + ": 커스텀 옵션 이름을 입력하세요.");
      } else if (optionNames.has(option.name.trim())) {
        errors.push(prefix + ": 중복 connection 옵션입니다 (" + option.name + ").");
      }
      optionNames.add(option.name.trim());
    }

    try {
      parseData(draft);
    } catch (error) {
      errors.push(error instanceof Error ? error.message : String(error));
    }

    const commandIds = new Set<string>();
    for (let commandIndex = 0; commandIndex < draft.commands.length; commandIndex++) {
      const command = draft.commands[commandIndex];
      const commandPrefix = prefix + " / Command " + (commandIndex + 1);
      if (!command.id.trim()) {
        errors.push(commandPrefix + ": command id를 입력하세요.");
      } else if (commandIds.has(command.id)) {
        errors.push(commandPrefix + ": 중복 command id입니다 (" + command.id + ").");
      }
      commandIds.add(command.id);
    }
  }
  return errors;
}

function draftToCommand(command: CommandDraft): Command {
  return {
    id: command.id.trim(),
    order: command.order,
    type: command.type,
    periodGroup: command.periodGroup,
    requestInfo: command.requestInfo || null,
    afterDelay: command.afterDelay,
    commandTimeout: command.commandTimeout,
    cmdScript: command.cmdScript || null,
  };
}

export function draftsToDevices(drafts: DeviceDraft[]): Device[] {
  const errors = validateDrafts(drafts);
  if (errors.length > 0) {
    const message = errors.join("\n");
    throw new Error(message);
  }

  const devices: Device[] = [];
  for (const draft of drafts) {
    const commands: Command[] = [];
    for (const command of draft.commands) {
      const convertedCommand = draftToCommand(command);
      commands.push(convertedCommand);
    }
    const device: Device = {
      ...draft.extra,
      id: draft.id.trim(),
      group: draft.group,
      responseTimeout: draft.responseTimeout,
      maxRetryConnect: draft.maxRetryConnect,
      retryConnectDelay: draft.retryConnectDelay,
      socketTimeout: draft.socketTimeout,
      initialCommandDelay: draft.initialCommandDelay,
      connectionUrl: buildConnectionUrl(draft.connection),
      protocolScript: draft.protocolScript,
      commands,
      connectionCommand: draft.connectionCommand,
      data: parseData(draft),
    };
    devices.push(device);
  }
  return devices;
}

export function duplicateDeviceDraft(draft: DeviceDraft, index: number): DeviceDraft {
  const customOptions: KeyValueDraft[] = [];
  for (const option of draft.connection.customOptions) {
    customOptions.push({
      ...option,
      key: nextKey("option"),
    });
  }
  const commands: CommandDraft[] = [];
  for (const command of draft.commands) {
    commands.push({
      ...command,
      key: nextKey("command"),
    });
  }
  return {
    ...draft,
    key: nextKey("device"),
    id: (draft.id || "device") + "_copy" + index,
    connection: {
      ...draft.connection,
      options: { ...draft.connection.options },
      customOptions,
    },
    commands,
    extra: { ...draft.extra },
  };
}
