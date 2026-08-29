import {
  PROTOCOLS,
  buildConnectionUrl,
  createConnectionDraft,
  protocolDefinition,
} from "./deviceForm";
import type {
  ConnectionDraft,
  OptionDefinition,
  ProtocolId,
} from "./deviceForm";

interface ConnectionEditorProps {
  connection: ConnectionDraft;
  onChange: (connection: ConnectionDraft) => void;
}

function ConnectionEditor({ connection, onChange }: ConnectionEditorProps) {
  const definition = protocolDefinition(connection.protocol);

  const patch = (values: Partial<ConnectionDraft>) => {
    onChange({ ...connection, ...values });
  };

  const selectProtocol = (protocol: ProtocolId) => {
    const next = createConnectionDraft(protocol);
    const connectionLost = connection.options.connectionLostOnException;
    if (connectionLost !== undefined) {
      next.options.connectionLostOnException = connectionLost;
    }
    onChange(next);
  };

  const setOption = (name: string, value: string) => {
    onChange({
      ...connection,
      options: { ...connection.options, [name]: value },
    });
  };

  const addCustomOption = () => {
    onChange({
      ...connection,
      customOptions: [
        ...connection.customOptions,
        {
          key: "custom-" + Date.now() + "-" + connection.customOptions.length,
          name: "",
          value: "",
        },
      ],
    });
  };

  const updateCustomOption = (
    index: number,
    field: "name" | "value",
    value: string
  ) => {
    onChange({
      ...connection,
      customOptions: connection.customOptions.map((option, optionIndex) =>
        optionIndex === index ? { ...option, [field]: value } : option
      ),
    });
  };

  const removeCustomOption = (index: number) => {
    onChange({
      ...connection,
      customOptions: connection.customOptions.filter(
        (_, optionIndex) => optionIndex !== index
      ),
    });
  };

  const renderOption = (option: OptionDefinition) => {
    const value = connection.options[option.key] ?? "";
    let control;
    if (option.kind === "boolean") {
      control = (
        <select
          value={value}
          onChange={(event) => setOption(option.key, event.target.value)}
        >
          <option value="">unset (default)</option>
          <option value="true">true</option>
          <option value="false">false</option>
        </select>
      );
    } else if (option.kind === "select") {
      control = (
        <select
          value={value}
          onChange={(event) => setOption(option.key, event.target.value)}
        >
          <option value="">{option.placeholder ?? "unset (default)"}</option>
          {option.choices?.map((choice) => (
            <option key={choice} value={choice}>
              {choice}
            </option>
          ))}
        </select>
      );
    } else {
      control = (
        <input
          type={option.kind === "password" ? "password" : option.kind}
          value={value}
          placeholder={option.placeholder}
          onChange={(event) => setOption(option.key, event.target.value)}
        />
      );
    }

    return (
      <label className="form-field" key={option.key}>
        <span>{option.label}</span>
        {control}
        {option.hint && <small>{option.hint}</small>}
      </label>
    );
  };

  return (
    <section className="editor-section connection-section">
      <div className="section-title-row">
        <div>
          <h4>Protocol and connectionUrl</h4>
          <p>Selecting a protocol shows the matching address fields and supported options.</p>
        </div>
      </div>

      <div className="form-grid">
        <label className="form-field span-2">
          <span>Protocol</span>
          <select
            value={connection.protocol}
            onChange={(event) => selectProtocol(event.target.value as ProtocolId)}
          >
            {PROTOCOLS.map((protocol) => (
              <option key={protocol.id} value={protocol.id}>
                {protocol.label}
              </option>
            ))}
          </select>
          <small>{definition.hint}</small>
        </label>

        {definition.target === "base-url" && (
          <label className="form-field span-2">
            <span>Base URL</span>
            <input
              type="text"
              value={connection.baseUrl}
              placeholder="https://api.example.com/v1"
              onChange={(event) => patch({ baseUrl: event.target.value })}
            />
          </label>
        )}

        {(definition.target === "host-port" || definition.target === "opc") && (
          <>
            <label className="form-field">
              <span>Host / Bind address</span>
              <input
                type="text"
                value={connection.host}
                placeholder={connection.protocol.endsWith("-server") ? "empty binds every interface" : "127.0.0.1"}
                onChange={(event) => patch({ host: event.target.value })}
              />
            </label>
            <label className="form-field">
              <span>Port</span>
              <input
                type="number"
                min="1"
                max="65535"
                value={connection.port}
                onChange={(event) => patch({ port: event.target.value })}
              />
            </label>
          </>
        )}

        {definition.target === "opc" && (
          <label className="form-field span-2">
            <span>Endpoint path</span>
            <input
              type="text"
              value={connection.path}
              placeholder="/server"
              onChange={(event) => patch({ path: event.target.value })}
            />
          </label>
        )}
      </div>

      <div className="connection-url-preview">
        <span>generated connectionUrl</span>
        <code>{buildConnectionUrl(connection)}</code>
      </div>

      <div className="subsection-title">
        <div>
          <h5>Protocol options</h5>
          <p>An empty value produces no query parameter.</p>
        </div>
      </div>
      <div className="form-grid option-grid">
        {definition.options.map(renderOption)}
      </div>

      <div className="subsection-title custom-option-title">
        <div>
          <h5>Custom options</h5>
          <p>Custom query options of an imported file are preserved as they are.</p>
        </div>
        <button type="button" className="small" onClick={addCustomOption}>
          + Add option
        </button>
      </div>
      {connection.customOptions.length > 0 && (
        <div className="custom-options">
          {connection.customOptions.map((option, index) => (
            <div className="custom-option-row" key={option.key}>
              <input
                type="text"
                aria-label={"Custom option " + (index + 1) + " name"}
                placeholder="option name"
                value={option.name}
                onChange={(event) =>
                  updateCustomOption(index, "name", event.target.value)
                }
              />
              <input
                type="text"
                aria-label={"Custom option " + (index + 1) + " value"}
                placeholder="value"
                value={option.value}
                onChange={(event) =>
                  updateCustomOption(index, "value", event.target.value)
                }
              />
              <button
                type="button"
                className="danger small"
                onClick={() => removeCustomOption(index)}
              >
                remove
              </button>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

export default ConnectionEditor;
