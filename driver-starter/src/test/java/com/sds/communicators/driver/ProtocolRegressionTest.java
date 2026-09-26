package com.sds.communicators.driver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sds.communicators.common.struct.Command;
import com.sds.communicators.common.struct.Device;
import com.sds.communicators.common.struct.Response;
import com.sds.communicators.common.type.CommandType;
import com.sds.communicators.driver.support.PythonEngine;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolRegressionTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<PythonEngine> engines = new ArrayList<>();

    @AfterEach
    void closeEngines() {
        engines.forEach(PythonEngine::close);
    }

    private <T extends DriverProtocol> T create(T protocol, Command... commands) throws Exception {
        var device = new Device();
        device.setId("regression");
        device.setConnectionUrl("dummy://");
        device.setCommands(Set.of(commands));
        protocol.create(null, "", device);
        engines.add(protocol.driverCommand.pythonEngine);
        return protocol;
    }

    private Command command(String id, String value) {
        var command = new Command();
        command.setId(id);
        command.setType(CommandType.REQUEST);
        command.setCmdScript("def cmdFunc():\n    return [('result', '" + value + "')]\n");
        return command;
    }

    private PythonEngine engine() {
        var engine = new PythonEngine();
        engines.add(engine);
        return engine;
    }

    private JsonNode evaluate(PythonEngine engine, String expression) throws Exception {
        engine.exec("result = " + expression);
        return json.readTree(engine.get("result").asString());
    }

    @Test
    void deviceInitializesThroughTheRealConstructorAndExposesJavaGlobals() throws Exception {
        var protocol = create(new DriverProtocolDummy());
        var engine = protocol.driverCommand.pythonEngine;
        engine.exec("size = len(UtilFunc.stringToByteArray('A'))\njavaName = java.type('java.lang.String').valueOf(7)");
        assertEquals(1, engine.get("size").asInt());
        assertEquals("7", engine.get("javaName").asString());
    }

    @Test
    void editedCommandExecutesWithoutReplacingTheRegisteredCommand() throws Exception {
        var protocol = create(new DriverProtocolDummy(), command("same_id", "registered"));
        var commands = protocol.driverCommand;
        var response = commands.lockedExecuteCommands(Set.of(command("same_id", "edited")), (String) null, false);
        assertEquals("edited", response.getFirst().getValue());
        assertEquals("registered", commands.lockedExecuteCommands(List.of("same_id"), (String) null, false).getFirst().getValue());

        var invalidEdit = command("same_id", "unused");
        invalidEdit.setCmdScript("def requestInfo():\n    return None\n");
        assertTrue(assertThrows(Exception.class, () -> commands.lockedExecuteCommands(Set.of(invalidEdit), (String) null, false))
                .getMessage().contains("has no \"cmdFunc\""));
        assertEquals("registered", commands.lockedExecuteCommands(List.of("same_id"), (String) null, false).getFirst().getValue());
        commands.pythonEngine.exec("temporaryNames = [n for n in globals() if n.startswith(('cmdFunc_temporary_', 'requestInfo_temporary_'))]");
        assertEquals(0, commands.pythonEngine.get("temporaryNames").getArraySize());
    }

    @Test
    void editedCommandUsesItsOwnTypeAndRequestInfo() throws Exception {
        var protocol = create(new RecordingProtocol(), command("same_id", "registered"));
        var edit = new Command();
        edit.setId("same_id");
        edit.setType(CommandType.WRITE_REQUEST);
        edit.setRequestInfo("edited request");
        assertTrue(protocol.driverCommand.lockedExecuteCommands(Set.of(edit), (String) null, false).isEmpty());
        assertEquals("edited request", protocol.request);
        assertFalse(protocol.read);
    }

    @Test
    void registeredEventFunctionsAreNotRecompiledOnEveryInvocation() throws Exception {
        var registered = command("stateful", "unused");
        registered.setType(CommandType.READ_REQUEST);
        registered.setCmdScript("counter = 0\ndef cmdFunc(value):\n    global counter\n    counter += 1\n    return [('count', str(counter))]\n");
        var protocol = create(new DriverProtocolDummy(), registered);
        var engine = protocol.driverCommand.pythonEngine;
        protocol.driverCommand.executeNonPeriodicCommands(new Value[]{engine.asValue(1)}, 1L, null);
        protocol.driverCommand.executeNonPeriodicCommands(new Value[]{engine.asValue(2)}, 2L, null);
        assertEquals(2, engine.get("counter").asInt());
    }

    @Test
    void tcpHelperPreservesEscapesAndUsesTheMessageSendPath() throws Exception {
        var engine = engine();
        var tcp = new RecordingTcp();
        engine.set("tcp", tcp);
        var message = "quote\" 한글 \\x0D\\x0A";
        engine.set("message", message);
        assertEquals(message, evaluate(engine, "tcp.requestInfo(message)").asText());
        tcp.requestCommand("write", engine.get("result").asString(), 100, false, null, null, null);
        assertEquals(message, tcp.sent);
        var addressed = evaluate(engine, "tcp.requestInfo(message, '127.0.0.1', 5000)");
        assertEquals(message, addressed.get("message").asText());
        assertEquals(5000, addressed.get("port").asInt());
    }

    @Test
    void httpHelpersPreserveStructuredBodiesAndWireEscapes() throws Exception {
        var engine = engine();
        engine.set("http", new DriverProtocolHttpClient());
        engine.set("server", new DriverProtocolHttpServer());
        var object = evaluate(engine, "http.requestInfo('POST', '/a', None, {'text': '한글 \\\"', 'items': [1, True, None]}, None)");
        var body = object.get("body");
        assertEquals("한글 \"", body.get("text").asText());
        assertEquals(json.readTree("[1,true,null]"), body.get("items"));
        assertEquals(5, evaluate(engine, "http.requestInfo('POST', '/a', None, 5, None)").get("body").asInt());
        assertEquals(json.readTree("[\"x\"]"), evaluate(engine, "http.requestInfo('POST', '/a', None, ['x'], None)").get("body"));
        assertEquals("raw text", evaluate(engine, "http.requestInfo('POST', '/a', None, 'raw text', None)").get("body").asText());
        assertEquals(json.readTree("{\"ok\":true}"), evaluate(engine, "server.requestInfo(200, {'ok': True}, 'Content-Type', 'application/json')").get("body"));
        var http = new DriverProtocolHttpClient();
        assertEquals(body, json.readTree(http.bodyBytes(json.convertValue(body, Object.class))));
        assertArrayEquals(new byte[]{65, 13, 10}, http.bodyBytes("A\\x0D\\x0A"));
    }

    @Test
    void httpHelperDispatchesHeaderAndProxyFormsWithoutGraalPyOverloadAmbiguity() throws Exception {
        var engine = engine();
        engine.set("http", new DriverProtocolHttpClient());
        var headers = evaluate(engine, "http.requestInfo('GET', '/a', None, None, {'q':['1']}, 'Accept', 'text/plain', 'Accept', 'application/json')");
        assertEquals(json.readTree("[\"text/plain\",\"application/json\"]"), headers.at("/headers/Accept"));
        assertFalse(headers.has("proxy"));
        var shortProxy = evaluate(engine, "http.requestInfo('GET', '/a', None, None, None, 'localhost', 3128, 'Accept', 'text/plain')");
        assertEquals("localhost", shortProxy.at("/proxy/host").asText());
        assertEquals("3128", shortProxy.at("/proxy/port").asText());
        assertEquals("text/plain", shortProxy.at("/headers/Accept/0").asText());
        var fullProxy = evaluate(engine, "http.requestInfo('GET', '/a', None, None, None, 'HTTP', 'localhost', 3128, 'user', 'pass', 'Accept', 'text/plain')");
        assertEquals("HTTP", fullProxy.at("/proxy/type").asText());
        assertEquals("user", fullProxy.at("/proxy/username").asText());
        var placeholders = evaluate(engine, "http.requestInfo('GET', '/a', None, None, None, None, None, None, None, None, 'Accept', 'text/plain')");
        assertFalse(placeholders.has("proxy"));
        assertEquals("text/plain", placeholders.at("/headers/Accept/0").asText());
        var noHeaders = evaluate(engine, "http.requestInfo('GET', '/a', None, None, None, 'HTTP', 'localhost', 3128, None, None)");
        assertEquals("3128", noHeaders.at("/proxy/port").asText());
    }

    @Test
    void opcuaHelpersHandleSingleReadsAndPreserveWriteValueTypes() throws Exception {
        var engine = engine();
        engine.set("opc", new DriverProtocolOpcuaClient());
        assertEquals(json.readTree("[\"ns=2;s=A\"]"), evaluate(engine, "opc.requestInfo('ns=2;s=A')"));
        assertEquals(json.readTree("[\"ns=2;s=A\",\"ns=2;s=B\"]"), evaluate(engine, "opc.requestInfo('ns=2;s=A', 'ns=2;s=B')"));
        var write = evaluate(engine, "opc.requestInfo({'ns=2;s=A':[1,2], 'ns=2;s=B':True, 'ns=2;s=C':None, 'ns=2;s=D':'a\\\"b'})");
        assertTrue(write.get("ns=2;s=A").isArray());
        assertEquals(2, write.get("ns=2;s=A").get(1).asInt());
        assertTrue(write.get("ns=2;s=B").asBoolean());
        assertTrue(write.get("ns=2;s=C").isNull());
        assertEquals("a\"b", write.get("ns=2;s=D").asText());
    }

    static class RecordingProtocol extends DriverProtocolDummy {
        String request;
        boolean read;

        @Override
        List<Response> requestCommand(String cmdId, String requestInfo, int timeout, boolean isReadCommand,
                                      Value function, Value initialValue, Object nonPeriodicObject) {
            request = requestInfo;
            read = isReadCommand;
            return null;
        }
    }

    static class RecordingTcp extends DriverProtocolTcpClient {
        String sent;

        @Override
        protected void sendString(String message, ReplyTarget target) {
            sent = message;
        }
    }
}
