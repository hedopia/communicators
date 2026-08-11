import { useCallback, useEffect, useRef, useState } from "react";
import {
  connectDevices,
  disconnectAll,
  disconnectDevices,
  errorMessage,
  fetchDeviceIdMap,
  fetchDevices,
  fetchNodeDeviceStatus,
  reconnectAll,
} from "./api";
import { useAutoRefresh } from "./hooks";
import type { ConnectResult } from "./types";

interface DeviceRow {
  deviceId: string;
  nodeIndex: string;
  status: string;
}

const SAMPLE_PLACEHOLDER = `[
  { "id": "device1", "connectionUrl": "dummy://", "commands": [] }
]`;

function DevicesTab() {
  const [rows, setRows] = useState<DeviceRow[]>([]);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [autoRefresh, setAutoRefresh] = useState(false);
  const [refreshedAt, setRefreshedAt] = useState("");
  const [deviceJson, setDeviceJson] = useState("");
  const [connectResult, setConnectResult] = useState<ConnectResult | null>(null);
  const [connectError, setConnectError] = useState("");
  const fileInputRef = useRef<HTMLInputElement>(null);

  const refresh = useCallback(async () => {
    try {
      const idMap = await fetchDeviceIdMap();
      const nodeIndexes = Object.keys(idMap).sort((a, b) => Number(a) - Number(b));
      const statusByNode = await Promise.all(
        nodeIndexes.map(async (nodeIndex) => {
          try {
            return await fetchNodeDeviceStatus(nodeIndex);
          } catch {
            return null; // node unreachable
          }
        })
      );
      const next: DeviceRow[] = [];
      nodeIndexes.forEach((nodeIndex, i) => {
        const statusMap = statusByNode[i];
        for (const deviceId of [...idMap[nodeIndex]].sort()) {
          next.push({
            deviceId,
            nodeIndex,
            status: statusMap ? (statusMap[deviceId] ?? "UNKNOWN") : "NODE_UNREACHABLE",
          });
        }
      });
      setRows(next);
      setError("");
      setRefreshedAt(new Date().toLocaleTimeString());
    } catch (e) {
      setError(errorMessage(e));
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);
  useAutoRefresh(autoRefresh, 5000, refresh);

  const run = async (action: () => Promise<unknown>) => {
    setBusy(true);
    try {
      await action();
      setError("");
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
      refresh();
    }
  };

  const connect = async () => {
    setBusy(true);
    setConnectResult(null);
    setConnectError("");
    try {
      JSON.parse(deviceJson); // syntax check before sending
      setConnectResult(await connectDevices(deviceJson));
    } catch (e) {
      setConnectError(errorMessage(e));
    } finally {
      setBusy(false);
      refresh();
    }
  };

  const importFile = (file: File | undefined) => {
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      setDeviceJson(String(reader.result ?? ""));
      setConnectResult(null);
      setConnectError("");
    };
    reader.onerror = () => setConnectError("failed to read file");
    reader.readAsText(file);
  };

  const exportFile = async () => {
    setBusy(true);
    try {
      const devices = await fetchDevices();
      const blob = new Blob([JSON.stringify(devices, null, 2)], {
        type: "application/json",
      });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = "devices.json";
      a.click();
      URL.revokeObjectURL(url);
      setError("");
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <section className="panel">
        <div className="panel-header">
          <h2>Devices in cluster</h2>
          <div className="toolbar">
            <label className="toggle">
              <input
                type="checkbox"
                checked={autoRefresh}
                onChange={(e) => setAutoRefresh(e.target.checked)}
              />
              auto refresh (5s)
            </label>
            <button onClick={refresh} disabled={busy}>
              refresh
            </button>
            <button onClick={() => run(reconnectAll)} disabled={busy}>
              reconnect-all
            </button>
            <button className="danger" onClick={() => run(disconnectAll)} disabled={busy}>
              disconnect-all
            </button>
            <button onClick={exportFile} disabled={busy}>
              export file
            </button>
          </div>
        </div>
        {refreshedAt && <div className="hint">last refreshed: {refreshedAt}</div>}
        {error && <div className="error">{error}</div>}
        <table>
          <thead>
            <tr>
              <th>deviceId</th>
              <th>node</th>
              <th>status</th>
              <th>action</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr>
                <td colSpan={4} className="empty">
                  no devices
                </td>
              </tr>
            ) : (
              rows.map((row) => (
                <tr key={row.deviceId}>
                  <td>{row.deviceId}</td>
                  <td className="center">{row.nodeIndex}</td>
                  <td>
                    <span className={`status status-${row.status.toLowerCase()}`}>
                      {row.status}
                    </span>
                  </td>
                  <td>
                    <button
                      className="danger small"
                      onClick={() => run(() => disconnectDevices([row.deviceId]))}
                      disabled={busy}
                    >
                      disconnect
                    </button>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </section>

      <section className="panel">
        <div className="panel-header">
          <h2>Connect devices</h2>
          <div className="toolbar">
            <button onClick={() => fileInputRef.current?.click()} disabled={busy}>
              import file
            </button>
            <input
              ref={fileInputRef}
              type="file"
              accept=".json,application/json"
              style={{ display: "none" }}
              onChange={(e) => {
                importFile(e.target.files?.[0]);
                e.target.value = "";
              }}
            />
            <button onClick={connect} disabled={busy || deviceJson.trim().length === 0}>
              connect
            </button>
          </div>
        </div>
        <textarea
          className="json-input"
          placeholder={SAMPLE_PLACEHOLDER}
          value={deviceJson}
          onChange={(e) => setDeviceJson(e.target.value)}
          spellCheck={false}
        />
        {connectError && <div className="error">{connectError}</div>}
        {connectResult && (
          <table className="result-table">
            <thead>
              <tr>
                <th>deviceId</th>
                <th>result</th>
              </tr>
            </thead>
            <tbody>
              {Object.entries(connectResult).map(([deviceId, result]) => (
                <tr key={deviceId}>
                  <td>{deviceId}</td>
                  <td>
                    <span
                      className={
                        result.toUpperCase().includes("FAIL")
                          ? "status status-failed"
                          : "status status-connected"
                      }
                    >
                      {result}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}

export default DevicesTab;
