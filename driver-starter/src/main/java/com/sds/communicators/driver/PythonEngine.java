package com.sds.communicators.driver;

import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Value;

import java.util.function.BiConsumer;

/**
 * GraalPy(Python 3) script engine wrapper.
 * one context per device (DriverCommand), GraalPy serializes multi-threaded access with its GIL
 */
@Slf4j
class PythonEngine {
    private static final Engine SHARED_ENGINE = Engine.newBuilder()
            .option("engine.WarnInterpreterOnly", "false")
            .build();

    private final Context context;
    private final Value bindings;
    private final Value listConstructor;
    private final Value dictConstructor;
    private final Value jsonLoads;

    PythonEngine() {
        context = Context.newBuilder("python")
                .engine(SHARED_ENGINE)
                .allowAllAccess(true)
                .allowExperimentalOptions(true)
                .option("python.EmulateJython", "true")
                .build();
        bindings = context.getBindings("python");
        listConstructor = context.eval("python", "list");
        dictConstructor = context.eval("python", "dict");
        context.eval("python", "from json import loads as __json_loads__");
        jsonLoads = bindings.getMember("__json_loads__");
    }

    void exec(String script) {
        context.eval("python", script);
    }

    Value get(String name) {
        return bindings.getMember(name);
    }

    void set(String name, Object value) {
        bindings.putMember(name, value);
    }

    Value asValue(Object obj) {
        return context.asValue(obj);
    }

    /** create python list, copying elements from a java array or list */
    Value toPyList(Object arrayOrList) {
        // cast to Object prevents object arrays from being spread as varargs
        return listConstructor.execute((Object) arrayOrList);
    }

    Value newList() {
        return listConstructor.execute();
    }

    Value newDict() {
        return dictConstructor.execute();
    }

    /** json.loads(s), fallback to str on parsing failure (null returns null) */
    Value stringToPyObject(String s) {
        if (s == null) return null;
        try {
            return jsonLoads.execute(s);
        } catch (Exception e) {
            return asValue(s);
        }
    }

    void close() {
        try {
            context.close(true);
        } catch (Exception e) {
            log.trace("python context close failed", e);
        }
    }

    static int getArgumentCount(Value function) {
        return function.getMember("__code__").getMember("co_argcount").asInt();
    }

    static boolean isFunction(Value v) {
        return v != null && !v.isNull() && v.canExecute();
    }

    static boolean isString(Value v) {
        return v != null && v.isString();
    }

    static boolean isNone(Value v) {
        return v == null || v.isNull();
    }

    /** matches Jython PyInteger check (python bool is an int subclass) */
    static boolean isInteger(Value v) {
        return v != null && !v.isNull() && (v.isBoolean() || (v.isNumber() && v.fitsInInt()));
    }

    static int asInt(Value v) {
        return v.isBoolean() ? (v.asBoolean() ? 1 : 0) : v.asInt();
    }

    static boolean isList(Value v) {
        return typeIs(v, "list");
    }

    static boolean isTuple(Value v) {
        return typeIs(v, "tuple");
    }

    static boolean isDict(Value v) {
        return v != null && !v.isNull() && v.hasHashEntries();
    }

    static String typeName(Value v) {
        if (v == null || v.isNull()) return "NoneType";
        var meta = v.getMetaObject();
        return meta != null ? meta.getMetaSimpleName() : v.getClass().getSimpleName();
    }

    static String asString(Value v) {
        if (v == null || v.isNull()) return null;
        return v.isString() ? v.asString() : v.toString();
    }

    static void forEachHashEntry(Value dict, BiConsumer<Value, Value> consumer) {
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
