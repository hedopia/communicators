import {
  api,
  rootApi,
  nodePath,
  nodeDriverPath,
  clusterBasePath,
  driverBasePath,
} from "./client";
import type {
  Command,
  CommandEndpoint,
  ConnectResult,
  Device,
  DeviceIdMap,
  DeviceStatusMap,
  NodeStatus,
  ResponseEntry,
  ResponseMap,
} from "./types";

// ---- driver ----

export async function fetchDeviceIdMap(): Promise<DeviceIdMap> {
  return (await api.get<DeviceIdMap>("/device-id-map")).data;
}

export async function fetchNodeDeviceStatus(
  nodeIndex: number | string
): Promise<DeviceStatusMap> {
  return (await rootApi.get<DeviceStatusMap>(nodeDriverPath(nodeIndex, "/device-status"))).data;
}

export async function fetchDevices(): Promise<Device[]> {
  return (await api.get<Device[]>("/devices")).data;
}

export async function connectDevices(devicesJson: string): Promise<ConnectResult> {
  const res = await api.post<ConnectResult>("/balanced-connect-all", devicesJson, {
    headers: { "Content-Type": "application/json" },
  });
  return res.data;
}

export async function disconnectDevices(deviceIds: string[]): Promise<unknown> {
  const res = await api.delete("/disconnect", {
    data: deviceIds,
    headers: { "Content-Type": "application/json" },
  });
  return res.data;
}

export async function disconnectAll(): Promise<unknown> {
  return (await api.delete("/disconnect-all")).data;
}

export async function reconnectAll(): Promise<unknown> {
  return (await api.put("/reconnect-all")).data;
}

export async function fetchResponses(): Promise<ResponseMap> {
  return (await api.get<ResponseMap>("/response")).data;
}

/** path of a command endpoint, e.g. "/driver/execute-command-ids/device1" */
export function commandEndpointPath(endpoint: CommandEndpoint, deviceId: string) {
  return `${driverBasePath}/${endpoint}/${encodeURIComponent(deviceId)}`;
}

/**
 * Run commands on the node holding the device. The endpoints only look at the
 * protocols of the receiving node, so the request is routed to the owning node.
 * {@code initial-value} must be URL-encoded (UTF-8): header values carry Latin-1 only.
 */
export async function postCommandEndpoint(
  nodeIndex: number | string,
  endpoint: CommandEndpoint,
  deviceId: string,
  body: Command[] | string[],
  initialValue: string
): Promise<ResponseEntry[]> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (initialValue !== "") headers["initial-value"] = encodeURIComponent(initialValue);
  const res = await rootApi.post(
    nodePath(nodeIndex, commandEndpointPath(endpoint, deviceId)),
    JSON.stringify(body),
    { headers }
  );
  return Array.isArray(res.data) ? (res.data as ResponseEntry[]) : [];
}

// ---- cluster ----

export async function fetchClusterNodes(): Promise<number[]> {
  return (await rootApi.get<number[]>(`${clusterBasePath}/get-cluster-nodes`)).data;
}

export async function fetchNodeStatus(nodeIndex: number | string): Promise<NodeStatus> {
  return (await rootApi.get<NodeStatus>(nodePath(nodeIndex, `${clusterBasePath}/node-status`)))
    .data;
}

export async function fetchMyNodeStatus(): Promise<NodeStatus> {
  return (await rootApi.get<NodeStatus>(`${clusterBasePath}/node-status`)).data;
}

export async function fetchLeaderUrl(): Promise<string> {
  return (await rootApi.get<string>(`${clusterBasePath}/leader-url`)).data;
}

export async function setToLeader(nodeIndex: number | string): Promise<void> {
  await rootApi.put(nodePath(nodeIndex, `${clusterBasePath}/set-to-leader`));
}

export async function setToFollower(nodeIndex: number | string): Promise<void> {
  await rootApi.put(nodePath(nodeIndex, `${clusterBasePath}/set-to-follower`));
}

// ---- misc ----

export function errorMessage(e: unknown): string {
  if (e && typeof e === "object" && "message" in e) {
    const withResponse = e as { response?: { data?: unknown }; message?: string };
    const data = withResponse.response?.data;
    if (typeof data === "string") {
      // an empty body carries nothing, so fall back to the axios message
      if (data.length > 0) return data;
    } else if (data !== undefined && data !== null) {
      return JSON.stringify(data);
    }
    return String(withResponse.message);
  }
  return String(e);
}
