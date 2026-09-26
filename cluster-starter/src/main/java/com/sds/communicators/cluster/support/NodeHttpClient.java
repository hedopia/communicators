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
        return call(uri, method, body, responseType, headers, readTimeout);
    }

    /** same as above, but with its own response timeout instead of the client-wide read timeout */
    public <T> T call(String uri, String method, Object body, JavaType responseType, Map<String, String> headers, Duration timeout) {
        var response = exchange(uri, method, publisher(uri, method, body), headers, timeout);
        if (responseType == null)
            return null;
        return decode(uri, method, response, responseType);
    }

    /**
     * same as {@link #call(String, String, Object, JavaType)}, but an answer without a body gives null instead of a
     * decoding failure (a peer of an older version answers such a request with nothing)
     */
    public <T> T callOptional(String uri, String method, Object body, JavaType responseType) {
        var response = exchange(uri, method, publisher(uri, method, body), Map.of(), readTimeout);
        if (response.body().isBlank())
            return null;
        return decode(uri, method, response, responseType);
    }

    private HttpRequest.BodyPublisher publisher(String uri, String method, Object body) {
        try {
            return body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new CallException(method, uri, "request encoding failed::" + e.getMessage(), e, -1, true);
        }
    }

    private <T> T decode(String uri, String method, HttpResponse<String> response, JavaType responseType) {
        try {
            return objectMapper.readValue(response.body(), responseType);
        } catch (IOException e) {
            throw new CallException(method, uri, "response decoding failed::" + e.getMessage(), e, response.statusCode(), false);
        }
    }

    /** sends an already-serialized JSON body as-is and ignores the response body */
    public void callRaw(String uri, String method, String body) {
        exchange(uri, method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8), Map.of(), readTimeout);
    }

    private HttpResponse<String> exchange(String uri, String method, HttpRequest.BodyPublisher publisher,
                                          Map<String, String> headers, Duration timeout) {
        HttpRequest request;
        try {
            var builder = HttpRequest.newBuilder(URI.create(uri))
                    .timeout(timeout)
                    .header("Content-Type", "application/json");
            headers.forEach(builder::header);
            request = builder.method(method, publisher).build();
        } catch (Exception e) {
            throw new CallException(method, uri, "request encoding failed::" + e.getMessage(), e, -1, true);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CallException(method, uri, "interrupted", e, -1, false);
        } catch (IOException e) {
            throw new CallException(method, uri, "request failed::" +
                    (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e, -1, false);
        }

        if (response.statusCode() / 100 != 2)
            throw new CallException(method, uri, response.body().isEmpty()
                    ? "failed, status " + response.statusCode() : response.body(), null, response.statusCode(), false);
        return response;
    }

    /**
     * The message carries the target URI for logs; {@link #getReason()} is the part safe to
     * surface to API callers - the peer's response body or a short cause, without internal
     * node URLs.
     */
    public static final class CallException extends IllegalStateException {
        private final String reason;
        private final int statusCode;
        private final boolean requestEncodingFailure;

        CallException(String method, String uri, String reason, Throwable cause, int statusCode, boolean requestEncodingFailure) {
            super(method + " " + uri + " -> " + reason, cause);
            this.reason = reason;
            this.statusCode = statusCode;
            this.requestEncodingFailure = requestEncodingFailure;
        }

        public String getReason() {
            return reason;
        }

        /** HTTP status of the peer's answer, -1 when no response was received */
        public int getStatusCode() {
            return statusCode;
        }

        /** the request could not be built (body not serializable, invalid URI or header), so nothing was sent */
        public boolean isRequestEncodingFailure() {
            return requestEncodingFailure;
        }

        /** the same request would fail the same way again, so retrying it is pointless (408/425/429 are transient) */
        public boolean isDeterministic() {
            return requestEncodingFailure || (statusCode >= 400 && statusCode < 500
                    && statusCode != 408 && statusCode != 425 && statusCode != 429);
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
