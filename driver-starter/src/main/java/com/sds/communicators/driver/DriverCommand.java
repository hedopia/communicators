package com.sds.communicators.driver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.sds.communicators.common.struct.Command;
import com.sds.communicators.common.struct.Response;
import com.sds.communicators.common.type.CommandType;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.extern.slf4j.Slf4j;
import org.python.core.*;
import org.python.util.PythonInterpreter;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Slf4j
class DriverCommand {
    final PythonInterpreter pythonInterpreter = new PythonInterpreter();
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Map<String, CommandFunctions> functionMap = new HashMap<>();
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final Map<Integer, Set<Command>> periodGroupMap = new HashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private DriverProtocol protocol;

    DriverCommand(String defaultScript) {
        pythonInterpreter.set("log", LoggerFactory.getLogger(ScriptLogger.class));
        pythonInterpreter.exec("from com.sds.communicators.common import UtilFunc");
        pythonInterpreter.exec("import java");
        pythonInterpreter.exec(defaultScript);
    }

    void dispose() {
        disposables.clear();
    }

    void setProtocol(DriverProtocol protocol) throws Exception {
        this.protocol = protocol;
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
                pythonInterpreter.exec(script);
                var cmd = (PyFunction)pythonInterpreter.get("cmdFunc_" + command.getId());
                var req = (PyFunction)pythonInterpreter.get("requestInfo_" + command.getId());
                var delay = (PyFunction)pythonInterpreter.get("delay_" + command.getId());
                var control = (PyFunction)pythonInterpreter.get("control_" + command.getId());

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
            var argCnt = ((PyTableCode)ret.controlFunction.__code__).co_argcount;
            if (argCnt != 2 && argCnt != 3)
                throw new Exception("cmdId=" + command.getId() + ", control arguments count must be 2 or 3 >> control(commandList, idx, exception), arguments count: " + ((PyTableCode)ret.commandFunction.__code__).co_argcount);
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
                    executeCommands(startingCmd, null, null);
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
                                                    executeCommands(entry.getValue(), null, null);
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
                lockedExecuteCommands(stoppingCmd, null, true);
            } catch (Exception e) {
                log.error("[{}] error on executing stopping request commands", protocol.deviceId, e);
            }
        }
    }

    void executeNonPeriodicCommands(PyObject[] received, Long receivedTime) throws Exception {
        try {
            lock.lockInterruptibly();
            try {
                var nonPeriodicCmd = protocol.device.getCommands().stream()
                        .filter(cmd -> cmd.getPeriodGroup() < 0) // non-periodic group
                        .collect(Collectors.toSet());
                executeCommands(nonPeriodicCmd, received, receivedTime);
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            log.trace("[{}] execute non-periodic commands interrupted", protocol.deviceId, e);
        }
    }

    void executeNonPeriodicCommands(List<String> commandIdList, PyObject[] received, Long receivedTime) throws Exception {
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

                executeCommands(functionList, true, received, receivedTime, null);
            } finally {
                lock.unlock();
            }
        } catch (InterruptedException e) {
            log.trace("[{}] execute non-periodic commands({}) interrupted", protocol.deviceId, commandIdList, e);
        }
    }

    List<Response> lockedExecuteCommands(List<String> commandIdList, String initialValue, boolean isResponseOutput) throws Exception {
        lock.lockInterruptibly();
        try {
            List<CommandFunctions> functionList = new ArrayList<>();
            for (var cmdId : commandIdList) {
                if (functionMap.containsKey(cmdId))
                    functionList.add(functionMap.get(cmdId));
                else
                    throw new Exception("execute commands failed, (cmdId: " + cmdId + ") is not registered command");
            }
            return executeCommands(functionList, isResponseOutput, null, null, getInitialValue(initialValue));
        } finally {
            lock.unlock();
        }
    }

    List<Response> lockedExecuteCommands(Set<Command> commands, String initialValue, boolean isResponseOutput) throws Exception {
        lock.lockInterruptibly();
        try {
            return executeCommands(commands, isResponseOutput, null, null, getInitialValue(initialValue));
        } finally {
            lock.unlock();
        }
    }

    private PyObject getInitialValue(String initialValue) {
        PyObject initialObject;
        if (!Strings.isNullOrEmpty(initialValue)) {
            try {
                var value = objectMapper.readValue(initialValue, new TypeReference<Map<PyString, PyString>>() {});
                initialObject = new PyDictionary() {{putAll(value);}};
            } catch (JsonProcessingException e) {
                try {
                    var value = objectMapper.readValue(initialValue, new TypeReference<List<PyString>>() {});
                    initialObject = new PyList(value);
                } catch (JsonProcessingException ex) {
                    initialObject = new PyString(initialValue);
                }
            }
        } else {
            initialObject = null;
        }
        return initialObject;
    }

    private void executeCommands(Set<Command> commands, PyObject[] received, Long receivedTime) throws Exception {
        executeCommands(commands, true, received, receivedTime, null);
    }

    private List<Response> executeCommands(Set<Command> commands, boolean isResponseOutput, PyObject[] received, Long receivedTime, PyObject initialValue) throws Exception {
        List<CommandFunctions> functionList = new ArrayList<>();
        for (var command : commands)
            functionList.add(functionMap.containsKey(command.getId()) ? functionMap.get(command.getId()) : compileCommandScript(command));
        functionList = functionList.stream().sorted(
                        Comparator.comparingInt((CommandFunctions a) -> a.command.getOrder()))
                .collect(Collectors.toList());
        return executeCommands(functionList, isResponseOutput, received, receivedTime, initialValue);
    }

    private List<Response> executeCommands(List<CommandFunctions> functionList, boolean isResponseOutput, PyObject[] received, Long receivedTime, PyObject initialValue) throws Exception {
        var ret = new ArrayList<Response>();
        var commandList = new PyList(functionList.stream()
                .map(function -> new PyString(function.command.getId()))
                .collect(Collectors.toList()));

        for (int i = 0; i < functionList.size(); ) {
            var function = functionList.get(i);
            pythonInterpreter.set("protocol", protocol);
            Throwable ex = null;
            try {
                log.debug("[{}] cmdId={}, execute command (type: {})", protocol.deviceId, function.command.getId(), function.command.getType());
                var response = getCommandResponse(function.command, function, received, receivedTime, initialValue);
                if (response != null) {
                    ret.addAll(response);
                    if (isResponseOutput)
                        protocol.onResponse.onNext(response);
                } else {
                    log.trace("[{}] cmdId={}, null response received (type: {})", protocol.deviceId, function.command.getId(), function.command.getType());
                }

                if (function.delayFunction != null) {
                    PyObject delay;
                    try {
                        delay = function.delayFunction.__call__();
                    } catch (Exception e) {
                        throw new ScriptException("delay-function failed", e);
                    }

                    if (delay instanceof PyInteger)
                        Thread.sleep(delay.asInt());
                    else if (delay instanceof PyNone)
                        Thread.sleep(function.command.getAfterDelay());
                    else
                        throw new ScriptException(String.format("delay function output type is %s, output=%s", delay.getType().getName(), delay));
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
                    PyObject control;
                    try {
                        var argCnt = ((PyTableCode)function.controlFunction.__code__).co_argcount;
                        if (argCnt == 2)
                            control = function.controlFunction.__call__(commandList, new PyInteger(i));
                        else
                            control = function.controlFunction.__call__(commandList, new PyInteger(i), Py.java2py(ex));
                    } catch (Exception e) {
                        throw new ScriptException("control-function failed", e);
                    }
                    if (control instanceof PyInteger) {
                        var idx = control.asInt();
                        if (idx < 0)
                            i = Math.max(commandList.size() - idx, 0);
                        else
                            i = Math.min(idx, commandList.size());
                    } else if (control instanceof PyNone) {
                        i++;
                    } else if (Py.isInstance(control, Py.java2py(Throwable.class))) {
                        throw (Throwable) control.__tojava__(control.getType().getProxyType());
                    } else if (control instanceof PyBaseException) {
                        throw new ScriptException(control.toString());
                    } else {
                        throw new ScriptException(String.format("control function output type is %s, output=%s", control.getType().getName(), control));
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
    private List<Response> getCommandResponse(Command command, CommandFunctions cmdFunctions, PyObject[] received, Long receivedTime, PyObject initialValue) throws Exception {
        if (received != null && receivedTime != null && command.getType() == CommandType.READ_REQUEST)
            return processCommandFunction(received, cmdFunctions.commandFunction, receivedTime, initialValue);

        if (isRequest(command.getType()))
            return processCommandFunction((PyObject[]) null, cmdFunctions.commandFunction, ZonedDateTime.now().toInstant().toEpochMilli(), initialValue);

        String requestInfo = command.getRequestInfo();
        var requestInfoFunc = cmdFunctions.requestInfoFunction;
        if (requestInfoFunc != null) {
            PyObject result;
            try {
                result = requestInfoFunc.__call__(getArguments(requestInfoFunc, received, receivedTime, initialValue));
            } catch (Exception e) {
                throw new ScriptException("request-info failed", e);
            }
            if (result instanceof PyString) {
                log.trace("[{}] cmdId={}, set request-info as \"{}\"", protocol.deviceId, command.getId(), result.asString());
                requestInfo = result.asString();
            } else if (result instanceof PyNone) {
                if (Strings.isNullOrEmpty(command.getRequestInfo())) {
                    log.trace("[{}] cmdId={}, request function result is null", protocol.deviceId, command.getId());
                    return null;
                } else {
                    log.trace("[{}] cmdId={}, request-info function result is null -> use \"{}\"", protocol.deviceId, command.getId(), requestInfo);
                }
            } else {
                throw new ScriptException(String.format("request-info output type is %s, output=%s", result.getType().getName(), result));
            }
        }
        if (protocol.device.isConnectionCommand()) {
            protocol.requestConnect();
            try {
                return protocol.requestCommand(command.getId(), requestInfo, command.getCommandTimeout(), isReadRequest(command.getType()), cmdFunctions.commandFunction, initialValue);
            } finally {
                protocol.requestDisconnect();
            }
        } else {
            return protocol.requestCommand(command.getId(), requestInfo, command.getCommandTimeout(), isReadRequest(command.getType()), cmdFunctions.commandFunction, initialValue);
        }
    }

    List<Response> processCommandFunction(PyObject input, PyFunction cmdFunc, long receivedTime, PyObject initialValue) throws Exception {
        return processCommandFunction(new PyObject[]{input}, cmdFunc, receivedTime, initialValue);
    }

    List<Response> processCommandFunction(PyObject[] input, PyFunction cmdFunc, long receivedTime, PyObject initialValue) throws Exception {
        PyObject output;
        try {
            output = cmdFunc.__call__(getArguments(cmdFunc, input, receivedTime, initialValue));
        } catch (Exception e) {
            throw new ScriptException("command-function failed", e);
        }

        var ret = new ArrayList<Response>();
        if (output instanceof PyList) {
            var list = (PyList)output;
            for (Object o : list) {
                if (o instanceof PyTuple) {
                    var tuple = (PyTuple)o;
                    if (tuple.size() == 2 || tuple.size() == 3) {
                        if (tuple.size() == 3 && !(tuple.get(2) instanceof Long) && !(tuple.get(2) instanceof BigInteger))
                            throw new ScriptException(String.format("output parsing failed (wrong received-time format), tag-value=%s", tuple));
                        var rTime = tuple.size() == 2 ? receivedTime : (tuple.get(2) instanceof BigInteger ? ((BigInteger) tuple.get(2)).longValue() : (Long) tuple.get(2));
                        ret.add(new Response(protocol.deviceId, tuple.get(0).toString(), tuple.get(1).toString(), rTime));
                        var time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(rTime), ZoneId.systemDefault());
                        log.debug("[{}] tag: {}, value: {}, time: {}", protocol.deviceId, tuple.get(0), tuple.get(1), time);
                    } else {
                        throw new ScriptException(String.format("output parsing failed (wrong tuple size), tag-value=%s, size=%d", tuple, tuple.size()));
                    }
                } else {
                    throw new ScriptException(String.format("output parsing failed (type is not PyTuple), tag-value=%s, type=%s", o.toString(), o.getClass().toString()));
                }
            }
        } else if (output instanceof PyNone) {
            return null;
        } else {
            throw new ScriptException(String.format("command function output type is %s, output=%s", output.getType().getName(), output));
        }
        if (ret.isEmpty())
            return null;
        return ret;
    }

    private PyObject[] getArguments(PyFunction function, PyObject[] input, Long receivedTime, PyObject initialValue) throws Exception {
        int inputCount = input == null ? 0 : input.length;
        if (receivedTime != null)
            inputCount++;
        if (initialValue != null)
            inputCount++;
        int funcArgCount = ((PyTableCode)function.__code__).co_argcount;
        if (funcArgCount > inputCount)
            throw new Exception("invalid function, function arguments count: " + funcArgCount + ", possible input arguments count: " + inputCount);

        var arg = new PyObject[funcArgCount];
        if (funcArgCount > 0) {
            int offset = 0;
            if (initialValue != null) {
                arg[0] = initialValue;
                offset = 1;
            }
            if (input != null)
                System.arraycopy(input, 0, arg, offset, Math.min(input.length, funcArgCount - offset));
            if (receivedTime != null && funcArgCount == inputCount)
                arg[funcArgCount - 1] = new PyLong(receivedTime);
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
        PyFunction commandFunction;
        PyFunction requestInfoFunction;
        PyFunction delayFunction;
        PyFunction controlFunction;
        CommandFunctions(Command command, PyFunction commandFunction, PyFunction requestInfoFunction, PyFunction delayFunction, PyFunction controlFunction) {
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
