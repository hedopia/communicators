package com.sds.communicators.cluster;

import com.sds.communicators.common.type.NodeStatus;
import com.sds.communicators.common.type.Position;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.channel.ChannelOption;
import io.grpc.stub.ClientCalls;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * gRPC client for cluster internal node-to-node communication.
 * Node URLs ({@code http://host:port}) are mapped to gRPC targets
 * {@code host:(port + grpcPortOffset)}. Channels are cached per node URL.
 */
class ClusterGrpcClient {
    private final int grpcPortOffset;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();

    ClusterGrpcClient(int grpcPortOffset, int connectTimeoutMillis, int readTimeoutMillis) {
        this.grpcPortOffset = grpcPortOffset;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    void heartbeat(String url, int nodeIndex, Position position, long lastTransitionTime, Map<Integer, Long> sharedObjectSeq) {
        call(url, ClusterGrpc.HEARTBEAT, new ClusterGrpc.HeartbeatRequest(nodeIndex, position, lastTransitionTime, sharedObjectSeq));
    }

    NodeStatus getNodeStatus(String url) {
        return call(url, ClusterGrpc.GET_NODE_STATUS, new ClusterGrpc.Empty());
    }

    void setToLeader(String url) {
        call(url, ClusterGrpc.SET_TO_LEADER, new ClusterGrpc.Empty());
    }

    void clusterDeleted(String url, int nodeIndex) {
        call(url, ClusterGrpc.CLUSTER_DELETED, new ClusterGrpc.NodeIndexRequest(nodeIndex));
    }

    void removeSharedObject(String url, int nodeIndex) {
        call(url, ClusterGrpc.REMOVE_SHARED_OBJECT, new ClusterGrpc.NodeIndexRequest(nodeIndex));
    }

    int getNodeIndex(String url) {
        return call(url, ClusterGrpc.GET_NODE_INDEX, new ClusterGrpc.Empty());
    }

    void mergeSharedObjectToLeader(String url, int nodeIndex, ClusterService.MergeSharedObjectInfo mergeSharedObjectInfo) {
        call(url, ClusterGrpc.MERGE_SHARED_OBJECT_TO_LEADER, new ClusterGrpc.MergeRequest(nodeIndex, mergeSharedObjectInfo));
    }

    void deleteSharedObjectToLeader(String url, int nodeIndex, ClusterService.DeleteSharedObjectInfo deleteSharedObjectInfo) {
        call(url, ClusterGrpc.DELETE_SHARED_OBJECT_TO_LEADER, new ClusterGrpc.DeleteRequest(nodeIndex, deleteSharedObjectInfo));
    }

    boolean checkMergeSharedObject(String url, int nodeIndex, ClusterService.MergeSharedObjectInfo mergeSharedObjectInfo) {
        return call(url, ClusterGrpc.CHECK_MERGE_SHARED_OBJECT, new ClusterGrpc.MergeRequest(nodeIndex, mergeSharedObjectInfo));
    }

    boolean checkDeleteSharedObject(String url, int nodeIndex, ClusterService.DeleteSharedObjectInfo deleteSharedObjectInfo) {
        return call(url, ClusterGrpc.CHECK_DELETE_SHARED_OBJECT, new ClusterGrpc.DeleteRequest(nodeIndex, deleteSharedObjectInfo));
    }

    void overwriteSharedObject(String url, int nodeIndex, ClusterService.MergeSharedObjectInfo sharedObjectInfo) {
        call(url, ClusterGrpc.OVERWRITE_SHARED_OBJECT, new ClusterGrpc.MergeRequest(nodeIndex, sharedObjectInfo));
    }

    ClusterService.MergeSharedObjectInfo getSharedObject(String url) {
        return call(url, ClusterGrpc.GET_SHARED_OBJECT, new ClusterGrpc.Empty());
    }

    ClusterService.MergeSharedObjectInfo getSharedObject(String url, int nodeIndex) {
        return call(url, ClusterGrpc.GET_SHARED_OBJECT_OF, new ClusterGrpc.NodeIndexRequest(nodeIndex));
    }

    void syncSharedObject(String url, int nodeIndex, ClusterService.SharedObject sharedObject) {
        call(url, ClusterGrpc.SYNC_SHARED_OBJECT, new ClusterGrpc.SyncRequest(nodeIndex, sharedObject));
    }

    Set<Integer> checkSharedObjectSeq(String url, Map<Integer, Long> sharedObjectSeq) {
        return call(url, ClusterGrpc.CHECK_SHARED_OBJECT_SEQ, sharedObjectSeq);
    }

    synchronized void dispose() {
        for (var channel : channels.values())
            channel.shutdownNow();
        channels.clear();
    }

    <Req, Res> Res call(String url, MethodDescriptor<Req, Res> method, Req request) {
        return ClientCalls.blockingUnaryCall(channel(url), method,
                CallOptions.DEFAULT.withDeadlineAfter(readTimeoutMillis, TimeUnit.MILLISECONDS), request);
    }

    private synchronized ManagedChannel channel(String url) {
        return channels.computeIfAbsent(url, key -> {
            var uri = URI.create(key);
            var host = uri.getHost();
            var port = uri.getPort();
            if (port < 0)
                port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            return NettyChannelBuilder.forAddress(host, port + grpcPortOffset)
                    .withOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                    .maxInboundMessageSize(Integer.MAX_VALUE)
                    .usePlaintext()
                    .build();
        });
    }
}
