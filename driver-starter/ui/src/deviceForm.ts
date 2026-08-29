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
  label: "Treat exception as connection lost",
  kind: "boolean",
  hint: "Unset uses the protocol default.",
};

const streamOptions: OptionDefinition[] = [
  {
    key: "startBytes",
    label: "Start bytes",
    kind: "text",
    placeholder: "\\x02",
  },
  {
    key: "endBytes",
    label: "End bytes",
    kind: "text",
    placeholder: "\\x0D\\x0A",
  },
  {
    key: "retainStartEndBytes",
    label: "Retain start/end bytes",
    kind: "boolean",
  },
  {
    key: "combineBufferedData",
    label: "Combine buffered data",
    kind: "boolean",
  },
  {
    key: "bufferTime",
    label: "Buffer time (ms)",
    kind: "number",
    placeholder: "100",
  },
];

const httpOptions: OptionDefinition[] = [
  { key: "cert", label: "Certificate / keystore path", kind: "text" },
  { key: "format", label: "Keystore format", kind: "text", placeholder: "PKCS12" },
  { key: "password", label: "Key / keystore password", kind: "password" },
  { key: "key", label: "PEM private key path", kind: "text" },
  { key: "trustCert", label: "Trust certificate / keystore path", kind: "text" },
  { key: "trustFormat", label: "Trust keystore format", kind: "text", placeholder: "PKCS12" },
  { key: "trustPassword", label: "Trust keystore password", kind: "password" },
  { key: "useByteArrayBody", label: "Handle body as byte[]", kind: "boolean" },
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
    label: "PKI directory",
    kind: "text",
    hint: "Stores the persistent identity.pfx, the trust list, and rejected certificates.",
  },
  {
    key: "keyStorePassword",
    label: "Identity keystore password",
    kind: "password",
    hint: "Leave empty to use a system property, an environment variable, or the password generated in the PKI directory.",
  },
];

