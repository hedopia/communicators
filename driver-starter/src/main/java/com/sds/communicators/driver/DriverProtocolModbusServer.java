package com.sds.communicators.driver;

import com.digitalpetri.modbus.ExceptionCode;
import com.digitalpetri.modbus.requests.*;
import com.digitalpetri.modbus.responses.*;
import com.digitalpetri.modbus.slave.ModbusTcpSlave;
import com.digitalpetri.modbus.slave.ModbusTcpSlaveConfig;
import com.digitalpetri.modbus.slave.ServiceRequestHandler;
import com.google.common.base.Strings;
import com.sds.communicators.common.UtilFunc;
import com.sds.communicators.common.struct.Response;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import lombok.extern.slf4j.Slf4j;
import org.python.core.PyFunction;
import org.python.core.PyInteger;
import org.python.core.PyList;
import org.python.core.PyObject;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
public class DriverProtocolModbusServer extends DriverProtocol {
    private ModbusTcpSlave slave;
    private String host;
    private int port;
    private Disposable disposableOnReadHoldingRegisters = null;
    private Disposable disposableOnReadInputRegisters = null;
    private Disposable disposableOnReadCoils = null;
    private Disposable disposableOnReadDiscreteInputs = null;
    private Disposable disposableOnWriteSingleCoil = null;
    private Disposable disposableOnWriteSingleRegister = null;
    private Disposable disposableOnWriteMultipleCoils = null;
    private Disposable disposableOnWriteMultipleRegisters = null;

