import { readFileSync } from "node:fs";
import assert from "node:assert/strict";
import test from "node:test";
import ts from "typescript";

// Load the pure TypeScript form modules without adding a browser/test framework.
const compile = (name) => ts.transpileModule(readFileSync(new URL(`../src/${name}.ts`, import.meta.url), "utf8"), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
}).outputText;
const moduleUrl = (source) => `data:text/javascript;base64,${Buffer.from(source).toString("base64")}`;
const guidanceUrl = moduleUrl(compile("protocolGuidance"));
const guidance = await import(guidanceUrl);
const form = await import(moduleUrl(compile("deviceForm").replace('"./protocolGuidance"', JSON.stringify(guidanceUrl))));
const connection = (protocol, options = {}) => ({ ...form.createConnectionDraft(protocol), options });
const command = (type = "READ_REQUEST", extra = {}) => ({ ...form.createCommandDraft(), type, ...extra });
const guide = (protocol, type, context, initial = false) => guidance.commandGuidance(connection(protocol), command(type), context, initial);
const option = (protocol, key, options = {}, script = "") => guidance.optionPresentation(connection(protocol, options), form.protocolDefinition(protocol).options.find((item) => item.key === key), script);

test("all protocol defaults and forced connectionCommand agree between display and export", () => {
  for (const { id } of form.PROTOCOLS) {
    const server = id.endsWith("-server");
    assert.equal(option(id, "connectionLostOnException").defaultValue, String(!server));
    const draft = { ...form.createDeviceDraft(), connection: connection(id), connectionCommand: true };
    const setting = guidance.connectionCommandSetting(id, true);
    assert.equal(setting.value, !server);
    assert.equal(setting.locked, server || id === "http-client");
    assert.equal(form.draftsToDevices([draft])[0].connectionCommand, setting.value);
  }
  assert.equal(guidance.connectionCommandSetting("http-client", false).value, true);
});

test("TCP defaults distinguish endBytes and bufferingFunc from startBytes; UDP is zero", () => {
  assert.equal(option("tcp-client", "bufferTime").defaultValue, "100");
  assert.equal(option("tcp-server", "bufferTime", { startBytes: "\\x02" }).defaultValue, "100");
  assert.equal(option("tcp-client", "bufferTime", { endBytes: "\\x0D\\x0A" }).defaultValue, "0");
  assert.equal(option("tcp-client", "bufferTime", {}, "def bufferingFunc(buffer):\n    return True").defaultValue, "0");
  for (const id of ["udp-client", "udp-server"]) assert.equal(option(id, "bufferTime").defaultValue, "0");
});

test("OPC UA policy and username dependencies preserve inactive values", () => {
  assert.equal(option("opcua-client", "securityMode").disabled, true);
  assert.equal(option("opcua-client", "securityMode", { securityPolicy: "Basic256Sha256" }).disabled, false);
  assert.equal(option("opcua-server", "securityPolicy", { username: "user" }).defaultValue, "Basic256Sha256");
  assert.equal(option("opcua-server", "anonymous").defaultValue, "true");
  assert.equal(option("opcua-server", "anonymous", { username: "user" }).defaultValue, "false");
  const original = connection("opcua-client", { securityMode: "Sign", securityPolicy: "None" });
  guidance.optionPresentation(original, { key: "securityMode" });
  assert.equal(form.parseConnectionUrl(form.buildConnectionUrl(original)).options.securityMode, "Sign");
});

test("HTTP trust format reflects PEM vs password-only PKCS12", () => {
  assert.match(option("http-client", "trustFormat").defaultValue, /PEM/);
  assert.equal(option("http-server", "trustFormat", { trustPassword: "secret" }).defaultValue, "PKCS12");
  assert.match(option("http-client", "trustCert").hint, /only when cert/);
});

test("read response signatures include sender or complete HTTP fields and prepend initialValue", () => {
  const pools = {
    "tcp-client": ["received", "sender", "receivedTime"],
    "tcp-server": ["received", "sender", "receivedTime"],
    "udp-client": ["received", "sender", "receivedTime"],
    "udp-server": ["received", "sender", "receivedTime"],
    "modbus-client": ["values", "receivedTime"],
    "http-client": ["statusCode", "body", "headers", "receivedTime"],
    "opcua-client": ["received", "receivedTime"],
  };
  for (const [id, pool] of Object.entries(pools)) {
    assert.deepEqual(guide(id, "READ_REQUEST", "scheduled").cmdArgs, pool);
    assert.deepEqual(guide(id, "READ_REQUEST", "rest").requestArgs, []);
    assert.deepEqual(guide(id, "READ_REQUEST", "rest", true).cmdArgs, ["initialValue", ...pool]);
    assert.deepEqual(guide(id, "READ_REQUEST", "rest", true).requestArgs, ["initialValue"]);
  }
});

