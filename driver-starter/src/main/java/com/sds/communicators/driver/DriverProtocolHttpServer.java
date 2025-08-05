package com.sds.communicators.driver;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.python.core.*;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

import javax.net.ssl.KeyManagerFactory;
import java.io.InputStream;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
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
                                .asByteArray()
                                .defaultIfEmpty(new byte[]{})
                                .flatMap(body -> requestProcessing(request, response, body)))
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
        if (!(nonPeriodicObject instanceof List)) throw new DriverCommand.ScriptException("http-server only supports non-periodic commands");
        else if (isReadCommand) throw new DriverCommand.ScriptException("not supported command for http-server");
        ((List<String>) nonPeriodicObject).add(requestInfo);
        return null;
    }

    private Mono<Void> requestProcessing(HttpServerRequest request, HttpServerResponse response, byte[] body) {
        var receivedTime = ZonedDateTime.now().toInstant().toEpochMilli();
        var decoder = new QueryStringDecoder(request.uri());
        var params = getPyParams(decoder.parameters());
        var path = decoder.path();
        var method = request.method().name();
        var headers = getPyHeaders(request.requestHeaders());
        var rcvBody = useByteArrayBody ? new PyList(Arrays.asList(UtilFunc.arrayWrapper(body))) : stringToPyObject(new String(body));
        PyObject[] received = new PyObject[]{new PyString(method), new PyString(path), rcvBody, params, headers};
        var requestInfoList = new ArrayList<String>();
        try {
            if (protocolFunc != null)
                executeProtocolFunc(received, receivedTime, requestInfoList);
            else
                driverCommand.executeNonPeriodicCommands(received, receivedTime, requestInfoList);
            if (requestInfoList.isEmpty()) {
                return response.status(HttpResponseStatus.OK).then();
            } else {
                var requestInfo = requestInfoList.get(requestInfoList.size() - 1);
                var responseInfo = objectMapper.readValue(requestInfo, ResponseInfo.class);
                var statusCode = responseInfo.httpStatusCode == null ? 200 : responseInfo.httpStatusCode;
                var responseBody = Strings.isNullOrEmpty(responseInfo.body) ? new byte[]{} : UtilFunc.stringToByteArray(responseInfo.body);
                if (responseInfo.headers != null)
                    responseInfo.headers.forEach((k,v) -> v.forEach(s -> response.header(k, s)));
                return response.status(statusCode).sendByteArray(Mono.just(responseBody)).then();
            }
        } catch (Exception e) {
            return response.status(HttpResponseStatus.INTERNAL_SERVER_ERROR).sendString(Mono.just(e.getMessage())).then();
        }
    }

    private PyDictionary getPyParams(Map<String, List<String>> params) {
        var pyParams = new PyDictionary();
        params.forEach((k, v) ->
                pyParams.put(new PyString(k), new PyList(v.stream().map(PyString::new).collect(Collectors.toList()))));
        return pyParams;
    }

    private void executeProtocolFunc(PyObject[] received, long receivedTime, List<String> requestInfoList) throws Exception {
        var arg = driverCommand.getArguments(protocolFunc, received, receivedTime, null);
        PyObject result;
        try {
            result = protocolFunc.__call__(arg);
        } catch (Exception e) {
            throw new DriverCommand.ScriptException("protocol-function failed", e);
        }
        if (result instanceof PyList) {
            var list = new ArrayList<Object>((PyList) result);
            driverCommand.executeNonPeriodicCommands(list.stream().map(Object::toString).collect(Collectors.toList()), received, receivedTime, requestInfoList);
        } else if (result instanceof PyTuple) {
            var list = new ArrayList<Object>((PyTuple) result);
            driverCommand.executeNonPeriodicCommands(list.stream().map(Object::toString).collect(Collectors.toList()), received, receivedTime, requestInfoList);
        } else {
            log.error("[{}] protocol function invalid output type, output type={}, received data={}", deviceId, result.getType().getName(), Arrays.asList(received));
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
    public static class ResponseInfo {
        Integer httpStatusCode;
        String body;
        Map<String, List<String>> headers;
    }

    public String requestInfo(PyObject httpStatusCode, PyObject body, PyObject... headers) {
        var sb = new StringBuilder();
        if (httpStatusCode instanceof PyInteger)
            sb.append("\"httpStatusCode\":")
                    .append(httpStatusCode)
                    .append(",");
        if (body instanceof PyString)
            sb.append("\"body\":\"")
                    .append(body)
                    .append("\",");
        var headerMap = new HashMap<String, List<String>>();
        for (int i = 0; i < headers.length - 1; i += 2)
            headerMap.compute(headers[i].toString(), (k,v) -> v == null ? new ArrayList<>() : v)
                    .add(headers[i + 1].toString());
        if (!headerMap.isEmpty()) {
            sb.append("\"headers\":");
            try {
                sb.append(objectMapper.writeValueAsString(headerMap));
            } catch (JsonProcessingException ignored) {}
            sb.append(",");
        }
        if (sb.length() > 0) sb.setLength(sb.length() - 1);
        sb.insert(0, "{");
        sb.append("}");
        return sb.toString();
    }
}
