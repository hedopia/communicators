package com.sds.communicators.driver;

import com.sds.communicators.common.UtilFunc;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.javatuples.Pair;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.DisposableChannel;
import reactor.netty.NettyOutbound;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.tcp.TcpClient;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;

@Slf4j
public class DriverProtocolTcpClient extends DriverProtocolTcpUdp {
    private Channel channel = null;

    @Override
    void initialize(String connectionInfo, Map<String, String> option) throws Exception {
        super.initialize(connectionInfo, option);
        TcpInitialize(option);
    }

    @Override
    DisposableChannel makeChannel(String host, int port) {
        return TcpClient.create()
                .wiretap(true)
                .host(host)
                .port(port)
                .doOnConnected(c -> {
                    log.trace("[{}] channel connected", deviceId);
                    channel = c.channel();
                    bufferingInfo.put(c.outbound(), new Socket());
                })
                .doOnDisconnected(c -> {
                    log.trace("[{}] channel disconnected", deviceId);
                    bufferingInfo.remove(c.outbound());
                    if (!device.isConnectionCommand())
                        setConnectionLost();
                })
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handle((in, out) -> {
                    var address = (InetSocketAddress) ((ChannelOperations)in).channel().remoteAddress();
                    return buffering(in.receive().asByteArray().map(bytes -> new Pair<>(bytes, address)), out);
                })
                .connectNow(Duration.ofMillis(socketTimeout));
    }


    @Override
    protected void requestDisconnect() throws Exception {
        super.requestDisconnect();
        if (channel != null) {
            try {
                channel.close().get();
            } catch (Exception e) {
                log.error("[{}] closing channel error", deviceId, e);
            }
        }
    }

    @Override
    protected void sendString(RequestInfo requestInfo) throws Exception {
        throw new Exception("sendString with RequestInfo is not defined for tcp-client");
    }

    @Override
    protected void sendString(String msg, NettyOutbound outbound) {
        log.debug("[{}] send data: {}", deviceId, msg);
        var bytes = UtilFunc.stringToByteArray(msg);
        syncExecute(() -> ((Connection)channel).outbound().sendByteArray(Mono.just(bytes)).then().block());
    }
}
