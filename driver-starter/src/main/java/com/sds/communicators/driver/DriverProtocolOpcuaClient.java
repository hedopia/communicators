package com.sds.communicators.driver;

import com.google.common.base.Strings;
import com.sds.communicators.common.struct.Response;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.identity.AnonymousProvider;
import org.eclipse.milo.opcua.sdk.client.api.identity.IdentityProvider;
import org.eclipse.milo.opcua.sdk.client.api.identity.UsernameProvider;
import org.eclipse.milo.opcua.sdk.client.subscriptions.ManagedSubscription;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.util.EndpointUtil;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateBuilder;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateGenerator;
import org.graalvm.polyglot.Value;

import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

@Slf4j
public class DriverProtocolOpcuaClient extends DriverProtocolOpcua {
    private OpcUaClient client = null;
    private ManagedSubscription subscription = null;

    private String endpointUrl;
    private SecurityPolicy securityPolicy = SecurityPolicy.None;
    private MessageSecurityMode securityMode = MessageSecurityMode.None;
    private IdentityProvider identityProvider = new AnonymousProvider();
    private final List<String> subscriptionNodeIds = new ArrayList<>();
    private double publishingInterval = 1000.0;

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
    }

    @Override
    void requestConnect() throws Exception {
        log.info("[{}] endpoint={}, securityPolicy={}, socket-timeout={}", deviceId, endpointUrl, securityPolicy, socketTimeout);
        var applicationUri = "urn:sds:communicators:driver:" + deviceId;
        client = OpcUaClient.create(
                endpointUrl,
                endpoints -> endpoints.stream()
                        .filter(e -> securityPolicy.getUri().equals(e.getSecurityPolicyUri()))
                        .findFirst()
                        .map(e -> EndpointUtil.updateUrl(e, host, port)),
                configBuilder -> {
                    configBuilder
                            .setApplicationName(LocalizedText.english("communicators driver"))
                            .setApplicationUri(applicationUri)
                            .setIdentityProvider(identityProvider)
                            .setRequestTimeout(uint(socketTimeout));
                    if (securityPolicy != SecurityPolicy.None) {
                        try {
                            KeyPair keyPair = SelfSignedCertificateGenerator.generateRsaKeyPair(2048);
                            X509Certificate certificate = new SelfSignedCertificateBuilder(keyPair)
                                    .setCommonName("communicators driver")
                                    .setApplicationUri(applicationUri)
                                    .build();
                            configBuilder.setKeyPair(keyPair).setCertificate(certificate);
                        } catch (Exception e) {
                            throw new RuntimeException("generate self-signed certificate failed", e);
                        }
                    }
                    return configBuilder.build();
                });
        client.connect().get(socketTimeout, TimeUnit.MILLISECONDS);

        if (!subscriptionNodeIds.isEmpty())
            subscribe(subscriptionNodeIds);
    }

    @Override
    void requestDisconnect() throws Exception {
        if (client != null) {
            if (subscription != null) {
                try {
                    subscription.delete();
                } catch (Exception e) {
                    log.trace("[{}] delete subscription failed", deviceId, e);
                }
                subscription = null;
            }
            try {
                client.disconnect().get(socketTimeout, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // session-close ack may never arrive (e.g. server already gone) - the client is discarded anyway
                log.debug("[{}] opc-ua disconnect response not received, closing anyway::{}", deviceId, e.toString());
            }
            client = null;
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
            var values = client.readValues(0.0, TimestampsToReturn.Both, nodeIds).get(timeout, TimeUnit.MILLISECONDS);
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
            var results = client.writeValues(nodeIds, values).get(timeout, TimeUnit.MILLISECONDS);
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
            subscription = ManagedSubscription.create(client, publishingInterval);
            subscription.addDataChangeListener((items, values) -> {
                var receivedTime = ZonedDateTime.now().toInstant().toEpochMilli();
                for (int i = 0; i < items.size(); i++) {
                    var nodeId = items.get(i).getNodeId().toParseableString();
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
            });
        }
        var items = subscription.createDataItems(nodeIds.stream().map(NodeId::parse).collect(Collectors.toList()));
        for (var item : items) {
            if (!item.getStatusCode().isGood())
                throw new Exception("subscribe failed, nodeId=" + item.getNodeId().toParseableString() + ", status=" + item.getStatusCode());
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
