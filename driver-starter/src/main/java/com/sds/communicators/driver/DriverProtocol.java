package com.sds.communicators.driver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.sds.communicators.common.struct.Command;
import com.sds.communicators.common.struct.Device;
import com.sds.communicators.common.struct.Response;
import com.sds.communicators.common.struct.Status;
import com.sds.communicators.common.type.Position;
import com.sds.communicators.common.type.StatusCode;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.functions.Action;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.javatuples.Triplet;
import org.python.core.PyFunction;
import org.python.core.PyObject;
import org.python.core.PyString;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Slf4j
@EqualsAndHashCode(of = "deviceId")
abstract class DriverProtocol {
    private DriverService driverService;
    private StatusCode status = null;
    private int retryConnect = 0;
    private int initialCommandDelay;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newCachedThreadPool();

    protected int socketTimeout;
    protected boolean isSetDisconnected = false;
    protected boolean connectionLostOnException = true;
    protected final ObjectMapper objectMapper = new ObjectMapper();

    DriverCommand driverCommand;
    String deviceId;
    Device device;
    boolean isConnectionLostOccur = false;
    PublishSubject<List<Response>> onResponse = PublishSubject.create();

    static DriverProtocol build(DriverService driverService, String defaultScript, Device device) throws Exception {
        String protocolType = device.getConnectionUrl().split("://")[0];
        switch (protocolType) {
            case "tcp-client":
                return new DriverProtocolTcpClient().create(driverService, defaultScript, device);
            case "tcp-server":
                return new DriverProtocolTcpServer().create(driverService, defaultScript, device);
            case "udp-client":
                return new DriverProtocolUdpClient().create(driverService, defaultScript, device);
            case "udp-server":
                return new DriverProtocolUdpServer().create(driverService, defaultScript, device);
            case "secsgem-client":
                return new DriverProtocolSecsGemClient().create(driverService, defaultScript, device);
            case "secsgem-server":
                return new DriverProtocolSecsGemServer().create(driverService, defaultScript, device);
            case "modbus-client":
                return new DriverProtocolModbusClient().create(driverService, defaultScript, device);
            case "modbus-server":
                return new DriverProtocolModbusServer().create(driverService, defaultScript, device);
            case "http-client":
                return new DriverProtocolHttpClient().create(driverService, defaultScript, device);
            case "http-server":
                return new DriverProtocolHttpServer().create(driverService, defaultScript, device);
            case "dummy":
                return new DriverProtocolDummy().create(driverService, defaultScript, device);
            default:
                throw new Exception("[" + device.getId() + "] not found protocol: " + device.getConnectionUrl());
        }
    }

