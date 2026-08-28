package com.sds.communicators.cluster.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Shared HTTP client for node-to-node calls, with Jackson JSON bodies. */
public final class NodeHttpClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Duration readTimeout;

    public NodeHttpClient(int connectTimeoutMillis, int readTimeoutMillis) {
        this.readTimeout = Duration.ofMillis(readTimeoutMillis);
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .build();
    }

    public JavaType type(Class<?> clazz) {
        return objectMapper.constructType(clazz);
    }

    public JavaType type(TypeReference<?> typeReference) {
        return objectMapper.getTypeFactory().constructType(typeReference);
    }

    public <T> T call(String uri, String method, Object body, JavaType responseType) {
        return call(uri, method, body, responseType, Map.of());
    }

    /** a non-2xx answer becomes an exception carrying the response body; responseType null ignores the body */
    public <T> T call(String uri, String method, Object body, JavaType responseType, Map<String, String> headers) {
        HttpRequest request;
        try {
            var publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8);
            var builder = HttpRequest.newBuilder(URI.create(uri))
                    .timeout(readTimeout)
                    .header("Content-Type", "application/json");
            headers.forEach(builder::header);
            request = builder.method(method, publisher).build();
        } catch (Exception e) {
            throw new IllegalStateException(method + " " + uri + " request encoding failed::" + e.getMessage(), e);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(method + " " + uri + " interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException(method + " " + uri + " failed::" + e.getMessage(), e);
        }

        if (response.statusCode() / 100 != 2)
            throw new IllegalStateException(method + " " + uri +
                    " failed, status " + response.statusCode() + "::" + response.body());

        if (responseType == null)
            return null;
        try {
            return objectMapper.readValue(response.body(), responseType);
        } catch (IOException e) {
            throw new IllegalStateException(method + " " + uri + " response decoding failed::" + e.getMessage(), e);
        }
    }

    public void dispose() {
        httpClient.close();
    }
}