    @Override
    void initialize(String connectionInfo, Map<String, String> option) throws Exception {
        connectionLostOnException = false;
        executeProtocolScript();

        var hostPort = UtilFunc.extractIpPort(connectionInfo);
        if (!Strings.isNullOrEmpty(hostPort[0]))
            host = hostPort[0];
        else
            host = "0.0.0.0";
        port = Integer.parseInt(hostPort[1]);

        slave = new ModbusTcpSlave(new ModbusTcpSlaveConfig.Builder().build());
        slave.setRequestHandler(new ServiceRequestHandler() {
            @Override
            public void onReadHoldingRegisters(ServiceRequestHandler.ServiceRequest<ReadHoldingRegistersRequest, ReadHoldingRegistersResponse> service) {
                if (!isSetDisconnected) {
                    disposableOnReadHoldingRegisters = Schedulers.io().scheduleDirect(() -> {
                        var request = service.getRequest();
                        var address = request.getAddress() + 400001;
                        try {
                            executeNonPeriodicCommands(new PyObject[]{new PyInteger(address), new PyInteger(request.getQuantity()), new PyInteger(service.getUnitId())});
                            service.sendResponse(new ReadHoldingRegistersResponse(readRegisters(address, request.getQuantity(), service.getUnitId())));
                        } catch (Exception e) {
                            log.error("onReadHoldingRegisters failed, address={}, length={}, unitId={}", address, request.getQuantity(), service.getUnitId(), e);
                            service.sendException(ExceptionCode.SlaveDeviceFailure);
                        } finally {
                            ReferenceCountUtil.release(request);
                        }
                    });
                } else {
                    log.trace("[{}] set disconnected -> onReadHoldingRegisters ignored", deviceId);
                    service.sendException(ExceptionCode.SlaveDeviceFailure);
                    ReferenceCountUtil.release(service.getRequest());
                }
            }

            @Override
            public void onReadInputRegisters(ServiceRequestHandler.ServiceRequest<ReadInputRegistersRequest, ReadInputRegistersResponse> service) {
                if (!isSetDisconnected) {
                    disposableOnReadInputRegisters = Schedulers.io().scheduleDirect(() -> {
                        var request = service.getRequest();
                        var address = request.getAddress() + 300001;
                        try {
                            executeNonPeriodicCommands(new PyObject[]{new PyInteger(address), new PyInteger(request.getQuantity()), new PyInteger(service.getUnitId())});
                            service.sendResponse(new ReadInputRegistersResponse(readRegisters(address, request.getQuantity(), service.getUnitId())));
                        } catch (Exception e) {
                            log.error("onReadInputRegisters failed, address={}, length={}, unitId={}", address, request.getQuantity(), service.getUnitId(), e);
                            service.sendException(ExceptionCode.SlaveDeviceFailure);
                        } finally {
                            ReferenceCountUtil.release(request);
                        }
                    });
                } else {
                    log.trace("[{}] set disconnected -> onReadInputRegisters ignored", deviceId);
                    service.sendException(ExceptionCode.SlaveDeviceFailure);
                    ReferenceCountUtil.release(service.getRequest());
                }
            }

            @Override
            public void onReadCoils(ServiceRequestHandler.ServiceRequest<ReadCoilsRequest, ReadCoilsResponse> service) {
                if (!isSetDisconnected) {
                    disposableOnReadCoils = Schedulers.io().scheduleDirect(() -> {
                        var request = service.getRequest();
                        var address = request.getAddress() + 1;
                        try {
                            executeNonPeriodicCommands(new PyObject[]{new PyInteger(address), new PyInteger(request.getQuantity()), new PyInteger(service.getUnitId())});
                            service.sendResponse(new ReadCoilsResponse(Unpooled.copiedBuffer(readBits(address, request.getQuantity(), service.getUnitId(), true))));
                        } catch (Exception e) {
                            log.error("onReadCoils failed, address={}, length={}, unitId={}", address, request.getQuantity(), service.getUnitId(), e);
                            service.sendException(ExceptionCode.SlaveDeviceFailure);
                        } finally {
                            ReferenceCountUtil.release(request);
                        }
                    });
                } else {
                    log.trace("[{}] set disconnected -> onReadCoils ignored", deviceId);
                    service.sendException(ExceptionCode.SlaveDeviceFailure);
                    ReferenceCountUtil.release(service.getRequest());
                }
            }

            @Override
            public void onReadDiscreteInputs(ServiceRequestHandler.ServiceRequest<ReadDiscreteInputsRequest, ReadDiscreteInputsResponse> service) {
                if (!isSetDisconnected) {
                    disposableOnReadDiscreteInputs = Schedulers.io().scheduleDirect(() -> {
                        var request = service.getRequest();
                        var address = request.getAddress() + 100001;
                        try {
                            executeNonPeriodicCommands(new PyObject[]{new PyInteger(address), new PyInteger(request.getQuantity()), new PyInteger(service.getUnitId())});
                            service.sendResponse(new ReadDiscreteInputsResponse(Unpooled.copiedBuffer(readBits(address, request.getQuantity(), service.getUnitId(), false))));
                        } catch (Exception e) {
                            log.error("onReadDiscreteInputs failed, address={}, length={}, unitId={}", address, request.getQuantity(), service.getUnitId(), e);
                            service.sendException(ExceptionCode.SlaveDeviceFailure);
                        } finally {
                            ReferenceCountUtil.release(request);
                        }
                    });
                } else {
                    log.trace("[{}] set disconnected -> onReadDiscreteInputs ignored", deviceId);
                    service.sendException(ExceptionCode.SlaveDeviceFailure);
                    ReferenceCountUtil.release(service.getRequest());
                }
            }

            @Override
            public void onWriteSingleCoil(ServiceRequestHandler.ServiceRequest<WriteSingleCoilRequest, WriteSingleCoilResponse> service) {
                if (!isSetDisconnected) {
                    disposableOnWriteSingleCoil = Schedulers.io().scheduleDirect(() -> {
                        var request = service.getRequest();
                        var address = request.getAddress() + 1;
                        var values = new PyList(Collections.singletonList(request.getValue() != 0));
                        try {
                            write(address, values, service.getUnitId(), true);
                            executeNonPeriodicCommands(new PyObject[]{new PyInteger(address), values, new PyInteger(service.getUnitId())});
                            service.sendResponse(new WriteSingleCoilResponse(request.getAddress(), request.getValue()));
                        } catch (Exception e) {
                            log.error("onWriteSingleCoil failed, address={}, values={}, unitId={}", address, values, service.getUnitId(), e);
                            service.sendException(ExceptionCode.SlaveDeviceFailure);
                        } finally {
                            ReferenceCountUtil.release(request);
                        }
                    });
                } else {
                    log.trace("[{}] set disconnected -> onWriteSingleCoil ignored", deviceId);
                    service.sendException(ExceptionCode.SlaveDeviceFailure);
                    ReferenceCountUtil.release(service.getRequest());
                }
            }

            @Override
            public void onWriteSingleRegister(ServiceRequestHandler.ServiceRequest<WriteSingleRegisterRequest, WriteSingleRegisterResponse> service) {
                if (!isSetDisconnected) {
                    disposableOnWriteSingleRegister = Schedulers.io().scheduleDirect(() -> {
                        var request = service.getRequest();
                        var address = request.getAddress() + 400001;
                        var values = new PyList(Collections.singletonList(request.getValue()));
                        try {
                            write(address, values, service.getUnitId(), false);
                            executeNonPeriodicCommands(new PyObject[]{new PyInteger(address), values, new PyInteger(service.getUnitId())});
                            service.sendResponse(new WriteSingleRegisterResponse(request.getAddress(), request.getValue()));
                        } catch (Exception e) {
                            log.error("onWriteSingleRegister failed, address={}, values={}, unitId={}", address, values, service.getUnitId(), e);
                            service.sendException(ExceptionCode.SlaveDeviceFailure);
                        } finally {
                            ReferenceCountUtil.release(request);
                        }
                    });
                } else {
                    log.trace("[{}] set disconnected -> onWriteSingleRegister ignored", deviceId);
                    service.sendException(ExceptionCode.SlaveDeviceFailure);
                    ReferenceCountUtil.release(service.getRequest());
                }
            }

            @Override
            public void onWriteMultipleCoils(ServiceRequestHandler.ServiceRequest<WriteMultipleCoilsRequest, WriteMultipleCoilsResponse> service) {
                if (!isSetDisconnected) {
                    disposableOnWriteMultipleCoils = Schedulers.io().scheduleDirect(() -> {
                        var request = service.getRequest();
                        var address = request.getAddress() + 1;
                        var values = new PyList(DriverProtocolModbusClient.readBits(request.getValues(), request.getQuantity()));
                        try {
                            write(address, values, service.getUnitId(), true);
                            executeNonPeriodicCommands(new PyObject[]{new PyInteger(address), values, new PyInteger(service.getUnitId())});
                            service.sendResponse(new WriteMultipleCoilsResponse(request.getAddress(), request.getQuantity()));
                        } catch (Exception e) {
                            log.error("onWriteMultipleCoils failed, address={}, values={}, unitId={}", address, values, service.getUnitId(), e);
                            service.sendException(ExceptionCode.SlaveDeviceFailure);
                        } finally {
                            ReferenceCountUtil.release(request);
                        }
                    });
                } else {
                    log.trace("[{}] set disconnected -> onWriteMultipleCoils ignored", deviceId);
                    service.sendException(ExceptionCode.SlaveDeviceFailure);
                    ReferenceCountUtil.release(service.getRequest());
                }
            }

            @Override
            public void onWriteMultipleRegisters(ServiceRequestHandler.ServiceRequest<WriteMultipleRegistersRequest, WriteMultipleRegistersResponse> service) {
                if (!isSetDisconnected) {
                    disposableOnWriteMultipleRegisters = Schedulers.io().scheduleDirect(() -> {
                        var request = service.getRequest();
                        var address = request.getAddress() + 400001;
                        var values = new PyList(DriverProtocolModbusClient.readRegister(request.getValues()));
                        try {
                            write(address, values, service.getUnitId(), false);
                            executeNonPeriodicCommands(new PyObject[]{new PyInteger(address), values, new PyInteger(service.getUnitId())});
                            service.sendResponse(new WriteMultipleRegistersResponse(request.getAddress(), request.getQuantity()));
                        } catch (Exception e) {
                            log.error("onWriteMultipleRegisters failed, address={}, values={}, unitId={}", address, values, service.getUnitId(), e);
                            service.sendException(ExceptionCode.SlaveDeviceFailure);
                        } finally {
                            ReferenceCountUtil.release(request);
                        }
                    });
                } else {
                    log.trace("[{}] set disconnected -> onWriteMultipleRegisters ignored", deviceId);
                    service.sendException(ExceptionCode.SlaveDeviceFailure);
                    ReferenceCountUtil.release(service.getRequest());
                }
            }
        });
        device.setConnectionCommand(false);
    }

