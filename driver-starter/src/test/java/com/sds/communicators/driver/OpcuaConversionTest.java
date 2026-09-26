package com.sds.communicators.driver;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import org.eclipse.milo.opcua.stack.core.OpcUaDataType;
import org.eclipse.milo.opcua.stack.core.encoding.DefaultEncodingContext;
import org.eclipse.milo.opcua.stack.core.encoding.binary.OpcUaBinaryDecoder;
import org.eclipse.milo.opcua.stack.core.encoding.binary.OpcUaBinaryEncoder;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UByte;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * javaToVariant must produce values Milo can encode: an Object[] value has no OPC UA data type
 * and fails in OpcUaBinaryEncoder.encodeVariant
 */
class OpcuaConversionTest {
    private final ObjectMapper json = new ObjectMapper();
    private final DriverProtocolOpcua protocol = new DriverProtocolOpcuaClient();

    private Variant convert(String valueJson, String type) throws Exception {
        return protocol.javaToVariant(json.readValue(valueJson, Object.class), type);
    }

    /** binary encode + decode with Milo, returns the decoded value converted back to java */
    private Object roundTrip(Variant variant) {
        var buffer = Unpooled.buffer();
        try {
            new OpcUaBinaryEncoder(DefaultEncodingContext.INSTANCE).setBuffer(buffer).encodeVariant(variant);
            return protocol.variantToJava(new OpcUaBinaryDecoder(DefaultEncodingContext.INSTANCE).setBuffer(buffer).decodeVariant());
        } finally {
            buffer.release();
        }
    }

    private void assertTypedArray(Variant variant, OpcUaDataType dataType, Object[] expected) {
        assertEquals(Optional.of(dataType), variant.getDataType());
        assertEquals(expected.getClass(), variant.getValue().getClass());
        assertArrayEquals(expected, (Object[]) variant.getValue());
    }

    @Test
    void homogeneousListsBecomeTypedArrays() throws Exception {
        var ints = convert("[1, 2]", null);
        assertTypedArray(ints, OpcUaDataType.Int32, new Integer[]{1, 2});
        assertEquals(List.of(1, 2), roundTrip(ints));

        var doubles = convert("[1.0, 2.0]", null);
        assertTypedArray(doubles, OpcUaDataType.Double, new Double[]{1.0, 2.0});
        assertEquals(List.of(1.0, 2.0), roundTrip(doubles));

        var strings = convert("[\"a\", \"b\"]", null);
        assertTypedArray(strings, OpcUaDataType.String, new String[]{"a", "b"});
        assertEquals(List.of("a", "b"), roundTrip(strings));

        var booleans = convert("[true, false]", null);
        assertTypedArray(booleans, OpcUaDataType.Boolean, new Boolean[]{true, false});
        assertEquals(List.of(true, false), roundTrip(booleans));
    }

    @Test
    void mixedNumbersAreWidened() throws Exception {
        var intDouble = convert("[1, 2.5]", null);
        assertTypedArray(intDouble, OpcUaDataType.Double, new Double[]{1.0, 2.5});
        assertEquals(List.of(1.0, 2.5), roundTrip(intDouble));

        var intLong = convert("[1, 3000000000]", null);
        assertTypedArray(intLong, OpcUaDataType.Int64, new Long[]{1L, 3000000000L});
        assertEquals(List.of(1L, 3000000000L), roundTrip(intLong));

        var longDouble = convert("[3000000000, 0.5]", null);
        assertTypedArray(longDouble, OpcUaDataType.Double, new Double[]{3.0e9, 0.5});
        assertEquals(List.of(3.0e9, 0.5), roundTrip(longDouble));
    }

    @Test
    void heterogeneousListsBecomeVariantArrays() throws Exception {
        var mixed = convert("[\"a\", 1]", null);
        assertEquals(Optional.of(OpcUaDataType.Variant), mixed.getDataType());
        assertInstanceOf(Variant[].class, mixed.getValue());
        assertEquals(List.of("a", 1), roundTrip(mixed));

        var withNull = convert("[1, null]", null);
        assertInstanceOf(Variant[].class, withNull.getValue());
        assertEquals(java.util.Arrays.asList(1, null), roundTrip(withNull));

        var nested = convert("[[1], [2, 3]]", null);
        assertInstanceOf(Variant[].class, nested.getValue());
        assertEquals(List.of(List.of(1), List.of(2, 3)), roundTrip(nested));

        var empty = convert("[]", null);
        assertEquals(Optional.of(OpcUaDataType.Variant), empty.getDataType());
        assertEquals(0, ((Variant[]) empty.getValue()).length);
        assertEquals(List.of(), roundTrip(empty));
    }

    @Test
    void explicitTypeConvertsEveryListElement() throws Exception {
        var int16 = convert("[1, 2.7]", "Int16");
        assertTypedArray(int16, OpcUaDataType.Int16, new Short[]{1, 2});
        assertEquals(List.of((short) 1, (short) 2), roundTrip(int16));

        var doubles = convert("[1, 2]", "Double");
        assertTypedArray(doubles, OpcUaDataType.Double, new Double[]{1.0, 2.0});
        assertEquals(List.of(1.0, 2.0), roundTrip(doubles));

        var bytes = convert("[1, 255]", "Byte");
        assertTypedArray(bytes, OpcUaDataType.Byte, new UByte[]{UByte.valueOf(1), UByte.valueOf(255)});
        assertEquals(List.of(1, 255), roundTrip(bytes));

        var uint32 = convert("[4000000000]", "UInt32");
        assertTypedArray(uint32, OpcUaDataType.UInt32, new UInteger[]{UInteger.valueOf(4000000000L)});
        assertEquals(List.of(4000000000L), roundTrip(uint32));

        var emptyTyped = convert("[]", "Int16");
        assertTypedArray(emptyTyped, OpcUaDataType.Int16, new Short[0]);
        assertEquals(List.of(), roundTrip(emptyTyped));

        var nestedTyped = convert("[[1], [2]]", "Int16");
        assertInstanceOf(Variant[].class, nestedTyped.getValue());
        assertEquals(List.of(List.of((short) 1), List.of((short) 2)), roundTrip(nestedTyped));

        assertEquals("unsupported opc-ua type: Int8",
                assertThrows(Exception.class, () -> convert("[]", "Int8")).getMessage());
    }

    @Test
    void scalarsAreUnchangedAndStructuresRejected() throws Exception {
        assertEquals(Optional.of(OpcUaDataType.Int32), convert("1", null).getDataType());
        assertEquals(1, roundTrip(convert("1", null)));
        assertEquals("x", roundTrip(convert("\"x\"", null)));
        assertEquals((short) 3, roundTrip(convert("3", "Int16")));
        assertTrue(convert("null", null).isNull());

        assertTrue(assertThrows(Exception.class, () -> convert("{\"a\": 1}", null))
                .getMessage().contains("structure is not supported"));
        assertTrue(assertThrows(Exception.class, () -> convert("[{\"a\": 1}]", null))
                .getMessage().contains("structure is not supported"));
    }
}
