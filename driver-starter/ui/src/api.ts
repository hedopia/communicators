import { api, rootApi, nodePath, nodeDriverPath, clusterBasePath } from "./client";
import type {
  ConnectResult,
  Device,
  DeviceIdMap,
  DeviceStatusMap,
  NodeStatus,
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
    if (typeof data === "string" && data.length > 0) return data;
    if (data !== undefined && data !== null) return JSON.stringify(data);
    return String(withResponse.message);
  }
  return String(e);
}
