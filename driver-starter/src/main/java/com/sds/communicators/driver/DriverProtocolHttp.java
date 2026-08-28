package com.sds.communicators.driver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.sds.communicators.driver.support.PythonEngine;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Value;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
abstract class DriverProtocolHttp extends DriverProtocol {
    protected SslContext sslContext;
    protected boolean useByteArrayBody = false;

    @Override
    void initialize(String connectionInfo, Map<String, String> option) throws Exception {
        var cert = option.get("cert") != null ? new FileInputStream(option.get("cert")) : null;
        var format = option.get("format");
        var password = option.get("password");
        var key = option.get("key") != null ? new FileInputStream(option.get("key")) : null;

        var trustCert = option.get("trustCert") != null ? new FileInputStream(option.get("trustCert")) : null;
        var trustFormat = option.get("trustFormat");
        var trustPassword = option.get("trustPassword");
        if (cert != null) {
            SslContextBuilder sslContextBuilder;
            if (key != null) {
                log.debug("[{}] create PEM format ssl context", deviceId);
                sslContextBuilder = getSslContextBuilder(cert, key, password);
            } else {
                if (format == null) format = "PKCS12";
                log.debug("[{}] create {} format ssl context", deviceId, format);
                var ks = KeyStore.getInstance(format);
                ks.load(cert, password != null ? password.toCharArray() : null);
                var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, password != null ? password.toCharArray() : null);
                sslContextBuilder = getSslContextBuilder(kmf);
            }

            if (trustCert != null) {
                if (trustFormat == null && trustPassword == null) {
                    log.debug("[{}] create PEM format mTLS ssl context", deviceId);
                    sslContextBuilder.trustManager(trustCert);
                } else {
                    if (trustFormat == null) trustFormat = "PKCS12";
                    log.debug("[{}] create {} format mTLS ssl context", deviceId, trustFormat);
                    var ks = KeyStore.getInstance(trustFormat);
                    ks.load(trustCert, trustPassword != null ? trustPassword.toCharArray() : null);
                    var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                    tmf.init(ks);
                    sslContextBuilder.trustManager(tmf);
                }
                sslContext = getTrustSslContextBuilder(sslContextBuilder).build();
            } else {
                sslContext = sslContextBuilder.build();
            }
        } else {
            sslContext = null;
        }
        useByteArrayBody = Boolean.parseBoolean(option.get("useByteArrayBody"));
    }

    protected Value getPyHeaders(HttpHeaders headers) {
        var headerMap = new HashMap<String, List<String>>();
        headers.forEach(entry ->
                headerMap.compute(entry.getKey(), (k, v) -> v == null ? new ArrayList<>() : v)
                        .add(entry.getValue()));
        var pyHeaders = driverCommand.pythonEngine.newDict();
        headerMap.forEach((k, v) -> pyHeaders.putHashEntry(k, driverCommand.pythonEngine.toPyList(v)));
        return pyHeaders;
    }

    protected void setHeaders(Value[] headers, StringBuilder sb) {
        var headerMap = new HashMap<String, List<String>>();
        for (int i = 0; i < headers.length - 1; i += 2) {
            var key = PythonEngine.asString(headers[i]);
            var value = PythonEngine.asString(headers[i + 1]);
            headerMap.compute(key, (k, v) -> v == null ? new ArrayList<>() : v).add(value);
        }
        if (!headerMap.isEmpty()) {
            sb.append("\"headers\":");
            try {
                sb.append(objectMapper.writeValueAsString(headerMap));
            } catch (JsonProcessingException ignored) {}
            sb.append(",");
        }
    }

    protected String makeBody(Value body) {
        String ret = "\"\"";
        if (PythonEngine.isString(body)) {
            try {
                ret = objectMapper.writeValueAsString(body.asString());
            } catch (JsonProcessingException ignored) {
            }
        } else {
            try {
                Object javaBody = body.hasArrayElements() ? body.as(List.class) : body.toString();
                ret = "\"" + objectMapper.writeValueAsString(javaBody) + "\"";
            } catch (JsonProcessingException e) {
                ret = "\"" + body + "\"";
            }
        }
        return ret;
    }

    protected abstract SslContextBuilder getSslContextBuilder(InputStream keyCertChainInputStream, InputStream keyInputStream, String keyPassword);
    protected abstract SslContextBuilder getSslContextBuilder(KeyManagerFactory keyManagerFactory);
    protected abstract SslContextBuilder getTrustSslContextBuilder(SslContextBuilder sslContextBuilder);
}
