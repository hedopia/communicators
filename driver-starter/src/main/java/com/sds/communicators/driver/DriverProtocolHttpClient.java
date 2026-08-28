package com.sds.communicators.driver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Strings;
import com.google.common.primitives.Ints;
import com.sds.communicators.common.UtilFunc;
import com.sds.communicators.common.struct.Response;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.ssl.SslContextBuilder;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Value;
import org.javatuples.Quartet;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import javax.net.ssl.KeyManagerFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class DriverProtocolHttpClient extends DriverProtocolHttp {
    private String basePath;
    @Override
    void initialize(String connectionInfo, Map<String, String> option) throws Exception {
        super.initialize(connectionInfo, option);
        basePath = connectionInfo;
        device.setConnectionCommand(true);
    }
    @Override
    void requestConnect() throws Exception {
        log.trace("[{}] http client requestConnect ignored", deviceId);
    }

    @Override
    void requestDisconnect() throws Exception {
        log.trace("[{}] http client requestDisconnect ignored", deviceId);
    }

    @Override
    List<Response> requestCommand(String cmdId, String requestInfo, int timeout, boolean isReadCommand, Value function, Value initialValue, Object nonPeriodicObject) throws Exception {
        if (!isReadCommand) throw new DriverCommand.ScriptException("http-client doesn't support write-command");
        var info = objectMapper.readValue(requestInfo, RequestInfo.class);
        var method = HttpMethod.valueOf(info.method == null ? "GET" : info.method);
        var path = (Strings.isNullOrEmpty(info.basePath) ? basePath : info.basePath) + (Strings.isNullOrEmpty(info.path) ? "" : info.path);
        var sb = new StringBuilder(path);
        if (info.params != null) {
            sb.append("?");
            info.params.forEach((k, v) -> v.forEach(s ->
                    sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                            .append("=")
                            .append(URLEncoder.encode(s, StandardCharsets.UTF_8))
                            .append("&")
            ));
            sb.setLength(sb.length() - 1);
        }
        var uri = URI.create(sb.toString());
        var body = Strings.isNullOrEmpty(info.body) ? new byte[]{} : UtilFunc.stringToByteArray(info.body);
        log.trace("[{}] send request, method={}, path={}, body={}, params={}, headers={}", deviceId, method.toString(), path, info.body, info.params, info.headers);
        AtomicReference<Quartet<byte[], HttpHeaders, Integer, Long>> reference = new AtomicReference<>(null);
        AtomicReference<Exception> exception = new AtomicReference<>(null);
        var client = getClient(info);
        syncExecute(() -> {
            try {
                reference.set(client
                        .headers(headers -> {
                            if (info.headers != null) {
                                for (var entry : info.headers.entrySet())
                                    headers.add(entry.getKey(), entry.getValue());
                            }
                        })
                        .request(method)
                        .uri(uri)
                        .send(ByteBufFlux.fromInbound(Mono.just(body)))
                        .responseSingle((response, byteBufMono) ->
                                byteBufMono.asByteArray().map(byteArray ->
                                        new Quartet<>(byteArray, response.responseHeaders(), response.status().code(), ZonedDateTime.now().toInstant().toEpochMilli())))
                        .block(Duration.ofMillis(timeout)));
            } catch (Exception e) {
                exception.set(e);
            }
        });
        if (reference.get() == null) {
            if (exception.get() != null)
                throw exception.get();
            else
                throw new Exception("unknown exception occur");
        }
        var response = reference.get();
        var headers = getPyHeaders(response.getValue1());
        var rcvBody = useByteArrayBody ? driverCommand.pythonEngine.toPyList(UtilFunc.arrayWrapper(response.getValue0())) :
                stringToPyObject(new String(response.getValue0(), StandardCharsets.UTF_8));
        log.trace("[{}] response received, httpStatusCode={}, body={}, headers={}", deviceId, response.getValue2(), toString(rcvBody), headers);
        Value[] received = new Value[] {driverCommand.pythonEngine.asValue(response.getValue2()), rcvBody, headers};
        return driverCommand.processCommandFunction(received, function, response.getValue3(), initialValue);
    }

    private HttpClient getClient(RequestInfo info) {
        var client = HttpClient.create();
        if (info.proxy != null && !Strings.isNullOrEmpty(info.proxy.get("host")) && Ints.tryParse(info.proxy.get("port")) != null)
            client = client.proxy(typeSpec -> {
                var spec = typeSpec.type(ProxyProvider.Proxy.valueOf(info.proxy.getOrDefault("type", "HTTP")))
                        .host(info.proxy.get("host"))
                        .port(Ints.tryParse(info.proxy.get("port")));
                if (!Strings.isNullOrEmpty(info.proxy.get("username")))
                    spec = spec.username(info.proxy.get("username"));
                if (!Strings.isNullOrEmpty(info.proxy.get("password")))
                    spec.password(username -> info.proxy.get("password"));
            });
        if (sslContext != null)
            client = client.secure(spec -> spec.sslContext(sslContext));
        return client;
    }

    protected SslContextBuilder getSslContextBuilder(InputStream keyCertChainInputStream, InputStream keyInputStream, String keyPassword) {
        return SslContextBuilder.forClient().keyManager(keyCertChainInputStream, keyInputStream, keyPassword);
    }

    protected SslContextBuilder getSslContextBuilder(KeyManagerFactory keyManagerFactory) {
        return SslContextBuilder.forClient().keyManager(keyManagerFactory);
    }

    protected SslContextBuilder getTrustSslContextBuilder(SslContextBuilder sslContextBuilder) {
        return sslContextBuilder;
    }

    @Setter
    @ToString
    private static class RequestInfo {
        String method;
        String path;
        String basePath;
        String body;
        Map<String, List<String>> params;
        Map<String, List<String>> headers;
        Map<String, String> proxy;
    }

    public String requestInfo(Value method, Value path, Value basePath, Value body, Value params, Value... headers) {
        return requestInfo(method, path, basePath, body, params, null, null, null, null, null, headers);
    }

    public String requestInfo(Value method, Value path, Value basePath, Value body, Value params, Value proxyHost, Value proxyPort, Value... headers) {
        return requestInfo(method, path, basePath, body, params, null, proxyHost, proxyPort, null, null, headers);
    }
    public String requestInfo(Value method, Value path, Value basePath, Value body, Value params,
                              Value proxyType, Value proxyHost, Value proxyPort, Value proxyUsername, Value proxyPassword,
                              Value... headers) {
        var sb = new StringBuilder();
        if (PythonEngine.isString(method))
            sb.append("\"method\":\"")
                    .append(method.asString())
                    .append("\",");
        if (PythonEngine.isString(path))
            sb.append("\"path\":\"")
                    .append(path.asString())
                    .append("\",");
        if (PythonEngine.isString(basePath))
            sb.append("\"basePath\":\"")
                    .append(basePath.asString())
                    .append("\",");
        if (!PythonEngine.isNone(body))
            sb.append("\"body\":")
                    .append(makeBody(body))
                    .append(",");
        if (PythonEngine.isDict(params)) {
            sb.append("\"params\":");
            var paramMap = new HashMap<String, List<String>>();
            PythonEngine.forEachHashEntry(params, (k, v) -> {
                if (v.hasArrayElements()) {
                    var list = new ArrayList<String>();
                    for (long i = 0; i < v.getArraySize(); i++)
                        list.add(PythonEngine.asString(v.getArrayElement(i)));
                    paramMap.put(PythonEngine.asString(k), list);
                }
            });
            try {
                sb.append(objectMapper.writeValueAsString(paramMap));
            } catch (JsonProcessingException ignored) {}
            sb.append(",");
        }
        var proxy = new HashMap<String, String>();
        if (PythonEngine.isString(proxyType))
            proxy.put("type", proxyType.asString());
        if (PythonEngine.isString(proxyHost))
            proxy.put("host", proxyHost.asString());
        if (PythonEngine.isInteger(proxyPort))
            proxy.put("port", Integer.toString(PythonEngine.asInt(proxyPort)));
        if (PythonEngine.isString(proxyUsername))
            proxy.put("username", proxyUsername.asString());
        if (PythonEngine.isString(proxyPassword))
            proxy.put("password", proxyPassword.asString());
        if (!proxy.isEmpty()) {
            sb.append("\"proxy\":");
            try {
                sb.append(objectMapper.writeValueAsString(proxy));
            } catch (JsonProcessingException ignored) {}
            sb.append(",");
        }
        setHeaders(headers, sb);
        if (sb.length() > 0) sb.setLength(sb.length() - 1);
        sb.insert(0, "{");
        sb.append("}");
        return sb.toString();
    }
}
