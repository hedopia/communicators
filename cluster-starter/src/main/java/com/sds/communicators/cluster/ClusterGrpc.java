package com.sds.communicators.cluster;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sds.communicators.common.type.NodeStatus;
import com.sds.communicators.common.type.Position;
import io.grpc.MethodDescriptor;
import io.grpc.Status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

/**
 * gRPC definitions for cluster internal node-to-node communication.
 * MethodDescriptors are hand-built (no protobuf codegen) with a Jackson-based
 * JSON marshaller, so payload semantics stay identical to the previous
 * Feign(Jackson JSON) based HTTP internal routes.
 */
public final class ClusterGrpc {
    static final String SERVICE_NAME = "cluster.ClusterInternal";

    // FAIL_ON_EMPTY_BEANS disabled so that the property-less Empty DTO serializes to "{}"
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private ClusterGrpc() {}

    static <Req, Res> MethodDescriptor<Req, Res> method(String name, TypeReference<Req> requestType, TypeReference<Res> responseType) {
        return method(SERVICE_NAME, name, requestType, responseType);
    }

    /**
     * Builds a unary {@link MethodDescriptor} with Jackson JSON marshalling for the given
     * service/method name. Intended for starters that register additional gRPC services
     * on the cluster gRPC server.
     */
    public static <Req, Res> MethodDescriptor<Req, Res> method(String serviceName, String methodName, TypeReference<Req> requestType, TypeReference<Res> responseType) {
        return MethodDescriptor.<Req, Res>newBuilder()
                .setFullMethodName(MethodDescriptor.generateFullMethodName(serviceName, methodName))
                .setType(MethodDescriptor.MethodType.UNARY)
                .setRequestMarshaller(marshaller(requestType))
                .setResponseMarshaller(marshaller(responseType))
                .build();
    }

    private static <T> MethodDescriptor.Marshaller<T> marshaller(TypeReference<T> type) {
        return new MethodDescriptor.Marshaller<>() {
            @Override
            public InputStream stream(T value) {
                try {
                    // writeValueAsBytes(null) produces the JSON literal "null"
                    return new ByteArrayInputStream(OBJECT_MAPPER.writeValueAsBytes(value));
                } catch (IOException e) {
                    throw Status.INTERNAL.withDescription("payload serialization failed::" + e.getMessage()).asRuntimeException();
                }
            }

            @Override
            public T parse(InputStream stream) {
                try {
                    // JSON literal "null" deserializes to null, which is allowed
                    return OBJECT_MAPPER.readValue(stream, type);
                } catch (IOException e) {
                    throw Status.INTERNAL.withDescription("payload deserialization failed::" + e.getMessage()).asRuntimeException();
                }
            }
        };
    }

    static class Empty {
        public Empty() {}
    }

    static class HeartbeatRequest {
        public int nodeIndex;
        public Position position;
        public long lastTransitionTime;
        public Map<Integer, Long> sharedObjectSeq;

        public HeartbeatRequest() {}

        HeartbeatRequest(int nodeIndex, Position position, long lastTransitionTime, Map<Integer, Long> sharedObjectSeq) {
            this.nodeIndex = nodeIndex;
            this.position = position;
            this.lastTransitionTime = lastTransitionTime;
            this.sharedObjectSeq = sharedObjectSeq;
        }
    }

    static class NodeIndexRequest {
        public int nodeIndex;

        public NodeIndexRequest() {}

        NodeIndexRequest(int nodeIndex) {
            this.nodeIndex = nodeIndex;
        }
    }

    static class MergeRequest {
        public int nodeIndex;
        public ClusterService.MergeSharedObjectInfo info;

        public MergeRequest() {}

        MergeRequest(int nodeIndex, ClusterService.MergeSharedObjectInfo info) {
            this.nodeIndex = nodeIndex;
            this.info = info;
        }
    }

    static class DeleteRequest {
        public int nodeIndex;
        public ClusterService.DeleteSharedObjectInfo info;

        public DeleteRequest() {}

