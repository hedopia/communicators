package com.sds.communicators.driver;

import com.sds.communicators.common.struct.Device;
import org.eclipse.milo.opcua.sdk.client.DiscoveryClient;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.OpcUaClientConfig;
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaMonitoredItem;
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaSubscription;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.util.EndpointUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * opcua-client subscription recovery (a subscription the server no longer knows must lead to a reconnect)
 * and opcua-server nodes holding list values, against an in-process opcua-server on loopback
 */
class OpcuaLoopbackTest {
    private static Path pkiDir;

    private final List<DriverProtocol> protocols = new ArrayList<>();
    private OpcUaClient rawClient;

    @AfterEach
    void close() throws Exception {
        if (rawClient != null)
            rawClient.disconnectAsync().get(5, TimeUnit.SECONDS);
        // clients before the server, so their CloseSession is still answered
        for (var protocol : protocols.reversed()) {
            protocol.isSetDisconnected = true;
            try {
                protocol.requestDisconnect();
            } catch (Exception ignored) {
            }
            protocol.driverCommand.pythonEngine.close();
        }
    }

    @BeforeAll
    static void createPkiDir() throws Exception {
        pkiDir = Files.createTempDirectory("opcua-test-pki");
    }

    /**
     * best effort (not @TempDir): Milo's KeyStoreCertificateStore never closes the stream it loads identity.pfx with,
     * so on Windows the file stays locked until that stream is garbage collected
     */
    @AfterAll
    static void deletePkiDir() throws Exception {
        for (int i = 0; i < 20 && Files.exists(pkiDir); i++) {
            System.gc();
            Thread.sleep(100);
            try (var paths = Files.walk(pkiDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (Exception ignored) {
            }
        }
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private <T extends DriverProtocol> T create(T protocol, String id, String connectionUrl, Map<String, Object> data) throws Exception {
        var device = new Device();
        device.setId(id);
        device.setConnectionUrl(connectionUrl);
        device.setSocketTimeout(5000);
        device.setData(data);
        protocol.create(null, "", device);
        protocols.add(protocol);
        return protocol;
    }

    private DriverProtocolOpcuaServer startServer(int port, Map<String, Object> data) throws Exception {
        var server = create(new DriverProtocolOpcuaServer(), "srv",
                "opcua-server://127.0.0.1:" + port + "?pkiDir=" + encode(pkiDir.toString()), data);
        server.requestConnect();
        return server;
    }

    private DriverProtocolOpcuaClient connectClient(int port, String subscriptionNodeId) throws Exception {
        var client = create(new DriverProtocolOpcuaClient(), "cli", "opcua-client://127.0.0.1:" + port +
                (subscriptionNodeId == null ? "" : "?subscriptionNodeIds=" + encode(subscriptionNodeId)), Map.of());
        client.requestConnect();
        return client;
    }

    private static OpcUaSubscription subscriptionOf(DriverProtocolOpcuaClient client) throws Exception {
        var field = DriverProtocolOpcuaClient.class.getDeclaredField("subscription");
        field.setAccessible(true);
        return (OpcUaSubscription) field.get(client);
    }

    private static boolean waitFor(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        var deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) return false;
            Thread.sleep(20);
        }
        return true;
    }

    @Test
    void transferFailedSubscriptionTriggersConnectionLostUnlessDisconnecting() throws Exception {
        var port = freePort();
        startServer(port, Map.of("v", 1));
        var client = connectClient(port, "ns=2;s=srv/v");
        var subscription = subscriptionOf(client);
        assertNotNull(subscription);
        var transferFailed = new StatusCode(StatusCodes.Bad_SubscriptionIdInvalid);

        // Milo resets the subscription and delivers onTransferFailed on its delivery queue
        client.isSetDisconnected = true;
        subscription.notifyTransferFailed(transferFailed);
        assertFalse(waitFor(() -> client.isConnectionLostOccur, 1000), "disconnecting device must not reconnect");

        client.isSetDisconnected = false;
        subscription.notifyTransferFailed(transferFailed);
        assertTrue(waitFor(() -> client.isConnectionLostOccur, 5000), "transfer failure must trigger connection-lost");
    }

    @Test
    void serverRestartTriggersConnectionLost() throws Exception {
        var port = freePort();
        var server = startServer(port, Map.of("v", 1));
        var client = connectClient(port, "ns=2;s=srv/v");

        server.requestDisconnect();
        server.requestConnect();

        // Milo re-establishes the session, TransferSubscriptions fails on the new server instance
        assertTrue(waitFor(() -> client.isConnectionLostOccur, 30000), "subscription lost by server restart must trigger connection-lost");
    }

    @Test
    void clientWritesListValues() throws Exception {
        var port = freePort();
        var server = startServer(port, Map.of("ints", List.of(0), "mixed", List.of()));
        var client = connectClient(port, null);

        client.requestCommand("write", "{\"ns=2;s=srv/ints\": [7, 8], \"ns=2;s=srv/mixed\": [\"a\", 1]}",
                5000, false, null, null, null);
        assertEquals(List.of(7, 8), server.read("ints"));
        assertEquals(List.of("a", 1), server.read("mixed"));

        client.requestCommand("write", "[{\"nodeId\": \"ns=2;s=srv/ints\", \"value\": [1, 2], \"type\": \"Int16\"}]",
                5000, false, null, null, null);
        assertEquals(List.of((short) 1, (short) 2), server.read("ints"));
        assertFalse(client.isConnectionLostOccur);
    }

    @Test
    void serverNodesWithListValuesCanBeReadAndSubscribed() throws Exception {
        var port = freePort();
        var data = new LinkedHashMap<String, Object>();
        data.put("v", 1);
        data.put("ints", List.of(1, 2));
        data.put("strings", List.of("a", "b"));
        data.put("mixed", List.of("a", 1));
        var server = startServer(port, data);
        var engine = server.driverCommand.pythonEngine;
        engine.exec("scripted = [1, 2.5]");
        server.write("scripted", engine.get("scripted"));

        var endpoint = DiscoveryClient.getEndpoints("opc.tcp://127.0.0.1:" + port).get(5, TimeUnit.SECONDS).stream()
                .filter(e -> SecurityPolicy.None.getUri().equals(e.getSecurityPolicyUri()))
                .findFirst()
                .map(e -> EndpointUtil.updateUrl(e, "127.0.0.1", port))
                .orElseThrow();
        rawClient = OpcUaClient.create(OpcUaClientConfig.builder()
                .setEndpoint(endpoint)
                .setApplicationUri("urn:sds:communicators:test")
                .build());
        rawClient.connectAsync().get(5, TimeUnit.SECONDS);
        var nodeIds = List.of(
                NodeId.parse("ns=2;s=srv/ints"),
                NodeId.parse("ns=2;s=srv/strings"),
                NodeId.parse("ns=2;s=srv/mixed"),
                NodeId.parse("ns=2;s=srv/scripted"));
        var values = rawClient.readValuesAsync(0.0, TimestampsToReturn.Both, nodeIds).get(5, TimeUnit.SECONDS);
        var javaValues = new ArrayList<>();
        for (var value : values) {
            assertTrue(value.getStatusCode() == null || value.getStatusCode().isGood(), value.toString());
            javaValues.add(server.variantToJava(value.getValue()));
        }
        assertEquals(List.of(List.of(1, 2), List.of("a", "b"), List.of("a", 1), List.of(1.0, 2.5)), javaValues);

        // a subscription containing list nodes delivers their values (publish response is encodable)
        var received = new LinkedBlockingQueue<Object>();
        var subscription = new OpcUaSubscription(rawClient, 100.0);
        subscription.setSubscriptionListener(new OpcUaSubscription.SubscriptionListener() {
            @Override
            public void onDataReceived(OpcUaSubscription subscription, List<OpcUaMonitoredItem> items, List<DataValue> values) {
                values.forEach(value -> received.add(server.variantToJava(value.getValue())));
            }
        });
        subscription.create();
        subscription.addMonitoredItems(List.of(
                OpcUaMonitoredItem.newDataItem(NodeId.parse("ns=2;s=srv/v")),
                OpcUaMonitoredItem.newDataItem(NodeId.parse("ns=2;s=srv/mixed"))));
        subscription.createMonitoredItems().forEach(result -> assertTrue(result.isGood(), result.toString()));
        var first = received.poll(5, TimeUnit.SECONDS);
        var second = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(second, "initial values of the subscribed nodes must be delivered, got " + first);
        assertTrue(List.of(first, second).contains(List.of("a", 1)), first + ", " + second);
    }
}
