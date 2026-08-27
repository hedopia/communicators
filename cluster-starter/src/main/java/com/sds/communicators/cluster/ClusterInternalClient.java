package com.sds.communicators.cluster;

import com.sds.communicators.common.type.NodeStatus;
import com.sds.communicators.common.type.Position;
import feign.Param;
import feign.RequestLine;

import java.util.Map;
import java.util.Set;

/**
 * HTTP client for cluster internal node-to-node communication.
 * Calls go to {@code {nodeUrl}{clusterBasePath}/internal/...} on the node's regular
 * HTTP server, so internal traffic shares the port and thread pool of the public API.
 * Payloads are Jackson JSON, matching what the routes in ClusterServerRoutes expect.
 */
class ClusterInternalClient {
    static final String INTERNAL_PATH = "/internal";

    private final ClusterClient clusterClient;
    private final String clusterBasePath;

    ClusterInternalClient(ClusterClient clusterClient, String clusterBasePath) {
        this.clusterClient = clusterClient;
        this.clusterBasePath = clusterBasePath;
    }

    private Api api(String url) {
        return clusterClient.getClient(url + clusterBasePath + INTERNAL_PATH, Api.class);
    }

    void heartbeat(String url, int nodeIndex, Position position, long lastTransitionTime, Map<Integer, Long> sharedObjectSeq) {
        api(url).heartbeat(new HeartbeatRequest(nodeIndex, position, lastTransitionTime, sharedObjectSeq));
    }

    NodeStatus getNodeStatus(String url) {
        return api(url).getNodeStatus();
    }

    void setToLeader(String url) {
        api(url).setToLeader();
    }

    void clusterDeleted(String url, int nodeIndex) {
        api(url).clusterDeleted(nodeIndex);
    }

    void removeSharedObject(String url, int nodeIndex) {
        api(url).removeSharedObject(nodeIndex);
    }

    int getNodeIndex(String url) {
        return api(url).getNodeIndex();
    }

    void mergeSharedObjectToLeader(String url, int nodeIndex, ClusterService.MergeSharedObjectInfo mergeSharedObjectInfo) {
        api(url).mergeSharedObjectToLeader(nodeIndex, mergeSharedObjectInfo);
    }

    void deleteSharedObjectToLeader(String url, int nodeIndex, ClusterService.DeleteSharedObjectInfo deleteSharedObjectInfo) {
        api(url).deleteSharedObjectToLeader(nodeIndex, deleteSharedObjectInfo);
    }

    boolean checkMergeSharedObject(String url, int nodeIndex, ClusterService.MergeSharedObjectInfo mergeSharedObjectInfo) {
        return api(url).checkMergeSharedObject(nodeIndex, mergeSharedObjectInfo);
    }

    boolean checkDeleteSharedObject(String url, int nodeIndex, ClusterService.DeleteSharedObjectInfo deleteSharedObjectInfo) {
        return api(url).checkDeleteSharedObject(nodeIndex, deleteSharedObjectInfo);
    }

    void overwriteSharedObject(String url, int nodeIndex, ClusterService.MergeSharedObjectInfo sharedObjectInfo) {
        api(url).overwriteSharedObject(nodeIndex, sharedObjectInfo);
    }

    ClusterService.MergeSharedObjectInfo getSharedObject(String url) {
        return api(url).getSharedObject();
    }

    ClusterService.MergeSharedObjectInfo getSharedObject(String url, int nodeIndex) {
        return api(url).getSharedObjectOf(nodeIndex);
    }

    void syncSharedObject(String url, int nodeIndex, ClusterService.SharedObject sharedObject) {
        api(url).syncSharedObject(nodeIndex, sharedObject);
    }

    Set<Integer> checkSharedObjectSeq(String url, Map<Integer, Long> sharedObjectSeq) {
        return api(url).checkSharedObjectSeq(sharedObjectSeq);
    }

    interface Api {
        @RequestLine("PUT /heartbeat")
        void heartbeat(HeartbeatRequest request);

        @RequestLine("GET /node-status")
        NodeStatus getNodeStatus();

        @RequestLine("PUT /set-to-leader")
        void setToLeader();

        @RequestLine("DELETE /cluster-deleted/{nodeIndex}")
        void clusterDeleted(@Param("nodeIndex") int nodeIndex);

        @RequestLine("DELETE /remove-shared-object/{nodeIndex}")
        void removeSharedObject(@Param("nodeIndex") int nodeIndex);

        @RequestLine("GET /node-index")
        int getNodeIndex();

        @RequestLine("POST /merge-shared-object-to-leader/{nodeIndex}")
        void mergeSharedObjectToLeader(@Param("nodeIndex") int nodeIndex, ClusterService.MergeSharedObjectInfo info);

        @RequestLine("POST /delete-shared-object-to-leader/{nodeIndex}")
        void deleteSharedObjectToLeader(@Param("nodeIndex") int nodeIndex, ClusterService.DeleteSharedObjectInfo info);

        @RequestLine("POST /check-merge-shared-object/{nodeIndex}")
        boolean checkMergeSharedObject(@Param("nodeIndex") int nodeIndex, ClusterService.MergeSharedObjectInfo info);

        @RequestLine("POST /check-delete-shared-object/{nodeIndex}")
        boolean checkDeleteSharedObject(@Param("nodeIndex") int nodeIndex, ClusterService.DeleteSharedObjectInfo info);

        @RequestLine("POST /overwrite-shared-object/{nodeIndex}")
        void overwriteSharedObject(@Param("nodeIndex") int nodeIndex, ClusterService.MergeSharedObjectInfo info);

        @RequestLine("GET /shared-object")
        ClusterService.MergeSharedObjectInfo getSharedObject();

        @RequestLine("GET /shared-object/{nodeIndex}")
        ClusterService.MergeSharedObjectInfo getSharedObjectOf(@Param("nodeIndex") int nodeIndex);

        @RequestLine("POST /sync-shared-object/{nodeIndex}")
        void syncSharedObject(@Param("nodeIndex") int nodeIndex, ClusterService.SharedObject sharedObject);

        @RequestLine("POST /check-shared-object-seq")
        Set<Integer> checkSharedObjectSeq(Map<Integer, Long> sharedObjectSeq);
    }

    /** heartbeat payload; sent as a single JSON body instead of path segments */
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
