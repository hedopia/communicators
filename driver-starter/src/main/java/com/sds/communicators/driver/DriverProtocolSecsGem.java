package com.sds.communicators.driver;

import com.google.common.base.Strings;
import com.sds.communicators.common.UtilFunc;
import com.sds.communicators.common.struct.Response;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.javatuples.Triplet;
import org.python.core.*;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;

@Slf4j
abstract class DriverProtocolSecsGem extends DriverProtocol {

    protected String host;
    protected int port;

    private final BlockingQueue<Triplet<String, PyObject[], Long>> requestedDataQueue = new ArrayBlockingQueue<>(10);

    @Override
    void initialize(String connectionInfo, Map<String, String> option) throws Exception {
        if (Strings.isNullOrEmpty(device.getProtocolScript()))
            throw new Exception("protocol script for secs-gem is empty");

        try {
            driverCommand.pythonInterpreter.set("secsgem_logger", LoggerFactory.getLogger(this.getClass()));
            var in = this.getClass().getResourceAsStream("/secsgem.py");
            driverCommand.pythonInterpreter.exec(IOUtils.toString(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new Exception("compile protocol script failed::" + e.getMessage(), e);
        }

        var hostPort = UtilFunc.extractIpPort(connectionInfo);
        host = hostPort[0];
        port = Integer.parseInt(hostPort[1]);
    }


    @Override
    void requestDisconnect() {
        try {
            Thread.sleep(3000);
        } catch (InterruptedException ignored) {}
        Schedulers.io().scheduleDirect(() -> driverCommand.pythonInterpreter.exec("host_device_" + deviceId + ".disable()"));
    }

    @Override
    List<Response> requestCommand(String cmdId, String requestInfo, int timeout, boolean isReadCommand, PyFunction function, PyObject initialValue, Object nonPeriodicObject) throws Exception {
        driverCommand.pythonInterpreter.exec("host_device_" + deviceId + ".send_stream_function(" + requestInfo + ")");
        if (!isReadCommand) return null;
        return requestCommand(cmdId, timeout, function, requestedDataQueue, initialValue);
    }

    public void packetReceived(PyObject pyCmdId, PyObject received) {
        if (isSetDisconnected) {
            log.trace("[{}] set disconnected -> packetReceived ignored, cmdId={}, received={}", deviceId, pyCmdId, received);
            return;
        }
        var receivedTime = ZonedDateTime.now().toInstant().toEpochMilli();
        try {
            if (pyCmdId instanceof PyNone) { // for anonymous read-command response
                requestedDataQueue.clear();
                requestedDataQueue.put(new Triplet<>(null, new PyObject[]{received}, receivedTime));
            } else if (pyCmdId instanceof PyString) { // for read-command response
                requestedDataQueue.clear();
                requestedDataQueue.put(new Triplet<>(pyCmdId.asString(), new PyObject[]{received}, receivedTime));
            } else if (pyCmdId instanceof PyList) {
                var list = new ArrayList<Object>((PyList) pyCmdId);
                driverCommand.executeNonPeriodicCommands(list.stream().map(Object::toString).collect(Collectors.toList()), new PyObject[]{received}, receivedTime, null);
            } else if (pyCmdId instanceof PyTuple) {
                var list = new ArrayList<Object>((PyTuple) pyCmdId);
                driverCommand.executeNonPeriodicCommands(list.stream().map(Object::toString).collect(Collectors.toList()), new PyObject[]{received}, receivedTime, null);
            } else {
                log.error("[{}] protocol function invalid output type, output type={}, received data={}", deviceId, pyCmdId.getType().getName(), received);
            }
        } catch (Exception e) {
            log.error("[{}] packet received function failed, received data={}", deviceId, received, e);
        }
    }
}
