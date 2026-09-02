package com.sds.communicators.driver;

import com.digitalpetri.modbus.ExceptionCode;
import com.digitalpetri.modbus.exceptions.ModbusResponseException;
import com.digitalpetri.modbus.pdu.*;
import com.digitalpetri.modbus.server.ModbusRequestContext;
import com.digitalpetri.modbus.server.ModbusServices;
import com.digitalpetri.modbus.server.ModbusTcpServer;
import com.digitalpetri.modbus.tcp.server.NettyTcpServerTransport;
import com.google.common.base.Strings;
import com.sds.communicators.common.struct.Response;
import com.sds.communicators.driver.support.PythonEngine;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Value;

import java.time.ZonedDateTime;
import java.util.*;

@Slf4j
public class DriverProtocolModbusServer extends DriverProtocolModbus {
    private ModbusTcpServer server;

    @Override
    void initialize(String connectionInfo, Map<String, String> option) throws Exception {
        connectionLostOnException = false;
        super.initialize(connectionInfo, option);
        if (Strings.isNullOrEmpty(host))
            host = "0.0.0.0";

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
            var address = canonicalAddress(ModbusTable.HOLDING_REGISTER, request.address());
            checkDisconnected(request.getFunctionCode(), "onReadHoldingRegisters");
            try {
                executeNonPeriodicCommands(new Value[]{pyStr(address), pyInt(request.quantity()), pyInt(unitId)});
                return new ReadHoldingRegistersResponse(readRegisterBytes(address, request.quantity(), unitId));
            } catch (Exception e) {
                log.error("onReadHoldingRegisters failed, address={}, length={}, unitId={}", address, request.quantity(), unitId, e);
                throw serverFailure(request.getFunctionCode());
            }
        }

        @Override
        public ReadInputRegistersResponse readInputRegisters(ModbusRequestContext context, int unitId, ReadInputRegistersRequest request) throws ModbusResponseException {
            var address = canonicalAddress(ModbusTable.INPUT_REGISTER, request.address());
            checkDisconnected(request.getFunctionCode(), "onReadInputRegisters");
            try {
                executeNonPeriodicCommands(new Value[]{pyStr(address), pyInt(request.quantity()), pyInt(unitId)});
                return new ReadInputRegistersResponse(readRegisterBytes(address, request.quantity(), unitId));
            } catch (Exception e) {
                log.error("onReadInputRegisters failed, address={}, length={}, unitId={}", address, request.quantity(), unitId, e);
                throw serverFailure(request.getFunctionCode());
            }
        }

        @Override
        public ReadCoilsResponse readCoils(ModbusRequestContext context, int unitId, ReadCoilsRequest request) throws ModbusResponseException {
            var address = canonicalAddress(ModbusTable.COIL, request.address());
            checkDisconnected(request.getFunctionCode(), "onReadCoils");
            try {
                executeNonPeriodicCommands(new Value[]{pyStr(address), pyInt(request.quantity()), pyInt(unitId)});
                return new ReadCoilsResponse(readBitBytes(address, request.quantity(), unitId));
            } catch (Exception e) {
                log.error("onReadCoils failed, address={}, length={}, unitId={}", address, request.quantity(), unitId, e);
                throw serverFailure(request.getFunctionCode());
            }
        }

        @Override
        public ReadDiscreteInputsResponse readDiscreteInputs(ModbusRequestContext context, int unitId, ReadDiscreteInputsRequest request) throws ModbusResponseException {
            var address = canonicalAddress(ModbusTable.DISCRETE_INPUT, request.address());
            checkDisconnected(request.getFunctionCode(), "onReadDiscreteInputs");
            try {
                executeNonPeriodicCommands(new Value[]{pyStr(address), pyInt(request.quantity()), pyInt(unitId)});
                return new ReadDiscreteInputsResponse(readBitBytes(address, request.quantity(), unitId));
            } catch (Exception e) {
                log.error("onReadDiscreteInputs failed, address={}, length={}, unitId={}", address, request.quantity(), unitId, e);
                throw serverFailure(request.getFunctionCode());
            }
        }

        @Override
        public WriteSingleCoilResponse writeSingleCoil(ModbusRequestContext context, int unitId, WriteSingleCoilRequest request) throws ModbusResponseException {
            var address = canonicalAddress(ModbusTable.COIL, request.address());
            checkDisconnected(request.getFunctionCode(), "onWriteSingleCoil");
            var values = driverCommand.pythonEngine.toPyList(Collections.singletonList(request.value() != 0));
            try {
                write(address, values, unitId);
                executeNonPeriodicCommands(new Value[]{pyStr(address), values, pyInt(unitId)});
                return new WriteSingleCoilResponse(request.address(), request.value());
            } catch (Exception e) {
                log.error("onWriteSingleCoil failed, address={}, values={}, unitId={}", address, values, unitId, e);
                throw serverFailure(request.getFunctionCode());
            }
        }

        @Override
        public WriteSingleRegisterResponse writeSingleRegister(ModbusRequestContext context, int unitId, WriteSingleRegisterRequest request) throws ModbusResponseException {
            var address = canonicalAddress(ModbusTable.HOLDING_REGISTER, request.address());
            checkDisconnected(request.getFunctionCode(), "onWriteSingleRegister");
            var values = driverCommand.pythonEngine.toPyList(Collections.singletonList(request.value()));
            try {
                write(address, values, unitId);
                executeNonPeriodicCommands(new Value[]{pyStr(address), values, pyInt(unitId)});
                return new WriteSingleRegisterResponse(request.address(), request.value());
            } catch (Exception e) {
                log.error("onWriteSingleRegister failed, address={}, values={}, unitId={}", address, values, unitId, e);
                throw serverFailure(request.getFunctionCode());
            }
        }

