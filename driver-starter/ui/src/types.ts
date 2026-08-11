/** Device setting (subset of the Java Device struct; extra keys are preserved) */
export interface Device {
  id: string;
  connectionUrl?: string;
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
