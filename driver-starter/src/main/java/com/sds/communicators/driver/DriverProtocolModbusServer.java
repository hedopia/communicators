package com.sds.communicators.driver;

import com.digitalpetri.modbus.ExceptionCode;
import com.digitalpetri.modbus.exceptions.ModbusResponseException;
import com.digitalpetri.modbus.pdu.*;
import com.digitalpetri.modbus.server.ModbusRequestContext;
import com.digitalpetri.modbus.server.ModbusServices;
import com.digitalpetri.modbus.server.ModbusTcpServer;
import com.digitalpetri.modbus.tcp.server.NettyTcpServerTransport;
import com.google.common.base.Strings;
import com.sds.communicators.common.UtilFunc;
import com.sds.communicators.common.struct.Response;
import com.sds.communicators.driver.support.PythonEngine;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Value;

import java.time.ZonedDateTime;
import java.util.*;

@Slf4j
public class DriverProtocolModbusServer extends DriverProtocol {
    private ModbusTcpServer server;
    private String host;
    private int port;

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

        var transport = NettyTcpServerTransport.create(cfg -> {
            cfg.bindAddress = host;
            cfg.port = port;
        });
        server = ModbusTcpServer.create(transport, new DriverModbusServices());
        device.setConnectionCommand(false);
    }

    @Override
    void requestConnect() throws Exception {
        log.info("[{}] host={}, port={}, socket-timeout={}", deviceId, host, port, socketTimeout);
        server.start();
    }

    @Override
    void requestDisconnect() throws Exception {
        server.stop();
    }

    @Override
    List<Response> requestCommand(String cmdId, String requestInfo, int timeout, boolean isReadCommand, Value function, Value initialValue, Object nonPeriodicObject) {
        log.info("[{}] cmdId={}, requestCommand not supported for modbus server", deviceId, cmdId);
        return null;
    }

    private class DriverModbusServices implements ModbusServices {
        @Override
        public ReadHoldingRegistersResponse readHoldingRegisters(ModbusRequestContext context, int unitId, ReadHoldingRegistersRequest request) throws ModbusResponseException {
            var address = request.address() + 400001;
            checkDisconnected(request.getFunctionCode(), "onReadHoldingRegisters");
            try {
                executeNonPeriodicCommands(new Value[]{pyInt(address), pyInt(request.quantity()), pyInt(unitId)});
                return new ReadHoldingRegistersResponse(readRegisterBytes(address, request.quantity(), unitId));
            } catch (Exception e) {
                log.error("onReadHoldingRegisters failed, address={}, length={}, unitId={}", address, request.quantity(), unitId, e);
                throw serverFailure(request.getFunctionCode());
            }
        }

        @Override
        public ReadInputRegistersResponse readInputRegisters(ModbusRequestContext context, int unitId, ReadInputRegistersRequest request) throws ModbusResponseException {
            var address = request.address() + 300001;
            checkDisconnected(request.getFunctionCode(), "onReadInputRegisters");
            try {
                executeNonPeriodicCommands(new Value[]{pyInt(address), pyInt(request.quantity()), pyInt(unitId)});
                return new ReadInputRegistersResponse(readRegisterBytes(address, request.quantity(), unitId));
            } catch (Exception e) {
                log.error("onReadInputRegisters failed, address={}, length={}, unitId={}", address, request.quantity(), unitId, e);
                throw serverFailure(request.getFunctionCode());
            }
        }

        @Override
        public ReadCoilsResponse readCoils(ModbusRequestContext context, int unitId, ReadCoilsRequest request) throws ModbusResponseException {
            var address = request.address() + 1;
            checkDisconnected(request.getFunctionCode(), "onReadCoils");
            try {
                executeNonPeriodicCommands(new Value[]{pyInt(address), pyInt(request.quantity()), pyInt(unitId)});
                return new ReadCoilsResponse(readBitBytes(address, request.quantity(), unitId, true));
            } catch (Exception e) {
                log.error("onReadCoils failed, address={}, length={}, unitId={}", address, request.quantity(), unitId, e);
                throw serverFailure(request.getFunctionCode());
            }
        }

        @Override
        public ReadDiscreteInputsResponse readDiscreteInputs(ModbusRequestContext context, int unitId, ReadDiscreteInputsRequest request) throws ModbusResponseException {
            var address = request.address() + 100001;
            checkDisconnected(request.getFunctionCode(), "onReadDiscreteInputs");
            try {
                executeNonPeriodicCommands(new Value[]{pyInt(address), pyInt(request.quantity()), pyInt(unitId)});
                return new ReadDiscreteInputsResponse(readBitBytes(address, request.quantity(), unitId, false));
            } catch (Exception e) {
                log.error("onReadDiscreteInputs failed, address={}, length={}, unitId={}", address, request.quantity(), unitId, e);
                throw serverFailure(request.getFunctionCode());
            }
        }

        @Override
        public WriteSingleCoilResponse writeSingleCoil(ModbusRequestContext context, int unitId, WriteSingleCoilRequest request) throws ModbusResponseException {
            var address = request.address() + 1;
            checkDisconnected(request.getFunctionCode(), "onWriteSingleCoil");
            var values = driverCommand.pythonEngine.toPyList(Collections.singletonList(request.value() != 0));
            try {
                write(address, values, unitId, true);
                executeNonPeriodicCommands(new Value[]{pyInt(address), values, pyInt(unitId)});
                return new WriteSingleCoilResponse(request.address(), request.value());
            } catch (Exception e) {
                log.error("onWriteSingleCoil failed, address={}, values={}, unitId={}", address, values, unitId, e);
                throw serverFailure(request.getFunctionCode());
            }
        }

        @Override
        public WriteSingleRegisterResponse writeSingleRegister(ModbusRequestContext context, int unitId, WriteSingleRegisterRequest request) throws ModbusResponseException {
            var address = request.address() + 400001;
            checkDisconnected(request.getFunctionCode(), "onWriteSingleRegister");
            var values = driverCommand.pythonEngine.toPyList(Collections.singletonList(request.value()));
            try {
                write(address, values, unitId, false);
                executeNonPeriodicCommands(new Value[]{pyInt(address), values, pyInt(unitId)});
                return new WriteSingleRegisterResponse(request.address(), request.value());
            } catch (Exception e) {
                log.error("onWriteSingleRegister failed, address={}, values={}, unitId={}", address, values, unitId, e);
                throw serverFailure(request.getFunctionCode());
            }
        }

        @Override
        public WriteMultipleCoilsResponse writeMultipleCoils(ModbusRequestContext context, int unitId, WriteMultipleCoilsRequest request) throws ModbusResponseException {
            var address = request.address() + 1;
            checkDisconnected(request.getFunctionCode(), "onWriteMultipleCoils");
            var values = driverCommand.pythonEngine.toPyList(DriverProtocolModbusClient.readBits(request.values(), request.quantity()));
            try {
                write(address, values, unitId, true);
                executeNonPeriodicCommands(new Value[]{pyInt(address), values, pyInt(unitId)});
                return new WriteMultipleCoilsResponse(request.address(), request.quantity());
            } catch (Exception e) {
                log.error("onWriteMultipleCoils failed, address={}, values={}, unitId={}", address, values, unitId, e);
                throw serverFailure(request.getFunctionCode());
            }
        }

        @Override
        public WriteMultipleRegistersResponse writeMultipleRegisters(ModbusRequestContext context, int unitId, WriteMultipleRegistersRequest request) throws ModbusResponseException {
            var address = request.address() + 400001;
            checkDisconnected(request.getFunctionCode(), "onWriteMultipleRegisters");
            var values = driverCommand.pythonEngine.toPyList(DriverProtocolModbusClient.readRegister(request.values()));
            try {
                write(address, values, unitId, false);
                executeNonPeriodicCommands(new Value[]{pyInt(address), values, pyInt(unitId)});
                return new WriteMultipleRegistersResponse(request.address(), request.quantity());
            } catch (Exception e) {
                log.error("onWriteMultipleRegisters failed, address={}, values={}, unitId={}", address, values, unitId, e);
                throw serverFailure(request.getFunctionCode());
            }
        }

        private void checkDisconnected(int functionCode, String name) throws ModbusResponseException {
            if (isSetDisconnected) {
                log.trace("[{}] set disconnected -> {} ignored", deviceId, name);
                throw serverFailure(functionCode);
            }
        }

        private ModbusResponseException serverFailure(int functionCode) {
            return new ModbusResponseException(functionCode, ExceptionCode.SLAVE_DEVICE_FAILURE.getCode());
        }
    }

    private Value pyInt(int value) {
        return driverCommand.pythonEngine.asValue(value);
    }

    private void executeNonPeriodicCommands(Value[] input) throws Exception {
        driverCommand.executeNonPeriodicCommands(input, ZonedDateTime.now().toInstant().toEpochMilli(), null);
    }

    private byte[] readBitBytes(int address, int length, int unitId, boolean isCoil) throws Exception {
        var values = read(address, length, unitId, isCoil);
        if (values == null) throw new Exception("read values is null");
        int size = (int) values.getArraySize();
        byte[] buf = new byte[(size + 7) >> 3];
        for (int i = 0; i < size; i++) {
            if (values.getArrayElement(i).asBoolean()) buf[i >> 3] |= 1 << (i & 7);
        }
        return buf;
    }

    private byte[] readRegisterBytes(int address, int length, int unitId) throws Exception {
        var values = read(address, length, unitId, false);
        if (values == null) throw new Exception("read values is null");
        int size = (int) values.getArraySize();
        byte[] buf = new byte[size << 1];
        for (int i = 0; i < size; i++) {
            int value = values.getArrayElement(i).asInt();
            buf[(i << 1)] = (byte) (value >> 8);
            buf[(i << 1) + 1] = (byte) value;
        }
        return buf;
    }

    public Value read(int address, int length, int unitId) {
        return read(address, length, unitId, false);
    }

    public Value read(int address, int length, int unitId, boolean isCoil) {
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
        return driverCommand.pythonEngine.toPyList(result);
    }

    public void write(int address, Value values, int unitId) {
        write(address, values, unitId, false);
    }

    public void write(int address, Value values, int unitId, boolean isCoil) {
        int tableIdx;
        int convertedAddress = address;
        long size = values == null || values.isNull() ? -1 : values.getArraySize();
        if (size >= 0 && unitId >= 0 && isCoil && address > 0 && address + size <= 65537) {
            tableIdx = 0;
        } else if (size >= 0 && unitId >= 0 && !isCoil && address > 10000 && address + size <= 20001) {
            tableIdx = 1;
            convertedAddress = address - 10001 + 100001;
        } else if (size >= 0 && unitId >= 0 && !isCoil && address > 100000 && address + size <= 165537) {
            tableIdx = 1;
        } else if (size >= 0 && unitId >= 0 && !isCoil && address > 30000 && address + size <= 40001) {
            tableIdx = 3;
            convertedAddress = address - 30001 + 300001;
        } else if (size >= 0 && unitId >= 0 && !isCoil && address > 300000 && address + size <= 365537) {
            tableIdx = 3;
        } else if (size >= 0 && unitId >= 0 && !isCoil && address > 40000 && address + size <= 50001) {
            tableIdx = 4;
            convertedAddress = address - 40001 + 400001;
        } else if (size >= 0 && unitId >= 0 && !isCoil && address > 400000 && address + size <= 465537) {
            tableIdx = 4;
        } else {
            log.error("[{}] write failed, invalid address: {}, unitId: {}, isCoil: {}, values: {}", deviceId, address, unitId, isCoil, values);
            return;
        }

        boolean isCoilTable = tableIdx == 0 || tableIdx == 1;
        if (size > 0) {
            var first = values.getArrayElement(0);
            if ((isCoilTable && !first.isBoolean()) ||
                    (!isCoilTable && !PythonEngine.isInteger(first))) {
                log.error("[{}] write failed, invalid address: {}, unitId: {}, isCoil: {}, values: {}", deviceId, address, unitId, isCoil, values);
                return;
            }
        } else {
            log.debug("[{}] empty write request for address={}", deviceId, address);
            return;
        }

        var map = new HashMap<String, Object>();
        for (int i = 0; i < size; i++) {
            var element = values.getArrayElement(i);
            map.put(Integer.toString(i + convertedAddress), isCoilTable ? element.asBoolean() : PythonEngine.asInt(element));
        }
        setData(map, Collections.singletonList(Integer.toString(unitId)));
    }
}
