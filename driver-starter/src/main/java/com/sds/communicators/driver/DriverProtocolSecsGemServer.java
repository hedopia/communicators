package com.sds.communicators.driver;

import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;
import org.python.core.PyClass;

import java.util.Map;

@Slf4j
public class DriverProtocolSecsGemServer extends DriverProtocolSecsGem {

    @Override
    void initialize(String connectionInfo, Map<String, String> option) throws Exception {
        connectionLostOnException = false;
        super.initialize(connectionInfo, option);
        try {
            var protocolScript = device.getProtocolScript();
            protocolScript = protocolScript.replaceFirst("class[ \t]+SecsGemServer[ \t]*\\([ \t]*SecsGemServerBase[ \t]*\\)[ \t]*:", "class SecsGemServer_" + deviceId + "(SecsGemServerBase):");
            driverCommand.pythonInterpreter.exec(protocolScript);

            var secsgem = (PyClass) driverCommand.pythonInterpreter.get("SecsGemServer_" + deviceId);
            if (secsgem == null)
                throw new Exception("\"SecsGemServer\" is not defined");
            boolean baseClassCheck = false;
            for (Object o : secsgem.__bases__) {
                if (((PyClass)o).__name__.equals("SecsGemServerBase")) {
                    baseClassCheck = true;
                    break;
                }
            }
            if (!baseClassCheck)
                throw new Exception("\"SecsGemServer\" base class is not \"SecsGemServerBase\"");
        } catch (Exception e) {
            throw new Exception("compile protocol script failed::" + e.getMessage(), e);
        }
        if (!Strings.isNullOrEmpty(host))
            driverCommand.pythonInterpreter.exec("host_device_" + deviceId + " = SecsGemServer_" + deviceId + "('" + host + "', " + port + ", '" + deviceId + "')");
        else
            driverCommand.pythonInterpreter.exec("host_device_" + deviceId + " = SecsGemServer_" + deviceId + "('0.0.0.0', " + port + ", '" + deviceId + "')");
        device.setConnectionCommand(false);
    }

    @Override
    void requestConnect() throws Exception {
        log.info("[{}] host={}, port={}, socket-timeout={}", deviceId, host, port, socketTimeout);
        driverCommand.pythonInterpreter.exec("host_device_" + deviceId + ".enable()");
    }
}
