package com.sds.communicators.driver;

import com.google.common.base.Strings;
import com.sds.communicators.common.struct.Command;
import com.sds.communicators.common.struct.Response;
import com.sds.communicators.common.type.CommandType;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Value;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Slf4j
class DriverCommand {
    final PythonEngine pythonEngine = new PythonEngine();
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Map<String, CommandFunctions> functionMap = new HashMap<>();
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final Map<Integer, Set<Command>> periodGroupMap = new HashMap<>();
    private final DriverProtocol protocol;

    DriverCommand(String defaultScript, DriverProtocol protocol) throws Exception {
        pythonEngine.set("log", LoggerFactory.getLogger(ScriptLogger.class));
        pythonEngine.exec("from com.sds.communicators.common import UtilFunc");
        pythonEngine.exec("import java");
        pythonEngine.exec(defaultScript);

        this.protocol = protocol;
        pythonEngine.set("protocol", protocol);
        for (Command command : protocol.device.getCommands()) {
            log.trace("[{}] cmdId={}, initialize command script", protocol.deviceId, command.getId());
            functionMap.put(command.getId(), compileCommandScript(command));
        }
        protocol.device.getCommands().stream()
                .filter(cmd -> cmd.getType() == CommandType.READ_REQUEST || cmd.getType() == CommandType.WRITE_REQUEST || cmd.getType() == CommandType.REQUEST)
                .filter(cmd -> cmd.getPeriodGroup() >= 0) // periodic group
                .forEach(cmd -> {
                    int period = cmd.getPeriodGroup();
                    if (period < Command.MINIMUM_PERIOD_GROUP) period = Command.MINIMUM_PERIOD_GROUP;
                    periodGroupMap.compute(period, (k, v) -> v == null ? new HashSet<>() : v).add(cmd);
                });
    }

    void dispose() {
        disposables.clear();
    }

    Value stringToPyObject(String s) {
        return pythonEngine.stringToPyObject(s);
    }

    private Value getFunction(String name) throws Exception {
        var function = pythonEngine.get(name);
        if (function == null || function.isNull())
            return null;
        if (!PythonEngine.isFunction(function))
            throw new Exception("\"" + name + "\" is not a function");
        return function;
    }

    private CommandFunctions compileCommandScript(Command command) throws Exception {
        if (!command.getId().matches("^[a-zA-Z0-9_]+$"))
            throw new Exception("cmdId=" + command.getId() + ", invalid command-id");

        CommandFunctions ret;
        try {
            if (Strings.isNullOrEmpty(command.getCmdScript())) {
                ret = new CommandFunctions(command, null, null, null, null);
            } else {
                String script = command.getCmdScript();
                script = script.replaceFirst("def[ \t]+cmdFunc[ \t]*\\(", "def cmdFunc_" + command.getId() + "(");
                script = script.replaceFirst("def[ \t]+requestInfo[ \t]*\\(", "def requestInfo_" + command.getId() + "(");
                script = script.replaceFirst("def[ \t]+delay[ \t]*\\(", "def delay_" + command.getId() + "(");
                script = script.replaceFirst("def[ \t]+control[ \t]*\\(", "def control_" + command.getId() + "(");
                pythonEngine.exec(script);
                var cmd = getFunction("cmdFunc_" + command.getId());
                var req = getFunction("requestInfo_" + command.getId());
                var delay = getFunction("delay_" + command.getId());
                var control = getFunction("control_" + command.getId());

                ret = new CommandFunctions(command, cmd, req, delay, control);
            }
        } catch (Exception e) {
            throw new Exception("cmdId=" + command.getId() + ", compile failed", e);
        }

        // requestInfo not defined, requestInfo is empty, read(periodic) or write request
        if (ret.requestInfoFunction == null && Strings.isNullOrEmpty(command.getRequestInfo()) &&
                (isWriteRequest(command.getType()) || (isReadRequest(command.getType()) && command.getPeriodGroup() >= 0)))
            throw new Exception("cmdId=" + command.getId() + ", request-info is not defined");

        if ((isReadRequest(command.getType()) || isRequest(command.getType())) && ret.commandFunction == null)
            throw new Exception("cmdId=" + command.getId() + ", " + command.getType() + " has no \"cmdFunc\"");

        if (ret.controlFunction != null) {
            var argCnt = PythonEngine.getArgumentCount(ret.controlFunction);
            if (argCnt != 2 && argCnt != 3)
                throw new Exception("cmdId=" + command.getId() + ", control arguments count must be 2 or 3 >> control(commandList, idx, exception), arguments count: " + argCnt);
        }

        return ret;
    }