        @Override
        public WriteMultipleCoilsResponse writeMultipleCoils(ModbusRequestContext context, int unitId, WriteMultipleCoilsRequest request) throws ModbusResponseException {
            var address = canonicalAddress(ModbusTable.COIL, request.address());
            checkDisconnected(request.getFunctionCode(), "onWriteMultipleCoils");
            var values = driverCommand.pythonEngine.toPyList(readBits(request.values(), request.quantity()));
            try {
                write(address, values, unitId);
                executeNonPeriodicCommands(new Value[]{pyStr(address), values, pyInt(unitId)});
                return new WriteMultipleCoilsResponse(request.address(), request.quantity());
            } catch (Exception e) {
                log.error("onWriteMultipleCoils failed, address={}, values={}, unitId={}", address, values, unitId, e);
                throw serverFailure(request.getFunctionCode());
            }
        }

        @Override
        public WriteMultipleRegistersResponse writeMultipleRegisters(ModbusRequestContext context, int unitId, WriteMultipleRegistersRequest request) throws ModbusResponseException {
            var address = canonicalAddress(ModbusTable.HOLDING_REGISTER, request.address());
            checkDisconnected(request.getFunctionCode(), "onWriteMultipleRegisters");
            var values = driverCommand.pythonEngine.toPyList(readRegister(request.values()));
            try {
                write(address, values, unitId);
                executeNonPeriodicCommands(new Value[]{pyStr(address), values, pyInt(unitId)});
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

    /**
     * device data key of a resolved address. each table occupies its own numeric range,
     * so one flat key space per unit id stays collision free
     */
    private static int storageKey(ModbusAddress resolved) {
        return switch (resolved.table()) {
            case COIL -> resolved.address() + 1;
            case DISCRETE_INPUT -> resolved.address() + 100001;
            case INPUT_REGISTER -> resolved.address() + 300001;
            case HOLDING_REGISTER -> resolved.address() + 400001;
        };
    }

    private Value pyInt(int value) {
        return driverCommand.pythonEngine.asValue(value);
    }

    private Value pyStr(String value) {
        return driverCommand.pythonEngine.asValue(value);
    }

    private void executeNonPeriodicCommands(Value[] input) throws Exception {
        driverCommand.executeNonPeriodicCommands(input, ZonedDateTime.now().toInstant().toEpochMilli(), null);
    }

    private byte[] readBitBytes(String address, int length, int unitId) throws Exception {
        var values = read(address, length, unitId);
        if (values == null) throw new Exception("read values is null");
        int size = (int) values.getArraySize();
        byte[] buf = new byte[(size + 7) >> 3];
        for (int i = 0; i < size; i++) {
            if (values.getArrayElement(i).asBoolean()) buf[i >> 3] |= 1 << (i & 7);
        }
        return buf;
    }

    private byte[] readRegisterBytes(String address, int length, int unitId) throws Exception {
        var values = read(address, length, unitId);
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

    public Value read(String address, int length, int unitId) {
        var resolved = length < 0 || unitId < 0 ? null
                : resolveAddress(address, length);
        if (resolved == null) {
            log.error("[{}] read failed, invalid address: {}, length: {}, unitId: {}, {}",
                    deviceId, address, length, unitId, ADDRESS_FORMAT);
            return null;
        }

        boolean bitTable = isBitTable(resolved.table());
        int key = storageKey(resolved);
        var result = new ArrayList<>();
        for (int i = key; i < key + length; i++) {
            var data = getData(Arrays.asList(Integer.toString(unitId), Integer.toString(i)));
            if (bitTable)
                result.add(data instanceof Boolean ? data : false);
            else
                result.add(data instanceof Integer ? data : 0);
        }
        return driverCommand.pythonEngine.toPyList(result);
    }

    public void write(String address, Value values, int unitId) {
        long size = values == null || values.isNull() ? -1 : values.getArraySize();
        var resolved = size < 0 || unitId < 0 ? null
                : resolveAddress(address, (int) size);
        if (resolved == null) {
            log.error("[{}] write failed, invalid address: {}, unitId: {}, values: {}, {}",
                    deviceId, address, unitId, values, ADDRESS_FORMAT);
            return;
        }
        if (size == 0) {
            log.debug("[{}] empty write request for address={}", deviceId, address);
            return;
        }

        boolean bitTable = isBitTable(resolved.table());
        int key = storageKey(resolved);
        var map = new HashMap<String, Object>();
        for (int i = 0; i < size; i++) {
            var element = values.getArrayElement(i);
            Object stored;
            if (bitTable) {
                // a bit table takes true/false, or a number where zero is false and anything else is true
                if (element.isBoolean())
                    stored = element.asBoolean();
                else if (element.isNumber())
                    stored = element.asDouble() != 0;
                else {
                    log.error("[{}] write failed, invalid values: {}, address: {}, unitId: {}", deviceId, values, address, unitId);
                    return;
                }
            } else {
                if (!PythonEngine.isInteger(element)) {
                    log.error("[{}] write failed, invalid values: {}, address: {}, unitId: {}", deviceId, values, address, unitId);
                    return;
                }
                stored = PythonEngine.asInt(element);
            }
            map.put(Integer.toString(key + i), stored);
        }
        setData(map, Collections.singletonList(Integer.toString(unitId)));
    }
}