const opcuaClientOptions: OptionDefinition[] = [
  {
    key: "securityPolicy",
    label: "Security policy",
    kind: "select",
    choices: opcuaSecurityPolicies,
    placeholder: "None (default)",
  },
  {
    key: "securityMode",
    label: "Security mode",
    kind: "select",
    choices: ["Sign", "SignAndEncrypt"],
    placeholder: "SignAndEncrypt (default)",
  },
  { key: "username", label: "Username", kind: "text" },
  { key: "password", label: "Password", kind: "password" },
  {
    key: "subscriptionNodeIds",
    label: "Subscription NodeIds",
    kind: "text",
    placeholder: "ns=2;s=PLC1/temp,ns=2;s=PLC1/alarm",
    hint: "Separate multiple NodeIds with commas.",
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
    placeholder: "Basic256Sha256 with username, otherwise None",
  },
  {
    key: "securityMode",
    label: "Security mode",
    kind: "select",
    choices: ["Sign", "SignAndEncrypt"],
    placeholder: "SignAndEncrypt (default)",
  },
  { key: "username", label: "Username", kind: "text" },
  { key: "password", label: "Password", kind: "password" },
  {
    key: "anonymous",
    label: "Also allow anonymous access",
    kind: "boolean",
    hint: "Applies only when a username is set.",
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
    hint: "Connects to a TCP server and handles byte/string requests and responses.",
    options: withCommon(streamOptions),
  },
  {
    id: "tcp-server",
    label: "TCP Server",
    target: "host-port",
    defaultHost: "",
    defaultPort: "5000",
    hint: "Accepts TCP client connections on the given port. Host may be left empty.",
    options: withCommon(streamOptions),
  },
  {
    id: "udp-client",
    label: "UDP Client",
    target: "host-port",
    defaultHost: "127.0.0.1",
    defaultPort: "5000",
    hint: "Sends and receives UDP datagrams with the given host/port.",
    options: withCommon(streamOptions),
  },
  {
    id: "udp-server",
    label: "UDP Server",
    target: "host-port",
    defaultHost: "",
    defaultPort: "5000",
    hint: "Receives UDP datagrams and can join multicast groups.",
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
    hint: "Connects to a Modbus TCP slave/server.",
    options: withCommon([
      { key: "unitId", label: "Default unit ID", kind: "number", placeholder: "1" },
      { key: "combineData", label: "Combine data of multiple blocks", kind: "boolean" },
    ]),
  },
  {
    id: "modbus-server",
    label: "Modbus TCP Server",
    target: "host-port",
    defaultHost: "",
    defaultPort: "502",
    hint: "Serves Device data as a Modbus address space.",
    options: withCommon(),
  },
  {
    id: "http-client",
    label: "HTTP Client",
    target: "base-url",
    defaultHost: "",
    defaultPort: "",
    hint: "Enter the full base URL, for example https://api.example.com/v1",
    options: withCommon(httpOptions),
  },
  {
    id: "http-server",
    label: "HTTP Server",
    target: "host-port",
    defaultHost: "",
    defaultPort: "8080",
    hint: "Accepts HTTP requests on the given port.",
    options: withCommon(httpOptions),
  },
  {
    id: "opcua-client",
    label: "OPC UA Client",
    target: "opc",
    defaultHost: "127.0.0.1",
    defaultPort: "4840",
    defaultPath: "",
    hint: "Connects to an OPC UA endpoint and performs read, write, and subscription.",
    options: withCommon(opcuaClientOptions),
  },
  {
    id: "opcua-server",
    label: "OPC UA Server",
    target: "opc",
    defaultHost: "",
    defaultPort: "4840",
    defaultPath: "",
    hint: "Exposes Device data as OPC UA variable nodes.",
    options: withCommon(opcuaServerOptions),
  },
  {
    id: "dummy",
    label: "Dummy",
    target: "none",
    defaultHost: "",
    defaultPort: "",
    hint: "Tests script and command behavior without an external connection.",
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
    throw new Error("invalid connectionUrl format: " + connectionUrl);

  const protocol = connectionUrl.slice(0, separator) as ProtocolId;
  if (!PROTOCOLS.some((definition) => definition.id === protocol)) {
    throw new Error("unsupported protocol: " + protocol);
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

export function commandToDraft(command: Command, index: number): CommandDraft {
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
    throw new Error("[" + draft.id + "] data must be a JSON object.");
  }
  return value as Record<string, unknown>;
}

export function validateDrafts(drafts: DeviceDraft[]) {
  const errors: string[] = [];
  if (drafts.length === 0) return ["Add at least one Device to connect."];

  const deviceIds = new Set<string>();
  for (let deviceIndex = 0; deviceIndex < drafts.length; deviceIndex++) {
    const draft = drafts[deviceIndex];
    const prefix = "Device " + (deviceIndex + 1);
    if (!draft.id.trim()) {
      errors.push(prefix + ": enter a deviceId.");
    } else if (!/^[a-zA-Z0-9_]+$/.test(draft.id)) {
      errors.push(prefix + ": deviceId may contain only letters, digits, and underscores.");
    } else if (deviceIds.has(draft.id)) {
      errors.push(prefix + ": duplicate deviceId (" + draft.id + ").");
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
      errors.push(prefix + ": enter the client host.");
    }
    if (definition.target === "host-port" || definition.target === "opc") {
      const port = Number(draft.connection.port);
      if (!Number.isInteger(port) || port < 1 || port > 65535) {
        errors.push(prefix + ": port must be an integer between 1 and 65535.");
      }
    }
    if (definition.target === "base-url" && !draft.connection.baseUrl.trim()) {
      errors.push(prefix + ": enter the HTTP base URL.");
    }
    if (
      draft.connection.protocol === "opcua-server" &&
      draft.connection.options.username?.trim() &&
      draft.connection.options.securityPolicy === "None"
    ) {
      errors.push(prefix + ": OPC UA username authentication requires a secure security policy.");
    }

    const optionNames = new Set(definition.options.map((option) => option.key));
    for (const option of draft.connection.customOptions) {
      if (!option.name.trim()) {
        errors.push(prefix + ": enter the custom option name.");
      } else if (optionNames.has(option.name.trim())) {
        errors.push(prefix + ": duplicate connection option (" + option.name + ").");
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
        errors.push(commandPrefix + ": enter the command id.");
      } else if (commandIds.has(command.id)) {
        errors.push(commandPrefix + ": duplicate command id (" + command.id + ").");
      }
      commandIds.add(command.id);
    }
  }
  return errors;
}

/** validate commands that are sent on their own, without an enclosing device */
export function validateCommandDrafts(commands: CommandDraft[]) {
  const errors: string[] = [];
  if (commands.length === 0) return ["Add at least one Command to send."];

  const commandIds = new Set<string>();
  for (let commandIndex = 0; commandIndex < commands.length; commandIndex++) {
    const command = commands[commandIndex];
    const prefix = "Command " + (commandIndex + 1);
    if (!command.id.trim()) {
      errors.push(prefix + ": enter the command id.");
    } else if (commandIds.has(command.id)) {
      errors.push(prefix + ": duplicate command id (" + command.id + ").");
    }
    commandIds.add(command.id);
  }
  return errors;
}

export function draftsToCommands(drafts: CommandDraft[]): Command[] {
  const errors = validateCommandDrafts(drafts);
  if (errors.length > 0) throw new Error(errors.join("\n"));
  return drafts.map(draftToCommand);
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
