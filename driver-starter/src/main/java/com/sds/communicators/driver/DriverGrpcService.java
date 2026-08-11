package com.sds.communicators.driver;

import io.grpc.ServerCallHandler;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCalls;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Function;

/**
 * gRPC server implementation for driver internal node-to-node communication.
 * Each handler keeps the exact semantics of the corresponding HTTP internal route.
 */
@Slf4j
class DriverGrpcService {
    private final DriverService driverService;

    DriverGrpcService(DriverService driverService) {
        this.driverService = driverService;
    }

    ServerServiceDefinition bindService() {
        return ServerServiceDefinition.builder(DriverGrpc.SERVICE_NAME)
                .addMethod(DriverGrpc.CONNECT_ALL_TO_INDEX, unary("connectAllToIndex",
                        driverService::connectAll))
                .addMethod(DriverGrpc.CONNECT_ALL_TO_LEADER, unary("connectAllToLeader", request ->
                        driverService.connectAllToLeader(request.nodeIndex, request.devices)))
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