    void startingCommands() {
        var startingCmd = protocol.device.getCommands().stream()
                .filter(cmd -> cmd.getType() == CommandType.STARTING_READ_REQUEST || cmd.getType() == CommandType.STARTING_WRITE_REQUEST || cmd.getType() == CommandType.STARTING_REQUEST)
                .collect(Collectors.toSet());

        protocol.isConnectionLostOccur = false;
        if (!startingCmd.isEmpty()) {
            log.debug("[{}] execute starting command", protocol.deviceId);
            try {
                lock.lockInterruptibly();
                try {
                    executeCommands(startingCmd, null, null, null);
                } finally {
                    lock.unlock();
                }
            } catch (Exception e) {
                log.error("[{}] error on executing starting request commands", protocol.deviceId, e);
                protocol.setConnectionLost();
            }
        }
        if (!protocol.isConnectionLostOccur) {
            try {
                lock.lockInterruptibly();
                try {
                    for (var entry : periodGroupMap.entrySet())
                        disposables.add(
                                Flowable
                                        .interval(0, entry.getKey(), TimeUnit.MILLISECONDS, Schedulers.io())
                                        .onBackpressureLatest()
                                        .observeOn(Schedulers.io(), false, 1)
                                        .subscribe(it -> {
                                            try {
                                                lock.lockInterruptibly();
                                                try {
                                                    executeCommands(entry.getValue(), null, null, null);
                                                } finally {
                                                    lock.unlock();
                                                }
                                            } catch (InterruptedException e) {
                                                log.trace("[{}] executing periodic commands interrupted", protocol.deviceId, e);
                                            } catch (Exception e) {
                                                log.error("[{}] error on executing periodic commands", protocol.deviceId, e);
                                            }
                                        }));
                } finally {
                    lock.unlock();
                }
            } catch (InterruptedException ignored) {}
        }
    }

    void stoppingCommands() {
        var stoppingCmd = protocol.device.getCommands().stream()
                .filter(cmd -> cmd.getType() == CommandType.STOPPING_READ_REQUEST || cmd.getType() == CommandType.STOPPING_WRITE_REQUEST || cmd.getType() == CommandType.STOPPING_REQUEST)
                .collect(Collectors.toSet());
        if (!stoppingCmd.isEmpty()) {
            log.debug("[{}] execute stopping command", protocol.deviceId);
            try {
                lockedExecuteCommands(stoppingCmd, (Value) null, true);
            } catch (Exception e) {
                log.error("[{}] error on executing stopping request commands", protocol.deviceId, e);
            }
        }
    }

