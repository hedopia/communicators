package com.sds.communicators.cluster;

import com.fasterxml.jackson.databind.type.TypeFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Content that diverged at an equal sequence number, which heartbeats cannot see, is repaired by the digest comparison. */
class AntiEntropyTest {

    @Test
    void equalSequenceDivergenceIsRepaired() throws Exception {
        try (var cluster = new TestCluster(3, 200, 1, 1, null)) {
            cluster.startAll();
            var all = cluster.all();
            cluster.node(3).mergeSharedObject(Map.of("a", 1, "b", Map.of("c", 2)));
            cluster.node(2).mergeSharedObject(Map.of("x", 1));
            cluster.awaitConsistent(all, Duration.ofSeconds(5));
            var sequences = cluster.service(2).copySharedObjectSeq();

            // changed without a sequence change: a follower's copy of node 3, and the leader's copy of node 2
            cluster.service(2).readSharedObject(map -> map.get(3).put("a", 99));
            cluster.service(1).readSharedObject(map -> map.get(2).remove("x"));
            assertFalse(cluster.divergences(all).isEmpty());
            assertNotEquals(cluster.service(1).digests().digests.get(2).digest, cluster.service(2).digests().digests.get(2).digest);

            // compared every 5 heartbeats (1 s)
            cluster.awaitConsistent(all, Duration.ofSeconds(10));
            assertEquals(sequences, cluster.service(2).copySharedObjectSeq());
            assertEquals(Map.of("a", 1, "b", Map.of("c", 2)), cluster.node(2).getSharedObjectMap().get(3));

            // the leader asks each member for the digest of the member's own object only
            var client = cluster.node(1).getNodeHttpClient();
            var digests = TypeFactory.defaultInstance().constructType(ClusterService.SharedObjectDigests.class);
            String url = cluster.node(2).nodeUrl + "/cluster" + ClusterInternalClient.INTERNAL_PATH + "/shared-object-digest";
            ClusterService.SharedObjectDigests own = client.call(url + "?own=true", "GET", null, digests);
            assertEquals(2, own.nodeIndex);
            assertEquals(Set.of(2), own.digests.keySet());
            assertEquals(cluster.service(2).digests().digests.get(2).digest, own.digests.get(2).digest);
            ClusterService.SharedObjectDigests every = client.call(url, "GET", null, digests);
            assertEquals(Set.of(1, 2, 3), every.digests.keySet());
        }
    }
}
