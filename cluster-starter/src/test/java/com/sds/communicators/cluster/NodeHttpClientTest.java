package com.sds.communicators.cluster;

import com.sds.communicators.cluster.support.NodeHttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@link NodeHttpClient.CallException} tells failures a retry cannot fix from transient ones. */
class NodeHttpClientTest {
    private static StubServer stub;
    private static NodeHttpClient client;

    @BeforeAll
    static void setUp() throws IOException {
        stub = new StubServer()
                .respond("/ok", 200, "")
                .respond("/three", 200, "3")
                .respond("/bad-request", 400, "invalid request body")
                .respond("/not-found", 404, "")
                .respond("/unavailable", 503, "sync failed")
                .respond("/request-timeout", 408, "")
                .respond("/too-many", 429, "")
                .hang("/hang");
        client = new NodeHttpClient(1000, 60000);
    }

    @AfterAll
    static void tearDown() {
        stub.close();
        client.dispose();
    }

    @Test
    void successfulCallThrowsNothing() {
        assertNull(client.call(stub.url("/ok"), "POST", Map.of("a", 1), null));
    }

    @Test
    void clientErrorIsDeterministic() {
        var e = assertThrows(NodeHttpClient.CallException.class,
                () -> client.call(stub.url("/bad-request"), "POST", Map.of("a", 1), null));
        assertEquals(400, e.getStatusCode());
        assertFalse(e.isRequestEncodingFailure());
        assertTrue(e.isDeterministic());
        assertEquals("invalid request body", e.getReason());

        var notFound = assertThrows(NodeHttpClient.CallException.class,
                () -> client.call(stub.url("/not-found"), "GET", null, null));
        assertEquals(404, notFound.getStatusCode());
        assertTrue(notFound.isDeterministic());
    }

    @Test
    void serverErrorIsTransient() {
        var e = assertThrows(NodeHttpClient.CallException.class,
                () -> client.call(stub.url("/unavailable"), "POST", Map.of("a", 1), null));
        assertEquals(503, e.getStatusCode());
        assertFalse(e.isRequestEncodingFailure());
        assertFalse(e.isDeterministic());
    }

    @Test
    void retryableClientErrorsAreTransient() {
        for (var path : new String[]{"/request-timeout", "/too-many"}) {
            var e = assertThrows(NodeHttpClient.CallException.class,
                    () -> client.call(stub.url(path), "POST", Map.of("a", 1), null));
            assertFalse(e.isDeterministic(), path);
        }
    }

    @Test
    void unencodableRequestIsDeterministic() {
        var body = assertThrows(NodeHttpClient.CallException.class,
                () -> client.call(stub.url("/ok"), "POST", new Object(), null));
        assertTrue(body.isRequestEncodingFailure());
        assertEquals(-1, body.getStatusCode());
        assertTrue(body.isDeterministic());

        var uri = assertThrows(NodeHttpClient.CallException.class,
                () -> client.call("http://bad host/", "GET", null, null));
        assertTrue(uri.isRequestEncodingFailure());
        assertTrue(uri.isDeterministic());
    }

    @Test
    void noResponseIsTransient() throws IOException {
        int closedPort = StubServer.freePort();
        var e = assertThrows(NodeHttpClient.CallException.class,
                () -> client.call("http://127.0.0.1:" + closedPort + "/x", "GET", null, null));
        assertEquals(-1, e.getStatusCode());
        assertFalse(e.isRequestEncodingFailure());
        assertFalse(e.isDeterministic());
    }

    @Test
    void optionalAnswerWithoutABodyIsNull() {
        var integer = com.fasterxml.jackson.databind.type.TypeFactory.defaultInstance().constructType(Integer.class);
        // a peer of an older version answers nothing
        assertNull(client.callOptional(stub.url("/ok"), "PUT", Map.of("a", 1), integer));
        assertEquals(3, (Integer) client.callOptional(stub.url("/three"), "PUT", Map.of("a", 1), integer));
        var e = assertThrows(NodeHttpClient.CallException.class,
                () -> client.callOptional(stub.url("/bad-request"), "PUT", Map.of("a", 1), integer));
        assertEquals(400, e.getStatusCode());
    }

    @Test
    void perCallTimeoutReplacesReadTimeout() {
        long begin = System.nanoTime();
        var e = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(NodeHttpClient.CallException.class,
                () -> client.call(stub.url("/hang"), "GET", null, null, Map.of(), Duration.ofMillis(300))));
        long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;
        assertTrue(elapsedMillis < 5000, "took " + elapsedMillis + " ms");
        assertEquals(-1, e.getStatusCode());
        assertFalse(e.isDeterministic());
    }
}
