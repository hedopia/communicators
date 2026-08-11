package com.sds.communicators.driver;

import com.google.common.base.Strings;
import com.sds.communicators.common.struct.Response;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.api.config.OpcUaServerConfig;
import org.eclipse.milo.opcua.sdk.server.identity.UsernameIdentityValidator;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.server.nodes.filters.AttributeFilters;
import org.eclipse.milo.opcua.sdk.server.util.HostnameUtil;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.security.DefaultCertificateManager;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.transport.TransportProfile;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.core.types.structured.BuildInfo;
import org.eclipse.milo.opcua.stack.server.EndpointConfiguration;
import org.eclipse.milo.opcua.stack.server.security.DefaultServerCertificateValidator;
import org.eclipse.milo.opcua.stack.core.security.DefaultTrustListManager;
import org.graalvm.polyglot.Value;

import java.nio.file.Files;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
public class DriverProtocolOpcuaServer extends DriverProtocolOpcua {
    private OpcUaServer server = null;
    private DriverNamespace namespace = null;

    private String bindAddress;
    private String namespaceUri;
    private String username = null;
    private String password = null;
    private boolean anonymous = true;

    @Override
    void initialize(String connectionInfo, Map<String, String> option) throws Exception {
        connectionLostOnException = false;
        super.initialize(connectionInfo, option);
        bindAddress = Strings.isNullOrEmpty(host) ? "0.0.0.0" : host;

        namespaceUri = Strings.isNullOrEmpty(option.get("namespaceUri")) ?
                "urn:sds:communicators:" + deviceId : option.get("namespaceUri");
        if (!Strings.isNullOrEmpty(option.get("username"))) {
            username = option.get("username");
            password = option.get("password");
            anonymous = Boolean.parseBoolean(option.getOrDefault("anonymous", "false"));
        }
        device.setConnectionCommand(false);
    }

    @Override
    void requestConnect() throws Exception {
        log.info("[{}] bind={}:{}{}, socket-timeout={}", deviceId, bindAddress, port, path, socketTimeout);
        var identityValidator = new UsernameIdentityValidator(anonymous, challenge ->
                username != null && username.equals(challenge.getUsername()) &&
                        (password == null ? challenge.getPassword() == null || challenge.getPassword().isEmpty() : password.equals(challenge.getPassword())));

        var endpoints = new LinkedHashSet<EndpointConfiguration>();
        for (var hostname : HostnameUtil.getHostnames(bindAddress)) {
            endpoints.add(EndpointConfiguration.newBuilder()
                    .setBindAddress(bindAddress)
                    .setBindPort(port)
                    .setHostname(hostname)
                    .setPath(path)
                    .addTokenPolicies(OpcUaServerConfig.USER_TOKEN_POLICY_ANONYMOUS, OpcUaServerConfig.USER_TOKEN_POLICY_USERNAME)
                    .setSecurityPolicy(SecurityPolicy.None)
                    .setSecurityMode(MessageSecurityMode.None)
                    .setTransportProfile(TransportProfile.TCP_UASC_UABINARY)
                    .build());
        }

        var pkiDir = Files.createTempDirectory("opcua-pki-" + deviceId).toFile();
        pkiDir.deleteOnExit();
        var trustListManager = new DefaultTrustListManager(pkiDir);

        var config = OpcUaServerConfig.builder()
                .setApplicationName(LocalizedText.english("communicators driver"))
                .setApplicationUri("urn:sds:communicators:driver:" + deviceId)
                .setProductUri("urn:sds:communicators:driver")
                .setBuildInfo(new BuildInfo("urn:sds:communicators:driver", "SDS", "communicators driver", "", "", DateTime.now()))
                .setEndpoints(endpoints)
                .setIdentityValidator(identityValidator)
                .setCertificateManager(new DefaultCertificateManager())
                .setTrustListManager(trustListManager)
                .setCertificateValidator(new DefaultServerCertificateValidator(trustListManager))
                .build();

        server = new OpcUaServer(config);
        namespace = new DriverNamespace(server);
        namespace.startup();
        server.startup().get(socketTimeout, TimeUnit.MILLISECONDS);
    }

    @Override
    void requestDisconnect() throws Exception {
        if (server != null) {
            namespace.shutdown();
            server.shutdown().get(socketTimeout, TimeUnit.MILLISECONDS);
            server = null;
            namespace = null;
        }
    }

    @Override
    List<Response> requestCommand(String cmdId, String requestInfo, int timeout, boolean isReadCommand, Value function, Value initialValue, Object nonPeriodicObject) throws Exception {
        if (isReadCommand) throw new DriverCommand.ScriptException("read-command not supported for opcua-server, use non-periodic command");
        if (namespace == null) throw new Exception("cmdId=" + cmdId + ", opc-ua server is not started");
        var object = objectMapper.readValue(requestInfo, Object.class);
        if (!(object instanceof Map))
            throw new DriverCommand.ScriptException("invalid request-info for opcua-server, expected {\"name\": value, ...}, requestInfo=" + requestInfo);
        for (var entry : ((Map<?, ?>) object).entrySet())
            namespace.writeNode(entry.getKey().toString(), javaToVariant(entry.getValue(), null));
        return null;
    }

