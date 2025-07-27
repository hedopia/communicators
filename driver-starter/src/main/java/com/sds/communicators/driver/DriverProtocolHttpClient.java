package com.sds.communicators.driver;

import com.google.common.base.Strings;
import com.sds.communicators.common.UtilFunc;
import com.sds.communicators.common.struct.Response;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.ssl.SslContextBuilder;
import lombok.extern.slf4j.Slf4j;
import org.python.core.PyFunction;
import org.python.core.PyObject;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.KeyManagerFactory;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

@Slf4j
public class DriverProtocolHttpClient extends DriverProtocolHttp {
    @Override
    void initialize(String connectionInfo, Map<String, String> option) throws Exception {
        super.initialize(connectionInfo, option);
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
    List<Response> requestCommand(String cmdId, String requestInfo, int timeout, boolean isReadCommand, PyFunction function, PyObject initialValue) throws Exception {
//        var t = HttpClient.create()
//                .headers(headers -> request.headers().asHttpHeaders().forEach(headers::add))
//                .request(HttpMethod.valueOf(method.name()))
//                .uri(UriComponentsBuilder.fromHttpUrl(path).queryParams(request.queryParams()).toUriString())
//                .send(ByteBufFlux.fromInbound(request.bodyToMono(byte[].class)))
//                .responseSingle((response, byteBufMono) ->
//                        byteBufMono.asByteArray().map(body ->
//                                ServerResponse.status(response.status().code())
//                                        .headers(httpHeaders -> response.responseHeaders())
//                                        .body(BodyInserters.fromValue(body))
//                        ))
//                .flatMap(response -> response);
//        t.block();
        return List.of();
    }

    SslContextBuilder getSslContextBuilder(InputStream keyCertChainInputStream, InputStream keyInputStream, String keyPassword) {
        return SslContextBuilder.forClient().keyManager(keyCertChainInputStream, keyInputStream, keyPassword);
    }

    SslContextBuilder getSslContextBuilder(KeyManagerFactory keyManagerFactory) {
        return SslContextBuilder.forClient().keyManager(keyManagerFactory);
    }

    SslContextBuilder getTrustSslContextBuilder(SslContextBuilder sslContextBuilder) {
        return sslContextBuilder;
    }
}