        DeleteRequest(int nodeIndex, ClusterService.DeleteSharedObjectInfo info) {
            this.nodeIndex = nodeIndex;
            this.info = info;
        }
    }

    static class SyncRequest {
        public int nodeIndex;
        public ClusterService.SharedObject sharedObject;

        public SyncRequest() {}

        SyncRequest(int nodeIndex, ClusterService.SharedObject sharedObject) {
            this.nodeIndex = nodeIndex;
            this.sharedObject = sharedObject;
        }
    }

    static final MethodDescriptor<HeartbeatRequest, Empty> HEARTBEAT =
            method("heartbeat", new TypeReference<HeartbeatRequest>() {}, new TypeReference<Empty>() {});
    static final MethodDescriptor<Empty, NodeStatus> GET_NODE_STATUS =
            method("getNodeStatus", new TypeReference<Empty>() {}, new TypeReference<NodeStatus>() {});
    static final MethodDescriptor<Empty, Empty> SET_TO_LEADER =
            method("setToLeader", new TypeReference<Empty>() {}, new TypeReference<Empty>() {});
    static final MethodDescriptor<NodeIndexRequest, Empty> CLUSTER_DELETED =
            method("clusterDeleted", new TypeReference<NodeIndexRequest>() {}, new TypeReference<Empty>() {});
    static final MethodDescriptor<NodeIndexRequest, Empty> REMOVE_SHARED_OBJECT =
            method("removeSharedObject", new TypeReference<NodeIndexRequest>() {}, new TypeReference<Empty>() {});
    static final MethodDescriptor<Empty, Integer> GET_NODE_INDEX =
            method("getNodeIndex", new TypeReference<Empty>() {}, new TypeReference<Integer>() {});
    static final MethodDescriptor<MergeRequest, Empty> MERGE_SHARED_OBJECT_TO_LEADER =
            method("mergeSharedObjectToLeader", new TypeReference<MergeRequest>() {}, new TypeReference<Empty>() {});
    static final MethodDescriptor<DeleteRequest, Empty> DELETE_SHARED_OBJECT_TO_LEADER =
            method("deleteSharedObjectToLeader", new TypeReference<DeleteRequest>() {}, new TypeReference<Empty>() {});
    static final MethodDescriptor<MergeRequest, Boolean> CHECK_MERGE_SHARED_OBJECT =
            method("checkMergeSharedObject", new TypeReference<MergeRequest>() {}, new TypeReference<Boolean>() {});
    static final MethodDescriptor<DeleteRequest, Boolean> CHECK_DELETE_SHARED_OBJECT =
            method("checkDeleteSharedObject", new TypeReference<DeleteRequest>() {}, new TypeReference<Boolean>() {});
    static final MethodDescriptor<MergeRequest, Empty> OVERWRITE_SHARED_OBJECT =
            method("overwriteSharedObject", new TypeReference<MergeRequest>() {}, new TypeReference<Empty>() {});
    static final MethodDescriptor<Empty, ClusterService.MergeSharedObjectInfo> GET_SHARED_OBJECT =
            method("getSharedObject", new TypeReference<Empty>() {}, new TypeReference<ClusterService.MergeSharedObjectInfo>() {});
    static final MethodDescriptor<NodeIndexRequest, ClusterService.MergeSharedObjectInfo> GET_SHARED_OBJECT_OF =
            method("getSharedObjectOf", new TypeReference<NodeIndexRequest>() {}, new TypeReference<ClusterService.MergeSharedObjectInfo>() {});
    static final MethodDescriptor<SyncRequest, Empty> SYNC_SHARED_OBJECT =
            method("syncSharedObject", new TypeReference<SyncRequest>() {}, new TypeReference<Empty>() {});
    static final MethodDescriptor<Map<Integer, Long>, Set<Integer>> CHECK_SHARED_OBJECT_SEQ =
            method("checkSharedObjectSeq", new TypeReference<Map<Integer, Long>>() {}, new TypeReference<Set<Integer>>() {});
}
