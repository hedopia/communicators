export type CommandType =
  | "READ_REQUEST"
  | "STARTING_READ_REQUEST"
  | "STOPPING_READ_REQUEST"
  | "WRITE_REQUEST"
  | "STARTING_WRITE_REQUEST"
  | "STOPPING_WRITE_REQUEST"
  | "REQUEST"
  | "STARTING_REQUEST"
  | "STOPPING_REQUEST";

export interface Command {
  id: string;
  order?: number;
  type?: CommandType;
  periodGroup?: number;
  requestInfo?: string | null;
  afterDelay?: number;
  commandTimeout?: number;
  cmdScript?: string | null;
}

/** Device setting consumed by the Java Device struct; extra keys are preserved. */
export interface Device {
  id: string;
  group?: string;
  responseTimeout?: number;
  maxRetryConnect?: number;
  retryConnectDelay?: number;
  socketTimeout?: number;
  initialCommandDelay?: number;
  connectionUrl?: string;
  protocolScript?: string;
  commands?: Command[];
  connectionCommand?: boolean;
  data?: Record<string, unknown>;
  [key: string]: unknown;
}

/** GET /driver/device-id-map : nodeIndex -> deviceId[] */
export type DeviceIdMap = Record<string, string[]>;

/** GET /driver/device-status : deviceId -> StatusCode */
export type DeviceStatusMap = Record<string, string>;

/** POST /driver/balanced-connect-all : deviceId -> result status */
export type ConnectResult = Record<string, string>;

/** GET /cluster/node-status */
export interface NodeStatus {
  nodeIndex: number;
  position: string;
  activated: boolean;
}

/** single response entry of GET /driver/response */
export interface ResponseEntry {
  deviceId: string;
  tagId: string;
  value: string;
  receivedTime: number;
}

/** GET /driver/response : deviceId -> tagId -> ResponseEntry */
export type ResponseMap = Record<string, Record<string, ResponseEntry>>;

/** the four POST /driver/{endpoint}/{deviceId} command endpoints */
export type CommandEndpoint =
  | "execute-commands"
  | "request-commands"
  | "execute-command-ids"
  | "request-command-ids";
