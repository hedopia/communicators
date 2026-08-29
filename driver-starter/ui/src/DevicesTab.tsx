import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import DeviceEditor from "./DeviceEditor";
import {
  createDeviceDraft,
  deviceToDraft,
  draftsToDevices,
  duplicateDeviceDraft,
} from "./deviceForm";
import type { DeviceDraft } from "./deviceForm";
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
import type { ConnectResult, Device } from "./types";

interface DeviceRow {
  deviceId: string;
  nodeIndex: string;
  status: string;
}


function DevicesTab() {
  const [rows, setRows] = useState<DeviceRow[]>([]);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [autoRefresh, setAutoRefresh] = useState(false);
  const [refreshedAt, setRefreshedAt] = useState("");
  const [drafts, setDrafts] = useState<DeviceDraft[]>([createDeviceDraft(1)]);
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
      const devices = draftsToDevices(drafts);
      setConnectResult(await connectDevices(JSON.stringify(devices)));
    } catch (e) {
      setConnectError(errorMessage(e));
    } finally {
      setBusy(false);
      refresh();
    }
  };

  const applyDevices = (devices: Device[]) => {
    if (devices.length === 0) {
      throw new Error("the file contains no Device.");
    }
    for (const device of devices) {
      if (!device || typeof device !== "object") {
        throw new Error("invalid Device array format.");
      }
    }
    setDrafts(devices.map((device, index) => deviceToDraft(device, index)));
    setConnectResult(null);
    setConnectError("");
  };

  const importFile = (file: File | undefined) => {
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      try {
        const parsed = JSON.parse(String(reader.result ?? "")) as unknown;
        if (!Array.isArray(parsed)) {
          throw new Error("the Device JSON file must be an array.");
        }
        applyDevices(parsed as Device[]);
      } catch (e) {
        setConnectError(errorMessage(e));
      }
    };
    reader.onerror = () => setConnectError("could not read the file.");
    reader.readAsText(file);
  };

  const downloadDevices = (devices: Device[], fileName = "devices.json") => {
    const blob = new Blob([JSON.stringify(devices, null, 2)], {
      type: "application/json",
    });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = fileName;
    anchor.click();
    URL.revokeObjectURL(url);
  };

  const exportConnectedFile = async () => {
    setBusy(true);
    try {
      const devices = await fetchDevices();
      downloadDevices(devices);
      setError("");
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const loadConnectedDevices = async () => {
    setBusy(true);
    try {
      applyDevices(await fetchDevices());
    } catch (e) {
      setConnectError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const saveDraftFile = () => {
    try {
      downloadDevices(draftsToDevices(drafts));
      setConnectError("");
    } catch (e) {
      setConnectError(errorMessage(e));
    }
  };

  const updateDraft = (index: number, next: DeviceDraft) => {
    setDrafts((current) =>
      current.map((draft, draftIndex) => (draftIndex === index ? next : draft))
    );
  };

  const addDraft = () => {
    setDrafts((current) => [
      ...current,
      createDeviceDraft(current.length + 1),
    ]);
  };

  const duplicateDraft = (index: number) => {
    setDrafts((current) => {
      const copy = duplicateDeviceDraft(current[index], current.length + 1);
      const next = [...current];
      next.splice(index + 1, 0, copy);
      return next;
    });
  };

  const removeDraft = (index: number) => {
    setDrafts((current) =>
      current.length <= 1
        ? current
        : current.filter((_, draftIndex) => draftIndex !== index)
    );
  };

  const generatedJson = useMemo(() => {
    try {
      return JSON.stringify(draftsToDevices(drafts), null, 2);
    } catch (e) {
      return "configuration error: " + errorMessage(e);
    }
  }, [drafts]);

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
            <button onClick={exportConnectedFile} disabled={busy}>
              export connected
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

      <section className="panel builder-panel">
        <div className="panel-header">
          <div>
            <h2>Device connection builder</h2>
            <p className="panel-description">
              Select a protocol, fill in the Device and Command fields, then connect.
            </p>
          </div>
          <div className="toolbar">
            <button type="button" onClick={addDraft} disabled={busy}>
              + Add device
            </button>
            <button onClick={() => fileInputRef.current?.click()} disabled={busy}>
              import file
            </button>
            <input
              ref={fileInputRef}
              type="file"
              accept=".json,application/json"
              style={{ display: "none" }}
              onChange={(event) => {
                importFile(event.target.files?.[0]);
                event.target.value = "";
              }}
            />
            <button type="button" onClick={loadConnectedDevices} disabled={busy}>
              load connected
            </button>
            <button type="button" onClick={saveDraftFile} disabled={busy}>
              save draft
            </button>
            <button className="primary" onClick={connect} disabled={busy || drafts.length === 0}>
              Connect devices
            </button>
          </div>
        </div>
        <div className="builder-summary">
          <span>{drafts.length} devices</span>
          <span>
            {drafts.reduce((count, draft) => count + draft.commands.length, 0)} commands
          </span>
        </div>

        <div className="device-editor-list">
          {drafts.map((draft, index) => (
            <DeviceEditor
              key={draft.key}
              draft={draft}
              index={index}
              total={drafts.length}
              onChange={(next) => updateDraft(index, next)}
              onDuplicate={() => duplicateDraft(index)}
              onRemove={() => removeDraft(index)}
            />
          ))}
        </div>

        <div className="add-device-row">
          <button type="button" onClick={addDraft} disabled={busy}>
            + Add device
          </button>
        </div>

        <details className="json-preview">
          <summary>generated JSON preview</summary>
          <pre>{generatedJson}</pre>
        </details>

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
