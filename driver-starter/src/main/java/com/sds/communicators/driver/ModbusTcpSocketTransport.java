package com.sds.communicators.driver;

import com.digitalpetri.modbus.MbapHeader;
import com.digitalpetri.modbus.ModbusTcpFrame;
import com.digitalpetri.modbus.client.ModbusTcpClientTransport;
import lombok.extern.slf4j.Slf4j;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Plain-socket Modbus/TCP client transport.
 * <p>
 * Keeps connection timeout and retry behavior under the driver lifecycle instead of
 * delegating it to the fsm-based default transport.
 */
@Slf4j
class ModbusTcpSocketTransport implements ModbusTcpClientTransport {
    private static final int MBAP_HEADER_LENGTH = 7;

    private final String hostname;
    private final int port;
    private final int connectTimeoutMillis;
    private final AtomicReference<Consumer<ModbusTcpFrame>> frameReceiver = new AtomicReference<>();

    private Socket socket = null;

    ModbusTcpSocketTransport(String hostname, int port, int connectTimeoutMillis) {
        this.hostname = hostname;
        this.port = port;
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    @Override
    public synchronized CompletionStage<Void> connect() {
        if (isConnected())
            return CompletableFuture.completedFuture(null);
        closeSocket();
        try {
            var s = new Socket();
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(hostname, port), connectTimeoutMillis);
            socket = s;
            var readThread = new Thread(() -> readLoop(s), "modbus-tcp-read-" + hostname + ":" + port);
            readThread.setDaemon(true);
            readThread.start();
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            closeSocket();
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public synchronized CompletionStage<Void> disconnect() {
        closeSocket();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    @Override
    public CompletionStage<Void> send(ModbusTcpFrame frame) {
        Socket s;
        synchronized (this) {
            s = socket;
        }
        if (s == null)
            return CompletableFuture.failedFuture(new IOException("modbus transport is not connected, " + hostname + ":" + port));
        try {
            var pdu = frame.pdu().duplicate();
            var buffer = ByteBuffer.allocate(MBAP_HEADER_LENGTH + pdu.remaining());
            buffer.putShort((short) frame.header().transactionId());
            buffer.putShort((short) frame.header().protocolId());
            buffer.putShort((short) (pdu.remaining() + 1));
            buffer.put((byte) frame.header().unitId());
            buffer.put(pdu);
            var out = s.getOutputStream();
            synchronized (out) {
                out.write(buffer.array());
                out.flush();
            }
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public void receive(Consumer<ModbusTcpFrame> frameReceiver) {
        this.frameReceiver.set(frameReceiver);
    }

    private void readLoop(Socket s) {
        try {
            var in = new DataInputStream(s.getInputStream());
            var header = new byte[MBAP_HEADER_LENGTH];
            while (!s.isClosed()) {
                in.readFully(header);
                var transactionId = ((header[0] & 0xFF) << 8) | (header[1] & 0xFF);
                var protocolId = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
                var length = ((header[4] & 0xFF) << 8) | (header[5] & 0xFF);
                var unitId = header[6] & 0xFF;
                if (length < 1)
                    throw new IOException("invalid mbap length field: " + length);
                var pdu = new byte[length - 1];
                in.readFully(pdu);
                var frame = new ModbusTcpFrame(new MbapHeader(transactionId, protocolId, length, unitId), ByteBuffer.wrap(pdu));
                var receiver = frameReceiver.get();
                if (receiver != null) {
                    try {
                        receiver.accept(frame);
                    } catch (Exception e) {
                        log.error("modbus frame receiver failed, {}:{}", hostname, port, e);
                    }
                }
            }
        } catch (IOException e) {
            // socket closed locally (disconnect) or by the peer - pending requests fail via the client's request timeout
            log.debug("modbus read loop terminated, {}:{}::{}", hostname, port, e.toString());
        } finally {
            synchronized (this) {
                if (socket == s)
                    closeSocket();
            }
        }
    }

    private void closeSocket() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                log.trace("close socket failed, {}:{}", hostname, port, e);
            }
            socket = null;
        }
    }
}
