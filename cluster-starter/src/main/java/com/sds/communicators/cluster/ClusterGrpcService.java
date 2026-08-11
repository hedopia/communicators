package com.sds.communicators.cluster;

import com.sds.communicators.common.type.NodeStatus;
import com.sds.communicators.common.type.Position;
import io.grpc.ServerCallHandler;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCalls;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.function.Function;

/**
 * gRPC server implementation for cluster internal node-to-node communication.
 * Each handler keeps the exact semantics of the former HTTP internal routes
 * (ClusterServerRoutes.internal()); former 400 responses map to FAILED_PRECONDITION.
 */
@Slf4j
class ClusterGrpcService {
    private final ClusterStarter clusterStarter;
    private final ClusterService clusterService;

    ClusterGrpcService(ClusterStarter clusterStarter, ClusterService clusterService) {
        this.clusterStarter = clusterStarter;
        this.clusterService = clusterService;
    }

    ServerServiceDefinition bindService() {
        return ServerServiceDefinition.builder(ClusterGrpc.SERVICE_NAME)
                .addMethod(ClusterGrpc.HEARTBEAT, unary("heartbeat", request -> {
                    if (clusterStarter.nodeIndex != request.nodeIndex)
                        clusterService.heartbeatReceived(request.nodeIndex, request.position, request.lastTransitionTime, request.sharedObjectSeq);
                    return new ClusterGrpc.Empty();
                }))
                .addMethod(ClusterGrpc.GET_NODE_STATUS, unary("getNodeStatus", request -> {
                    if (clusterStarter.isPrepared)
                        return new NodeStatus(clusterStarter.nodeIndex, clusterStarter.position, clusterStarter.isActivated);
                    else
                        throw Status.FAILED_PRECONDITION.withDescription("application is not prepared, get status ignored").asRuntimeException();
                }))
                .addMethod(ClusterGrpc.SET_TO_LEADER, unary("setToLeader", request -> {
                    if (clusterStarter.isPrepared) {
                        clusterService.transition(Position.LEADER);
                        return new ClusterGrpc.Empty();
                    } else {
                        throw Status.FAILED_PRECONDITION.withDescription("application is not prepared, set to leader ignored").asRuntimeException();
                    }
                }))
                .addMethod(ClusterGrpc.CLUSTER_DELETED, unary("clusterDeleted", request -> {
                    if (clusterStarter.nodeIndex != request.nodeIndex)
                        clusterService.clusterDeleted(request.nodeIndex);
                    return new ClusterGrpc.Empty();
                }))
                .addMethod(ClusterGrpc.REMOVE_SHARED_OBJECT, unary("removeSharedObject", request -> {
                    if (clusterStarter.nodeIndex != request.nodeIndex)
                        clusterService.removeSharedObject(request.nodeIndex);
                    return new ClusterGrpc.Empty();
                }))
                .addMethod(ClusterGrpc.GET_NODE_INDEX, unary("getNodeIndex", request -> clusterStarter.nodeIndex))
                .addMethod(ClusterGrpc.MERGE_SHARED_OBJECT_TO_LEADER, unary("mergeSharedObjectToLeader", request -> {
                    var ret = clusterService.setSharedObjectToLeader(request.nodeIndex, request.info);
                    if (ret == null)
                        return new ClusterGrpc.Empty();
                    else
                        throw Status.FAILED_PRECONDITION.withDescription(ret).asRuntimeException();
                }))
                .addMethod(ClusterGrpc.DELETE_SHARED_OBJECT_TO_LEADER, unary("deleteSharedObjectToLeader", request -> {
                    var ret = clusterService.setSharedObjectToLeader(request.nodeIndex, request.info);
                    if (ret == null)
                        return new ClusterGrpc.Empty();
                    else
                        throw Status.FAILED_PRECONDITION.withDescription(ret).asRuntimeException();
                }))
                .addMethod(ClusterGrpc.CHECK_MERGE_SHARED_OBJECT, unary("checkMergeSharedObject", request -> {
                    if (clusterStarter.nodeIndex != request.nodeIndex)
                        return clusterService.checkSharedObject(request.nodeIndex, request.info);
                    else
                        return true;
                }))
                .addMethod(ClusterGrpc.CHECK_DELETE_SHARED_OBJECT, unary("checkDeleteSharedObject", request -> {
                    if (clusterStarter.nodeIndex != request.nodeIndex)
                        return clusterService.checkSharedObject(request.nodeIndex, request.info);
                    else
                        return true;
                }))
                .addMethod(ClusterGrpc.OVERWRITE_SHARED_OBJECT, unary("overwriteSharedObject", request -> {
                    if (clusterStarter.nodeIndex != request.nodeIndex)
                        clusterService.overwriteSharedObject(request.nodeIndex, request.info);
                    return new ClusterGrpc.Empty();
                }))
                .addMethod(ClusterGrpc.GET_SHARED_OBJECT, unary("getSharedObject", request ->
                        new ClusterService.MergeSharedObjectInfo(clusterService.sharedObjectSeq.get(clusterStarter.nodeIndex),
                                clusterService.sharedObject.get(clusterStarter.nodeIndex))))
                .addMethod(ClusterGrpc.GET_SHARED_OBJECT_OF, unary("getSharedObjectOf", request ->
                        new ClusterService.MergeSharedObjectInfo(clusterService.sharedObjectSeq.get(request.nodeIndex),
                                clusterService.sharedObject.get(request.nodeIndex))))
                .addMethod(ClusterGrpc.SYNC_SHARED_OBJECT, unary("syncSharedObject", request -> {
                    if (clusterStarter.nodeIndex != request.nodeIndex)
                        clusterService.syncSharedObject(request.sharedObject);
                    return new ClusterGrpc.Empty();
                }))
                .addMethod(ClusterGrpc.CHECK_SHARED_OBJECT_SEQ, unary("checkSharedObjectSeq", sharedObjectSeq -> {
                    var result = new HashSet<Integer>();
                    for (var nodeIndex : sharedObjectSeq.keySet()) {
                        if (clusterStarter.nodeIndex != nodeIndex) {
                            if (!clusterService.sharedObjectSeq.containsKey(nodeIndex) ||
                                    !clusterService.sharedObjectSeq.get(nodeIndex).equals(sharedObjectSeq.get(nodeIndex)))
                                result.add(nodeIndex);
                        }
                    }
                    return result;
                }))
                .build();
    }

    private <Req, Res> ServerCallHandler<Req, Res> unary(String name, Function<Req, Res> handler) {
        return ServerCalls.asyncUnaryCall((request, responseObserver) -> {
            log.trace("grpc request: {}", name);
            try {
                responseObserver.onNext(handler.apply(request));
                responseObserver.onCompleted();
            } catch (StatusRuntimeException e) {
                responseObserver.onError(e);
            } catch (Exception e) {
                responseObserver.onError(Status.INTERNAL.withDescription(name + " failed::" + e.getMessage()).asRuntimeException());
            }
        });
    }
}
