package com.sds.communicators.cluster.support;

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
                // JDK default is NEVER, and call() treats 3xx as failure - follow them instead,
                // so nodes behind a redirecting proxy/ingress keep working
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .build();
    }

    public <T> T call(String uri, String method, Object body, JavaType responseType) {
        return call(uri, method, body, responseType, Map.of());
    }

    /** a non-2xx answer becomes a {@link CallException} carrying the response body; responseType null ignores the body */
    public <T> T call(String uri, String method, Object body, JavaType responseType, Map<String, String> headers) {
        HttpRequest.BodyPublisher publisher;
        try {
            publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new CallException(method, uri, "request encoding failed::" + e.getMessage(), e);
        }
        String responseBody = exchange(uri, method, publisher, headers);
        if (responseType == null)
            return null;
        try {
            return objectMapper.readValue(responseBody, responseType);
        } catch (IOException e) {
            throw new CallException(method, uri, "response decoding failed::" + e.getMessage(), e);
        }
    }

    /** sends an already-serialized JSON body as-is and ignores the response body */
    public void callRaw(String uri, String method, String body) {
        exchange(uri, method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8), Map.of());
    }

    private String exchange(String uri, String method, HttpRequest.BodyPublisher publisher, Map<String, String> headers) {
        HttpRequest request;
        try {
            var builder = HttpRequest.newBuilder(URI.create(uri))
                    .timeout(readTimeout)
                    .header("Content-Type", "application/json");
            headers.forEach(builder::header);
            request = builder.method(method, publisher).build();
        } catch (Exception e) {
            throw new CallException(method, uri, "request encoding failed::" + e.getMessage(), e);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CallException(method, uri, "interrupted", e);
        } catch (IOException e) {
            throw new CallException(method, uri, "request failed::" +
                    (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
        }

        if (response.statusCode() / 100 != 2)
            throw new CallException(method, uri, response.body().isEmpty()
                    ? "failed, status " + response.statusCode() : response.body(), null);
        return response.body();
    }

    /**
     * The message carries the target URI for logs; {@link #getReason()} is the part safe to
     * surface to API callers - the peer's response body or a short cause, without internal
     * node URLs.
     */
    public static final class CallException extends IllegalStateException {
        private final String reason;

        CallException(String method, String uri, String reason, Throwable cause) {
            super(method + " " + uri + " -> " + reason, cause);
            this.reason = reason;
        }

        public String getReason() {
            return reason;
        }
    }

    /**
     * Bounded shutdown: a plain {@code close()} waits for in-flight exchanges, which against a
     * hung peer means up to the full read timeout - so give them one second, then force.
     */
    public void dispose() {
        httpClient.shutdown();
        try {
            if (!httpClient.awaitTermination(Duration.ofSeconds(1)))
                httpClient.shutdownNow();
        } catch (InterruptedException e) {
            httpClient.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
