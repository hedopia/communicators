package com.sds.communicators.cluster;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.sds.communicators.cluster.support.NodeHttpClient;
import com.sds.communicators.common.type.NodeStatus;
import com.sds.communicators.common.type.Position;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Typed client for {@code {nodeUrl}{clusterBasePath}/internal/...}, served on the node's
 * regular HTTP server so internal traffic shares the port of the public API.
 */
class ClusterInternalClient {
    static final String INTERNAL_PATH = "/internal";
    static final String OWN_DIGEST_PARAMETER = "own";
    /** cluster-deleted: the sequence of the object failed over (a node of an older version ignores it) */
    static final String SEQ_PARAMETER = "seq";
    /**
     * cluster-deleted: the failover finished after the sender stopped leading, so a leader keeps what it holds of that
     * object (a node of an older version ignores it)
     */
    static final String DEMOTED_PARAMETER = "demoted";
    /**
     * cluster-deleted, with demoted: the node-index of the sender, whose failover it was: a follower drops a copy that
     * the sender listed there, and keeps another one, the winner's (a node of an older version ignores it)
     */
    static final String SENDER_PARAMETER = "sender";
    /** sync-shared-object: the demoted node's handover, the same for each attempt (a node of an older version ignores it) */
    static final String HANDOVER_PARAMETER = "handover";
    /**
     * sync-shared-object: nodeIndex:sequence of each handed-over object the winner lacks because it was failed over on
     * the demoted node since (a node of an older version ignores it)
     */
    static final String REMOVED_PARAMETER = "removed";

    private static final TypeFactory TYPES = TypeFactory.defaultInstance();
    private static final JavaType NODE_STATUS = TYPES.constructType(NodeStatus.class);
    private static final JavaType INTEGER = TYPES.constructType(Integer.class);
    private static final JavaType BOOLEAN = TYPES.constructType(Boolean.class);
    private static final JavaType SHARED_OBJECT_INFO = TYPES.constructType(ClusterService.MergeSharedObjectInfo.class);
    private static final JavaType SET_OF_INTEGER = TYPES.constructType(new TypeReference<Set<Integer>>() {});
    private static final JavaType SHARED_OBJECT_DIGESTS = TYPES.constructType(ClusterService.SharedObjectDigests.class);
    private static final JavaType SHARED_OBJECT_CHANGES_RESULT = TYPES.constructType(ClusterService.SharedObjectChangesResult.class);

    private final NodeHttpClient client;
    private final String basePath;
    /** status probes answer immediately on a live node, so a frozen peer must not hold a lookup for the full read timeout */
    private final Duration probeTimeout;

    ClusterInternalClient(NodeHttpClient client, String clusterBasePath, Duration probeTimeout) {
        this.client = client;
        this.basePath = clusterBasePath + INTERNAL_PATH;
        this.probeTimeout = probeTimeout;
    }

    Duration probeTimeout() {
        return probeTimeout;
    }

    /** @return the receiver's node-index, null from a node of an older version (which answers nothing) */
    Integer heartbeat(String url, int nodeIndex, Position position, long lastTransitionTime, Map<Integer, Long> sharedObjectSeq) {
        // full read timeout: the receiver syncs shared objects inside this request, and a client timeout would interrupt that work
        try {
            return client.callOptional(url + basePath + "/heartbeat", "PUT",
                    new HeartbeatRequest(nodeIndex, position, lastTransitionTime, sharedObjectSeq), INTEGER);
        } catch (NodeHttpClient.CallException e) {
            // answered, but with something else than a node-index: the heartbeat itself got through
            if (e.getStatusCode() / 100 == 2)
                return null;
            throw e;
        }
    }

    NodeStatus getNodeStatus(String url) {
        return probe(url, "GET", "/node-status", null, NODE_STATUS);
    }

    /** full read timeout: a leader that is only paused (GC, CPU starvation) must not be replaced by an election */
    NodeStatus getNodeStatusForElection(String url) {
        return call(url, "GET", "/node-status", null, NODE_STATUS);
    }

    void setToLeader(String url) {
        call(url, "PUT", "/set-to-leader", null, null);
    }

