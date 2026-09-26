package com.sds.communicators.driver;

import com.sds.communicators.common.struct.Command;
import com.sds.communicators.common.struct.Device;
import com.sds.communicators.common.type.CommandType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TcpUdpBufferingTest {
    private final List<DriverProtocolTcpUdp> protocols = new ArrayList<>();
    private final BlockingQueue<String> received = new LinkedBlockingQueue<>();

    @AfterEach
    void close() throws Exception {
        for (var protocol : protocols) {
            protocol.requestDisconnect();
            protocol.driverCommand.pythonEngine.close();
        }
    }

    private <T extends DriverProtocolTcpUdp> T connect(T protocol, String url, Command command) throws Exception {
        var device = new Device();
        device.setId("tcpudp");
        device.setConnectionUrl(url);
        device.setCommands(Set.of(command));
        protocol.create(null, "", device);
        protocols.add(protocol);
        protocol.driverCommand.pythonEngine.set("received", received);
        protocol.requestConnect();
        return protocol;
    }

    private static int port(DriverProtocolTcpUdp protocol) {
        return ((InetSocketAddress) protocol.channel.address()).getPort();
    }

    /** non-periodic read-request event that records every received frame as text */
    private static Command recordingEvent() {
        var command = new Command();
        command.setId("record");
        command.setType(CommandType.READ_REQUEST);
        command.setCmdScript("def cmdFunc(data):\n    received.add(''.join(chr(b) for b in data))\n    return None\n");
        return command;
    }

    private static void write(Socket client, String data) throws Exception {
        client.getOutputStream().write(data.getBytes(StandardCharsets.US_ASCII));
        client.getOutputStream().flush();
    }

    private static void sleepUntil(long deadline) throws InterruptedException {
        var remaining = deadline - System.currentTimeMillis();
        if (remaining > 0)
            Thread.sleep(remaining);
    }

    @Test
    void tcpServerDeliversBufferedDataAfterThePeerClosed() throws Exception {
        // no endBytes/bufferingFunc -> 100 ms buffer-time, which expires after the connection is gone
        var server = connect(new DriverProtocolTcpServer(), "tcp-server://127.0.0.1:0", recordingEvent());
        try (var client = new Socket("127.0.0.1", port(server))) {
            write(client, "short");
        }
        assertEquals("short", received.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void completedFrameTimerDoesNotDiscardTheNextPartialFrame() throws Exception {
        var endBytes = URLEncoder.encode("\\x0D\\x0A", StandardCharsets.UTF_8);
        var server = connect(new DriverProtocolTcpServer(),
                "tcp-server://127.0.0.1:0?endBytes=" + endBytes + "&bufferTime=1000", recordingEvent());
        try (var client = new Socket("127.0.0.1", port(server))) {
            client.setTcpNoDelay(true);
            var start = System.currentTimeMillis();
            // "ab" arms a 1000 ms timer that must not fire into the "de" frame after "abc" completed:
            // it would fire at ~1000 ms, 300 ms after "de" and 300 ms before "f\r\n", while the timer armed
            // by "de" itself fires at ~1700 ms, 400 ms after that frame completed
            write(client, "ab");
            sleepUntil(start + 50);
            write(client, "c\r\n");
            sleepUntil(start + 700);
            write(client, "de");
            sleepUntil(start + 1300);
            write(client, "f\r\n");

            assertEquals("abc", received.poll(5, TimeUnit.SECONDS));
            assertEquals("def", received.poll(5, TimeUnit.SECONDS));
            assertNull(received.poll(500, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void udpServerRepliesToTheSenderOfTheDatagram() throws Exception {
        var reply = new Command();
        reply.setId("reply");
        reply.setType(CommandType.WRITE_REQUEST);
        reply.setRequestInfo("pong");
        var server = connect(new DriverProtocolUdpServer(), "udp-server://127.0.0.1:0", reply);

        try (var client = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            client.setSoTimeout(5000);
            var ping = "ping".getBytes(StandardCharsets.US_ASCII);
            client.send(new DatagramPacket(ping, ping.length, InetAddress.getByName("127.0.0.1"), port(server)));
            var packet = new DatagramPacket(new byte[64], 64);
            client.receive(packet);
            assertEquals("pong", new String(packet.getData(), 0, packet.getLength(), StandardCharsets.US_ASCII));
        }
    }
}
