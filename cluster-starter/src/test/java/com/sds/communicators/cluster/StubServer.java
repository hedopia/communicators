package com.sds.communicators.cluster;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Loopback HTTP server with a fixed answer per path prefix, including prefixes that never answer until closed. */
final class StubServer implements AutoCloseable {
    private static final String HOST = "127.0.0.1";

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        var thread = new Thread(runnable, "stub-http");
        thread.setDaemon(true);
        return thread;
    });
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> uris = new CopyOnWriteArrayList<>();
    private final Map<String, Set<Integer>> clientPorts = new ConcurrentHashMap<>();

    StubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(HOST, 0), 0);
        // a hung handler must not block the other contexts
        server.setExecutor(executor);
        server.start();
    }

    static int freePort() throws IOException {
        try (var socket = new ServerSocket(0, 0, InetAddress.getByName(HOST))) {
            return socket.getLocalPort();
        }
    }

    String url(String path) {
        return "http://" + HOST + ":" + server.getAddress().getPort() + path;
    }

    StubServer respond(String prefix, int status, String body) {
        return respond(prefix, 0, status, body);
    }

    /** answers after delayMillis; every request is recorded as "path body" */
    StubServer respond(String prefix, long delayMillis, int status, String body) {
        return respond(prefix, () -> delayMillis, status, body);
    }

    /** answers after the delay read when the request arrives, so a test can change it meanwhile */
    StubServer respond(String prefix, LongSupplier delay, int status, String body) {
        return respond(prefix, delay, () -> status, body);
    }

    /** answers with the status read when the request arrives, after the delay read then */
    StubServer respond(String prefix, LongSupplier delay, IntSupplier statusSupplier, String body) {
        return respond(prefix, delay, statusSupplier, () -> body);
    }

    /** answers with the status and the body read when the request arrives, after the delay read then */
    StubServer respond(String prefix, LongSupplier delay, IntSupplier statusSupplier, Supplier<String> bodySupplier) {
        server.createContext(prefix, exchange -> {
            String body = bodySupplier.get();
            int status = statusSupplier.getAsInt();
            clientPorts.computeIfAbsent(prefix, key -> ConcurrentHashMap.newKeySet()).add(exchange.getRemoteAddress().getPort());
            requests.add(exchange.getRequestURI().getPath() + " " +
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            uris.add(exchange.getRequestURI().getRawPath() + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery()));
            long delayMillis = delay.getAsLong();
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException ignored) {
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0)
                exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        return this;
    }

    /** accepts the connection and the request, but never answers (until {@link #close()}) */
    StubServer hang(String prefix) {
        server.createContext(prefix, exchange -> {
            try {
                release.await();
            } catch (InterruptedException ignored) {
            }
            exchange.close();
        });
        return this;
    }

    /** recorded "path body" of the answered requests whose path contains pathPart */
    List<String> requests(String pathPart) {
        return requests.stream().filter(request -> request.substring(0, request.indexOf(' ')).contains(pathPart)).toList();
    }

    /** recorded "path?query" of the answered requests whose path contains pathPart, in the order they came */
    List<String> uris(String pathPart) {
        return uris.stream().filter(uri -> (uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri).contains(pathPart)).toList();
    }

    /** distinct client connections (told apart by client port) that sent a request under prefix */
    int connections(String prefix) {
        return clientPorts.getOrDefault(prefix, Set.of()).size();
    }

    @Override
    public void close() {
        release.countDown();
        server.stop(0);
        executor.shutdownNow();
    }
}