    /**
     * read node value (from scripts)
     */
    public Object read(String name) {
        if (namespace == null) return null;
        return namespace.readNode(name);
    }

    /**
     * write node value, node is created when not exists (from scripts)
     */
    public void write(String name, Value value) throws Exception {
        if (namespace == null) throw new Exception("opc-ua server is not started");
        namespace.writeNode(name, javaToVariant(value == null || value.isNull() ? null : value.as(Object.class), null));
    }

    private class DriverNamespace extends org.eclipse.milo.opcua.sdk.server.api.ManagedNamespaceWithLifecycle {
        private final Map<String, UaVariableNode> nodes = new ConcurrentHashMap<>();
        private final org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel subscriptionModel;
        private UaFolderNode folder;

        DriverNamespace(OpcUaServer server) {
            super(server, namespaceUri);
            subscriptionModel = new org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel(server, this);
            getLifecycleManager().addLifecycle(subscriptionModel);
            getLifecycleManager().addStartupTask(this::createNodes);
        }

        @Override
        public void onDataItemsCreated(List<org.eclipse.milo.opcua.sdk.server.api.DataItem> dataItems) {
            subscriptionModel.onDataItemsCreated(dataItems);
        }

        @Override
        public void onDataItemsModified(List<org.eclipse.milo.opcua.sdk.server.api.DataItem> dataItems) {
            subscriptionModel.onDataItemsModified(dataItems);
        }

        @Override
        public void onDataItemsDeleted(List<org.eclipse.milo.opcua.sdk.server.api.DataItem> dataItems) {
            subscriptionModel.onDataItemsDeleted(dataItems);
        }

        @Override
        public void onMonitoringModeChanged(List<org.eclipse.milo.opcua.sdk.server.api.MonitoredItem> monitoredItems) {
            subscriptionModel.onMonitoringModeChanged(monitoredItems);
        }

        private void createNodes() {
            folder = new UaFolderNode(getNodeContext(),
                    newNodeId(deviceId),
                    newQualifiedName(deviceId),
                    LocalizedText.english(deviceId));
            getNodeManager().addNode(folder);
            folder.addReference(new Reference(folder.getNodeId(),
                    Identifiers.Organizes,
                    Identifiers.ObjectsFolder.expanded(),
                    false));

            // initial nodes from device data
            for (var entry : device.getData().entrySet()) {
                try {
                    writeNode(entry.getKey(), javaToVariant(entry.getValue(), null));
                } catch (Exception e) {
                    log.error("[{}] create initial node failed, name={}, value={}", deviceId, entry.getKey(), entry.getValue(), e);
                }
            }
        }

        Object readNode(String name) {
            var node = nodes.get(name);
            if (node == null) return null;
            return variantToJava(node.getValue().getValue());
        }

        void writeNode(String name, Variant variant) {
            var node = nodes.computeIfAbsent(name, this::createNode);
            node.setValue(new DataValue(variant));
        }

        private UaVariableNode createNode(String name) {
            var node = new UaVariableNode.UaVariableNodeBuilder(getNodeContext())
                    .setNodeId(newNodeId(deviceId + "/" + name))
                    .setAccessLevel(AccessLevel.READ_WRITE)
                    .setUserAccessLevel(AccessLevel.READ_WRITE)
                    .setBrowseName(newQualifiedName(name))
                    .setDisplayName(LocalizedText.english(name))
                    .setDataType(Identifiers.BaseDataType)
                    .setTypeDefinition(Identifiers.BaseDataVariableType)
                    .build();

            node.getFilterChain().addLast(AttributeFilters.setValue((ctx, value) -> {
                ctx.setAttribute(AttributeId.Value, value);
                if (ctx.getSession().isPresent() && !isSetDisconnected) { // written by opc-ua client
                    var receivedTime = ZonedDateTime.now().toInstant().toEpochMilli();
                    var javaValue = variantToJava(value.getValue());
                    log.trace("[{}] node written by client, name={}, value={}", deviceId, name, javaValue);
                    Schedulers.io().scheduleDirect(() -> {
                        try {
                            driverCommand.executeNonPeriodicCommands(new Value[]{
                                    driverCommand.pythonEngine.asValue(name),
                                    driverCommand.pythonEngine.asValue(javaValue)
                            }, receivedTime, null);
                        } catch (Exception e) {
                            log.error("[{}] execute non-periodic commands failed, name={}", deviceId, name, e);
                        }
                    });
                }
            }));

            getNodeManager().addNode(node);
            folder.addOrganizes(node);
            log.trace("[{}] node created, name={}", deviceId, name);
            return node;
        }
    }
}
