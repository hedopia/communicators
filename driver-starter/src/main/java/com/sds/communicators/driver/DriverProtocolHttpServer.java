package com.sds.communicators.driver;

import com.google.common.base.Strings;
import com.sds.communicators.common.UtilFunc;
import com.sds.communicators.common.struct.Response;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContextBuilder;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.javatuples.Triplet;
import org.python.core.*;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import javax.net.ssl.KeyManagerFactory;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
public class DriverProtocolHttpServer extends DriverProtocolHttp {
    private PyFunction protocolFunc = null;
    private String host;
    private int port;
    private DisposableServer disposableServer = null;
    private final Set<Channel> channels = ConcurrentHashMap.newKeySet();

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
                .doOnConnection(c -> {
                    channels.add(c.channel());
                    c.onDispose(() -> channels.remove(c.channel()));
                })
                .handle((request, response) ->
                        request.receive()
                                .aggregate()
                                .asString()
                                .defaultIfEmpty("")
                                .flatMap(body -> {
                                    var decoder = new QueryStringDecoder(request.uri());
                                    var params = decoder.parameters().entrySet().stream()
                                            .collect(Collectors.toMap(entry -> new PyString(entry.getKey()),
                                                    entry -> new PyList(entry.getValue().stream()
                                                            .map(PyString::new).collect(Collectors.toList()))));
                                    var path = decoder.path();
                                    var headers = request.requestHeaders().entries().stream()
                                            .collect(Collectors.toMap(entry -> new PyString(entry.getKey()),
                                                    entry -> new PyString(entry.getValue())));
                                    return response.status(HttpResponseStatus.OK).sendString(Mono.just("Received: ")).then();
                                }))
                .bindNow(Duration.ofMillis(socketTimeout));
    }

    @Override
    void requestDisconnect() throws Exception {
        if (disposableServer != null)
            disposableServer.disposeNow(Duration.ofMillis(socketTimeout));
        for (var channel : channels)
            channel.close().get();
    }

    @Override
    List<Response> requestCommand(String cmdId, String requestInfo, int timeout, boolean isReadCommand, PyFunction function, PyObject initialValue, Object nonPeriodicObject) throws Exception {
        log.info("[{}] cmdId={}, requestCommand not supported for http server", deviceId, cmdId);
        return null;
    }

    private void requestProcessing(PyObject[] received, long receivedTime) throws Exception {
        if (protocolFunc != null) {
            executeProtocolFunc(received, receivedTime);
        } else {
            driverCommand.executeNonPeriodicCommands(received, receivedTime);
        }
    }

    protected SslContextBuilder getSslContextBuilder(InputStream keyCertChainInputStream, InputStream keyInputStream, String keyPassword) {
        return SslContextBuilder.forServer(keyCertChainInputStream, keyInputStream, keyPassword);
    }

    protected SslContextBuilder getSslContextBuilder(KeyManagerFactory keyManagerFactory) {
        return SslContextBuilder.forServer(keyManagerFactory);
    }

    protected SslContextBuilder getTrustSslContextBuilder(SslContextBuilder sslContextBuilder) {
        return sslContextBuilder.clientAuth(ClientAuth.REQUIRE);
    }

    @Setter
    @ToString
    private static class ResponseInfo {
        Map<String, List<String>> headers;
        String body;
        Integer httpStatusCode;
    }
}