    protected DriverProtocol create(DriverService driverService, String defaultScript, Device device) throws Exception {
        log.trace("[{}] create", device.getId());
        this.driverService = driverService;
        this.driverCommand = new DriverCommand(defaultScript, this);
        this.device = device;
        deviceId = device.getId();
        socketTimeout = device.getSocketTimeout();

        initialCommandDelay = device.getInitialCommandDelay();
        if (initialCommandDelay < 100)
            initialCommandDelay = 100;

        var option = new HashMap<String, String>();
        var connection = device.getConnectionUrl().split("://", 2);
        if (connection.length == 1) {
            initialize("", option);
        } else {
            connection = device.getConnectionUrl().split("://", 2)[1].split("\\?",2);
            if (connection.length == 2) {
                for (var sp : connection[1].split("&")) {
                    var keyValue = sp.split("=");
                    if (keyValue.length != 2)
                        throw new Exception("option parsing error, option: " + connection[1]);
                    option.put(URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8), URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8));
                }
            }
            initialize(connection[0], option);
        }

        if (option.get("connectionLostOnException") != null)
            connectionLostOnException = Boolean.parseBoolean(option.get("connectionLostOnException"));

        return this;
    }

    protected void executeProtocolScript() throws Exception {
        var protocolScript = device.getProtocolScript();
        if (!Strings.isNullOrEmpty(protocolScript)) {
            try {
                driverCommand.pythonInterpreter.exec(protocolScript);
            } catch (Exception e) {
                throw new Exception("execute protocol script failed", e);
            }
        }
    }

    protected List<Response> requestCommand(String cmdId, int timeout, PyFunction function, BlockingQueue<Triplet<String, PyObject[], Long>> requestedDataQueue, PyObject initialValue) throws Exception {
        var data = requestedDataQueue.poll(timeout, TimeUnit.MILLISECONDS);
        if (data == null) {
            throw new Exception("cmdId=" + cmdId + ", command timeout");
        } else if (data.getValue0() == null) {
            log.trace("[{}] cmdId={}, received command is null", deviceId, cmdId);
            return driverCommand.processCommandFunction(data.getValue1(), function, data.getValue2(), initialValue);
        } else if (data.getValue0().equals(cmdId)) {
            log.trace("[{}] cmdId={}, received command match", deviceId, cmdId);
            return driverCommand.processCommandFunction(data.getValue1(), function, data.getValue2(), initialValue);
        } else {
            log.error("[{}] cmdId={}, received command not match, ignore received message, received cmdId={}", deviceId, cmdId, data.getValue0());
            return null;
        }
    }

    public StatusCode getStatus() {
        return status;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public Device getDevice() {
        return device;
    }

    protected PyObject stringToPyObject(String s) {
        return driverService.stringToPyObject(s);
    }

    protected String toString(Object obj) {
        if (obj instanceof PyString) return ((PyString) obj).asString();
        else if (obj instanceof String) return (String) obj;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return obj.toString();
        }
    }

    /**
     * status changed
     *
     * @param s changed status
     * @param issuedTime status issued time
     */
    private void statusChanged(StatusCode s, ZonedDateTime issuedTime) {
        log.info("[{}] status changed: {} -> {}", deviceId, status, s);
        status = s;
        var deviceStatus = new Status(deviceId, status, issuedTime.toInstant().toEpochMilli());
        log.trace("[{}] send status, status: {}", deviceId, deviceStatus);
        try {
            driverService.sendStatus(deviceStatus);
        } catch (Exception e) {
            log.error("[{}] send status error, status: {}", deviceId, deviceStatus, e);
        }
    }

    /**
     * send response data
     */
    private void sendResponse(List<Response> responses) {
        if (responses.isEmpty())
            return;
        try {
            driverService.sendResponse(responses);
        } catch (Exception e) {
            log.error("[{}] send response error", deviceId, e);
            for (Response response : responses) {
                var receivedTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(response.getReceivedTime()), ZoneId.systemDefault());
                log.error("[{}] send failed response, tag-id: {}, value: {}, received-time: {}", deviceId, response.getTagId(), response.getValue(), receivedTime);
            }
        }
    }

    /**
     * initialize response channel
     */
    private void initResponse() {
        disposables.add(
                device.getResponseTimeout() > 0 ?
                        onResponse
                                .timeout(device.getResponseTimeout(), TimeUnit.SECONDS, Schedulers.io())
                                .subscribe(this::sendResponse,
                                        e -> {
                                            if (e instanceof TimeoutException)
                                                log.error("[{}] response timeout for {} [sec]", deviceId, device.getResponseTimeout());
                                            else
                                                log.error("[{}] response data channel error", deviceId, e);

                                            setConnectionLost();
                                        }) :
                        onResponse
                                .observeOn(Schedulers.io())
                                .subscribe(this::sendResponse,
                                        e -> {
                                            log.error("[{}] response data channel error", deviceId, e);
                                            setConnectionLost();
                                        })
        );
    }

    void callChangeStatus(StatusCode desiredStatus) {
        disposables.add(Schedulers.io().scheduleDirect(() -> changeStatus(desiredStatus)));
    }

    /**
     * try to disconnect
     */
    private String disconnect() {
        disposables.clear();
        try {
            log.trace("[{}] start disconnect", deviceId);
            requestDisconnect();
            return null;
        } catch (Exception e) {
            log.error("[{}] disconnect failed", deviceId, e);
            return "disconnect failed::" + e.getMessage();
        }
    }

    private String reconnect() throws InterruptedException {
        var ret = disconnect();
        if (ret == null) {
            Thread.sleep(device.getRetryConnectDelay());
            callChangeStatus(StatusCode.CONNECTING);
            return null;
        } else {
            changeStatus(StatusCode.DISCONNECTION_FAIL);
            return ret;
        }
    }

    String changeStatus(StatusCode desiredStatus) {
        try {
            var preStatus = status;
            log.trace("[{}] try to change status: {} -> {}", deviceId, status, desiredStatus);
            lock.lockInterruptibly();
            log.trace("[{}] change status started: {} -> {}", deviceId, status, desiredStatus);
            var issuedTime = ZonedDateTime.now();
            try {
                if (desiredStatus == StatusCode.CONNECTING && (status == null || status == StatusCode.CONNECTION_FAIL || status == StatusCode.CONNECTION_LOST)) {
                    statusChanged(desiredStatus, issuedTime);
                    try {
                        initResponse();
                        if (!device.isConnectionCommand())
                            requestConnect();
                        retryConnect = 0;
                        log.info("[{}] connected successfully", deviceId);
                        changeStatus(StatusCode.CONNECTED);

                        disposables.add(
                                Schedulers.io().scheduleDirect(() ->
                                                driverCommand.startingCommands(),
                                        initialCommandDelay, TimeUnit.MILLISECONDS));
                    } catch (Exception e) {
                        log.error("[{}] connect failed", deviceId, e);
                        callChangeStatus(StatusCode.CONNECTION_FAIL);
                        return "connect failed::" + e.getMessage();
                    }
                } else if (desiredStatus == StatusCode.CONNECTED && status == StatusCode.CONNECTING) {
                    statusChanged(desiredStatus, issuedTime);
                } else if (desiredStatus == StatusCode.CONNECTION_FAIL && status == StatusCode.CONNECTING) {
                    statusChanged(desiredStatus, issuedTime);
                    if (device.getMaxRetryConnect() < 0 || retryConnect < device.getMaxRetryConnect()) {
                        retryConnect++;
                        log.info("[{}] retry to connect ({}/{})", deviceId, retryConnect, device.getMaxRetryConnect() < 0 ? "INF" : device.getMaxRetryConnect());
                        var ret = reconnect();
                        if (ret != null)
                            return ret;
                    } else {
                        log.info("[{}] retry process ended ({}/{})", deviceId, retryConnect, device.getMaxRetryConnect());
                        setDisconnected();
                    }
                } else if (desiredStatus == StatusCode.CONNECTION_LOST && status == StatusCode.CONNECTED) {
                    driverCommand.dispose();
                    statusChanged(desiredStatus, issuedTime);
                    var ret = reconnect();
                    if (ret != null)
                        return ret;
                } else if (desiredStatus == StatusCode.DISCONNECTED && status != StatusCode.DISCONNECTED) {
                    isSetDisconnected = true;
                    driverCommand.dispose();
                    if (status == StatusCode.CONNECTED) driverCommand.stoppingCommands();
                    var ret = disconnect();
                    if (ret == null) {
                        statusChanged(desiredStatus, issuedTime);
                    } else {
                        changeStatus(StatusCode.DISCONNECTION_FAIL);
                        return ret;
                    }
                } else if (desiredStatus == StatusCode.DISCONNECTION_FAIL && status != StatusCode.DISCONNECTION_FAIL) {
                    statusChanged(desiredStatus, issuedTime);
                } else {
                    log.trace("[{}] invalid status changing ignored: {} -> {}", deviceId, status, desiredStatus);
                }
            } finally {
                lock.unlock();
                log.trace("[{}] change status to {} finished: {} -> {}", deviceId, desiredStatus, preStatus, status);
            }
        } catch (InterruptedException e) {
            log.debug("[{}] change status thread interrupted (desiredStatus: {})", deviceId, desiredStatus, e);
        } catch (Throwable e) {
            log.error("[{}] unknown error occur (desiredStatus: {})", deviceId, desiredStatus, e);
        }
        return null;
    }

    public void setConnectionLost() {
        log.trace("[{}] set connection-lost", deviceId);
        isConnectionLostOccur = true;
        callChangeStatus(StatusCode.CONNECTION_LOST);
    }

    public void setDisconnected() {
        log.trace("[{}] set disconnected", deviceId);
        Schedulers.io().scheduleDirect(() -> driverService.disconnectList(Collections.singletonList(deviceId), true));
    }

    public void setData(Map<String, Object> data) {
        driverService.clusterStarter.mergeSharedObject(data, deviceId, "data");
    }

    public void setData(Object value, List<String> path) {
        driverService.clusterStarter.mergeSharedObject(value, getBasePath(path).toArray(new String[0]));
    }

    public void deleteData(List<Object> path) {
        if (path.stream().allMatch(p -> p instanceof List))
            driverService.clusterStarter.deleteSharedObject(path.stream().map(p ->
                            getBasePath(((List<?>)p).stream().map(Object::toString).collect(Collectors.toList())))
                    .collect(Collectors.toList()));
        else
            driverService.clusterStarter.deleteSharedObject(getBasePath(path.stream().map(Object::toString).collect(Collectors.toList())).toArray(new String[0]));
    }

    public Object getData(List<String> path) {
        return driverService.clusterStarter.getItem(driverService.clusterStarter.getNodeIndex(), getBasePath(path).toArray(new String[0]));
    }

    private List<String> getBasePath(List<String> path) {
        var appendPath = new LinkedList<>(path);
        appendPath.addFirst("data");
        appendPath.addFirst(deviceId);
        return appendPath;
    }

    public Map<String, Map<String, Response>> getResponse() {
        return driverService.responseMap;
    }

    public Map<String, Response> getResponse(String deviceId) {
        return driverService.responseMap.get(deviceId);
    }

    public Map<String, Map<String, Response>> getResponse(int nodeIndex) throws Throwable {
        return driverService.getResponse(nodeIndex);
    }

    public Map<String, Response> getResponse(int nodeIndex, String deviceId) throws Throwable {
        return driverService.getResponse(nodeIndex, deviceId);
    }

    public Map<String, StatusCode> getDeviceStatus() {
        return driverService.getDeviceStatus();
    }

    public StatusCode getDeviceStatus(String deviceId) {
        return driverService.getDeviceStatus().get(deviceId);
    }

    public Map<String, StatusCode> getDeviceStatus(int nodeIndex) throws Throwable {
        return driverService.getDeviceStatus(nodeIndex);
    }

    public StatusCode getDeviceStatus(int nodeIndex, String deviceId) throws Throwable {
        return driverService.getDeviceStatus(nodeIndex, deviceId);
    }

    public Position getPosition() {
        return driverService.getPosition();
    }

    public Position getPosition(int nodeIndex) throws Throwable {
        return driverService.getPosition(nodeIndex);
    }

    public Set<Integer> getClusterNodes() {
        return driverService.getClusterNodes();
    }

    public Map<Integer, Set<String>> getDeviceIdMap() {
        return driverService.getDeviceIdMap();
    }

    public List<Response> executeCommands(String deviceId, String initialValue, Set<Command> commands) throws Exception {
        if (this.deviceId.equals(deviceId))
            throw new Exception("execute commands for self device not allowed");
        var result = driverService.executeCommands(deviceId, commands, initialValue, false);
        if (result instanceof String) throw new Exception((String) result);
        return (List<Response>) result;
    }

    public List<Response> requestCommands(String deviceId, String initialValue, Set<Command> commands) throws Exception {
        if (this.deviceId.equals(deviceId))
            throw new Exception("execute commands for self device not allowed");
        var result = driverService.executeCommands(deviceId, commands, initialValue, true);
        if (result instanceof String) throw new Exception((String) result);
        return (List<Response>) result;
    }

    public List<Response> executeCommandIds(String deviceId, String initialValue, List<String> commandIdList) throws Exception {
        if (this.deviceId.equals(deviceId))
            throw new Exception("execute commands for self device not allowed");
        var result = driverService.executeCommandIds(deviceId, commandIdList, initialValue, true);
        if (result instanceof String) throw new Exception((String) result);
        return (List<Response>) result;
    }

    public List<Response> requestCommandIds(String deviceId, String initialValue, List<String> commandIdList) throws Exception {
        if (this.deviceId.equals(deviceId))
            throw new Exception("execute commands for self device not allowed");
        var result = driverService.executeCommandIds(deviceId, commandIdList, initialValue, true);
        if (result instanceof String) throw new Exception((String) result);
        return (List<Response>) result;
    }

    public List<Response> executeCommands(int nodeIndex, String deviceId, String initialValue, Set<Command> commands) throws Throwable {
        if (driverService.clusterStarter.getNodeIndex() == nodeIndex && this.deviceId.equals(deviceId))
            throw new Exception("execute commands for self device not allowed");
        return driverService.executeCommands(nodeIndex, deviceId, initialValue, commands);
    }

    public List<Response> requestCommands(int nodeIndex, String deviceId, String initialValue, Set<Command> commands) throws Throwable {
        if (driverService.clusterStarter.getNodeIndex() == nodeIndex && this.deviceId.equals(deviceId))
            throw new Exception("execute commands for self device not allowed");
        return driverService.requestCommands(nodeIndex, deviceId, initialValue, commands);
    }

    public List<Response> executeCommandIds(int nodeIndex, String deviceId, String initialValue, List<String> commandIdList) throws Throwable {
        if (driverService.clusterStarter.getNodeIndex() == nodeIndex && this.deviceId.equals(deviceId))
            throw new Exception("execute commands for self device not allowed");
        return driverService.executeCommandIds(nodeIndex, deviceId, initialValue, commandIdList);
    }

    public List<Response> requestCommandIds(int nodeIndex, String deviceId, String initialValue, List<String> commandIdList) throws Throwable {
        if (driverService.clusterStarter.getNodeIndex() == nodeIndex && this.deviceId.equals(deviceId))
            throw new Exception("execute commands for self device not allowed");
        return driverService.requestCommandIds(nodeIndex, deviceId, initialValue, commandIdList);
    }

    protected void syncExecute(Action action) {
        var future = executor.submit(() -> {
            try {
                action.run();
            } catch (Throwable e) {
                log.trace("[{}] sync-execute failed", deviceId, e);
            }
        });
        try {
            future.get();
        } catch (Exception e) {
            log.trace("[{}] sync-execute interrupted", deviceId, e);
        }
    }

    /**
     * initialize instance
     */
    abstract void initialize(String connectionInfo, Map<String, String> option) throws Exception;

    /**
     * request connect to equip
     */
    abstract void requestConnect() throws Exception;

    /**
     * request disconnect
     */
    abstract void requestDisconnect() throws Exception;

    /**
     * request command
     *
     * @param cmdId command id
     * @param requestInfo request info
     * @param timeout request timeout
     * @param isReadCommand which read-command
     * @param function command function
     * @param initialValue initial-value for execute/request-command function
     * @param nonPeriodicObject object for non-periodic commands
     * @return response
     */
    abstract List<Response> requestCommand(String cmdId, String requestInfo, int timeout, boolean isReadCommand, PyFunction function, PyObject initialValue, Object nonPeriodicObject) throws Exception;
}
