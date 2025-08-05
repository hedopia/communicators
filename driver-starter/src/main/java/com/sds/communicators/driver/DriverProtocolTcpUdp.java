package com.sds.communicators.driver;

import com.google.common.base.Strings;
import com.google.common.primitives.Ints;
import com.sds.communicators.common.UtilFunc;
import com.sds.communicators.common.struct.Response;
import io.netty.channel.socket.DatagramPacket;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.extern.slf4j.Slf4j;
import org.javatuples.Pair;
import org.javatuples.Triplet;
import org.python.core.*;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.netty.DisposableChannel;
import reactor.netty.NettyOutbound;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Slf4j
abstract class DriverProtocolTcpUdp extends DriverProtocol {
    private final BlockingQueue<Triplet<String, PyObject[], Long>> requestedDataQueue = new ArrayBlockingQueue<>(10);
    private String host;
    private int port;
    private Byte[] startBytes = null;
    private Byte[] endBytes = null;
    private boolean retainStartEndBytes = false;
    private boolean combineBufferedData = true;
    private int bufferTime;
    private Disposable bufferTimeDisposable = null;
    private Disposable bufferingDisposable = null;
    private PyFunction protocolFunc = null;
    private PyFunction bufferingFunc = null;

    protected DisposableChannel channel = null;
    protected final ConcurrentHashMap<NettyOutbound, Socket> bufferingInfo = new ConcurrentHashMap<>();

    abstract DisposableChannel makeChannel(String host, int port) throws Exception;
    protected abstract void sendString(RequestInfo requestInfo) throws Exception;
    protected abstract void sendString(String msg, NettyOutbound outbound) throws Exception;

    @Override
    void initialize(String connectionInfo, Map<String, String> option) throws Exception {
        var protocolScript = device.getProtocolScript();
        if (!Strings.isNullOrEmpty(protocolScript)) {
            try {
                protocolScript = protocolScript.replaceFirst("def[ \t]+protocolFunc[ \t]*\\(", "def protocolFunc_" + deviceId + "(");
                protocolScript = protocolScript.replaceFirst("def[ \t]+bufferingFunc[ \t]*\\(", "def bufferingFunc_" + deviceId + "(");
                driverCommand.pythonInterpreter.exec(protocolScript);
                protocolFunc = (PyFunction) driverCommand.pythonInterpreter.get("protocolFunc_" + deviceId);
                bufferingFunc = (PyFunction) driverCommand.pythonInterpreter.get("bufferingFunc_" + deviceId);
            } catch (Exception e) {
                throw new Exception("compile protocol script failed::" + e.getMessage(), e);
            }
        }

        var hostPort = UtilFunc.extractIpPort(connectionInfo);
        host = hostPort[0];
        port = Integer.parseInt(hostPort[1]);

        startBytes = UtilFunc.arrayWrapper(UtilFunc.stringToByteArray(option.get("startBytes")));
        endBytes = UtilFunc.arrayWrapper(UtilFunc.stringToByteArray(option.get("endBytes")));
        retainStartEndBytes = Boolean.parseBoolean(option.get("retainStartEndBytes"));
        if (option.get("combineBufferedData") != null)
            combineBufferedData = Boolean.parseBoolean(option.get("combineBufferedData"));
    }

    protected void TcpInitialize(Map<String, String> option) {
        Integer bufferTimeInput = option.get("bufferTime") == null ? null : Ints.tryParse(option.get("bufferTime"));
        if (bufferTimeInput != null) {
            bufferTime = bufferTimeInput;
            if (bufferTime < 0)
                bufferTime = 0;
        } else {
            if (endBytes == null && bufferingFunc == null)
                bufferTime = 100;
            else
                bufferTime = 0;
        }
    }

    protected void UdpInitialize(Map<String, String> option) {
        Integer bufferTimeInput = option.get("bufferTime") == null ? null : Ints.tryParse(option.get("bufferTime"));
        if (bufferTimeInput != null) {
            bufferTime = bufferTimeInput;
            if (bufferTime < 0)
                bufferTime = 0;
        } else {
            bufferTime = 0;
        }
    }

    @Override
    void requestConnect() throws Exception {
        log.info("[{}] host={}, port={}, socket-timeout={}", deviceId, host, port, socketTimeout);
        channel = makeChannel(host, port);
    }

