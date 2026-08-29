import { useCallback, useEffect, useMemo, useState } from "react";
import CommandEditor from "./CommandEditor";
import "./DeviceEditor.css";
import "./CommandsTab.css";
import {
  commandEndpointPath,
  errorMessage,
  fetchDeviceIdMap,
  fetchDevices,
  postCommandEndpoint,
} from "./api";
import {
  commandToDraft,
  createCommandDraft,
  draftsToCommands,
} from "./deviceForm";
import type { CommandDraft } from "./deviceForm";
import { driverBasePath } from "./client";
import type { CommandEndpoint, ResponseEntry } from "./types";

interface DeviceOption {
  deviceId: string;
  nodeIndex: string;
  commandIds: string[];
}

interface EndpointDefinition {
  id: CommandEndpoint;
  body: "commands" | "command-ids";
  description: string;
}

interface SendResult {
  endpoint: CommandEndpoint;
  deviceId: string;
  nodeIndex: string;
  sentAt: string;
  responses: ResponseEntry[];
}

const ENDPOINTS: EndpointDefinition[] = [
  {
    id: "request-commands",
    body: "commands",
    description:
      "Runs the commands written below once and returns their responses without emitting output.",
  },
  {
    id: "request-command-ids",
    body: "command-ids",
    description:
      "Runs the listed command IDs registered on the device without emitting output.",
  },
  {
    id: "execute-commands",
    body: "commands",
    description:
      "Runs the commands written below once and emits their responses to the configured output.",
  },
  {
    id: "execute-command-ids",
    body: "command-ids",
    description:
      "Runs the listed command IDs registered on the device and emits their responses.",
  },
];

const endpointDefinition = (endpoint: CommandEndpoint) =>
  ENDPOINTS.find((definition) => definition.id === endpoint) ?? ENDPOINTS[0];

