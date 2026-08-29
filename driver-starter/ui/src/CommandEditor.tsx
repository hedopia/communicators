import CodeEditor from "./CodeEditor";
import { COMMAND_TYPES } from "./deviceForm";
import type { CommandDraft } from "./deviceForm";
import type { CommandType } from "./types";

interface CommandEditorProps {
  command: CommandDraft;
  index: number;
  onChange: (command: CommandDraft) => void;
  onDuplicate: () => void;
  onRemove: () => void;
}

type NumberField = "order" | "periodGroup" | "afterDelay" | "commandTimeout";

function CommandEditor({
  command,
  index,
  onChange,
  onDuplicate,
  onRemove,
}: CommandEditorProps) {
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
            placeholder='String or JSON matching the protocol request format (e.g. ["ns=2;s=Tag1"])'
            onChange={(event) => patch({ requestInfo: event.target.value })}
            spellCheck={false}
          />
          <small>
            Used as the default when a request-info function is present, and may be left empty.
          </small>
        </label>
      </div>

      <CodeEditor
        label="Command script"
        value={command.cmdScript}
        onChange={(cmdScript) => patch({ cmdScript })}
        placeholder="def cmdFunc(received, receivedTime):"
        minHeight={190}
      />
    </article>
  );
}

export default CommandEditor;