    @Override
    void requestDisconnect() throws Exception {
        if (bufferTimeDisposable != null && !bufferTimeDisposable.isDisposed())
            bufferTimeDisposable.dispose();
        if (bufferingDisposable != null && !bufferingDisposable.isDisposed())
            bufferingDisposable.dispose();

        if (channel != null)
            channel.disposeNow(Duration.ofMillis(socketTimeout));
    }

    @Override
    List<Response> requestCommand(String cmdId, String requestInfo, int timeout, boolean isReadCommand, PyFunction function, PyObject initialValue, Object nonPeriodicObject) throws Exception {
        log.trace("[{}] send byte array: {}", deviceId, requestInfo);
        requestedDataQueue.clear();
        NettyOutbound outbound = null;
        if (nonPeriodicObject != null && !(nonPeriodicObject instanceof NettyOutbound))
            throw new Exception("nonPeriodicObject has wrong variable type: " + nonPeriodicObject.getClass());
        else if (nonPeriodicObject != null)
            outbound = (NettyOutbound) nonPeriodicObject;
        var obj = objectMapper.readValue(requestInfo, Object.class);
        if (obj instanceof Map) {
            var map = (Map<?, ?>) obj;
            var req = new RequestInfo(map.get("message"), map.get("host"), map.get("port"));
            log.trace("sendString as RequestInfo: {}", requestInfo);
            sendString(req);
        } else {
            log.trace("sendString as String: {}", obj.toString());
            sendString(obj.toString(), outbound);
        }
        if (!isReadCommand) return null;
        return requestCommand(cmdId, timeout, function, requestedDataQueue, initialValue);
    }