    private void executeNonPeriodicCommands(PyObject[] input) throws Exception {
        driverCommand.executeNonPeriodicCommands(input, ZonedDateTime.now().toInstant().toEpochMilli(), null);
    }

    private byte[] readBits(int address, int length, int unitId, boolean isCoil) throws Exception {
        var values = read(address, length, unitId, isCoil);
        if (values == null) throw new Exception("read values is null");
        byte[] buf = new byte[(values.size() + 7) >> 3];
        for (int i = 0; i < values.size(); i++) {
            if ((Boolean) values.get(i)) buf[i >> 3] |= 1 << (i & 7);
        }
        return buf;
    }

    private ByteBuf readRegisters(int address, int length, int unitId) throws Exception {
        var values = read(address, length, unitId, false);
        if (values == null) throw new Exception("read values is null");
        var registers = PooledByteBufAllocator.DEFAULT.buffer(length);
        for (var value : values)
            registers.writeShort((Integer) value);
        return registers;
    }

    @Override
    void requestConnect() throws Exception {
        log.info("[{}] host={}, port={}, socket-timeout={}", deviceId, host, port, socketTimeout);
        slave.bind(host, port).get(socketTimeout, TimeUnit.MILLISECONDS);
    }

    @Override
    void requestDisconnect() {
        if (disposableOnReadHoldingRegisters != null && !disposableOnReadHoldingRegisters.isDisposed())
            disposableOnReadHoldingRegisters.dispose();
        if (disposableOnReadInputRegisters != null && !disposableOnReadInputRegisters.isDisposed())
            disposableOnReadInputRegisters.dispose();
        if (disposableOnReadCoils != null && !disposableOnReadCoils.isDisposed())
            disposableOnReadCoils.dispose();
        if (disposableOnReadDiscreteInputs != null && !disposableOnReadDiscreteInputs.isDisposed())
            disposableOnReadDiscreteInputs.dispose();
        if (disposableOnWriteSingleCoil != null && !disposableOnWriteSingleCoil.isDisposed())
            disposableOnWriteSingleCoil.dispose();
        if (disposableOnWriteSingleRegister != null && !disposableOnWriteSingleRegister.isDisposed())
            disposableOnWriteSingleRegister.dispose();
        if (disposableOnWriteMultipleCoils != null && !disposableOnWriteMultipleCoils.isDisposed())
            disposableOnWriteMultipleCoils.dispose();
        if (disposableOnWriteMultipleRegisters != null && !disposableOnWriteMultipleRegisters.isDisposed())
            disposableOnWriteMultipleRegisters.dispose();
        slave.shutdown();
    }