test("event read bypasses requestInfo and uses the event pool", () => {
  const pools = {
    "tcp-client": ["received", "sender", "receivedTime"],
    "tcp-server": ["received", "sender", "receivedTime"],
    "udp-client": ["received", "sender", "receivedTime"],
    "udp-server": ["received", "sender", "receivedTime"],
    "modbus-server": ["address", "quantityOrValues", "unitId", "receivedTime"],
    "http-server": ["method", "path", "body", "params", "headers", "receivedTime"],
    "opcua-client": ["nodeId", "value", "receivedTime"],
    "opcua-server": ["name", "value", "receivedTime"],
  };
  for (const [id, pool] of Object.entries(pools)) {
    const result = guide(id, "READ_REQUEST", "event", true);
    assert.deepEqual(result.cmdArgs, pool);
    assert.equal(result.requestArgs, null);
    assert.deepEqual(guide(id, "WRITE_REQUEST", "event").requestArgs, pool);
    assert.equal(guide(id, "WRITE_REQUEST", "event").cmdArgs, null);
  }
});

test("OPC UA lifecycle READ selected by events uses subscription input then read response", () => {
  const result = guide("opcua-client", "STARTING_READ_REQUEST", "event");
  assert.deepEqual(result.requestArgs, ["nodeId", "value", "receivedTime"]);
  assert.deepEqual(result.cmdArgs, ["received", "receivedTime"]);
  assert.ok(result.warnings.some((warning) => warning.includes("Lifecycle")));
});

test("all REQUEST variants ignore event input and requestInfo, including Dummy", () => {
  for (const { id } of form.PROTOCOLS) for (const type of ["REQUEST", "STARTING_REQUEST", "STOPPING_REQUEST"]) {
    assert.deepEqual(guide(id, type, "event").cmdArgs, ["receivedTime"]);
    assert.deepEqual(guide(id, type, "rest", true).cmdArgs, ["initialValue", "receivedTime"]);
    assert.equal(guide(id, type, "rest").requestArgs, null);
  }
});

test("unsupported paths have visible warnings and no invented receive signature", () => {
  assert.match(guide("http-client", "WRITE_REQUEST", "rest").warnings.join(), /does not support WRITE/);
  assert.equal(guide("http-client", "WRITE_REQUEST", "rest").template, "");
  for (const id of ["http-server", "opcua-server", "modbus-server", "dummy"]) {
    const result = guide(id, "READ_REQUEST", "rest");
    assert.equal(result.cmdArgs, null);
    assert.equal(result.template, "");
    assert.ok(result.warnings.length);
  }
  const unknown = guidance.commandGuidance(undefined, command(), "rest");
  assert.equal(unknown.cmdArgs, null);
  assert.equal(unknown.template, "");
  assert.match(unknown.warnings.join(), /unavailable/);
});

test("protocol initialization guidance does not suggest unused protocolFunc", () => {
  assert.match(guidance.protocolScriptGuidance("http-server").signature, /method, path, body, params, headers/);
  for (const id of ["modbus-client", "modbus-server", "opcua-client", "opcua-server", "http-client"]) {
    assert.match(guidance.protocolScriptGuidance(id).hint, /does not call protocolFunc/);
  }
  assert.match(guidance.protocolScriptGuidance("dummy").hint, /does not execute/);
});

test("HTTP static response and Modbus helper examples use supported shapes", () => {
  const http = guidance.requestExample("http-server", true);
  assert.deepEqual(JSON.parse(http.value).headers, { "Content-Type": ["application/json"] });
  assert.match(guidance.requestExample("modbus-client", true).helper, /"40001"/);
  assert.match(guidance.requestExample("opcua-client", false).helper, /requestInfo\("ns=2;s=Tag1"\)/);
});

test("unitId rejects fractional and non-decimal input before connect or export", () => {
  for (const value of ["1.5", "1e2", "-1", "256", "NaN", "0x10", " 1"]) {
    const draft = { ...form.createDeviceDraft(), connection: connection("modbus-client", { unitId: value }) };
    assert.throws(() => form.draftsToDevices([draft]), /unitId/);
  }
  for (const value of ["", "0", "1", "255"]) {
    const draft = { ...form.createDeviceDraft(), connection: connection("modbus-client", { unitId: value }) };
    assert.doesNotThrow(() => form.draftsToDevices([draft]));
  }
});

test("OPC UA insecure username and invalid command IDs are rejected in both forms", () => {
  const draft = { ...form.createDeviceDraft(), connection: connection("opcua-server", { username: "user", securityPolicy: "None" }) };
  assert.throws(() => form.draftsToDevices([draft]), /secure security policy/);
  const bad = command("REQUEST", { id: "bad-id" });
  assert.throws(() => form.draftsToCommands([bad]), /letters, digits/);
  assert.throws(() => form.draftsToDevices([{ ...form.createDeviceDraft(), commands: [bad] }]), /letters, digits/);
});

test("import/export preserves scripts, custom options and encoded byte delimiters", () => {
  const source = { id: "device_1", connectionUrl: "tcp-client://127.0.0.1:5000?endBytes=%5Cx0D%5Cx0A&extra=a%26b", commands: [{ id: "read", type: "READ_REQUEST", cmdScript: "def cmdFunc(received, sender, receivedTime):\n    return []" }] };
  const draft = form.deviceToDraft(source, 0);
  const originalScript = draft.commands[0].cmdScript;
  guidance.commandGuidance(draft.connection, draft.commands[0], "rest", true);
  const exported = form.draftsToDevices([draft])[0];
  assert.equal(exported.commands[0].cmdScript, originalScript);
  assert.equal(exported.connectionUrl, source.connectionUrl);
});
