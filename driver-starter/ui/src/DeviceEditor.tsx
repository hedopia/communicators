import CodeEditor from "./CodeEditor";
import "./DeviceEditor.css";
import CommandEditor from "./CommandEditor";
import ConnectionEditor from "./ConnectionEditor";
import {
  createCommandDraft,
  protocolDefinition,
} from "./deviceForm";
import type { CommandDraft, DeviceDraft } from "./deviceForm";

interface DeviceEditorProps {
  draft: DeviceDraft;
  index: number;
  total: number;
  onChange: (draft: DeviceDraft) => void;
  onDuplicate: () => void;
  onRemove: () => void;
}

type DeviceNumberField =
  | "responseTimeout"
  | "maxRetryConnect"
  | "retryConnectDelay"
  | "socketTimeout"
  | "initialCommandDelay";

function DeviceEditor({
  draft,
  index,
  total,
  onChange,
  onDuplicate,
  onRemove,
}: DeviceEditorProps) {
  const definition = protocolDefinition(draft.connection.protocol);

  const patch = (values: Partial<DeviceDraft>) => {
    onChange({ ...draft, ...values });
  };

  const setNumber = (field: DeviceNumberField, value: string) => {
    patch({ [field]: value === "" ? 0 : Number(value) });
  };

  const updateCommand = (commandIndex: number, command: CommandDraft) => {
    patch({
      commands: draft.commands.map((current, indexToUpdate) =>
        indexToUpdate === commandIndex ? command : current
      ),
    });
  };

  const addCommand = () => {
    patch({
      commands: [
        ...draft.commands,
        createCommandDraft(draft.commands.length + 1),
      ],
    });
  };

  const duplicateCommand = (commandIndex: number) => {
    const source = draft.commands[commandIndex];
    const copy: CommandDraft = {
      ...source,
      key: source.key + "-copy-" + draft.commands.length + "-" + commandIndex,
      id: (source.id || "command") + "_copy",
    };
    const commands = [...draft.commands];
    commands.splice(commandIndex + 1, 0, copy);
    patch({ commands });
  };

  const removeCommand = (commandIndex: number) => {
    patch({
      commands: draft.commands.filter((_, indexToRemove) => indexToRemove !== commandIndex),
    });
  };

  return (
    <article className="device-card">
      <div className="device-card-header">
        <div className="device-card-identity">
          <span className="item-index">Device {index + 1}</span>
          <div>
            <h3>{draft.id || "unnamed device"}</h3>
            <span>{definition.label}</span>
          </div>
        </div>
        <div className="toolbar">
          <button type="button" className="small" onClick={onDuplicate}>
            Duplicate device
          </button>
          <button
            type="button"
            className="danger small"
            onClick={onRemove}
            disabled={total <= 1}
          >
            Remove device
          </button>
        </div>
      </div>

      <section className="editor-section">
        <div className="section-title-row">
          <div>
            <h4>Device basic settings</h4>
            <p>Each field of the Java Device class is configured individually.</p>
          </div>
        </div>
        <div className="form-grid">
          <label className="form-field">
            <span>Device ID</span>
            <input
              type="text"
              value={draft.id}
              placeholder="device1"
              onChange={(event) => patch({ id: event.target.value })}
            />
            <small>Only letters, digits, and underscores are allowed.</small>
          </label>
          <label className="form-field">
            <span>Group</span>
            <input
              type="text"
              value={draft.group}
              placeholder="group placed on the same node"
              onChange={(event) => patch({ group: event.target.value })}
            />
          </label>
          <label className="form-field">
            <span>Response timeout (sec)</span>
            <input
              type="number"
              value={draft.responseTimeout}
              onChange={(event) => setNumber("responseTimeout", event.target.value)}
            />
            <small>Zero or less means unlimited.</small>
          </label>
          <label className="form-field">
            <span>Max retry connect</span>
            <input
              type="number"
              value={draft.maxRetryConnect}
              onChange={(event) => setNumber("maxRetryConnect", event.target.value)}
            />
            <small>A negative value retries forever.</small>
          </label>
          <label className="form-field">
            <span>Retry delay (ms)</span>
            <input
              type="number"
              min="0"
              value={draft.retryConnectDelay}
              onChange={(event) => setNumber("retryConnectDelay", event.target.value)}
            />
          </label>
          <label className="form-field">
            <span>Socket timeout (ms)</span>
            <input
              type="number"
              min="0"
              value={draft.socketTimeout}
              onChange={(event) => setNumber("socketTimeout", event.target.value)}
            />
          </label>
          <label className="form-field">
            <span>Initial command delay (ms)</span>
            <input
              type="number"
              min="0"
              value={draft.initialCommandDelay}
              onChange={(event) => setNumber("initialCommandDelay", event.target.value)}
            />
          </label>
          <label className="form-field checkbox-field">
            <span>Connection command</span>
            <span className="checkbox-control">
              <input
                type="checkbox"
                checked={draft.connectionCommand}
                onChange={(event) => patch({ connectionCommand: event.target.checked })}
              />
              Connect only while a request runs
            </span>
          </label>
          <label className="form-field span-2">
            <span>Device data (JSON object)</span>
            <textarea
              className="data-input"
              value={draft.dataText}
              onChange={(event) => patch({ dataText: event.target.value })}
              spellCheck={false}
            />
          </label>
        </div>
        {Object.keys(draft.extra).length > 0 && (
          <div className="preserved-fields">
            Extra fields of the imported file ({Object.keys(draft.extra).join(", ")}) are kept when saving.
          </div>
        )}
      </section>

      <ConnectionEditor
        connection={draft.connection}
        onChange={(connection) => patch({ connection })}
      />

      <section className="editor-section">
        <div className="section-title-row">
          <div>
            <h4>Protocol script</h4>
            <p>Protocol-level packet handling functions are written in Python.</p>
          </div>
        </div>
        <CodeEditor
          label="Protocol script"
          value={draft.protocolScript}
          onChange={(protocolScript) => patch({ protocolScript })}
          placeholder="def protocolFunc(received, sender, receivedTime):"
          minHeight={220}
        />
      </section>

      <section className="editor-section command-section">
        <div className="section-title-row">
          <div>
            <h4>Commands</h4>
            <p>Execution conditions, requestInfo, and the Python cmdScript of each Command.</p>
          </div>
          <button type="button" className="primary" onClick={addCommand}>
            + Add command
          </button>
        </div>
        {draft.commands.length === 0 ? (
          <div className="empty-editor-state">
            No command is registered. Add a command if one is needed.
          </div>
        ) : (
          <div className="command-list">
            {draft.commands.map((command, commandIndex) => (
              <CommandEditor
                key={command.key}
                command={command}
                index={commandIndex}
                onChange={(next) => updateCommand(commandIndex, next)}
                onDuplicate={() => duplicateCommand(commandIndex)}
                onRemove={() => removeCommand(commandIndex)}
              />
            ))}
          </div>
        )}
      </section>
    </article>
  );
}

export default DeviceEditor;