    void executeNonPeriodicCommands(Value[] received, Long receivedTime, Object nonPeriodicObject) throws Exception {
        try {
            lock.lockInterruptibly();
            try {
                var nonPeriodicCmd = protocol.device.getCommands().stream()
                        .filter(cmd -> cmd.getPeriodGroup() < 0) // non-periodic group
                        .collect(Collectors.toSet());
                executeCommands(nonPeriodicCmd, received, receivedTime, nonPeriodicObject);
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            log.trace("[{}] execute non-periodic commands interrupted", protocol.deviceId, e);
        }
    }

    void executeNonPeriodicCommands(List<String> commandIdList, Value[] received, Long receivedTime, Object nonPeriodicObject) throws Exception {
        try {
            lock.lockInterruptibly();
            try {
                List<CommandFunctions> functionList = new ArrayList<>();
                for (var cmdId : commandIdList) {
                    if (functionMap.containsKey(cmdId))
                        functionList.add(functionMap.get(cmdId));
                    else
                        throw new Exception("execute non-periodic commands failed, (cmdId: " + cmdId + ") is not registered command");
                }

                executeCommands(functionList, true, received, receivedTime, null, nonPeriodicObject);
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            log.trace("[{}] execute non-periodic commands({}) interrupted", protocol.deviceId, commandIdList, e);
        }
    }

    List<Response> lockedExecuteCommands(List<String> commandIdList, String initialValue, boolean isResponseOutput) throws Exception {
        return lockedExecuteCommands(commandIdList, stringToPyObject(initialValue), isResponseOutput);
    }

    List<Response> lockedExecuteCommands(List<String> commandIdList, Value initialValue, boolean isResponseOutput) throws Exception {
        lock.lockInterruptibly();
        try {
            List<CommandFunctions> functionList = new ArrayList<>();
            for (var cmdId : commandIdList) {
                if (functionMap.containsKey(cmdId))
                    functionList.add(functionMap.get(cmdId));
                else
                    throw new Exception("execute commands failed, (cmdId: " + cmdId + ") is not registered command");
            }
            return executeCommands(functionList, isResponseOutput, null, null, initialValue, null);
        } finally {
            lock.unlock();
        }
    }

    List<Response> lockedExecuteCommands(Set<Command> commands, String initialValue, boolean isResponseOutput) throws Exception {
        return lockedExecuteCommands(commands, stringToPyObject(initialValue), isResponseOutput);
    }

    List<Response> lockedExecuteCommands(Set<Command> commands, Value initialValue, boolean isResponseOutput) throws Exception {
        lock.lockInterruptibly();
        try {
            return executeCommands(commands, isResponseOutput, null, null, initialValue, null);
        } finally {
            lock.unlock();
        }
    }

    private void executeCommands(Set<Command> commands, Value[] received, Long receivedTime, Object nonPeriodicObject) throws Exception {
        executeCommands(commands, true, received, receivedTime, null, nonPeriodicObject);
    }

    private List<Response> executeCommands(Set<Command> commands, boolean isResponseOutput, Value[] received, Long receivedTime, Value initialValue, Object nonPeriodicObject) throws Exception {
        List<CommandFunctions> functionList = new ArrayList<>();
        for (var command : commands)
            functionList.add(functionMap.containsKey(command.getId()) ? functionMap.get(command.getId()) : compileCommandScript(command));
        functionList = functionList.stream().sorted(
                        Comparator.comparingInt((CommandFunctions a) -> a.command.getOrder()))
                .collect(Collectors.toList());
        return executeCommands(functionList, isResponseOutput, received, receivedTime, initialValue, nonPeriodicObject);
    }

    private List<Response> executeCommands(List<CommandFunctions> functionList, boolean isResponseOutput, Value[] received, Long receivedTime, Value initialValue, Object nonPeriodicObject) throws Exception {
        var ret = new ArrayList<Response>();
        var commandList = pythonEngine.toPyList(functionList.stream()
                .map(function -> function.command.getId())
                .collect(Collectors.toList()));

        for (int i = 0; i < functionList.size(); ) {
            var function = functionList.get(i);
            Throwable ex = null;
            try {
                log.debug("[{}] cmdId={}, execute command (type: {})", protocol.deviceId, function.command.getId(), function.command.getType());
                var response = getCommandResponse(function.command, function, received, receivedTime, initialValue, nonPeriodicObject);
                if (response != null) {
                    ret.addAll(response);
                    if (isResponseOutput)
                        protocol.onResponse.onNext(response);
                } else {
                    log.trace("[{}] cmdId={}, null response received (type: {})", protocol.deviceId, function.command.getId(), function.command.getType());
                }

                if (function.delayFunction != null) {
                    Value delay;
                    try {
                        delay = function.delayFunction.execute();
                    } catch (Exception e) {
                        throw new ScriptException("delay-function failed", e);
                    }

                    if (PythonEngine.isInteger(delay))
                        Thread.sleep(PythonEngine.asInt(delay));
                    else if (PythonEngine.isNone(delay))
                        Thread.sleep(function.command.getAfterDelay());
                    else
                        throw new ScriptException(String.format("delay function output type is %s, output=%s", PythonEngine.typeName(delay), delay));
                } else {
                    Thread.sleep(function.command.getAfterDelay());
                }
            } catch (Throwable e) {
                if (e instanceof InterruptedException)
                    throw e;
                ex = e;
            }

            try {
                if (function.controlFunction != null) {
                    Value control;
                    try {
                        var argCnt = PythonEngine.getArgumentCount(function.controlFunction);
                        if (argCnt == 2)
                            control = function.controlFunction.execute(commandList, i);
                        else
                            control = function.controlFunction.execute(commandList, i, pythonEngine.asValue(ex));
                    } catch (Exception e) {
                        throw new ScriptException("control-function failed", e);
                    }
                    if (PythonEngine.isInteger(control)) {
                        var idx = PythonEngine.asInt(control);
                        int size = (int) commandList.getArraySize();
                        if (idx < 0)
                            i = Math.max(size - idx, 0);
                        else
                            i = Math.min(idx, size);
                    } else if (PythonEngine.isNone(control)) {
                        i++;
                    } else if (control.isHostObject() && control.asHostObject() instanceof Throwable) {
                        throw (Throwable) control.asHostObject();
                    } else if (control.isException()) {
                        throw new ScriptException(control.toString());
                    } else {
                        throw new ScriptException(String.format("control function output type is %s, output=%s", PythonEngine.typeName(control), control));
                    }
                } else {
                    if (ex != null)
                        throw ex;
                    i++;
                }
            } catch (Throwable e) {
                if (!(e instanceof ScriptException) && protocol.connectionLostOnException)
                    protocol.setConnectionLost();
                throw new Exception(String.format("execute commands(%s) failed", function.command.getId()), e);
            }
            log.trace("[{}] cmdId={}, execute command finished (type: {})", protocol.deviceId, function.command.getId(), function.command.getType());
        }
        return ret;
    }

    /**
     * get response of requested command
     *
     * @param command command
     * @return response list
     */
    private List<Response> getCommandResponse(Command command, CommandFunctions cmdFunctions, Value[] received, Long receivedTime, Value initialValue, Object nonPeriodicObject) throws Exception {
        if (received != null && receivedTime != null && command.getType() == CommandType.READ_REQUEST)
            return processCommandFunction(received, cmdFunctions.commandFunction, receivedTime, initialValue);

        if (isRequest(command.getType()))
            return processCommandFunction((Value[]) null, cmdFunctions.commandFunction, ZonedDateTime.now().toInstant().toEpochMilli(), initialValue);

        String requestInfo = command.getRequestInfo();
        var requestInfoFunc = cmdFunctions.requestInfoFunction;
        if (requestInfoFunc != null) {
            Value result;
            try {
                result = requestInfoFunc.execute(getArguments(requestInfoFunc, received, receivedTime, initialValue));
            } catch (Exception e) {
                throw new ScriptException("request-info failed", e);
            }
            if (PythonEngine.isString(result)) {
                log.trace("[{}] cmdId={}, set request-info as \"{}\"", protocol.deviceId, command.getId(), result.asString());
                requestInfo = result.asString();
            } else if (PythonEngine.isNone(result)) {
                if (Strings.isNullOrEmpty(command.getRequestInfo())) {
                    log.trace("[{}] cmdId={}, request function result is null", protocol.deviceId, command.getId());
                    return null;
                } else {
                    log.trace("[{}] cmdId={}, request-info function result is null -> use \"{}\"", protocol.deviceId, command.getId(), requestInfo);
                }
            } else {
                throw new ScriptException(String.format("request-info output type is %s, output=%s", PythonEngine.typeName(result), result));
            }
        } else if (Strings.isNullOrEmpty(requestInfo)) {
            throw new ScriptException("cmdId=" + command.getId() + ", request-info is not defined");
        }

        if (protocol.device.isConnectionCommand()) {
            protocol.requestConnect();
            try {
                return protocol.requestCommand(command.getId(), requestInfo, command.getCommandTimeout(), isReadRequest(command.getType()), cmdFunctions.commandFunction, initialValue, nonPeriodicObject);
            } finally {
                protocol.requestDisconnect();
            }
        } else {
            return protocol.requestCommand(command.getId(), requestInfo, command.getCommandTimeout(), isReadRequest(command.getType()), cmdFunctions.commandFunction, initialValue, nonPeriodicObject);
        }
    }

    List<Response> processCommandFunction(Value input, Value cmdFunc, long receivedTime, Value initialValue) throws Exception {
        return processCommandFunction(new Value[]{input}, cmdFunc, receivedTime, initialValue);
    }

    List<Response> processCommandFunction(Value[] input, Value cmdFunc, long receivedTime, Value initialValue) throws Exception {
        Value output;
        try {
            output = cmdFunc.execute(getArguments(cmdFunc, input, receivedTime, initialValue));
        } catch (Exception e) {
            throw new ScriptException("command-function failed", e);
        }

        var ret = new ArrayList<Response>();
        if (PythonEngine.isList(output)) {
            for (long idx = 0; idx < output.getArraySize(); idx++) {
                var o = output.getArrayElement(idx);
                if (PythonEngine.isTuple(o)) {
                    var size = o.getArraySize();
                    if (size == 2 || size == 3) {
                        Long rTime;
                        if (size == 3) {
                            var timeValue = o.getArrayElement(2);
                            if (timeValue == null || timeValue.isNull() || !timeValue.isNumber() || !timeValue.fitsInLong())
                                throw new ScriptException(String.format("output parsing failed (wrong received-time format), tag-value=%s", o));
                            rTime = timeValue.asLong();
                        } else {
                            rTime = receivedTime;
                        }
                        var tag = PythonEngine.asString(o.getArrayElement(0));
                        var value = PythonEngine.asString(o.getArrayElement(1));
                        ret.add(new Response(protocol.deviceId, tag, value, rTime));
                        var time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(rTime), ZoneId.systemDefault());
                        log.debug("[{}] tag: {}, value: {}, time: {}", protocol.deviceId, tag, value, time);
                    } else {
                        throw new ScriptException(String.format("output parsing failed (wrong tuple size), tag-value=%s, size=%d", o, size));
                    }
                } else {
                    throw new ScriptException(String.format("output parsing failed (type is not tuple), tag-value=%s, type=%s", o, PythonEngine.typeName(o)));
                }
            }
        } else if (PythonEngine.isNone(output)) {
            return null;
        } else {
            throw new ScriptException(String.format("command function output type is %s, output=%s", PythonEngine.typeName(output), output));
        }
        if (ret.isEmpty())
            return null;
        return ret;
    }

    Object[] getArguments(Value function, Value[] input, Long receivedTime, Value initialValue) throws Exception {
        int inputCount = input == null ? 0 : input.length;
        if (receivedTime != null)
            inputCount++;
        if (initialValue != null)
            inputCount++;
        int funcArgCount = PythonEngine.getArgumentCount(function);
        if (funcArgCount > inputCount)
            throw new Exception("invalid function, function arguments count: " + funcArgCount + ", possible input arguments count: " + inputCount);

        var arg = new Object[funcArgCount];
        if (funcArgCount > 0) {
            int offset = 0;
            if (initialValue != null) {
                arg[0] = initialValue;
                offset = 1;
            }
            if (input != null)
                System.arraycopy(input, 0, arg, offset, Math.min(input.length, funcArgCount - offset));
            if (receivedTime != null && funcArgCount == inputCount)
                arg[funcArgCount - 1] = receivedTime;
        }
        return arg;
    }

    private boolean isReadRequest(CommandType type) {
        return type == CommandType.READ_REQUEST ||
                type == CommandType.STARTING_READ_REQUEST ||
                type == CommandType.STOPPING_READ_REQUEST;
    }

    private boolean isWriteRequest(CommandType type) {
        return type == CommandType.WRITE_REQUEST ||
                type == CommandType.STARTING_WRITE_REQUEST ||
                type == CommandType.STOPPING_WRITE_REQUEST;
    }

    private boolean isRequest(CommandType type) {
        return type == CommandType.REQUEST ||
                type == CommandType.STARTING_REQUEST ||
                type == CommandType.STOPPING_REQUEST;
    }

    interface ScriptLogger {}

    private static class CommandFunctions {
        Command command;
        Value commandFunction;
        Value requestInfoFunction;
        Value delayFunction;
        Value controlFunction;
        CommandFunctions(Command command, Value commandFunction, Value requestInfoFunction, Value delayFunction, Value controlFunction) {
            this.command = command;
            this.commandFunction = commandFunction;
            this.requestInfoFunction = requestInfoFunction;
            this.delayFunction = delayFunction;
            this.controlFunction = controlFunction;
        }
    }

    static class ScriptException extends Exception {
        ScriptException(String message) {
            super(message);
        }
        ScriptException(String message, Throwable cause) {
            super(message, cause);
        }
        ScriptException(Throwable cause) {
            super(cause);
        }
    }
}
