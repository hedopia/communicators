package com.sds.communicators.driver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sds.communicators.common.struct.Device;
import com.sds.communicators.common.struct.Response;
import com.sds.communicators.common.type.StatusCode;
import com.sds.communicators.driver.support.PythonEngine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ScriptDataConversionTest {
    private static DriverProtocol protocol;
    private static PythonEngine engine;

    /** receives script values the way GraalPy hands them to Java parameters of the data API */
    public static class Capture {
        public Object last;

        public void map(Map<String, Object> value) {
            last = value;
        }

        public void object(Object value, List<String> path) {
            last = value;
        }
    }

    @BeforeAll
    static void createProtocol() throws Exception {
        var device = new Device();
        device.setId("conversion");
        device.setConnectionUrl("dummy://");
        device.setCommands(Set.of());
        protocol = new DriverProtocolDummy().create(null, "", device);
        engine = protocol.driverCommand.pythonEngine;
        engine.exec("import datetime, decimal");
    }

    @AfterAll
    static void closeEngine() {
        engine.close();
    }

    private static Object convert(String expression) {
        engine.exec("value = " + expression);
        return PythonEngine.toJavaObject(engine.get("value"));
    }

    private static String rejected(String expression) {
        return assertThrows(IllegalArgumentException.class, () -> convert(expression)).getMessage();
    }

    private static IllegalArgumentException scriptFailure(String script) {
        var e = assertThrows(PolyglotException.class, () -> engine.exec(script));
        assertTrue(e.isHostException(), e::toString);
        return assertInstanceOf(IllegalArgumentException.class, e.asHostException());
    }

    @Test
    void integralFloatStaysDouble() throws Exception {
        assertEquals(Double.valueOf(25.0), convert("25.0"));
        assertEquals(List.of(1.0, 2.5), convert("[1.0, 2.5]"));
        assertEquals(Double.valueOf(-0.0), convert("-0.0"));
        // HTTP bodies and OPC UA write request-info are serialized from this conversion
        assertEquals("{\"t\":25.0,\"n\":7}", new ObjectMapper().writeValueAsString(convert("{'t': 25.0, 'n': 7}")));
    }

    @Test
    void integersUseTheSmallestJacksonType() {
        assertEquals(Integer.valueOf(7), convert("7"));
        assertEquals(Integer.valueOf(-2147483648), convert("-2**31"));
        assertEquals(Long.valueOf(1L << 40), convert("2**40"));
        assertEquals(Long.valueOf(Long.MIN_VALUE), convert("-2**63"));
        assertEquals(new BigInteger("9223372036854775808"), convert("2**63"));
        assertEquals(new BigInteger("18446744073709551616"), convert("2**64"));
        assertEquals(new BigInteger("-18446744073709551616"), convert("-2**64"));
        assertEquals(Boolean.TRUE, convert("True"));
    }

    @Test
    void nestedContainersArePreservedInOrder() {
        var result = convert("{'b': {'list': [1, 2.5, 'x', None, False], 'tuple': (1, 2)}, 'a': [], 1: 'intKey'}");
        var expected = new LinkedHashMap<String, Object>();
        var inner = new LinkedHashMap<String, Object>();
        inner.put("list", Arrays.asList(1, 2.5, "x", null, false));
        inner.put("tuple", List.of(1, 2));
        expected.put("b", inner);
        expected.put("a", List.of());
        expected.put("1", "intKey");
        assertEquals(expected, result);
        assertEquals(List.of("b", "a", "1"), List.copyOf(((Map<?, ?>) result).keySet()));

        engine.exec("deep = []\nfor _ in range(200):\n    deep = [deep]");
        assertInstanceOf(List.class, PythonEngine.toJavaObject(engine.get("deep")));
    }

    @Test
    void nonJsonValuesAreRejectedWithTheirType() {
        assertEquals("unsupported JSON value: datetime", rejected("datetime.datetime(2026, 1, 1)"));
        assertEquals("unsupported JSON value: Decimal", rejected("decimal.Decimal('1.5')"));
        assertEquals("unsupported JSON value: set", rejected("{1, 2}"));
        assertEquals("unsupported JSON value: datetime", rejected("{'t': [datetime.datetime(2026, 1, 1)]}"));
        assertTrue(rejected("float('nan')").contains("NaN"));
        assertTrue(rejected("[float('inf')]").contains("Infinity"));
        assertTrue(rejected("{None: 1}").contains("unsupported JSON key"));
        assertTrue(rejected("{(1, 2): 1}").contains("unsupported JSON key"));
    }

    @Test
    void cyclicStructuresAreRejectedWithoutStackOverflow() {
        engine.exec("cyclicList = []\ncyclicList.append(cyclicList)\ncyclicDict = {}\ncyclicDict['self'] = cyclicDict");
        for (var name : List.of("cyclicList", "cyclicDict")) {
            var message = assertThrows(IllegalArgumentException.class, () -> PythonEngine.toJavaObject(engine.get(name))).getMessage();
            assertTrue(message.contains("nested deeper than"), message);
        }
    }

    @Test
    void hostNumbersKeepTheirKind() {
        assertEquals(Double.valueOf(25.0), PythonEngine.toJavaObject(engine.asValue(25.0)));
        assertEquals(Double.valueOf(1.5), PythonEngine.toJavaObject(engine.asValue(1.5f)));
        assertEquals(Integer.valueOf(7), PythonEngine.toJavaObject(engine.asValue(7)));
        assertEquals(Boolean.FALSE, PythonEngine.toJavaObject(engine.asValue(false)));
        assertEquals(new BigInteger("18446744073709551616"), PythonEngine.toJavaObject(engine.asValue(new BigInteger("18446744073709551616"))));
    }

    @Test
    void hostEnumsAndBeansFromTheProtocolApiMapToJson() {
        assertEquals("CONNECTED", PythonEngine.toJavaObject(engine.asValue(StatusCode.CONNECTED)));
        var response = PythonEngine.toJavaObject(engine.asValue(new Response("dev", "tag", "1.5", 1700000000000L)));
        assertEquals(Map.of("deviceId", "dev", "tagId", "tag", "value", "1.5", "receivedTime", 1700000000000L), response);
        assertTrue(rejected("datetime.datetime(2024, 1, 1)").contains("datetime"));
    }

    @Test
    void protocolHelperCopiesScriptProxiesIntoPlainJavaObjects() {
        var capture = new Capture();
        engine.set("capture", capture);
        engine.exec("capture.map({'t': 25.0, 'n': 7, 'l': [1.0, 2.5], 'big': 2**64, 'nested': {'k': [1]}})");
        assertFalse(capture.last instanceof LinkedHashMap, "expected a GraalPy proxy, got " + capture.last.getClass());

        var expected = new LinkedHashMap<String, Object>();
        expected.put("t", 25.0);
        expected.put("n", 7);
        expected.put("l", List.of(1.0, 2.5));
        expected.put("big", new BigInteger("18446744073709551616"));
        expected.put("nested", Map.of("k", List.of(1)));
        var converted = protocol.toJsonData(capture.last, "setData value");
        assertEquals(expected, converted);
        assertInstanceOf(Double.class, ((Map<?, ?>) converted).get("t"));
        assertInstanceOf(Integer.class, ((Map<?, ?>) converted).get("n"));

        // a Python int >= 2**63 given to an Object parameter arrives as a raw Value
        engine.exec("capture.object(2**64, ['x'])");
        assertInstanceOf(Value.class, capture.last);
        assertEquals(new BigInteger("18446744073709551616"), protocol.toJsonData(capture.last, "setData value"));

        engine.exec("capture.object(datetime.datetime(2026, 1, 1), ['x'])");
        var message = assertThrows(IllegalArgumentException.class, () -> protocol.toJsonData(capture.last, "setData value")).getMessage();
        assertEquals("setData value must be JSON-compatible: unsupported JSON value: datetime", message);
    }

    @Test
    void protocolHelperKeepsJavaCallerValues() {
        // DriverProtocolModbusServer.write stores Integer/Boolean and read checks "instanceof Integer"
        var map = new HashMap<String, Object>();
        map.put("40001", 5);
        map.put("1", true);
        map.put("ratio", 25.0);
        var converted = (Map<?, ?>) protocol.toJsonData(map, "setData value");
        assertEquals(map, converted);
        assertInstanceOf(Integer.class, converted.get("40001"));
        assertInstanceOf(Boolean.class, converted.get("1"));
        assertInstanceOf(Double.class, converted.get("ratio"));
        assertEquals(List.of("3"), protocol.toJsonData(List.of("3"), "setData path"));
        assertNull(protocol.toJsonData(null, "setData value"));
    }

    @Test
    void scriptDataCallsFailFastOnNonJsonValues() {
        // the checks run before the cluster is touched (driverService is null in this test)
        assertEquals("setData value must be JSON-compatible: unsupported JSON value: datetime",
                scriptFailure("protocol.setData({'t': datetime.datetime(2026, 1, 1)})").getMessage());
        assertEquals("setData value must be JSON-compatible: unsupported JSON value: Decimal",
                scriptFailure("protocol.setData(decimal.Decimal('1'), ['a'])").getMessage());
        assertEquals("setData value must be JSON-compatible: unsupported JSON value: set",
                scriptFailure("protocol.setData({1, 2}, ['a'])").getMessage());
        assertTrue(scriptFailure("protocol.setData({'k': float('nan')})").getMessage().startsWith("setData value must be JSON-compatible"));
        assertTrue(scriptFailure("protocol.setData(1, [{'a': 1}])").getMessage().startsWith("data path key must be a string or number"));
        assertEquals("deleteData path must be JSON-compatible: unsupported JSON value: datetime",
                scriptFailure("protocol.deleteData([datetime.datetime(2026, 1, 1)])").getMessage());
        assertTrue(scriptFailure("protocol.deleteData([['a'], 'b'])").getMessage().startsWith("data path key must be a string or number"));
        assertTrue(scriptFailure("protocol.deleteData([['a'], ['b', None]])").getMessage().startsWith("data path key must be a string or number"));
    }
}
