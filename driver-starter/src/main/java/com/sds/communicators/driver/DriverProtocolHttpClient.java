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
import org.javatuples.Quartet;
import org.python.core.*;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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
    List<Response> requestCommand(String cmdId, String requestInfo, int timeout, boolean isReadCommand, PyFunction function, PyObject initialValue, Object nonPeriodicObject) throws Exception {
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
        var rcvBody = useByteArrayBody ? new PyList(Arrays.asList(UtilFunc.arrayWrapper(response.getValue0()))) :
                stringToPyObject(new String(response.getValue0(), StandardCharsets.UTF_8));
        log.trace("[{}] response received, body={}, headers={}", deviceId, rcvBody, headers);
        PyObject[] received = new PyObject[] {new PyInteger(response.getValue2()), rcvBody, headers};
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

    public String requestInfo(PyObject method, PyObject path, PyObject basePath, PyObject body, PyObject params, PyObject... headers) {
        return requestInfo(method, path, basePath, body, params, null, null, null, null, null, headers);
    }

    public String requestInfo(PyObject method, PyObject path, PyObject basePath, PyObject body, PyObject params, PyObject proxyHost, PyObject proxyPort, PyObject... headers) {
        return requestInfo(method, path, basePath, body, params, null, proxyHost, proxyPort, null, null, headers);
    }
    public String requestInfo(PyObject method, PyObject path, PyObject basePath, PyObject body, PyObject params,
                              PyObject proxyType, PyObject proxyHost, PyObject proxyPort, PyObject proxyUsername, PyObject proxyPassword,
                              PyObject... headers) {
        var sb = new StringBuilder();
        if (method instanceof PyString)
            sb.append("\"method\":\"")
                    .append(method)
                    .append("\",");
        if (path instanceof PyString)
            sb.append("\"path\":\"")
                    .append(path)
                    .append("\",");
        if (basePath instanceof PyString)
            sb.append("\"basePath\":\"")
                    .append(basePath)
                    .append("\",");
        if (!(body instanceof PyNone))
            sb.append("\"body\":")
                    .append(makeBody(body))
                    .append(",");
        if (params instanceof PyDictionary) {
            sb.append("\"params\":");
            var paramMap = new HashMap<String, List<String>>();
            ((PyDictionary) params).forEach((k,v) -> {
                if (v instanceof PyList)
                    paramMap.put(k.toString(), (List<String>) ((PyList) v).stream().map(Object::toString).collect(Collectors.toList()));
            });
            try {
                sb.append(objectMapper.writeValueAsString(paramMap));
            } catch (JsonProcessingException ignored) {}
            sb.append(",");
        }
        var proxy = new HashMap<String, String>();
        if (proxyType instanceof PyString)
            proxy.put("type", proxyType.toString());
        if (proxyHost instanceof PyString)
            proxy.put("host", proxyHost.toString());
        if (proxyPort instanceof PyInteger)
            proxy.put("port", proxyPort.toString());
        if (proxyUsername instanceof PyString)
            proxy.put("username", proxyUsername.toString());
        if (proxyPassword instanceof PyString)
            proxy.put("password", proxyPassword.toString());
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
