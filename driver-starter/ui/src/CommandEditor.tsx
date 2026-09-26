import CodeEditor from "./CodeEditor";
import { useState } from "react";
import { commandGuidance } from "./protocolGuidance";
import type { ExecutionContext } from "./protocolGuidance";
import { COMMAND_TYPES } from "./deviceForm";
import type { CommandDraft, ConnectionDraft } from "./deviceForm";
import type { CommandType } from "./types";

interface CommandEditorProps {
  command: CommandDraft;
  connection?: ConnectionDraft;
  executionContext?: ExecutionContext;
  initialValue?: boolean;
  index: number;
  onChange: (command: CommandDraft) => void;
  onDuplicate: () => void;
  onRemove: () => void;
}

type NumberField = "order" | "periodGroup" | "afterDelay" | "commandTimeout";

function CommandEditor({
  command,
  connection,
  executionContext,
  initialValue = false,
  index,
  onChange,
  onDuplicate,
  onRemove,
}: CommandEditorProps) {
  const [previewContext, setPreviewContext] = useState<ExecutionContext | "auto">("auto");
  const [previewInitialValue, setPreviewInitialValue] = useState(false);
  const context = executionContext ?? (previewContext === "auto"
    ? command.periodGroup < 0 && !command.type.startsWith("STARTING_") && !command.type.startsWith("STOPPING_") ? "event" : "scheduled"
    : previewContext);
  const guide = commandGuidance(connection, command, context, executionContext ? initialValue : previewInitialValue);
  const patch = (values: Partial<CommandDraft>) => {
    onChange({ ...command, ...values });
  };

  const setNumber = (field: NumberField, value: string) => {
    patch({ [field]: value === "" ? 0 : Number(value) });
  };

  return (
    <article className="command-card">
      <div className="command-card-header">
        <div>
          <span className="item-index">Command {index + 1}</span>
          <strong>{command.id || "unnamed command"}</strong>
          <span className="command-type-chip">{command.type}</span>
        </div>
        <div className="toolbar">
          <button type="button" className="small" onClick={onDuplicate}>
            duplicate
          </button>
          <button type="button" className="danger small" onClick={onRemove}>
            remove
          </button>
        </div>
      </div>

      <div className="form-grid command-fields">
        <label className="form-field">
          <span>Command ID</span>
          <input
            type="text"
            value={command.id}
            placeholder="read_temperature"
            onChange={(event) => patch({ id: event.target.value })}
          />
        </label>
        <label className="form-field">
          <span>Type</span>
          <select
            value={command.type}
            onChange={(event) =>
              patch({ type: event.target.value as CommandType })
            }
          >
            {COMMAND_TYPES.map((type) => (
              <option key={type} value={type}>
                {type}
              </option>
            ))}
          </select>
        </label>
        <label className="form-field">
          <span>Order</span>
          <input
            type="number"
            value={command.order}
            onChange={(event) => setNumber("order", event.target.value)}
          />
          <small>Executed in ascending order.</small>
        </label>
        <label className="form-field">
          <span>Period group (ms)</span>
          <input
            type="number"
            value={command.periodGroup}
            onChange={(event) => setNumber("periodGroup", event.target.value)}
          />
          <small>A negative value means an event / non-periodic command.</small>
        </label>
        <label className="form-field">
          <span>After delay (ms)</span>
          <input
            type="number"
            min="0"
            value={command.afterDelay}
            onChange={(event) => setNumber("afterDelay", event.target.value)}
          />
        </label>
        <label className="form-field">
          <span>Command timeout (ms)</span>
          <input
            type="number"
            min="0"
            value={command.commandTimeout}
            onChange={(event) => setNumber("commandTimeout", event.target.value)}
          />
        </label>
        <label className="form-field span-2">
          <span>Request info</span>
          <textarea
            className="request-info-input"
            value={command.requestInfo}
            placeholder={guide.example.value || "No static request example for this protocol"}
            onChange={(event) => patch({ requestInfo: event.target.value })}
            spellCheck={false}
          />
          <small>
            {guide.requestHint}
          </small>
        </label>
      </div>

      <section className="script-guide" aria-label="Protocol script guidance">
        <strong>{connection?.protocol ?? "Unknown protocol"} · Script arguments</strong>
        {!executionContext && <div className="form-grid">
          <label className="form-field">
            <span>Argument preview for (does not change execution)</span>
            <select value={previewContext} onChange={(event) => setPreviewContext(event.target.value as ExecutionContext | "auto")}>
              <option value="auto">Automatic from type / period group</option>
              <option value="event">Incoming event</option>
              <option value="scheduled">Periodic / starting / stopping</option>
              <option value="rest">REST command endpoint</option>
            </select>
          </label>
          {context === "rest" && <label className="form-field checkbox-field">
            <span>REST preview</span>
            <span className="checkbox-control"><input type="checkbox" checked={previewInitialValue} onChange={(event) => setPreviewInitialValue(event.target.checked)} /> Include initialValue</span>
          </label>}
        </div>}
        <p>Execution context: {context}. Arguments bind by position, not name. Fewer parameters take the leading values; extra parameters fail. receivedTime is last and requires the full argument list.</p>
        <code>{guide.cmdArgs ? `def cmdFunc(${guide.cmdArgs.join(", ")}):` : "cmdFunc: not called on this path, or no supported receive path"}</code>
        <code>{guide.requestArgs ? `def requestInfo(${guide.requestArgs.join(", ")}):` : "requestInfo callback: unused on this path, or arguments unavailable"}</code>
        <p>cmdFunc returns a list of (tagId, value[, time]) tuples, [] or None. requestInfo returns a string or None; use a helper or json.dumps for JSON, never return a dict/list directly.</p>
        <p>Compilation requires cmdFunc for READ and REQUEST types, and static requestInfo or its callback for WRITE and nonnegative-period READ types. Python code and request payloads are validated by the runtime.</p>
        {guide.warnings.map((warning) => <p className="script-warning" key={warning}>{warning}</p>)}
        <details>
          <summary>Protocol request format and helper example</summary>
          <p>{guide.example.hint}</p>
          {guide.example.value && <><span>Static requestInfo example</span><code>{guide.example.value}</code></>}
          {guide.example.helper && <><span>Inside the Python requestInfo callback</span><code>{`return ${guide.example.helper}`}</code></>}
          <p>protocol.requestInfo(...) builds a request string; it does not execute a command. Its arguments differ from def requestInfo(...).</p>
        </details>
        <button type="button" className="small" disabled={!!command.cmdScript || !guide.template || !connection} onClick={() => patch({ cmdScript: guide.template })}>Insert skeleton into empty script</button>
        <small>The skeleton follows this preview only. Review it for every execution path; changing protocol or preview leaves existing code intact.</small>
      </section>
      <CodeEditor
        label="Command script"
        value={command.cmdScript}
        onChange={(cmdScript) => patch({ cmdScript })}
        placeholder={guide.template || "# Select a supported protocol and execution path"}
        minHeight={190}
      />
    </article>
  );
}

export default CommandEditor;