    protected Publisher<Void> udpBuffering(Flux<?> flux, NettyOutbound outbound) {
        return buffering(flux.map(obj -> {
            var data = (DatagramPacket) obj;
            var buf = data.content();
            var bytes = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), bytes);
            return new Pair<>(bytes, data.sender());
        }), outbound);
    }

    protected Publisher<Void> buffering(Flux<Pair<byte[], InetSocketAddress>> flux, NettyOutbound outbound) {
        flux = flux.map(pair -> {
            log.trace("[{}] received raw data from {}: {}", deviceId, pair.getValue1(), UtilFunc.printByteData(pair.getValue0()));
            return pair.getValue0() == null ? new Pair<>(new byte[]{}, pair.getValue1()) : pair;
        });
        if (bufferTime == 0 && bufferingFunc == null && endBytes == null) {
            return flux
                    .doOnNext(pair -> buffering(
                            new Packet(pair.getValue1(), UtilFunc.arrayWrapper(pair.getValue0()), combineBufferedData), outbound)).then();
        } else {
            return flux.doOnNext(pair -> buffering(pair, outbound, true)).then();
        }
    }

    private void buffering(Pair<byte[], InetSocketAddress> pair, NettyOutbound outbound, boolean isFirst) {
        try {
            var socket = bufferingInfo.get(outbound);
            if (socket == null) {
                log.error("[{}] bufferingInfo is null for {}", deviceId, outbound);
                return;
            }
            socket.lock.lockInterruptibly();
            try {
                var data = UtilFunc.arrayWrapper(pair.getValue0());
                var sender = pair.getValue1();
                var packet = socket.senderDataMap.compute(sender, (k, v) -> v == null ? new Packet(k) : v);

                packet.compositeData.add(data);
                if (bufferingFunc != null || !combineBufferedData)
                    packet.addPyList(data);
                if ((bufferingFunc == null && endBytes != null) || combineBufferedData)
                    packet.writeBuffer(data);

                if (isFirst && bufferingFunc != null) {
                    if (bufferingFunction(socket, packet, data, pair.getValue1(), outbound)) return;
                } else if (isFirst && endBytes != null) {
                    if (checkEndBytes(socket, packet, data, pair.getValue1(), outbound)) return;
                }

                if (packet.isFirstPacket && bufferTime != 0) {
                    packet.isFirstPacket = false;
                    bufferTimeDisposable = Schedulers.io()
                            .scheduleDirect(() -> {
                                try {
                                    var s = bufferingInfo.get(outbound);
                                    s.lock.lockInterruptibly();
                                    try {
                                        var p = s.senderDataMap.remove(packet.sender);
                                        if (p != null) {
                                            if (bufferingFunc == null && endBytes == null)
                                                buffering(p, outbound);
                                            else
                                                log.error("[{}] buffering timeout ({} [ms]):" + p.compositeData.stream().map(UtilFunc::printByteData).collect(Collectors.joining("")), deviceId, bufferTime);
                                        }
                                    } finally {
                                        s.lock.unlock();
                                    }
                                } catch (InterruptedException ignored) {
                                }
                            }, bufferTime, TimeUnit.MILLISECONDS);
                }
            } finally {
                socket.lock.unlock();
            }
        } catch (InterruptedException e) {
            log.debug("[{}] buffering thread interrupted ({})", deviceId, outbound);
        }
    }

    private boolean bufferingFunction(Socket socket, Packet packet, Byte[] data, InetSocketAddress socketAddress, NettyOutbound outbound) {
        try {
            PyObject funcResult = bufferingFunc.__call__(packet.pyListBuffer);
            if (funcResult instanceof PyBoolean) {
                if (((PyBoolean) funcResult).getBooleanValue()) {
                    socket.senderDataMap.remove(packet.sender);
                    buffering(packet, outbound);
                    return true;
                } else {
                    log.trace("[{}] buffering function continue, data: {}", deviceId, UtilFunc.printByteData(data));
                    return false;
                }
            } else if (funcResult instanceof PyList) {
                var list = (PyList) funcResult;
                var arr = new byte[list.size()];
                for (int i = 0; i < list.size(); i++)
                    arr[i] = ((Integer) list.get(i)).byteValue();
                socket.senderDataMap.remove(packet.sender);
                buffering(packet, outbound);
                buffering(new Pair<>(arr, socketAddress), outbound, false);
                return true;
            } else if (funcResult instanceof PyNone) {
                log.trace("[{}] buffering-function return none, clear buffer", deviceId);
            } else {
                log.error("[{}] buffering-function failed, wrong return type: {}", deviceId, funcResult);
            }
        } catch (Exception e) {
            log.error("[{}] buffering-function failed", deviceId, e);
        }
        socket.senderDataMap.remove(packet.sender);
        return true;
    }

    private boolean checkEndBytes(Socket socket, Packet packet, Byte[] data, InetSocketAddress socketAddress, NettyOutbound outbound) {
        var combinedData = packet.getBuffer();
        var endBytesIdx = UtilFunc.findFirst(combinedData, endBytes, true);
        if (endBytesIdx == -1) {
            log.trace("[{}] buffering end-bytes continue, data: {}", deviceId, UtilFunc.printByteData(data));
            return false;
        } else {
            int size = combinedData.length - endBytesIdx - endBytes.length;
            var arr = new byte[size];
            int start = endBytesIdx + endBytes.length;
            for (int i = 0; i < size; i++)
                arr[i] = combinedData[i + start];
            socket.senderDataMap.remove(packet.sender);
            if (arr.length == 0) {
                buffering(packet, outbound);
            } else {
                if (!combineBufferedData) {
                    var last = (PyList) packet.pyListBuffer.pyget(packet.pyListBuffer.size() - 1);
                    for (int i = 0; i < arr.length; i++)
                        last.remove(last.size() - 1);
                }
                buffering(packet, outbound);
                buffering(new Pair<>(arr, socketAddress), outbound, false);
            }
            return true;
        }
    }

    private void buffering(Packet packet, NettyOutbound outbound) {
        if (!packet.compositeData.isEmpty() && !isSetDisconnected) {
            bufferingDisposable = Schedulers.io().scheduleDirect(() -> {
                try {
                    var receivedTime = ZonedDateTime.now().toInstant().toEpochMilli();
                    if (combineBufferedData) {
                        var combinedData = packet.getBuffer();
                        log.trace("[{}] buffered raw data(combined):" + UtilFunc.printByteData(combinedData), deviceId);
                        var byteDataList = getSubArrays(startBytes, endBytes, combinedData, retainStartEndBytes);
                        for (var byteData : byteDataList) {
                            var received = new PyList(Arrays.asList(byteData));
                            packetProcessing(new PyObject[]{received, Py.java2py(packet.sender)}, receivedTime, outbound);
                        }
                    } else {
                        log.trace("[{}] buffered raw data:" + packet.compositeData.stream().map(UtilFunc::printByteData).collect(Collectors.joining("")), deviceId);
                        packetProcessing(new PyObject[]{packet.pyListBuffer, Py.java2py(packet.sender)}, receivedTime, outbound);
                    }
                } catch (Exception e) {
                    log.error("[{}] packet processing failed:" + packet.compositeData.stream().map(UtilFunc::printByteData).collect(Collectors.joining("")), deviceId, e);
                }
            });
        } else {
            log.trace("[{}] buffering ignored, bufferedData={}, sender={}", deviceId, packet.compositeData, packet.sender);
        }
    }

    private void packetProcessing(PyObject[] received, long receivedTime, NettyOutbound outbound) throws Exception {
        if (protocolFunc != null) {
            executeProtocolFunc(received, receivedTime, outbound);
        } else {
            requestedDataQueue.clear();
            requestedDataQueue.put(new Triplet<>(null, received, receivedTime));
            driverCommand.executeNonPeriodicCommands(received, receivedTime, outbound);
        }
    }

    private void executeProtocolFunc(PyObject[] received, long receivedTime, NettyOutbound outbound) throws Exception {
        var arg = driverCommand.getArguments(protocolFunc, received, receivedTime, null);
        PyObject result;
        try {
            result = protocolFunc.__call__(arg);
        } catch (Exception e) {
            throw new DriverCommand.ScriptException("protocol-function failed", e);
        }
        if (result instanceof PyNone) { // for anonymous read-command response
            requestedDataQueue.clear();
            requestedDataQueue.put(new Triplet<>(null, received, receivedTime));
        } else if (result instanceof PyString) { // for read-command response
            requestedDataQueue.clear();
            requestedDataQueue.put(new Triplet<>(result.asString(), received, receivedTime));
        } else if (result instanceof PyList) {
            var list = new ArrayList<Object>((PyList) result);
            driverCommand.executeNonPeriodicCommands(list.stream().map(Object::toString).collect(Collectors.toList()), received, receivedTime, outbound);
        } else if (result instanceof PyTuple) {
            var list = new ArrayList<Object>((PyTuple) result);
            driverCommand.executeNonPeriodicCommands(list.stream().map(Object::toString).collect(Collectors.toList()), received, receivedTime, outbound);
        } else {
            log.error("[{}] protocol function invalid output type, output type={}, received data={}", deviceId, result.getType().getName(), Arrays.asList(received));
        }
    }

    private static List<Byte[]> getSubArrays(Byte[] startBytes, Byte[] endBytes, Byte[] bytes, boolean retainStartEndBytes) {
        List<Byte[]> ret = new ArrayList<>();
        if (startBytes != null && endBytes == null) {
            var startIdx = UtilFunc.findArrayPattern(bytes, startBytes);
            if (!startIdx.isEmpty()) {
                startIdx.add(bytes.length);
                for (int i = 0; i < startIdx.size() - 1; i++) {
                    if (retainStartEndBytes)
                        ret.add(Arrays.copyOfRange(bytes, startIdx.get(i), startIdx.get(i + 1)));
                    else
                        ret.add(Arrays.copyOfRange(bytes, startIdx.get(i) + startBytes.length, startIdx.get(i + 1)));
                }
            }
        } else if (startBytes == null && endBytes != null) {
            var endIdx = UtilFunc.findArrayPattern(bytes, endBytes);
            if (!endIdx.isEmpty()) {
                if (retainStartEndBytes)
                    ret.add(Arrays.copyOfRange(bytes, 0, endIdx.get(0) + endBytes.length));
                else
                    ret.add(Arrays.copyOfRange(bytes, 0, endIdx.get(0)));

                for (int i = 0; i < endIdx.size() - 1; i++) {
                    if (retainStartEndBytes)
                        ret.add(Arrays.copyOfRange(bytes, endIdx.get(i) + endBytes.length, endIdx.get(i + 1) + endBytes.length));
                    else
                        ret.add(Arrays.copyOfRange(bytes, endIdx.get(i) + endBytes.length, endIdx.get(i + 1)));
                }
            }
        } else if (startBytes != null) {
            var startIdx = UtilFunc.findArrayPattern(bytes, startBytes);
            var endIdx = UtilFunc.findArrayPattern(bytes, endBytes);
            for (int s = 0, e = 0; s < startIdx.size() && e < endIdx.size(); e++) {
                if (startIdx.get(s) + startBytes.length <= endIdx.get(e)) {
                    if (retainStartEndBytes)
                        ret.add(Arrays.copyOfRange(bytes, startIdx.get(s), endIdx.get(e) + endBytes.length));
                    else
                        ret.add(Arrays.copyOfRange(bytes, startIdx.get(s) + startBytes.length, endIdx.get(e)));
                    s++;
                }
            }
        } else {
            ret.add(bytes);
        }
        return ret;
    }

    protected static class Socket {
        final Map<InetSocketAddress, Packet> senderDataMap = new HashMap<>();
        final ReentrantLock lock = new ReentrantLock();
    }

    private static class Packet {
        final LinkedList<Byte[]> compositeData = new LinkedList<>();
        boolean isFirstPacket = true;
        InetSocketAddress sender;
        final PyList pyListBuffer = new PyList();
        Byte[] combinedBuffer = new Byte[0];
        private Byte[] buf = new Byte[32];
        private int count = 0;
        private int getCount = 0;

        private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

        Packet(InetSocketAddress sender) {
            this.sender = sender;
        }

        Packet(InetSocketAddress sender, Byte[] data, boolean combineBufferedData) {
            this(sender);
            compositeData.add(data);
            if (combineBufferedData)
                writeBuffer(data);
            else
                addPyList(data);
        }

        void addPyList(Byte[] data) {
            pyListBuffer.add(new PyList(Arrays.asList(data)));
        }

        void writeBuffer(Byte[] b) {
            writeBuffer(b, 0, b.length);
        }

        void writeBuffer(Byte[] b, int off, int len) {
            if ((off < 0) || (off > b.length) || (len < 0) ||
                    ((off + len) - b.length > 0)) {
                throw new IndexOutOfBoundsException();
            }
            ensureCapacity(count + len);
            System.arraycopy(b, off, buf, count, len);
            count += len;
        }

        Byte[] getBuffer() {
            if (count != getCount) {
                getCount = count;
                return combinedBuffer = Arrays.copyOf(buf, count);
            } else {
                return combinedBuffer;
            }
        }

        private void grow(int minCapacity) {
            int oldCapacity = buf.length;
            int newCapacity = oldCapacity << 1;
            if (newCapacity - minCapacity < 0)
                newCapacity = minCapacity;
            if (newCapacity - MAX_ARRAY_SIZE > 0)
                newCapacity = hugeCapacity(minCapacity);
            buf = Arrays.copyOf(buf, newCapacity);
        }

        private static int hugeCapacity(int minCapacity) {
            if (minCapacity < 0) // overflow
                throw new OutOfMemoryError();
            return (minCapacity > MAX_ARRAY_SIZE) ?
                    Integer.MAX_VALUE :
                    MAX_ARRAY_SIZE;
        }

        private void ensureCapacity(int minCapacity) {
            // overflow-conscious code
            if (minCapacity - buf.length > 0)
                grow(minCapacity);
        }
    }

    public String requestInfo(String message, InetSocketAddress address) {
        return "{\"message\":\"" + message + "\", \"host\":\"" + address.getHostString() + "\", \"port\":" + address.getPort() + "}";
    }

    public String requestInfo(String message, String host, int port) {
        return "{\"message\":\"" + message + "\", \"host\":\"" + host + "\", \"port\":" + port + "}";
    }

    public String requestInfo(String message) {
        return "{\"message\":\"" + message + "}";
    }

    protected static class RequestInfo {
        String msg;
        String host;
        int port;

        RequestInfo(Object msg, Object host, Object port) throws Exception {
            if (msg instanceof String) {
                if (host instanceof String && port instanceof Integer) {
                    this.msg = (String) msg;
                    this.host = (String) host;
                    this.port = (Integer) port;
                    return;
                } else if (host == null && port == null) {
                    this.msg = (String) msg;
                    this.host = null;
                    this.port = -1;
                    return;
                }
            }
            throw new Exception("creating RequestInfo failed, message=" + msg + ", host=" + host + ", port=" + port);
        }
    }
}
