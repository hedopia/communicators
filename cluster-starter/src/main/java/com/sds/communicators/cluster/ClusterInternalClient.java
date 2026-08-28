package com.sds.communicators.cluster;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.sds.communicators.cluster.support.NodeHttpClient;
import com.sds.communicators.common.type.NodeStatus;
import com.sds.communicators.common.type.Position;

import java.util.Map;
import java.util.Set;

/**
 * Typed client for {@code {nodeUrl}{clusterBasePath}/internal/...}, served on the node's
 * regular HTTP server so internal traffic shares the port of the public API.
 */
class ClusterInternalClient {
    static final String INTERNAL_PATH = "/internal";

    private static final TypeFactory TYPES = TypeFactory.defaultInstance();
    private static final JavaType NODE_STATUS = TYPES.constructType(NodeStatus.class);
    private static final JavaType INTEGER = TYPES.constructType(Integer.class);
    private static final JavaType BOOLEAN = TYPES.constructType(Boolean.class);
    private static final JavaType SHARED_OBJECT_INFO = TYPES.constructType(ClusterService.MergeSharedObjectInfo.class);
    private static final JavaType SET_OF_INTEGER = TYPES.constructType(new TypeReference<Set<Integer>>() {});

    private final NodeHttpClient client;
    private final String basePath;

    ClusterInternalClient(NodeHttpClient client, String clusterBasePath) {
        this.client = client;
        this.basePath = clusterBasePath + INTERNAL_PATH;
    }

    void heartbeat(String url, int nodeIndex, Position position, long lastTransitionTime, Map<Integer, Long> sharedObjectSeq) {
        call(url, "PUT", "/heartbeat", new HeartbeatRequest(nodeIndex, position, lastTransitionTime, sharedObjectSeq), null);
    }

    NodeStatus getNodeStatus(String url) {
        return call(url, "GET", "/node-status", null, NODE_STATUS);
    }

    void setToLeader(String url) {
        call(url, "PUT", "/set-to-leader", null, null);
    }

    void clusterDeleted(String url, int nodeIndex) {
        call(url, "DELETE", "/cluster-deleted/" + nodeIndex, null, null);
    }

    void removeSharedObject(String url, int nodeIndex) {
        call(url, "DELETE", "/remove-shared-object/" + nodeIndex, null, null);
    }

    int getNodeIndex(String url) {
        return call(url, "GET", "/node-index", null, INTEGER);
    }

    void mergeSharedObjectToLeader(String url, int nodeIndex, ClusterService.MergeSharedObjectInfo mergeSharedObjectInfo) {
        call(url, "POST", "/merge-shared-object-to-leader/" + nodeIndex, mergeSharedObjectInfo, null);
    }

    void deleteSharedObjectToLeader(String url, int nodeIndex, ClusterService.DeleteSharedObjectInfo deleteSharedObjectInfo) {
        call(url, "POST", "/delete-shared-object-to-leader/" + nodeIndex, deleteSharedObjectInfo, null);
    }

    boolean checkMergeSharedObject(String url, int nodeIndex, ClusterService.MergeSharedObjectInfo mergeSharedObjectInfo) {
        return call(url, "POST", "/check-merge-shared-object/" + nodeIndex, mergeSharedObjectInfo, BOOLEAN);
    }

    boolean checkDeleteSharedObject(String url, int nodeIndex, ClusterService.DeleteSharedObjectInfo deleteSharedObjectInfo) {
        return call(url, "POST", "/check-delete-shared-object/" + nodeIndex, deleteSharedObjectInfo, BOOLEAN);
    }

    void overwriteSharedObject(String url, int nodeIndex, ClusterService.MergeSharedObjectInfo sharedObjectInfo) {
        call(url, "POST", "/overwrite-shared-object/" + nodeIndex, sharedObjectInfo, null);
    }

    ClusterService.MergeSharedObjectInfo getSharedObject(String url) {
        return call(url, "GET", "/shared-object", null, SHARED_OBJECT_INFO);
    }

    ClusterService.MergeSharedObjectInfo getSharedObject(String url, int nodeIndex) {
        return call(url, "GET", "/shared-object/" + nodeIndex, null, SHARED_OBJECT_INFO);
    }

    void syncSharedObject(String url, int nodeIndex, ClusterService.SharedObject sharedObject) {
        call(url, "POST", "/sync-shared-object/" + nodeIndex, sharedObject, null);
    }

    Set<Integer> checkSharedObjectSeq(String url, Map<Integer, Long> sharedObjectSeq) {
        return call(url, "POST", "/check-shared-object-seq", sharedObjectSeq, SET_OF_INTEGER);
    }

    private <T> T call(String url, String method, String path, Object body, JavaType responseType) {
        return client.call(url + basePath + path, method, body, responseType);
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
}
