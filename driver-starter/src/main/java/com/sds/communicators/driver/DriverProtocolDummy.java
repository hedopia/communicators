package com.sds.communicators.driver;

import com.sds.communicators.common.struct.Response;
import lombok.extern.slf4j.Slf4j;
import org.python.core.PyFunction;
import org.python.core.PyObject;

import java.util.List;
import java.util.Map;

@Slf4j
public class DriverProtocolDummy extends DriverProtocol {
    @Override
    void initialize(String connectionInfo, Map<String, String> option) throws Exception {
        log.trace("dummy protocol initialize");
    }

    @Override
    void requestConnect() throws Exception {
        log.trace("dummy protocol request connect");
    }

    @Override
    void requestDisconnect() throws Exception {
        log.trace("dummy protocol request disconnect");
    }

    @Override
    List<Response> requestCommand(String cmdId, String requestInfo, int timeout, boolean isReadCommand, PyFunction function, PyObject initialValue) throws Exception {
        log.trace("dummy protocol request command");
        return null;
    }
}
