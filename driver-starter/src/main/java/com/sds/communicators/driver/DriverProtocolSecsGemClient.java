package com.sds.communicators.driver;

import lombok.extern.slf4j.Slf4j;
import org.python.core.PyClass;

import java.util.Map;

@Slf4j
public class DriverProtocolSecsGemClient extends DriverProtocolSecsGem {

    @Override
    void initialize(String connectionInfo, Map<String, String> option) throws Exception {
        super.initialize(connectionInfo, option);
        try {
            var protocolScript = device.getProtocolScript();
            protocolScript = protocolScript.replaceFirst("class[ \t]+SecsGemClient[ \t]*\\([ \t]*SecsGemClientBase[ \t]*\\)[ \t]*:", "class SecsGemClient_" + deviceId + "(SecsGemClientBase):");
            driverCommand.pythonInterpreter.exec(protocolScript);

            var secsgem = (PyClass) driverCommand.pythonInterpreter.get("SecsGemClient_" + deviceId);
            if (secsgem == null)
                throw new Exception("\"SecsGemClient\" is not defined");
            boolean baseClassCheck = false;
            for (Object o : secsgem.__bases__) {
                if (((PyClass)o).__name__.equals("SecsGemClientBase")) {
                    baseClassCheck = true;
                    break;
                }
            }
            if (!baseClassCheck)
                throw new Exception("\"SecsGemClient\" base class is not \"SecsGemClientBase\"");
        } catch (Exception e) {
            throw new Exception("compile protocol script failed::" + e.getMessage(), e);
        }
        driverCommand.pythonInterpreter.set("secsgem_protocol", this);
        driverCommand.pythonInterpreter.exec("host_device_" + deviceId + " = SecsGemClient_" + deviceId + "('" + host + "', " + port + ", '" + deviceId + "', secsgem_protocol.setConnectionLost)");
    }

    @Override
    void requestConnect() throws Exception {
        log.info("[{}] host={}, port={}, socket-timeout={}", deviceId, host, port, socketTimeout);
        driverCommand.pythonInterpreter.exec("host_device_" + deviceId + ".enable()\n" +
                "host_device_" + deviceId + ".waitfor_communicating(" + (device.getSocketTimeout()/1000) + ")");
        var isCommunicating = driverCommand.pythonInterpreter.get("host_device_" + deviceId).__getattr__("communicationState").__getattr__("current").asString();
        if (!isCommunicating.equals("COMMUNICATING"))
            throw new Exception("status is not communicating");
    }
}
