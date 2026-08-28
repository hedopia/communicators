package com.sds.communicators.driver;

import com.sds.communicators.common.UtilFunc;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UByte;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.ULong;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
abstract class DriverProtocolOpcua extends DriverProtocol {
    protected String host;
    protected int port;
    protected String path = "";

    @Override
    void initialize(String connectionInfo, Map<String, String> option) throws Exception {
        executeProtocolScript();

        var pathIndex = connectionInfo.indexOf('/');
        String hostPortInfo;
        if (pathIndex >= 0) {
            hostPortInfo = connectionInfo.substring(0, pathIndex);
            path = connectionInfo.substring(pathIndex);
        } else {
            hostPortInfo = connectionInfo;
        }
        var hostPort = UtilFunc.extractIpPort(hostPortInfo);
        host = hostPort[0];
        port = Integer.parseInt(hostPort[1]);
    }

    /** convert OPC UA variant value to plain java object for scripts */
    protected Object variantToJava(Variant variant) {
        return uaToJava(variant.getValue());
    }

    private Object uaToJava(Object value) {
        if (value == null) return null;
        if (value instanceof UByte) return ((UByte) value).intValue();
        if (value instanceof UShort) return ((UShort) value).intValue();
        if (value instanceof UInteger) return ((UInteger) value).longValue();
        if (value instanceof ULong) return ((ULong) value).toBigInteger();
        if (value instanceof DateTime) return ((DateTime) value).getJavaTime();
        if (value instanceof LocalizedText) return ((LocalizedText) value).getText();
        if (value instanceof QualifiedName) return ((QualifiedName) value).getName();
        if (value instanceof StatusCode) return ((StatusCode) value).getValue();
        if (value instanceof Object[]) {
            var list = new ArrayList<>();
            for (var element : (Object[]) value)
                list.add(uaToJava(element));
            return list;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof String || value instanceof Character)
            return value;
        return value.toString();
    }

    /** convert json-native java object (from jackson) to OPC UA variant, with optional type coercion */
    protected Variant javaToVariant(Object value, String type) throws Exception {
        if (value == null) return Variant.NULL_VALUE;
        if (type != null) {
            switch (type) {
                case "Boolean": return new Variant(toBoolean(value));
                case "SByte": return new Variant(((Number) value).byteValue());
                case "Byte": return new Variant(UByte.valueOf(((Number) value).shortValue()));
                case "Int16": return new Variant(((Number) value).shortValue());
                case "UInt16": return new Variant(UShort.valueOf(((Number) value).intValue()));
                case "Int32": return new Variant(((Number) value).intValue());
                case "UInt32": return new Variant(UInteger.valueOf(((Number) value).longValue()));
                case "Int64": return new Variant(((Number) value).longValue());
                case "UInt64": return new Variant(ULong.valueOf(((Number) value).longValue()));
                case "Float": return new Variant(((Number) value).floatValue());
                case "Double": return new Variant(((Number) value).doubleValue());
                case "String": return new Variant(value.toString());
                case "DateTime": return new Variant(new DateTime(new java.util.Date(((Number) value).longValue())));
                default: throw new Exception("unsupported opc-ua type: " + type);
            }
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long ||
                value instanceof Double || value instanceof Float || value instanceof String)
            return new Variant(value);
        if (value instanceof Number)
            return new Variant(((Number) value).doubleValue());
        if (value instanceof List<?> list) {
            var array = new Object[list.size()];
            for (int i = 0; i < list.size(); i++) {
                var variant = javaToVariant(list.get(i), null);
                array[i] = variant.getValue();
            }
            return new Variant(array);
        }
        throw new Exception("unsupported value type for opc-ua write: " + value.getClass());
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        return Boolean.parseBoolean(value.toString());
    }
}
