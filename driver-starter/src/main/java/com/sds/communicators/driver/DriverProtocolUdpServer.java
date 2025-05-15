package com.sds.communicators.driver;

import com.google.common.base.Strings;
import com.sds.communicators.common.UtilFunc;
import io.netty.buffer.Unpooled;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.InternetProtocolFamily;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.DisposableChannel;
import reactor.netty.udp.UdpServer;

import java.net.*;
import java.time.Duration;
import java.util.*;

@Slf4j
public class DriverProtocolUdpServer extends DriverProtocolTcpUdp {
    private final List<InetAddress> multicastGroup = new ArrayList<>();
    private boolean isIPv4;

    @Override
    void initialize(String connectionInfo, Map<String, String> option) throws Exception {
        connectionLostOnException = false;
        super.initialize(connectionInfo, option);
        UdpInitialize(option);

        if (!Strings.isNullOrEmpty(option.get("multicastGroup"))) {
            for (var address : option.get("multicastGroup").split(",")) {
                multicastGroup.add(InetAddress.getByName(address));
            }
        }

        if (!multicastGroup.isEmpty()) {
            if (multicastGroup.stream().allMatch(group -> group instanceof Inet4Address))
                isIPv4 = true;
            else if (multicastGroup.stream().allMatch(group -> group instanceof Inet6Address))
                isIPv4 = false;
            else
                throw new Exception("invalid multicast addresses " + multicastGroup);
        }
    }

    @Override
    DisposableChannel makeChannel(String host, int port) throws Exception {
        var server = UdpServer.create().wiretap(true);
        if (!Strings.isNullOrEmpty(host))
            server = server.host(host);
        else
            server = server.host("0.0.0.0");

        Enumeration<NetworkInterface> iFaces;
        if (!multicastGroup.isEmpty()) {
            if (isIPv4)
                server = server.runOn(server.configuration().loopResources(), InternetProtocolFamily.IPv4);
            else
                server = server.runOn(server.configuration().loopResources(), InternetProtocolFamily.IPv6);
            iFaces = NetworkInterface.getNetworkInterfaces();
        } else {
            iFaces = Collections.enumeration(Collections.emptyList());
        }

        return server
                .port(port)
                .doOnBound(c -> bufferingInfo.put(c.outbound(), new Socket()))
                .doOnUnbound(c -> bufferingInfo.remove(c.outbound()))
                .handle((in, out) -> {
                    while (iFaces.hasMoreElements()) {
                        NetworkInterface iFace = iFaces.nextElement();
                        for (var group : multicastGroup) {
                            if (isMulticastEnabledInterface(iFace))
                                in.join(group, iFace);
                        }
                    }

                    return udpBuffering(in.receiveObject(), out);
                })
                .bindNow(Duration.ofMillis(socketTimeout));
    }

    private boolean isMulticastEnabledInterface(NetworkInterface iFace) {
        try {
            if (!iFace.supportsMulticast() || !iFace.isUp()) {
                return false;
            }
        }
        catch (SocketException se) {
            return false;
        }

        for (Enumeration<InetAddress> i = iFace.getInetAddresses(); i.hasMoreElements(); ) {
            InetAddress address = i.nextElement();
            if ((isIPv4 && address.getClass() == Inet4Address.class) ||
                    (!isIPv4 && address.getClass() == Inet6Address.class))
                return true;
        }

        return false;
    }

    @Override
    protected void sendString(RequestInfo requestInfo) {
        var socketAddress = new InetSocketAddress(requestInfo.host, requestInfo.port);
        log.debug("[{}] send to {}, data: {}", deviceId, socketAddress, requestInfo.msg);
        ((Connection)channel).outbound()
                .sendObject(
                        Mono.just(new DatagramPacket(Unpooled.copiedBuffer(UtilFunc.stringToByteArray(requestInfo.msg)),
                                socketAddress)))
                .then().block();
    }

    @Override
    protected void sendString(String msg) throws Exception {
        throw new Exception("sendString with msg is not defined for udp-server");
    }
}