function CommandsTab() {
  const [options, setOptions] = useState<DeviceOption[]>([]);
  const [deviceId, setDeviceId] = useState("");
  const [endpoint, setEndpoint] = useState<CommandEndpoint>("request-commands");
  const [initialValue, setInitialValue] = useState("");
  const [commandIdsText, setCommandIdsText] = useState("");
  const [drafts, setDrafts] = useState<CommandDraft[]>([createCommandDraft(1)]);
  const [result, setResult] = useState<SendResult | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const [idMap, devices] = await Promise.all([fetchDeviceIdMap(), fetchDevices()]);
      const commandIdsByDevice = new Map<string, string[]>();
      for (const device of devices) {
        commandIdsByDevice.set(
          device.id,
          (device.commands ?? []).map((command) => command.id).filter(Boolean)
        );
      }
      const next: DeviceOption[] = [];
      for (const nodeIndex of Object.keys(idMap).sort((a, b) => Number(a) - Number(b))) {
        for (const id of [...idMap[nodeIndex]].sort()) {
          next.push({
            deviceId: id,
            nodeIndex,
            commandIds: commandIdsByDevice.get(id) ?? [],
          });
        }
      }
      setOptions(next);
      setError("");
    } catch (e) {
      setError(errorMessage(e));
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  useEffect(() => {
    setDeviceId((current) =>
      options.some((option) => option.deviceId === current)
        ? current
        : (options[0]?.deviceId ?? "")
    );
  }, [options]);

  const selected = options.find((option) => option.deviceId === deviceId);
  const definition = endpointDefinition(endpoint);
  const usesCommandIds = definition.body === "command-ids";

  const commandIds = useMemo(
    () =>
      commandIdsText
        .split("\n")
        .map((line) => line.trim())
        .filter((line) => line !== ""),
    [commandIdsText]
  );

  const bodyPreview = useMemo(() => {
    try {
      const body = usesCommandIds ? commandIds : draftsToCommands(drafts);
      return JSON.stringify(body, null, 2);
    } catch (e) {
      return "invalid command: " + errorMessage(e);
    }
  }, [usesCommandIds, commandIds, drafts]);

  const appendCommandId = (commandId: string) => {
    setCommandIdsText((current) =>
      current.trim() === "" ? commandId : current.replace(/\s*$/, "") + "\n" + commandId
    );
  };

  const loadRegisteredCommands = async () => {
    if (!selected) return;
    setBusy(true);
    try {
      const devices = await fetchDevices();
      const device = devices.find((candidate) => candidate.id === selected.deviceId);
      const commands = device?.commands ?? [];
      if (commands.length === 0) {
        throw new Error("[" + selected.deviceId + "] has no registered command.");
      }
      setDrafts(commands.map((command, index) => commandToDraft(command, index)));
      setError("");
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const updateDraft = (index: number, next: CommandDraft) => {
    setDrafts((current) =>
      current.map((draft, draftIndex) => (draftIndex === index ? next : draft))
    );
  };

  const addDraft = () => {
    setDrafts((current) => [...current, createCommandDraft(current.length + 1)]);
  };

  const duplicateDraft = (index: number) => {
    setDrafts((current) => {
      const source = current[index];
      const copy: CommandDraft = {
        ...source,
        key: source.key + "-copy-" + current.length + "-" + index,
        id: (source.id || "command") + "_copy",
      };
      const next = [...current];
      next.splice(index + 1, 0, copy);
      return next;
    });
  };

  const removeDraft = (index: number) => {
    setDrafts((current) => current.filter((_, draftIndex) => draftIndex !== index));
  };

  const send = async () => {
    if (!selected) {
      setError("select a connected device first.");
      return;
    }
    setBusy(true);
    setError("");
    setResult(null);
    try {
      const body = usesCommandIds ? commandIds : draftsToCommands(drafts);
      if (usesCommandIds && body.length === 0) {
        throw new Error("enter at least one command id.");
      }
      const responses = await postCommandEndpoint(
        selected.nodeIndex,
        endpoint,
        selected.deviceId,
        body,
        initialValue
      );
      setResult({
        endpoint,
        deviceId: selected.deviceId,
        nodeIndex: selected.nodeIndex,
        sentAt: new Date().toLocaleTimeString(),
        responses,
      });
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
          <div>
            <h2>Command execution</h2>
            <p className="panel-description">
              Send one of the four command endpoints to a connected device. The request is
              routed to the node currently holding the device.
            </p>
          </div>
          <div className="toolbar">
            <button onClick={refresh} disabled={busy}>
              refresh devices
            </button>
            <button
              className="primary"
              onClick={send}
              disabled={busy || !selected}
            >
              send request
            </button>
          </div>
        </div>
        {options.length === 0 && (
          <div className="hint">no connected device. Connect a device on the Devices tab first.</div>
        )}

        <div className="endpoint-choices">
          {ENDPOINTS.map((candidate) => (
            <button
              key={candidate.id}
              type="button"
              className={
                candidate.id === endpoint ? "endpoint-choice active" : "endpoint-choice"
              }
              onClick={() => setEndpoint(candidate.id)}
            >
              <strong>{candidate.id}</strong>
              <span>{candidate.description}</span>
            </button>
          ))}
        </div>

        <div className="form-grid command-request-grid">
          <label className="form-field">
            <span>Device</span>
            <select
              value={deviceId}
              onChange={(event) => setDeviceId(event.target.value)}
              disabled={options.length === 0}
            >
              {options.length === 0 && <option value="">(no connected device)</option>}
              {options.map((option) => (
                <option key={option.deviceId} value={option.deviceId}>
                  {option.deviceId} (node {option.nodeIndex})
                </option>
              ))}
            </select>
            <small>Only devices currently connected in the cluster are listed.</small>
          </label>
          <label className="form-field">
            <span>initial-value header</span>
            <input
              type="text"
              value={initialValue}
              placeholder='optional, e.g. {"speed":1500}'
              onChange={(event) => setInitialValue(event.target.value)}
            />
            <small>Sent URL-encoded, and passed to the scripts as the first argument.</small>
          </label>
        </div>

        <div className="request-preview">
          <span>request</span>
          <code>
            POST{" "}
            {selected
              ? `/redirect-to-index/${selected.nodeIndex}${commandEndpointPath(
                  endpoint,
                  selected.deviceId
                )}`
              : `${driverBasePath}/${endpoint}/{deviceId}`}
          </code>
          {initialValue !== "" && (
            <code>initial-value: {encodeURIComponent(initialValue)}</code>
          )}
        </div>
      </section>

      <section className="panel builder-panel">
        {usesCommandIds ? (
          <>
            <div className="panel-header">
              <div>
                <h2>Command IDs</h2>
                <p className="panel-description">
                  One registered command ID per line. They run in the order written.
                </p>
              </div>
              <div className="toolbar">
                <button type="button" onClick={() => setCommandIdsText("")} disabled={busy}>
                  clear
                </button>
              </div>
            </div>
            {selected && selected.commandIds.length > 0 && (
              <div className="command-id-chips">
                {selected.commandIds.map((commandId) => (
                  <button
                    key={commandId}
                    type="button"
                    className="small"
                    onClick={() => appendCommandId(commandId)}
                  >
                    + {commandId}
                  </button>
                ))}
              </div>
            )}
            <label className="form-field">
              <span>command ids</span>
              <textarea
                className="command-ids-input"
                value={commandIdsText}
                placeholder={"read_temperature\nwrite_speed"}
                onChange={(event) => setCommandIdsText(event.target.value)}
                spellCheck={false}
              />
              <small>{commandIds.length} command ids</small>
            </label>
          </>
        ) : (
          <>
            <div className="panel-header">
              <div>
                <h2>Commands</h2>
                <p className="panel-description">
                  The commands are sent in the request body and do not have to be registered
                  on the device.
                </p>
              </div>
              <div className="toolbar">
                <button
                  type="button"
                  onClick={loadRegisteredCommands}
                  disabled={busy || !selected}
                >
                  load registered commands
                </button>
                <button type="button" className="primary" onClick={addDraft} disabled={busy}>
                  + Add command
                </button>
              </div>
            </div>
            {drafts.length === 0 ? (
              <div className="empty-editor-state">
                No command is written. Add a command to send.
              </div>
            ) : (
              <div className="command-list">
                {drafts.map((draft, index) => (
                  <CommandEditor
                    key={draft.key}
                    command={draft}
                    index={index}
                    onChange={(next) => updateDraft(index, next)}
                    onDuplicate={() => duplicateDraft(index)}
                    onRemove={() => removeDraft(index)}
                  />
                ))}
              </div>
            )}
          </>
        )}

        <details className="json-preview">
          <summary>request body preview</summary>
          <pre>{bodyPreview}</pre>
        </details>
      </section>

      <section className="panel">
        <div className="panel-header">
          <h2>Result</h2>
          {result && (
            <div className="hint">
              {result.endpoint} · {result.deviceId} (node {result.nodeIndex}) · {result.sentAt}
            </div>
          )}
        </div>
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
            {!result ? (
              <tr>
                <td colSpan={4} className="empty">
                  no request sent
                </td>
              </tr>
            ) : result.responses.length === 0 ? (
              <tr>
                <td colSpan={4} className="empty">
                  request succeeded without a response
                </td>
              </tr>
            ) : (
              result.responses.map((response, index) => (
                <tr key={`${response.deviceId}/${response.tagId}/${index}`}>
                  <td>{response.deviceId}</td>
                  <td>{response.tagId}</td>
                  <td className="value-cell">{response.value}</td>
                  <td>{new Date(response.receivedTime).toLocaleString()}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </section>
    </div>
  );
}

export default CommandsTab;
