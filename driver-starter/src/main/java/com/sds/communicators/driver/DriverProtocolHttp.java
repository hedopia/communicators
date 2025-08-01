package com.sds.communicators.driver;

import com.sds.communicators.common.struct.Response;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import lombok.extern.slf4j.Slf4j;
import org.python.core.PyFunction;
import org.python.core.PyObject;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
abstract class DriverProtocolHttp extends DriverProtocol {
    protected SslContext sslContext;
    protected PyFunction jsonLoads;
    @Override
    void initialize(String connectionInfo, Map<String, String> option) throws Exception {
        // cert, key, trustCert -> need base64 encoded
        var cert = option.get("cert") != null ? new ByteArrayInputStream(Base64.getDecoder().decode(option.get("cert"))) : null;
        var format = option.get("format");
        var password = option.get("password");
        var key = option.get("key") != null ? new ByteArrayInputStream(Base64.getDecoder().decode(option.get("key"))) : null;

        var trustCert = option.get("trustCert") != null ? new ByteArrayInputStream(Base64.getDecoder().decode(option.get("trustCert"))) : null;
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

        driverCommand.pythonInterpreter.exec("from json import loads as json_loads");
        jsonLoads = (PyFunction)driverCommand.pythonInterpreter.get("json_loads");
        if (jsonLoads == null) throw new Exception("json loads function is not loaded");
    }

    protected abstract SslContextBuilder getSslContextBuilder(InputStream keyCertChainInputStream, InputStream keyInputStream, String keyPassword);
    protected abstract SslContextBuilder getSslContextBuilder(KeyManagerFactory keyManagerFactory);
    protected abstract SslContextBuilder getTrustSslContextBuilder(SslContextBuilder sslContextBuilder);


}