    @Override
    List<Response> requestCommand(String cmdId, String requestInfo, int timeout, boolean isReadCommand, PyFunction function, PyObject initialValue, Object nonPeriodicObject) {
        log.info("[{}] cmdId={}, requestCommand not supported for modbus server", deviceId, cmdId);
        return null;
    }

    public PyList read(int address, int length, int unitId) {
        return read(address, length, unitId, false);
    }

    public PyList read(int address, int length, int unitId, boolean isCoil) {
        int tableIdx;
        int convertedAddress = address;
        if (length >= 0 && unitId >= 0 && isCoil && address > 0 && address + length <= 65537) {
            tableIdx = 0;
        } else if (length >= 0 && unitId >= 0 && !isCoil && address > 10000 && address + length <= 20001) {
            tableIdx = 1;
            convertedAddress = address - 10001 + 100001;
        } else if (length >= 0 && unitId >= 0 && !isCoil && address > 100000 && address + length <= 165537) {
            tableIdx = 1;
        } else if (length >= 0 && unitId >= 0 && !isCoil && address > 30000 && address + length <= 40001) {
            tableIdx = 3;
            convertedAddress = address - 30001 + 300001;
        } else if (length >= 0 && unitId >= 0 && !isCoil && address > 300000 && address + length <= 365537) {
            tableIdx = 3;
        } else if (length >= 0 && unitId >= 0 && !isCoil && address > 40000 && address + length <= 50001) {
            tableIdx = 4;
            convertedAddress = address - 40001 + 400001;
        } else if (length >= 0 && unitId >= 0 && !isCoil && address > 400000 && address + length <= 465537) {
            tableIdx = 4;
        } else {
            log.error("[{}] read failed, invalid address: {}, length: {}, unitId: {}, isCoil: {}", deviceId, address, length, unitId, isCoil);
            return null;
        }

        var result = new ArrayList<>();
        for (int i = convertedAddress; i < convertedAddress + length; i++) {
            var data = getData(Arrays.asList(Integer.toString(unitId), Integer.toString(i)));
            if (tableIdx == 0 || tableIdx == 1) {
                if (data instanceof Boolean)
                    result.add(data);
                else
                    result.add(false);
            } else {
                if (data instanceof Integer)
                    result.add(data);
                else
                    result.add(0);
            }
        }
        return new PyList(result);
    }

