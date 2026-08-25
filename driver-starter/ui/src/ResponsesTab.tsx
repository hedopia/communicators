import { useCallback, useEffect, useMemo, useState } from "react";
import { errorMessage, fetchResponses } from "./api";
import { useAutoRefresh } from "./hooks";
import type { ResponseEntry, ResponseMap } from "./types";

function ResponsesTab() {
  const [responseMap, setResponseMap] = useState<ResponseMap>({});
  const [filter, setFilter] = useState("");
  const [error, setError] = useState("");
  const [autoRefresh, setAutoRefresh] = useState(false);
  const [refreshedAt, setRefreshedAt] = useState("");

  const refresh = useCallback(async () => {
    try {
      setResponseMap(await fetchResponses());
      setError("");
      setRefreshedAt(new Date().toLocaleTimeString());
    } catch (e) {
      setError(errorMessage(e));
    }
  }, []);

  useEffect(() => {
    const timeout = window.setTimeout(refresh, 0);
    return () => window.clearTimeout(timeout);
  }, [refresh]);
  useAutoRefresh(autoRefresh, 5000, refresh);

  const deviceIds = useMemo(() => Object.keys(responseMap).sort(), [responseMap]);

  const rows = useMemo(() => {
    const result: ResponseEntry[] = [];
    for (const deviceId of deviceIds) {
      if (filter && deviceId !== filter) continue;
      const tagMap = responseMap[deviceId] ?? {};
      for (const tagId of Object.keys(tagMap).sort()) {
        result.push(tagMap[tagId]);
      }
    }
    return result;
  }, [responseMap, deviceIds, filter]);

  return (
    <div>
      <section className="panel">
        <div className="panel-header">
          <h2>Collected responses</h2>
          <div className="toolbar">
            <label className="toggle">
              device:{" "}
              <select value={filter} onChange={(e) => setFilter(e.target.value)}>
                <option value="">(all)</option>
                {deviceIds.map((deviceId) => (
                  <option key={deviceId} value={deviceId}>
                    {deviceId}
                  </option>
                ))}
              </select>
            </label>
            <label className="toggle">
              <input
                type="checkbox"
                checked={autoRefresh}
                onChange={(e) => setAutoRefresh(e.target.checked)}
              />
              auto refresh (5s)
            </label>
            <button onClick={refresh}>refresh</button>
          </div>
        </div>
        {refreshedAt && <div className="hint">last refreshed: {refreshedAt}</div>}
        {error && <div className="error">{error}</div>}
        <table>
          <thead>
            <tr>
              <th>deviceId</th>
              <th>tagId</th>
              <th>value</th>
              <th>receivedTime</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr>
                <td colSpan={4} className="empty">
                  no responses
                </td>
              </tr>
            ) : (
              rows.map((row) => (
                <tr key={`${row.deviceId}/${row.tagId}`}>
                  <td>{row.deviceId}</td>
                  <td>{row.tagId}</td>
                  <td className="value-cell">{row.value}</td>
                  <td>{new Date(row.receivedTime).toLocaleString()}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </section>
    </div>
  );
}

export default ResponsesTab;
