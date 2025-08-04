package com.sds.communicators.driver;

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
import org.javatuples.Triplet;
import org.python.core.PyFunction;
import org.python.core.PyList;
import org.python.core.PyObject;
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
import java.util.Arrays;
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
        log.info("[{}] http client is not support connect", deviceId);
    }

    @Override
    void requestDisconnect() throws Exception {
        log.info("[{}] http client is not support disconnect", deviceId);
    }

    @Override
    List<Response> requestCommand(String cmdId, String requestInfo, int timeout, boolean isReadCommand, PyFunction function, PyObject initialValue, Object nonPeriodicObject) throws Exception {
        var info = objectMapper.readValue(requestInfo, RequestInfo.class);
        var method = HttpMethod.valueOf(info.method == null ? "GET" : info.method);
        var path = (Strings.isNullOrEmpty(info.basePath) ? basePath : info.basePath) + (Strings.isNullOrEmpty(info.path) ? "" : info.path);
        var sb = new StringBuilder(path);
        if (info.params != null) {
            sb.append("?");
            info.params.forEach((key, value) -> {
                sb.append(URLEncoder.encode(key, StandardCharsets.UTF_8));
                sb.append("=");
                sb.append(URLEncoder.encode(value, StandardCharsets.UTF_8));
                sb.append("&");
            });
            sb.setLength(sb.length() - 1);
        }
        var uri = URI.create(sb.toString());
        var body = Strings.isNullOrEmpty(info.body) ? new byte[]{} : UtilFunc.stringToByteArray(info.body);
        AtomicReference<Triplet<byte[], HttpHeaders, Integer>> reference = new AtomicReference<>(null);
        AtomicReference<Exception> exception = new AtomicReference<>(null);
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
                        .responseSingle((httpClientResponse, byteBufMono) ->
                                byteBufMono.asByteArray().map(byteArray ->
                                        new Triplet<>(byteArray, httpClientResponse.responseHeaders(), httpClientResponse.status().code())))
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
        PyObject[] received;
        new PyList(Arrays.asList(UtilFunc.arrayWrapper(response.getValue0())))
        return List.of();
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
        Map<String, List<String>> headers;
        String body;
        String method;
        String basePath;
        String path;
        Map<String, String> params;
        Map<String, String> proxy;
    }
}
