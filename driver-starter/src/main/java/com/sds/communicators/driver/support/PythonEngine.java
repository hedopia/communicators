package com.sds.communicators.driver.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Value;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.function.BiConsumer;

/**
 * GraalPy(Python 3) script engine wrapper.
 * one context per device (DriverCommand), GraalPy serializes multi-threaded access with its GIL
 */
@Slf4j
public class PythonEngine {
    private static final Engine SHARED_ENGINE = Engine.newBuilder()
            .option("engine.WarnInterpreterOnly", "false")
            .build();
    private static final int MAX_JSON_DEPTH = 256;
    private static final ObjectMapper HOST_MAPPER = new ObjectMapper();

    private final Context context;
    private final Value bindings;
    private final Value listConstructor;
    private final Value dictConstructor;
    private final Value jsonLoads;

    public PythonEngine() {
        context = Context.newBuilder("python")
                .engine(SHARED_ENGINE)
                .allowAllAccess(true)
                .build();
        bindings = context.getBindings("python");
        listConstructor = context.eval("python", "list");
        dictConstructor = context.eval("python", "dict");
        context.eval("python", "from json import loads as __json_loads__");
        jsonLoads = bindings.getMember("__json_loads__");
    }

    public void exec(String script) {
        context.eval("python", script);
    }

    public Value get(String name) {
        return bindings.getMember(name);
    }

    public void set(String name, Object value) {
        bindings.putMember(name, value);
    }

    public void remove(String name) {
        bindings.removeMember(name);
    }

    /**
     * Convert a JSON-compatible Python (or host) value to plain Java objects:
     * <ul>
     *   <li>None -> null, str -> String, bool -> Boolean</li>
     *   <li>float (and host Double/Float) -> Double, even without a fractional part (25.0 stays 25.0);
     *       NaN and +/-Infinity are rejected</li>
     *   <li>int -> Integer if it fits in 32 bits, else Long if it fits in 64 bits, else BigInteger
     *       (the types Jackson produces when the JSON is parsed back)</li>
     *   <li>dict/mapping -> LinkedHashMap&lt;String, Object&gt; (str/int/float/bool keys, as their string form)</li>
     *   <li>list/tuple/array -> ArrayList</li>
     * </ul>
     * Host beans and enums returned by the protocol API are mapped by Jackson (enum -> name).
     * Anything else (datetime, Decimal, set, objects, ...) or nesting deeper than {@value #MAX_JSON_DEPTH}
     * levels (e.g. a cyclic reference) throws IllegalArgumentException.
     */
    public static Object toJavaObject(Value value) {
        return toJavaObject(value, 0);
    }

    private static Object toJavaObject(Value value, int depth) {
        if (depth > MAX_JSON_DEPTH)
            throw new IllegalArgumentException("unsupported JSON value: nested deeper than " + MAX_JSON_DEPTH + " levels (cyclic reference?)");
        if (isNone(value)) return null;
        if (value.isString()) return value.asString();
        if (value.isBoolean()) return value.asBoolean();
        if (value.isNumber()) return toJavaNumber(value);
        if (value.hasHashEntries()) {
            var result = new LinkedHashMap<String, Object>();
            forEachHashEntry(value, (key, item) -> result.put(toJsonKey(key), toJavaObject(item, depth + 1)));
            return result;
        }
        if (value.hasArrayElements()) {
            var result = new ArrayList<Object>();
            for (long i = 0; i < value.getArraySize(); i++)
                result.add(toJavaObject(value.getArrayElement(i), depth + 1));
            return result;
        }
        if (value.isHostObject())
            return hostToJavaObject(value);
        throw new IllegalArgumentException("unsupported JSON value: " + typeName(value));
    }

    /** host beans and enums returned by the protocol API (e.g. Response, StatusCode) map to their JSON form */
    private static Object hostToJavaObject(Value value) {
        var host = value.asHostObject();
        if (host instanceof Enum<?> e) return e.name();
        try {
            return HOST_MAPPER.convertValue(host, Object.class);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unsupported JSON value: " + typeName(value), e);
        }
    }

