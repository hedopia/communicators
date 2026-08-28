package com.sds.communicators.driver;

import com.google.common.base.Strings;
import com.sds.communicators.common.struct.Response;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.milo.opcua.sdk.client.DiscoveryClient;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.OpcUaClientConfig;
import org.eclipse.milo.opcua.sdk.client.identity.AnonymousProvider;
import org.eclipse.milo.opcua.sdk.client.identity.IdentityProvider;
import org.eclipse.milo.opcua.sdk.client.identity.UsernameProvider;
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaMonitoredItem;
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaSubscription;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.util.EndpointUtil;
import org.graalvm.polyglot.Value;

import java.net.ConnectException;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

@Slf4j
public class DriverProtocolOpcuaClient extends DriverProtocolOpcua {
    private OpcUaClient client = null;
    private OpcUaSubscription subscription = null;

    private String endpointUrl;
    private SecurityPolicy securityPolicy = SecurityPolicy.None;
    private MessageSecurityMode securityMode = MessageSecurityMode.None;
    private IdentityProvider identityProvider = new AnonymousProvider();
    private final List<String> subscriptionNodeIds = new ArrayList<>();
    private double publishingInterval = 1000.0;
    private Path pkiDir;
    private String keyStorePassword;
    private OpcuaSecurityStore securityStore;

    @Override
    void initialize(String connectionInfo, Map<String, String> option) throws Exception {
        super.initialize(connectionInfo, option);
        endpointUrl = "opc.tcp://" + host + ":" + port + path;

        if (!Strings.isNullOrEmpty(option.get("securityPolicy")))
            securityPolicy = SecurityPolicy.valueOf(option.get("securityPolicy"));
        if (securityPolicy != SecurityPolicy.None)
            securityMode = "Sign".equals(option.get("securityMode")) ?
                    MessageSecurityMode.Sign : MessageSecurityMode.SignAndEncrypt;

        if (!Strings.isNullOrEmpty(option.get("username")))
            identityProvider = new UsernameProvider(option.get("username"), option.get("password"));

        if (!Strings.isNullOrEmpty(option.get("subscriptionNodeIds"))) {
            for (var nodeId : option.get("subscriptionNodeIds").split(","))
                subscriptionNodeIds.add(nodeId.trim());
        }
        if (!Strings.isNullOrEmpty(option.get("publishingInterval")))
            publishingInterval = Double.parseDouble(option.get("publishingInterval"));
        pkiDir = Path.of(option.getOrDefault("pkiDir", "pki/opcua/client/" + deviceId));
        keyStorePassword = option.get("keyStorePassword");
    }

    /**
     * retry interval for connection-refused during {@link #requestConnect()} [ms]
     */
    private static final long CONNECT_RETRY_INTERVAL = 500;

