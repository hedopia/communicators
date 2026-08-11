package com.sds.communicators.driver;

import com.fasterxml.jackson.core.type.TypeReference;
import com.sds.communicators.cluster.ClusterGrpc;
import com.sds.communicators.common.struct.Device;
import io.grpc.MethodDescriptor;

import java.util.Map;
import java.util.Set;

/**
 * gRPC definitions for driver internal node-to-node communication
 * (connect-all delegation between leader and followers).
 * MethodDescriptors are hand-built with the cluster-starter Jackson-based
 * JSON marshaller, so payload semantics stay identical to Feign(JSON) calls.
 */
final class DriverGrpc {
    static final String SERVICE_NAME = "driver.DriverInternal";

    private DriverGrpc() {}

    static class ConnectRequest {
        public int nodeIndex;
        public Set<Device> devices;

        public ConnectRequest() {}

        ConnectRequest(int nodeIndex, Set<Device> devices) {
            this.nodeIndex = nodeIndex;
            this.devices = devices;
        }
    }

    static final MethodDescriptor<Set<Device>, Map<String, String>> CONNECT_ALL_TO_INDEX =
            ClusterGrpc.method(SERVICE_NAME, "connectAllToIndex",
                    new TypeReference<Set<Device>>() {}, new TypeReference<Map<String, String>>() {});
    static final MethodDescriptor<ConnectRequest, Map<String, String>> CONNECT_ALL_TO_LEADER =
            ClusterGrpc.method(SERVICE_NAME, "connectAllToLeader",
                    new TypeReference<ConnectRequest>() {}, new TypeReference<Map<String, String>>() {});
}
