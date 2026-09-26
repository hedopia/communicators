package com.sds.communicators.driver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sds.communicators.common.struct.Command;
import com.sds.communicators.common.struct.Device;
import com.sds.communicators.common.type.CommandType;
import io.netty.channel.Channel;
import io.netty.util.concurrent.DefaultPromise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.netty.DisposableServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HttpServerProtocolTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private DriverProtocolHttpServer protocol;

    @AfterEach
    void stop() throws Exception {
        client.close();
        if (protocol != null) {
            protocol.requestDisconnect();
            protocol.driverCommand.pythonEngine.close();
        }
    }

    private int start() throws Exception {
        var reply = new Command();
        reply.setId("reply");
        reply.setType(CommandType.WRITE_REQUEST);
        reply.setCmdScript("""
                def requestInfo(method, path, body):
                    thread = java.type('java.lang.Thread').currentThread().getName()
                    return protocol.requestInfo(201, {'thread': thread, 'path': path, 'body': body}, 'Content-Type', 'application/json')
                """);
        var device = new Device();
        device.setId("httpserver");
        device.setConnectionUrl("http-server://127.0.0.1:0");
        device.setCommands(Set.of(reply));
        protocol = (DriverProtocolHttpServer) new DriverProtocolHttpServer().create(null, "", device);
        protocol.requestConnect();
        return this.<DisposableServer>field(protocol, DriverProtocolHttpServer.class, "disposableServer").port();
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private <T> T field(Object target, Class<?> owner, String name) throws Exception {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private int closeListenerCount(Channel channel) throws Exception {
        var closeFuture = channel.closeFuture();
        synchronized (closeFuture) {
            int count = field(closeFuture, DefaultPromise.class, "listener") != null ? 1 : 0;
            Object listeners = field(closeFuture, DefaultPromise.class, "listeners");
            if (listeners != null)
                count += this.<Integer>field(listeners, listeners.getClass(), "size");
            return count;
        }
    }

    @Test
    void commandScriptsRunOffTheEventLoopAndStillAnswerTheRequest() throws Exception {
        int port = start();
        var response = post(port, "/echo?site=A1", "hello");
        assertEquals(201, response.statusCode());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(null));
        var body = json.readTree(response.body());
        assertEquals("/echo", body.get("path").asText());
        assertEquals("hello", body.get("body").asText());
        var thread = body.get("thread").asText();
        assertFalse(thread.startsWith("reactor-http-"), "script ran on event loop thread " + thread);

        assertEquals(201, post(port, "/empty", "").statusCode());
    }

    @Test
    void keepAliveRequestsRegisterTheChannelOnlyOnce() throws Exception {
        int port = start();
        Set<Channel> channels = field(protocol, DriverProtocolHttpServer.class, "channels");
        assertEquals(201, post(port, "/first", "0").statusCode());
        assertEquals(1, channels.size());
        var channel = channels.iterator().next();
        int initialListeners = closeListenerCount(channel);

        for (int i = 1; i <= 200; i++)
            assertEquals(201, post(port, "/next", String.valueOf(i)).statusCode());

        assertEquals(Set.of(channel), channels, "keep-alive connection was not reused");
        assertTrue(channel.isActive());
        assertEquals(initialListeners, closeListenerCount(channel));

        channel.close().sync();
        for (int i = 0; i < 50 && !channels.isEmpty(); i++)
            Thread.sleep(20);
        assertTrue(channels.isEmpty(), "closed channel was not unregistered");
    }
}
