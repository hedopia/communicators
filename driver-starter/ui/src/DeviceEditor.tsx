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
            <h3>{draft.id || "이름 없는 device"}</h3>
            <span>{definition.label}</span>
          </div>
        </div>
        <div className="toolbar">
          <button type="button" className="small" onClick={onDuplicate}>
            Device 복제
          </button>
          <button
            type="button"
            className="danger small"
            onClick={onRemove}
            disabled={total <= 1}
          >
            Device 삭제
          </button>
        </div>
      </div>

      <section className="editor-section">
        <div className="section-title-row">
          <div>
            <h4>Device 기본 설정</h4>
            <p>Java Device class의 필드를 각각 설정합니다.</p>
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
            <small>영문, 숫자, 밑줄만 사용할 수 있습니다.</small>
          </label>
          <label className="form-field">
            <span>Group</span>
            <input
              type="text"
              value={draft.group}
              placeholder="같은 노드에 배치할 group"
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
            <small>0 이하는 무제한입니다.</small>
          </label>
          <label className="form-field">
            <span>Max retry connect</span>
            <input
              type="number"
              value={draft.maxRetryConnect}
              onChange={(event) => setNumber("maxRetryConnect", event.target.value)}
            />
            <small>음수는 무한 재시도입니다.</small>
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
              요청할 때만 연결
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
            가져온 파일의 추가 필드 {Object.keys(draft.extra).join(", ")}도 저장 시 유지됩니다.
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
            <p>Protocol 수준의 packet 처리 함수를 Python으로 작성합니다.</p>
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
            <p>각 Command의 실행 조건, requestInfo와 Python cmdScript를 설정합니다.</p>
          </div>
          <button type="button" className="primary" onClick={addCommand}>
            + Command 추가
          </button>
        </div>
        {draft.commands.length === 0 ? (
          <div className="empty-editor-state">
            등록된 Command가 없습니다. 필요한 경우 Command를 추가하세요.
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
