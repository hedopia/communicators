package com.sds.communicators.driver;

import com.google.common.base.Strings;
import com.sds.communicators.common.UtilFunc;
import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.javatuples.Pair;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableChannel;
import reactor.netty.NettyOutbound;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.tcp.TcpServer;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class DriverProtocolTcpServer extends DriverProtocolTcpUdp {
    private final ConcurrentHashMap<InetSocketAddress, NettyOutbound> outboundMap = new ConcurrentHashMap<>();
    private final Set<Channel> channels = ConcurrentHashMap.newKeySet();

    @Override
    void initialize(String connectionInfo, Map<String, String> option) throws Exception {
        connectionLostOnException = false;
        super.initialize(connectionInfo, option);
        TcpInitialize(option);
    }

    @Override
    DisposableChannel makeChannel(String host, int port) {
        var server = TcpServer.create().wiretap(true);
        if (!Strings.isNullOrEmpty(host))
            server = server.host(host);

        return server
                .port(port)
                .doOnConnection(c -> {
                    var address = (InetSocketAddress) c.channel().remoteAddress();
                    outboundMap.put(address, c.outbound());
                    channels.add(c.channel());
                    bufferingInfo.put(c.outbound(), new Socket());
                    c.onDispose(() -> {
                        channels.remove(c.channel());
                        outboundMap.remove(address);
                        bufferingInfo.remove(c.outbound());
                    });
                })
                .handle((in, out) -> {
                    var address = (InetSocketAddress) ((ChannelOperations)in).channel().remoteAddress();
                    return buffering(in.receive().asByteArray().map(bytes -> new Pair<>(bytes, address)), out);
                })
                .bindNow(Duration.ofMillis(socketTimeout));
    }

    @Override
    protected void requestDisconnect() throws Exception {
        super.requestDisconnect();
        for (Channel channel : channels)
            channel.close().get();
    }

    @Override
    protected void sendString(RequestInfo requestInfo) throws Exception {
        var key = new InetSocketAddress(requestInfo.host, requestInfo.port);
        if (outboundMap.containsKey(key)) {
            log.debug("[{}] send to {}, data: {}", deviceId, key, requestInfo.msg);
            outboundMap.get(key).sendByteArray(Mono.just(UtilFunc.stringToByteArray(requestInfo.msg))).then().block();
        } else {
            throw new Exception(key + " is not connected");
        }
    }

    @Override
    protected void sendString(String msg) {
        var bytes = UtilFunc.stringToByteArray(msg);
        for (var entry : outboundMap.entrySet()) {
            log.debug("[{}] send to {}, data: {}", deviceId, entry.getKey(), msg);
            entry.getValue().sendByteArray(Mono.just(bytes)).then().block();
        }
    }
}