    @Override
    void requestConnect() throws Exception {
        log.info("[{}] endpoint={}, securityPolicy={}, socket-timeout={}", deviceId, endpointUrl, securityPolicy, socketTimeout);
        // total time is bounded by socketTimeout:
        // connection-refused (e.g. balanced-connect-all starts server/client devices in parallel,
        // so the server socket may not be bound yet) is retried internally at CONNECT_RETRY_INTERVAL,
        // any other failure fails immediately (framework-level retry takes over)
        var deadline = System.currentTimeMillis() + Math.max(socketTimeout, 1);
        var attempt = 0;
        while (true) {
            attempt++;
            try {
                connectOnce(deadline);
                return;
            } catch (Exception e) {
                closeClientQuietly();
                if (isConnectionRefused(e) && !isSetDisconnected &&
                        System.currentTimeMillis() + CONNECT_RETRY_INTERVAL < deadline) {
                    log.info("[{}] connection refused (attempt {}), retry within socket-timeout after {} [ms]",
                            deviceId, attempt, CONNECT_RETRY_INTERVAL);
                    Thread.sleep(CONNECT_RETRY_INTERVAL);
                } else if (e instanceof TimeoutException) {
                    throw new Exception("connect timeout, endpoint=" + endpointUrl + ", socket-timeout=" + socketTimeout + " [ms]");
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * single connect attempt, every wait is bounded by the remaining time to deadline
     * (the OpcUaClient.create(endpointUrl, ...) convenience method waits for endpoint discovery
     * without timeout, so discovery is performed explicitly here with a bounded get)
     */
    private void connectOnce(long deadline) throws Exception {
        var applicationUri = "urn:sds:communicators:driver:" + deviceId;
        var endpoints = DiscoveryClient.getEndpoints(endpointUrl).get(remaining(deadline), TimeUnit.MILLISECONDS);
        var endpoint = endpoints.stream()
                .filter(e -> securityPolicy.getUri().equals(e.getSecurityPolicyUri()) &&
                        securityMode == e.getSecurityMode())
                .findFirst()
                .map(e -> EndpointUtil.updateUrl(e, host, port))
                .orElseThrow(() -> new Exception("no endpoint found, endpoint=" + endpointUrl + ", securityPolicy=" + securityPolicy));

        var configBuilder = OpcUaClientConfig.builder()
                .setEndpoint(endpoint)
                .setApplicationName(LocalizedText.english("communicators driver"))
                .setApplicationUri(applicationUri)
                .setIdentityProvider(identityProvider)
                .setRequestTimeout(uint(socketTimeout));
        if (securityPolicy != SecurityPolicy.None) {
            if (securityStore == null)
                securityStore = OpcuaSecurityStore.openClient(
                        pkiDir,
                        keyStorePassword,
                        applicationUri,
                        "communicators driver client " + deviceId);
            configBuilder
                    .setCertificateValidator(securityStore.certificateValidator())
                    .setKeyPair(securityStore.keyPair())
                    .setCertificate(securityStore.certificate())
                    .setCertificateChain(securityStore.certificateChain());
        }
        client = OpcUaClient.create(configBuilder.build());
        client.connectAsync().get(remaining(deadline), TimeUnit.MILLISECONDS);

        if (!subscriptionNodeIds.isEmpty())
            subscribe(subscriptionNodeIds);
    }

    private static long remaining(long deadline) {
        return Math.max(deadline - System.currentTimeMillis(), 1);
    }

    private static boolean isConnectionRefused(Throwable e) {
        var depth = 0;
        for (var cause = e; cause != null && depth < 16; cause = cause.getCause(), depth++) {
            if (cause instanceof ConnectException) return true;
            if (cause == cause.getCause()) break;
        }
        return false;
    }

    /**
     * discard a partially created client between connect attempts
     * (best effort, must not block the retry loop)
     */
    private void closeClientQuietly() {
        subscription = null;
        var c = client;
        client = null;
        if (c != null) {
            try {
                c.disconnectAsync();
            } catch (Exception e) {
                log.trace("[{}] close partially created opc-ua client failed", deviceId, e);
            }
        }
    }

    @Override
    void requestDisconnect() throws Exception {
        if (client != null) {
            if (subscription != null) {
                // deleting the subscription is a courtesy to the server, only meaningful while
                // the connection is alive - when the connection was lost the server-side session
                // (and its subscriptions) is cleaned up on session close/timeout anyway,
                // and waiting for a response would just block the disconnect
                if (!isConnectionLostOccur) {
                    try {
                        subscription.deleteAsync().toCompletableFuture().get(socketTimeout, TimeUnit.MILLISECONDS);
                    } catch (Exception e) {
                        log.trace("[{}] delete subscription failed", deviceId, e);
                    }
                }
                subscription = null;
            }
            try {
                client.disconnectAsync().get(socketTimeout, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // session-close ack may never arrive (e.g. server already gone) - the client is discarded anyway
                log.debug("[{}] opc-ua disconnect response not received, closing anyway::{}", deviceId, e.toString());
            }
            client = null;
        }
        if (securityStore != null) {
            securityStore.close();
            securityStore = null;
        }
    }

    @Override
    List<Response> requestCommand(String cmdId, String requestInfo, int timeout, boolean isReadCommand, Value function, Value initialValue, Object nonPeriodicObject) throws Exception {
        if (client == null) throw new Exception("cmdId=" + cmdId + ", opc-ua client is not connected");
        var object = objectMapper.readValue(requestInfo, Object.class);
        if (isReadCommand) {
            var nodeIds = new ArrayList<NodeId>();
            try {
                if (object instanceof List) {
                    for (var obj : (List<?>) object)
                        nodeIds.add(NodeId.parse(obj.toString()));
                } else {
                    nodeIds.add(NodeId.parse(object.toString()));
                }
            } catch (Exception e) {
                throw new DriverCommand.ScriptException(e);
            }
            var values = client.readValuesAsync(0.0, TimestampsToReturn.Both, nodeIds).get(timeout, TimeUnit.MILLISECONDS);
            var received = driverCommand.pythonEngine.newList();
            for (int i = 0; i < nodeIds.size(); i++) {
                var value = values.get(i);
                if (value.getStatusCode() != null && value.getStatusCode().isBad())
                    throw new Exception("read failed, nodeId=" + nodeIds.get(i).toParseableString() + ", status=" + value.getStatusCode());
                received.invokeMember("append",
                        driverCommand.pythonEngine.toPyList(List.of(nodeIds.get(i).toParseableString(), variantToJava(value.getValue()))));
                log.trace("[{}] read nodeId={}, value={}", deviceId, nodeIds.get(i).toParseableString(), value.getValue().getValue());
            }
            return driverCommand.processCommandFunction(received, function, ZonedDateTime.now().toInstant().toEpochMilli(), initialValue);
        } else {
            var nodeIds = new ArrayList<NodeId>();
            var values = new ArrayList<DataValue>();
            try {
                if (object instanceof List) { // [{"nodeId": "...", "value": ..., "type": "..."}, ...]
                    for (var obj : (List<?>) object) {
                        var map = (Map<?, ?>) obj;
                        nodeIds.add(NodeId.parse(map.get("nodeId").toString()));
                        values.add(DataValue.valueOnly(javaToVariant(map.get("value"), map.get("type") == null ? null : map.get("type").toString())));
                    }
                } else { // {"nodeId": value, ...}
                    for (var entry : ((Map<?, ?>) object).entrySet()) {
                        nodeIds.add(NodeId.parse(entry.getKey().toString()));
                        values.add(DataValue.valueOnly(javaToVariant(entry.getValue(), null)));
                    }
                }
            } catch (Exception e) {
                throw new DriverCommand.ScriptException(e);
            }
            var results = client.writeValuesAsync(nodeIds, values).get(timeout, TimeUnit.MILLISECONDS);
            for (int i = 0; i < results.size(); i++) {
                if (results.get(i).isBad())
                    throw new Exception("write failed, nodeId=" + nodeIds.get(i).toParseableString() + ", status=" + results.get(i));
                log.trace("[{}] write nodeId={}, value={}", deviceId, nodeIds.get(i).toParseableString(), values.get(i).getValue().getValue());
            }
            return null;
        }
    }

    /**
     * subscribe node-ids, data changes execute non-periodic commands with (nodeId, value, receivedTime)
     */
    public synchronized void subscribe(List<String> nodeIds) throws Exception {
        if (client == null) throw new Exception("opc-ua client is not connected");
        if (subscription == null) {
            subscription = new OpcUaSubscription(client, publishingInterval);
            subscription.setSubscriptionListener(new OpcUaSubscription.SubscriptionListener() {
                @Override
                public void onDataReceived(
                        OpcUaSubscription subscription,
                        List<OpcUaMonitoredItem> items,
                        List<DataValue> values) {
                    var receivedTime = ZonedDateTime.now().toInstant().toEpochMilli();
                    for (int i = 0; i < items.size(); i++) {
                        var nodeId = items.get(i).getReadValueId().getNodeId().toParseableString();
                        var value = variantToJava(values.get(i).getValue());
                        log.trace("[{}] data changed, nodeId={}, value={}", deviceId, nodeId, value);
                        try {
                            driverCommand.executeNonPeriodicCommands(new Value[]{
                                    driverCommand.pythonEngine.asValue(nodeId),
                                    driverCommand.pythonEngine.asValue(value)
                            }, receivedTime, null);
                        } catch (Exception e) {
                            log.error("[{}] execute non-periodic commands failed, nodeId={}", deviceId, nodeId, e);
                        }
                    }
                }
            });
            subscription.createAsync().toCompletableFuture().get(socketTimeout, TimeUnit.MILLISECONDS);
        }
        var items = nodeIds.stream()
                .map(NodeId::parse)
                .map(OpcUaMonitoredItem::newDataItem)
                .collect(Collectors.toList());
        subscription.addMonitoredItems(items);
        var results = subscription.createMonitoredItems();
        for (var result : results) {
            if (!result.isGood()) {
                var nodeId = result.monitoredItem().getReadValueId().getNodeId().toParseableString();
                throw new Exception("subscribe failed, nodeId=" + nodeId +
                        ", service=" + result.serviceResult() +
                        ", status=" + result.operationResult().orElse(null));
            }
        }
        log.info("[{}] subscribed: {}", deviceId, nodeIds);
    }

    public String requestInfo(String... nodeIds) {
        return "[" + List.of(nodeIds).stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(",")) + "]";
    }

    public String requestInfo(Value writeNodes) throws Exception {
        var sb = new StringBuilder("{");
        var first = true;
        var entries = new ArrayList<String>();
        PythonEngine.forEachHashEntry(writeNodes, (k, v) -> {
            String value;
            if (v.isString()) value = "\"" + v.asString() + "\"";
            else if (v.isBoolean()) value = Boolean.toString(v.asBoolean());
            else if (v.isNumber()) value = v.toString();
            else value = "\"" + v + "\"";
            entries.add("\"" + PythonEngine.asString(k) + "\":" + value);
        });
        sb.append(String.join(",", entries));
        sb.append("}");
        return sb.toString();
    }
}
