package com.sds.communicators.cluster;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.CharStreams;
import com.sds.communicators.common.LoadBalancer;
import com.sds.communicators.common.type.NodeStatus;
import com.sds.communicators.common.type.Position;
import feign.*;
import feign.codec.EncodeException;
import io.reactivex.rxjava3.functions.Consumer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

class ClusterClient {
    private final Feign.Builder builder;
    private final Map<String, Map<Class<?>, Object>> clientMap = new ConcurrentHashMap<>();
    private final Map<Set<String>, LoadBalancer> loadBalancerMap = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    ClusterClient(int connectTimeoutMillis, int readTimeoutMillis) {
        builder = Feign.builder()
                .options(new Request.Options(Duration.ofMillis(connectTimeoutMillis), Duration.ofMillis(readTimeoutMillis), true))
                .retryer(Retryer.NEVER_RETRY)
                .encoder((o, type, requestTemplate) -> {
                    try {
                        if (o instanceof String)
                            requestTemplate.body((String) o);
                        else
                            requestTemplate.body(objectMapper.writeValueAsString(o));
                    } catch (JsonProcessingException e) {
                        throw new EncodeException("Error encoding request body", e);
                    }
                })
                .decoder((response, type) -> {
                    if (type == String.class)
                        return CharStreams.toString(response.body().asReader(StandardCharsets.UTF_8));
                    else
                        return objectMapper.readValue(response.body().asInputStream(), objectMapper.constructType(type));
                })
                .requestInterceptor(requestTemplate -> requestTemplate.header("Content-Type", "application/json"));
    }

    <T> void loadBalancedClient(Set<String> urls, Class<T> api, Consumer<T> run) throws Throwable {
        var loadBalancer = loadBalancerMap.compute(urls, (k, v) -> v == null ? new LoadBalancer(k.size()) : v);
        var clients = new ArrayList<T>();
        for (var url : urls) clients.add(getClient(url, api));

        var ret = loadBalancer.run(idx -> run.accept(clients.get(idx)));
        if (ret != null) throw ret;
    }

    <T> T getClient(String url, Class<T> api) {
        return (T)clientMap.compute(url, (k, v) -> v == null ? new ConcurrentHashMap<>() : v)
                .compute(api, (k, v) -> v == null ? builder.target(k, url) : v);
    }

    ClusterClientApi getClient(String url) {
        return getClient(url, ClusterClientApi.class);
    }

    void dispose() {
        for (var lb : loadBalancerMap.values())
            lb.clear();
    }

    interface ClusterClientApi {
        @RequestLine("PUT {clusterBasePath}/heartbeat/{nodeIndex}/{position}")
        void heartbeat(@Param("clusterBasePath") String clusterBasePath, @Param("nodeIndex") int nodeIndex, @Param("position") Position position, Map<Integer, Long> sharedObjectSeq);

        @RequestLine("GET {clusterBasePath}/node-status")
        NodeStatus getNodeStatus(@Param("clusterBasePath") String clusterBasePath);

        @RequestLine("PUT {clusterBasePath}/set-to-leader")
        void setToLeader(@Param("clusterBasePath") String clusterBasePath);

        @RequestLine("DELETE {clusterBasePath}/cluster-deleted/{nodeIndex}")
        void clusterDeleted(@Param("clusterBasePath") String clusterBasePath, @Param("nodeIndex") int nodeIndex);

        @RequestLine("DELETE {clusterBasePath}/remove-shared-object/{nodeIndex}")
        void removeSharedObject(@Param("clusterBasePath") String clusterBasePath, @Param("nodeIndex") int nodeIndex);

        @RequestLine("GET {clusterBasePath}/get-node-index")
        int getNodeIndex(@Param("clusterBasePath") String clusterBasePath);

        @RequestLine("POST {clusterBasePath}/merge-shared-object-to-leader/{nodeIndex}")
        void mergeSharedObjectToLeader(@Param("clusterBasePath") String clusterBasePath, @Param("nodeIndex") int nodeIndex, ClusterService.MergeSharedObjectInfo mergeSharedObjectInfo);

        @RequestLine("POST {clusterBasePath}/delete-shared-object-to-leader/{nodeIndex}")
        void deleteSharedObjectToLeader(@Param("clusterBasePath") String clusterBasePath, @Param("nodeIndex") int nodeIndex, ClusterService.DeleteSharedObjectInfo deleteSharedObjectInfo);

        @RequestLine("POST {clusterBasePath}/overwrite-shared-object/{nodeIndex}")
        void overwriteSharedObject(@Param("clusterBasePath") String clusterBasePath, @Param("nodeIndex") int nodeIndex, ClusterService.MergeSharedObjectInfo sharedObjectInfo);

        @RequestLine("POST {clusterBasePath}/check-merge-shared-object/{nodeIndex}")
        boolean checkMergeSharedObject(@Param("clusterBasePath") String clusterBasePath, @Param("nodeIndex") int nodeIndex, ClusterService.MergeSharedObjectInfo mergeSharedObjectInfo);

        @RequestLine("POST {clusterBasePath}/check-delete-shared-object/{nodeIndex}")
        boolean checkDeleteSharedObject(@Param("clusterBasePath") String clusterBasePath, @Param("nodeIndex") int nodeIndex, ClusterService.DeleteSharedObjectInfo deleteSharedObjectInfo);

        @RequestLine("GET {clusterBasePath}/get-shared-object")
        ClusterService.MergeSharedObjectInfo getSharedObject(@Param("clusterBasePath") String clusterBasePath);

        @RequestLine("GET {clusterBasePath}/get-shared-object/{nodeIndex}")
        ClusterService.MergeSharedObjectInfo getSharedObject(@Param("clusterBasePath") String clusterBasePath, @Param("nodeIndex") int nodeIndex);

        @RequestLine("POST {clusterBasePath}/sync-shared-object/{nodeIndex}")
        void syncSharedObject(@Param("clusterBasePath") String clusterBasePath, @Param("nodeIndex") int nodeIndex, ClusterService.SharedObject sharedObject);

        @RequestLine("POST {clusterBasePath}/check-shared-object-sequence")
        Set<Integer> checkSharedObjectSeq(@Param("clusterBasePath") String clusterBasePath, Map<Integer, Long> sharedObjectSeq);
    }
}
