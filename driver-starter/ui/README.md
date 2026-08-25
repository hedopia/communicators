# Driver Management UI

This directory contains the React and TypeScript management UI served by `driver-starter`.

## Features

The application has three tabs:

- **Devices** shows cluster-wide device status and provides disconnect and reconnect actions.
- **Nodes** shows cluster membership, leader/follower position, activation state, and per-node device counts.
- **Responses** shows collected values and supports device filtering and automatic refresh.

The Devices tab also contains a structured device configuration builder:

- Select one of the supported protocols before entering connection details.
- Edit all `Device` fields individually.
- Configure protocol-specific `connectionUrl` options.
- Add, edit, duplicate, and remove commands.
- Edit all `Command` fields individually.
- Write `protocolScript` and `cmdScript` Python code in editors with line numbers and Tab indentation.
- Preview the generated Device JSON.
- Import a Device JSON array from a file.
- Save the current form as `devices.json`.
- Load or export the configuration currently stored by the cluster.
- Preserve imported Device extension fields and unrecognized URL query options. Unknown URL options appear under custom options. Changing the protocol intentionally resets options that belong to the previous protocol.

Supported protocols:

- TCP client and server
- UDP client and server
- Modbus TCP client and server
- HTTP client and server
- OPC UA client and server
- Dummy

See the repository-level [driver guide](../../driver.md) for Device fields, Command behavior, protocol URL formats, and script APIs.

## Source layout

```text
src/
  App.tsx                 Tab shell
  DevicesTab.tsx          Device status and structured configuration workflow
  DeviceEditor.tsx        Device field, script, and command sections
  ConnectionEditor.tsx    Protocol selector and URL option form
  CommandEditor.tsx       Command field and command-script editor
  CodeEditor.tsx          Lightweight Python code editor
  deviceForm.ts           Protocol schemas, drafts, validation, and URL conversion
  NodesTab.tsx            Cluster node management
  ResponsesTab.tsx        Collected response viewer
  api.ts                  REST API calls
  client.ts               Axios instance and base-path helpers
  hooks.ts                Automatic refresh hook
  types.ts                REST and form-facing data types
```

No external component or code-editor framework is used.

## Requirements

- Node.js and npm
- A running driver node on `http://localhost:4001` for live development

Install dependencies:

```bash
npm install
```

## Development

```bash
npm run dev
```

The Vite development server proxies these prefixes to `http://localhost:4001`:

- `/driver`
- `/cluster`
- `/redirect-to-index`
- `/redirect-to-leader`

The development build replaces `__APP_BASE_PATH__` with `/driver`.

## Quality checks

```bash
npm run lint
npm run build
```

`npm run build` runs the TypeScript project build before creating the production bundle.

## Production output

The Vite output directory is:

```text
driver-starter/src/main/resources/static
```

The generated files are packaged into the `driver-starter` JAR by the Java build.

Production `index.html` intentionally retains the `__APP_BASE_PATH__` placeholder. `DriverServerRoutes` replaces it with the configured `driverBasePath` while serving the page. Asset paths are relative, so non-default driver base paths remain supported.

## REST API usage

The UI uses the driver and cluster APIs documented in [driver.md](../../driver.md) and [cluster.md](../../cluster.md). Important calls include:

| Purpose | Request |
|---|---|
| Connect the form's devices | `POST /driver/balanced-connect-all` |
| Disconnect selected devices | `DELETE /driver/disconnect` |
| Disconnect all devices | `DELETE /driver/disconnect-all` |
| Reconnect all devices | `PUT /driver/reconnect-all` |
| Load or export connected settings | `GET /driver/devices` |
| Read cluster-wide device placement | `GET /driver/device-id-map` |
| Read a node's status map | `GET /redirect-to-index/{nodeIndex}/driver/device-status` |
| Read collected values | `GET /driver/response` |
| Read cluster membership | `GET /cluster/get-cluster-nodes` |

## Device file format

Import and export use the same Device JSON array accepted by `POST /driver/balanced-connect-all`.

```json
[
  {
    "id": "dummy_1",
    "connectionUrl": "dummy://",
    "responseTimeout": 0,
    "maxRetryConnect": 5,
    "retryConnectDelay": 5000,
    "socketTimeout": 5000,
    "initialCommandDelay": 5000,
    "connectionCommand": false,
    "data": {},
    "protocolScript": "",
    "commands": [
      {
        "id": "generate",
        "type": "REQUEST",
        "order": 0,
        "periodGroup": 1000,
        "afterDelay": 0,
        "commandTimeout": 5000,
        "requestInfo": "",
        "cmdScript": "def cmdFunc(receivedTime):\n    return [('value', '1', receivedTime)]"
      }
    ]
  }
]
```

Validation is performed before connecting or saving. Device and command IDs must contain only letters, numbers, and underscores; ports and numeric settings must be valid numbers; and `data` must contain a JSON object.