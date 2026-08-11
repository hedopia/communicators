import { useCallback, useEffect, useState } from "react";
import {
  errorMessage,
  fetchClusterNodes,
  fetchDeviceIdMap,
  fetchLeaderUrl,
  fetchMyNodeStatus,
  fetchNodeStatus,
  setToFollower,
  setToLeader,
} from "./api";
import { useAutoRefresh } from "./hooks";
import type { NodeStatus } from "./types";

interface NodeRow {
  nodeIndex: number;
  position: string;
  activated: string;
  deviceCount: number;
  error?: string;
}

function NodesTab() {
  const [rows, setRows] = useState<NodeRow[]>([]);
  const [leaderUrl, setLeaderUrl] = useState("");
  const [myStatus, setMyStatus] = useState<NodeStatus | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [autoRefresh, setAutoRefresh] = useState(false);
  const [refreshedAt, setRefreshedAt] = useState("");

  const refresh = useCallback(async () => {
    try {
      const [nodes, idMap] = await Promise.all([fetchClusterNodes(), fetchDeviceIdMap()]);
      const sorted = [...nodes].sort((a, b) => a - b);
      const next = await Promise.all(
        sorted.map(async (nodeIndex): Promise<NodeRow> => {
          const deviceCount = idMap[String(nodeIndex)]?.length ?? 0;
          try {
            const status = await fetchNodeStatus(nodeIndex);
            return {
              nodeIndex,
              position: status.position,
              activated: String(status.activated),
              deviceCount,
            };
          } catch (e) {
            return {
              nodeIndex,
              position: "-",
              activated: "-",
              deviceCount,
              error: errorMessage(e),
            };
          }
        })
      );
      setRows(next);
      try {
        setLeaderUrl(await fetchLeaderUrl());
      } catch {
        setLeaderUrl("");
      }
      try {
        setMyStatus(await fetchMyNodeStatus());
      } catch {
        setMyStatus(null);
      }
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

  return (
    <div>
      <section className="panel">
        <div className="panel-header">
          <h2>Cluster nodes</h2>
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
          </div>
        </div>
        <div className="summary">
          <span>
            leader-url: <b>{leaderUrl || "-"}</b>
          </span>
          <span>
            this node:{" "}
            <b>
              {myStatus
                ? `#${myStatus.nodeIndex} ${myStatus.position} (activated: ${myStatus.activated})`
                : "-"}
            </b>
          </span>
        </div>
        {refreshedAt && <div className="hint">last refreshed: {refreshedAt}</div>}
        {error && <div className="error">{error}</div>}
        <table>
          <thead>
            <tr>
              <th>node</th>
              <th>position</th>
              <th>activated</th>
              <th>devices</th>
              <th>action</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr>
                <td colSpan={5} className="empty">
                  no nodes
                </td>
              </tr>
            ) : (
              rows.map((row) => (
                <tr key={row.nodeIndex}>
                  <td className="center">{row.nodeIndex}</td>
                  <td>
                    {row.error ? (
                      <span className="status status-failed" title={row.error}>
                        UNREACHABLE
                      </span>
                    ) : (
                      <span
                        className={
                          row.position === "LEADER" ? "status status-leader" : "status"
                        }
                      >
                        {row.position}
                      </span>
                    )}
                  </td>
                  <td className="center">{row.activated}</td>
                  <td className="center">{row.deviceCount}</td>
                  <td>
                    <button
                      className="small"
                      onClick={() => run(() => setToLeader(row.nodeIndex))}
                      disabled={busy || row.position === "LEADER"}
                    >
                      set-to-leader
                    </button>{" "}
                    <button
                      className="small"
                      onClick={() => run(() => setToFollower(row.nodeIndex))}
                      disabled={busy || row.position === "FOLLOWER"}
                    >
                      set-to-follower
                    </button>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </section>
    </div>
  );
}

export default NodesTab;