    private static Object toJavaNumber(Value value) {
        // GraalPy reports an integral float (25.0) as fitsInInt/fitsInLong, so the float type is checked first
        if (isFloat(value)) return toJsonDouble(value);
        if (value.fitsInInt()) return value.asInt();
        if (value.fitsInLong()) return value.asLong();
        if (value.fitsInBigInteger()) return value.asBigInteger();
        // GraalPy does not report fitsInBigInteger for a Python int >= 2**63
        if (typeIs(value, "int")) return new BigInteger(value.toString());
        if (value.fitsInDouble()) return toJsonDouble(value);
        throw new IllegalArgumentException("unsupported JSON value: " + typeName(value));
    }

    private static boolean isFloat(Value value) {
        var meta = value.getMetaObject();
        if (meta == null) return false;
        var name = meta.getMetaQualifiedName();
        return "float".equals(name) || "java.lang.Double".equals(name) || "java.lang.Float".equals(name);
    }

    private static Double toJsonDouble(Value value) {
        double d = value.asDouble();
        if (!Double.isFinite(d))
            throw new IllegalArgumentException("unsupported JSON value: " + typeName(value) + " " + d);
        return d;
    }

    private static String toJsonKey(Value key) {
        if (key.isString()) return key.asString();
        if (key.isNull() || !(key.isNumber() || key.isBoolean()))
            throw new IllegalArgumentException("unsupported JSON key: " + typeName(key));
        return key.toString();
    }

    public Value asValue(Object obj) {
        return context.asValue(obj);
    }

    public Value toPyList(Object arrayOrList) {
        // cast to Object prevents object arrays from being spread as varargs
        return listConstructor.execute(arrayOrList);
    }

    public Value newList() {
        return listConstructor.execute();
    }

    public Value newDict() {
        return dictConstructor.execute();
    }

    /** json.loads(s), fallback to str on parsing failure (null returns null) */
    public Value stringToPyObject(String s) {
        if (s == null) return null;
        try {
            return jsonLoads.execute(s);
        } catch (Exception e) {
            return asValue(s);
        }
    }

    public void close() {
        try {
            context.close(true);
        } catch (Exception e) {
            log.trace("python context close failed", e);
        }
    }

    public static int getArgumentCount(Value function) {
        return function.getMember("__code__").getMember("co_argcount").asInt();
    }

    public static boolean isFunction(Value v) {
        return v != null && !v.isNull() && v.canExecute();
    }

    public static boolean isString(Value v) {
        return v != null && v.isString();
    }

    public static boolean isNone(Value v) {
        return v == null || v.isNull();
    }

    /** python bool is an int subclass */
    public static boolean isInteger(Value v) {
        return v != null && !v.isNull() && (v.isBoolean() || (v.isNumber() && v.fitsInInt()));
    }

    public static int asInt(Value v) {
        return v.isBoolean() ? (v.asBoolean() ? 1 : 0) : v.asInt();
    }

    public static boolean isList(Value v) {
        return typeIs(v, "list");
    }

    public static boolean isTuple(Value v) {
        return typeIs(v, "tuple");
    }

    public static boolean isDict(Value v) {
        return v != null && !v.isNull() && v.hasHashEntries();
    }

    public static String typeName(Value v) {
        if (v == null || v.isNull()) return "NoneType";
        var meta = v.getMetaObject();
        return meta != null ? meta.getMetaSimpleName() : v.getClass().getSimpleName();
    }

    public static String asString(Value v) {
        if (v == null || v.isNull()) return null;
        return v.isString() ? v.asString() : v.toString();
    }

    public static void forEachHashEntry(Value dict, BiConsumer<Value, Value> consumer) {
        var it = dict.getHashEntriesIterator();
        while (it.hasIteratorNextElement()) {
            var entry = it.getIteratorNextElement();
            consumer.accept(entry.getArrayElement(0), entry.getArrayElement(1));
        }
    }

    private static boolean typeIs(Value v, String name) {
        if (v == null || v.isNull()) return false;
        var meta = v.getMetaObject();
        return meta != null && name.equals(meta.getMetaSimpleName());
    }
}