    public void write(int address, PyList values, int unitId) {
        write(address, values, unitId, false);
    }

    public void write(int address, PyList values, int unitId, boolean isCoil) {
        int tableIdx;
        int convertedAddress = address;
        if (unitId >= 0 && isCoil && address > 0 && address + values.size() <= 65537) {
            tableIdx = 0;
        } else if (unitId >= 0 && !isCoil && address > 10000 && address + values.size() <= 20001) {
            tableIdx = 1;
            convertedAddress = address - 10001 + 100001;
        } else if (unitId >= 0 && !isCoil && address > 100000 && address + values.size() <= 165537) {
            tableIdx = 1;
        } else if (unitId >= 0 && !isCoil && address > 30000 && address + values.size() <= 40001) {
            tableIdx = 3;
            convertedAddress = address - 30001 + 300001;
        } else if (unitId >= 0 && !isCoil && address > 300000 && address + values.size() <= 365537) {
            tableIdx = 3;
        } else if (unitId >= 0 && !isCoil && address > 40000 && address + values.size() <= 50001) {
            tableIdx = 4;
            convertedAddress = address - 40001 + 400001;
        } else if (unitId >= 0 && !isCoil && address > 400000 && address + values.size() <= 465537) {
            tableIdx = 4;
        } else {
            log.error("[{}] write failed, invalid address: {}, unitId: {}, isCoil: {}, values: {}", deviceId, address, unitId, isCoil, values);
            return;
        }

        if (!values.isEmpty()) {
            if (((tableIdx == 0 || tableIdx == 1) && !(values.get(0) instanceof Boolean)) ||
                    ((tableIdx == 3 || tableIdx == 4) && !(values.get(0) instanceof Integer))) {
                log.error("[{}] write failed, invalid address: {}, unitId: {}, isCoil: {}, values: {}", deviceId, address, unitId, isCoil, values);
                return;
            }
        } else {
            log.debug("[{}] empty write request for address={}", deviceId, address);
            return;
        }

        var map = new HashMap<String, Object>();
        for (int i = 0; i < values.size(); i++)
            map.put(Integer.toString(i + convertedAddress), values.get(i));
        setData(map, Collections.singletonList(Integer.toString(unitId)));
    }
}
