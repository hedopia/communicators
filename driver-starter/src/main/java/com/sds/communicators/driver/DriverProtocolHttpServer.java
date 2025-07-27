package com.sds.communicators.driver;

import com.google.common.base.Strings;
import com.sds.communicators.common.UtilFunc;
import com.sds.communicators.common.struct.Response;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContextBuilder;
import org.python.core.PyDictionary;
import org.python.core.PyFunction;
import org.python.core.PyObject;
import org.python.core.PyString;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import javax.net.ssl.KeyManagerFactory;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DriverProtocolHttpServer extends DriverProtocolHttp {
    private PyFunction protocolFunc = null;
    private String host;
    private int port;
    private DisposableServer disposableServer = null;

    @Override
    void initialize(String connectionInfo, Map<String, String> option) throws Exception {
        connectionLostOnException = false;
        super.initialize(connectionInfo, option);
        var protocolScript = device.getProtocolScript();
        if (!Strings.isNullOrEmpty(protocolScript)) {
            try {
                protocolScript = protocolScript.replaceFirst("def[ \t]+protocolFunc[ \t]*\\(", "def protocolFunc_" + deviceId + "(");
                driverCommand.pythonInterpreter.exec(protocolScript);
                protocolFunc = (PyFunction) driverCommand.pythonInterpreter.get("protocolFunc_" + deviceId);
            } catch (Exception e) {
                throw new Exception("compile protocol script failed::" + e.getMessage(), e);
            }
        }
        var hostPort = UtilFunc.extractIpPort(connectionInfo);
        host = hostPort[0];
        port = Integer.parseInt(hostPort[1]);
        device.setConnectionCommand(false);
    }

    @Override
    void requestConnect() throws Exception {
        var server = HttpServer.create();
        if (sslContext != null) server = server.secure(spec -> spec.sslContext(sslContext));
        if (!Strings.isNullOrEmpty(host)) server = server.host(host);
        disposableServer = server
                .port(port)
                .handle((request, response) -> {
                    var params = request.params();
                    if (params != null) {
                        new PyDictionary(params.entrySet().stream()
                                .collect(Collectors.toMap(entry -> new PyString(entry.getKey()),
                                        entry -> new PyString(entry.getValue())))
                        );
                    }
                    var str = request.receive()
                            .aggregate()
                            .asString();
                    return str.then();
                })
                .bindNow(Duration.ofMillis(socketTimeout));
    }

    @Override
    void requestDisconnect() throws Exception {
        if (disposableServer != null)
            disposableServer.disposeNow(Duration.ofMillis(socketTimeout));
    }

    @Override
    List<Response> requestCommand(String cmdId, String requestInfo, int timeout, boolean isReadCommand, PyFunction function, PyObject initialValue) throws Exception {
        return List.of();
    }

    SslContextBuilder getSslContextBuilder(InputStream keyCertChainInputStream, InputStream keyInputStream, String keyPassword) {
        return SslContextBuilder.forServer(keyCertChainInputStream, keyInputStream, keyPassword);
    }

    SslContextBuilder getSslContextBuilder(KeyManagerFactory keyManagerFactory) {
        return SslContextBuilder.forServer(keyManagerFactory);
    }

    SslContextBuilder getTrustSslContextBuilder(SslContextBuilder sslContextBuilder) {
        return sslContextBuilder.clientAuth(ClientAuth.REQUIRE);
    }
}
