package com.sds.communicators.driver;

import com.sds.communicators.common.UtilFunc;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.DisposableChannel;
import reactor.netty.NettyOutbound;
import reactor.netty.udp.UdpClient;

import java.time.Duration;
import java.util.Map;

@Slf4j
public class DriverProtocolUdpClient extends DriverProtocolTcpUdp {

    @Override
    void initialize(String connectionInfo, Map<String, String> option) throws Exception {
        super.initialize(connectionInfo, option);
        UdpInitialize(option);
    }

    @Override
    DisposableChannel makeChannel(String host, int port) {
        return UdpClient.create()
                .wiretap(true)
                .host(host)
                .port(port)
                .doOnConnected(c -> bufferingInfo.put(c.outbound(), new Socket()))
                .doOnDisconnected(c -> {
                    bufferingInfo.remove(c.outbound());
                    if (!device.isConnectionCommand())
                        setConnectionLost();
                })
                .handle((in, out) -> udpBuffering(in.receiveObject(), out))
                .connectNow(Duration.ofMillis(socketTimeout));
    }

    @Override
    protected void sendString(RequestInfo requestInfo) throws Exception {
        throw new Exception("sendString with RequestInfo is not defined for udp-client");
    }

    @Override
    protected void sendString(String msg, NettyOutbound outbound) {
        log.debug("[{}] send data: {}", deviceId, msg);
        var bytes = UtilFunc.stringToByteArray(msg);
        syncExecute(() -> ((Connection)channel).outbound().sendByteArray(Mono.just(bytes)).then().block());
    }
}