    /**
     * probe timeout: sent to every node, including the lost one, which may accept the connection but never answer.
     * seq: the sequence of the object failed over; demoted: the sender no longer led when the failover finished; sender:
     * the sending node's index, sent with demoted
     */
    void clusterDeleted(String url, int nodeIndex, long seq, boolean demoted, int sender) {
        probe(url, "DELETE", "/cluster-deleted/" + nodeIndex + "?" + SEQ_PARAMETER + "=" + seq +
                (demoted ? "&" + DEMOTED_PARAMETER + "=true&" + SENDER_PARAMETER + "=" + sender : ""), null, null);
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

    // propagation calls use the probe timeout: a follower applies them without calling out, and one that does not
    // answer in time is resynchronized by the heartbeat sequence check instead of holding up the fan-out

    boolean checkMergeSharedObject(String url, int nodeIndex, ClusterService.MergeSharedObjectInfo mergeSharedObjectInfo) {
        return probe(url, "POST", "/check-merge-shared-object/" + nodeIndex, mergeSharedObjectInfo, BOOLEAN);
    }

    boolean checkDeleteSharedObject(String url, int nodeIndex, ClusterService.DeleteSharedObjectInfo deleteSharedObjectInfo) {
        return probe(url, "POST", "/check-delete-shared-object/" + nodeIndex, deleteSharedObjectInfo, BOOLEAN);
    }

    /** a batch of changes in one call; @throws NodeHttpClient.CallException with status 404 from a node of an older version */
    ClusterService.SharedObjectChangesResult checkSharedObjectChanges(String url, int nodeIndex, List<ClusterService.SharedObjectChange> changes) {
        return probe(url, "POST", "/check-shared-object-changes/" + nodeIndex, changes, SHARED_OBJECT_CHANGES_RESULT);
    }

    void overwriteSharedObject(String url, int nodeIndex, ClusterService.MergeSharedObjectInfo sharedObjectInfo) {
        probe(url, "POST", "/overwrite-shared-object/" + nodeIndex, sharedObjectInfo, null);
    }

    ClusterService.MergeSharedObjectInfo getSharedObject(String url) {
        return call(url, "GET", "/shared-object", null, SHARED_OBJECT_INFO);
    }

    /** @throws NodeHttpClient.CallException with status 404 when the node holds no replica of nodeIndex */
    ClusterService.MergeSharedObjectInfo getSharedObject(String url, int nodeIndex) {
        return call(url, "GET", "/shared-object/" + nodeIndex, null, SHARED_OBJECT_INFO);
    }

    ClusterService.SharedObjectDigests getSharedObjectDigests(String url) {
        return probe(url, "GET", "/shared-object-digest", null, SHARED_OBJECT_DIGESTS);
    }

    /** only the digest of the node's own object; a node of an older version ignores the parameter and lists every one */
    ClusterService.SharedObjectDigests getOwnSharedObjectDigest(String url) {
        return probe(url, "GET", "/shared-object-digest?" + OWN_DIGEST_PARAMETER + "=true", null, SHARED_OBJECT_DIGESTS);
    }

    /** removed: node-index -> sequence of the handed-over objects failed over on the demoted node since */
    void syncSharedObject(String url, int nodeIndex, ClusterService.SharedObject sharedObject, String handoverId, Map<Integer, Long> removed) {
        var query = new StringBuilder("?" + HANDOVER_PARAMETER + "=" + handoverId);
        for (var entry : removed.entrySet())
            query.append('&').append(REMOVED_PARAMETER).append('=').append(entry.getKey()).append(':').append(entry.getValue());
        call(url, "POST", "/sync-shared-object/" + nodeIndex + query, sharedObject, null);
    }

    /**
     * @return the node-indexes the leader declined because the node is its member (whose own object it gets from the
     *         node itself): offered again later. Empty from a leader of an older version, which answers nothing
     * @throws NodeHttpClient.CallException with status 404 from a leader of an older version, 503 from a node that does not lead
     */
    Set<Integer> offerSharedObject(String url, int nodeIndex, ClusterService.SharedObject sharedObject) {
        Set<Integer> declined = client.callOptional(url + basePath + "/offer-shared-object/" + nodeIndex, "POST", sharedObject, SET_OF_INTEGER);
        return declined == null ? Set.of() : declined;
    }

    /** probe timeout: the split-brain winner checks every peer, and one that does not answer must not hold up the others */
    Set<Integer> checkSharedObjectSeq(String url, Map<Integer, Long> sharedObjectSeq) {
        return probe(url, "POST", "/check-shared-object-seq", sharedObjectSeq, SET_OF_INTEGER);
    }

    private <T> T call(String url, String method, String path, Object body, JavaType responseType) {
        return client.call(url + basePath + path, method, body, responseType);
    }

    private <T> T probe(String url, String method, String path, Object body, JavaType responseType) {
        return client.call(url + basePath + path, method, body, responseType, Map.of(), probeTimeout);
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
