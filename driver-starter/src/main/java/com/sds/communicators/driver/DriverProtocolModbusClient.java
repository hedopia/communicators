package com.sds.communicators.driver;

import com.digitalpetri.modbus.client.ModbusTcpClient;
import com.digitalpetri.modbus.pdu.ReadCoilsRequest;
import com.digitalpetri.modbus.pdu.ReadDiscreteInputsRequest;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersRequest;
import com.digitalpetri.modbus.pdu.ReadInputRegistersRequest;
import com.digitalpetri.modbus.pdu.WriteMultipleCoilsRequest;
import com.digitalpetri.modbus.pdu.WriteMultipleRegistersRequest;
import com.digitalpetri.modbus.pdu.WriteSingleCoilRequest;
import com.digitalpetri.modbus.pdu.WriteSingleRegisterRequest;
import com.sds.communicators.common.UtilFunc;
import com.sds.communicators.common.struct.Response;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Value;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
public class DriverProtocolModbusClient extends DriverProtocol {
    private ModbusTcpClient master;
    private final int BATCH_SIZE = 120;

    private String host;
    private int port;
    private int defaultUnitId = 1;
    private boolean combineData = true;

    @Override
    void initialize(String connectionInfo, Map<String, String> option) throws Exception {
        executeProtocolScript();

        var hostPort = UtilFunc.extractIpPort(connectionInfo);
        host = hostPort[0];
        port = Integer.parseInt(hostPort[1]);
        if (option.containsKey("unitId"))
            defaultUnitId = Integer.parseInt(option.get("unitId"));
        if (option.containsKey("combineData"))
            combineData = Boolean.parseBoolean(option.get("combineData"));

        // keep connection timeout and retry behavior under the driver lifecycle instead of
        // delegating it to the fsm-based default transport
        var transport = new ModbusTcpSocketTransport(host, port, socketTimeout);
        master = ModbusTcpClient.create(transport, cfg -> cfg.setRequestTimeout(Duration.ofMillis(socketTimeout)));
    }

    @Override
    void requestConnect() throws Exception {
        log.info("[{}] host={}, port={}, socket-timeout={}", deviceId, host, port, socketTimeout);
        master.connect();
    }

    @Override
    void requestDisconnect() throws Exception {
        master.disconnect();
    }

    @Override
    List<Response> requestCommand(String cmdId, String requestInfo, int timeout, boolean isReadCommand, Value function, Value initialValue, Object nonPeriodicObject) throws Exception {
        var object = objectMapper.readValue(requestInfo, Object.class);
        if (isReadCommand) {
            var readAddress = new ArrayList<ModbusRead>();
            try {
                if (object instanceof List) {
                    for (var obj : (List<?>) object) {
                        var map = (Map<?, ?>) obj;
                        readAddress.add(new ModbusRead(map.get("address"), map.get("length"), map.get("unitId"), defaultUnitId, map.get("isCoil")));
                    }
                } else {
                    var map = (Map<?, ?>)object;
                    readAddress.add(new ModbusRead(map.get("address"), map.get("length"), map.get("unitId"), defaultUnitId, map.get("isCoil")));
                }
            } catch (Exception e) {
                throw new DriverCommand.ScriptException(e);
            }
            var results = new ArrayList<List<?>>();
            for (ModbusRead address : readAddress)
                results.add(readRequest(address, timeout));
            if (combineData) {
                var input = new ArrayList<>();
                for (var result : results)
                    input.addAll(result);
                return driverCommand.processCommandFunction(driverCommand.pythonEngine.toPyList(input), function, ZonedDateTime.now().toInstant().toEpochMilli(), initialValue);
            } else {
                var input = driverCommand.pythonEngine.newList();
                for (var result : results)
                    input.invokeMember("append", driverCommand.pythonEngine.toPyList(result));
                return driverCommand.processCommandFunction(input, function, ZonedDateTime.now().toInstant().toEpochMilli(), initialValue);
            }
        } else {
            var writeData = new ArrayList<ModbusWrite>();
            try {
                if (object instanceof List) {
                    for (var obj : ((List<?>) object)) {
                        var map = (Map<?, ?>) obj;
                        writeData.add(new ModbusWrite(map.get("address"), map.get("values"), map.get("unitId"), defaultUnitId));
                    }
                } else {
                    var map = (Map<?, ?>) object;
                    writeData.add(new ModbusWrite(map.get("address"), map.get("values"), map.get("unitId"), defaultUnitId));
                }
            } catch (Exception e) {
                throw new DriverCommand.ScriptException(e);
            }

            for (ModbusWrite writeInfo : writeData)
                writeRequest(writeInfo, timeout);

            return null;
        }
    }

    List<?> readRequest(ModbusRead readAddress, int timeout) throws Exception {
        log.trace("[{}] read request start, readAddress={}", deviceId, readAddress);
        if (readAddress.length == 0) {
            log.debug("[{}] zero length read request for address={}", deviceId, readAddress.address);
            return Collections.emptyList();
        }

        int address = readAddress.address;
        int length = readAddress.length;
        var ret = new ArrayList<>();
        while(length > BATCH_SIZE) {
            var result = read(address, BATCH_SIZE, readAddress.unitId, timeout, readAddress.type);
            log.trace("[{}] received raw data:{}", deviceId, result);
            ret.addAll(result);
            address += BATCH_SIZE;
            length -= BATCH_SIZE;
        }
        var result = read(address, length, readAddress.unitId, timeout, readAddress.type);
        log.trace("[{}] received raw data:{}", deviceId, result);
        ret.addAll(result);

        return ret;
    }

    private List<?> read(int address, int length, int unitId, int timeout, ModbusRead.Type type) throws Exception {
        if (type == ModbusRead.Type.COIL) {
            try {
                var result = master.readCoilsAsync(unitId, new ReadCoilsRequest(address, length))
                        .toCompletableFuture().get(timeout, TimeUnit.MILLISECONDS);
                return readBits(result.coils(), length);
            } catch (Exception e) {
                throw new Exception("ReadCoilsRequest failed, address=" + address + ", length=" + length + ", unitId=" + unitId, e);
            }
        } else if (type == ModbusRead.Type.DISCRETE_INPUT) {
            try {
                var result = master.readDiscreteInputsAsync(unitId, new ReadDiscreteInputsRequest(address, length))
                        .toCompletableFuture().get(timeout, TimeUnit.MILLISECONDS);
                return readBits(result.inputs(), length);
            } catch (Exception e) {
                throw new Exception("ReadDiscreteInputsRequest failed, address=" + address + ", length=" + length + ", unitId=" + unitId, e);
            }
        } else if (type == ModbusRead.Type.INPUT_REGISTER) {
            try {
                var result = master.readInputRegistersAsync(unitId, new ReadInputRegistersRequest(address, length))
                        .toCompletableFuture().get(timeout, TimeUnit.MILLISECONDS);
                return readRegister(result.registers());
            } catch (Exception e) {
                throw new Exception("ReadInputRegistersRequest failed, address=" + address + ", length=" + length + ", unitId=" + unitId, e);
            }
        } else {
            try {
                var result = master.readHoldingRegistersAsync(unitId, new ReadHoldingRegistersRequest(address, length))
                        .toCompletableFuture().get(timeout, TimeUnit.MILLISECONDS);
                return readRegister(result.registers());
            } catch (Exception e) {
                throw new Exception("ReadHoldingRegistersRequest failed, address=" + address + ", length=" + length + ", unitId=" + unitId, e);
            }
        }
    }

    static List<Boolean> readBits(byte[] bits, int length) {
        List<Boolean> ret = new ArrayList<>();
        int bitIndex = 0;
        for (int i = 0; i < bits.length && bitIndex < length; i++) {
            var val = bits[i];
            for (int b = 0; b < 8 && bitIndex++ < length; b++)
                ret.add((val & (1 << b)) != 0);
        }
        return ret;
    }

    static List<Integer> readRegister(byte[] registers) {
        var result = new ArrayList<Integer>();
        for (int i = 0; i + 1 < registers.length; i += 2)
            result.add(((registers[i] & 0xFF) << 8) | (registers[i + 1] & 0xFF));
        return result;
    }

    void writeRequest(ModbusWrite writeData, int timeout) throws Exception {
        log.trace("[{}] write request start, writeData={}", deviceId, writeData);
        if (writeData.values.isEmpty()) {
            log.debug("[{}] empty write request for address={}", deviceId, writeData.address);
            return;
        }
        int address = writeData.address;
        int length = writeData.values.size();
        int index = 0;
        while (length > BATCH_SIZE) {
            if (writeData.isCoil)
                writeCoil(address, writeData.values.subList(index, index + BATCH_SIZE), writeData.unitId, timeout);
            else
                writeRegister(address, writeData.values.subList(index, index + BATCH_SIZE), writeData.unitId, timeout);
            index += BATCH_SIZE;
            length -= BATCH_SIZE;
            address += BATCH_SIZE;
        }
        if (writeData.isCoil)
            writeCoil(address, writeData.values.subList(index, writeData.values.size()), writeData.unitId, timeout);
        else
            writeRegister(address, writeData.values.subList(index, writeData.values.size()), writeData.unitId, timeout);
    }

    private void writeCoil(int address, List<?> data, int unitId, int timeout) throws Exception {
        if (data.size() == 1) {
            try {
                master.writeSingleCoilAsync(unitId, new WriteSingleCoilRequest(address, (Boolean) data.get(0)))
                        .toCompletableFuture().get(timeout, TimeUnit.MILLISECONDS);
                log.trace("[{}] WriteSingleCoilRequest complete, address={}, unitId={}, data={}", deviceId, address, unitId, data);
            } catch (Exception e) {
                throw new Exception("WriteSingleCoilRequest failed, address=" + address + ", unitId=" + unitId + ", data=" + data, e);
            }
        } else {
            try {
                byte[] buf = new byte[(data.size() + 7) >> 3];
                for (int i = 0; i < data.size(); i++) {
                    if ((Boolean) data.get(i)) buf[i >> 3] |= 1 << (i & 7);
                }
                master.writeMultipleCoilsAsync(unitId, new WriteMultipleCoilsRequest(address, data.size(), buf))
                        .toCompletableFuture().get(timeout, TimeUnit.MILLISECONDS);
                log.trace("[{}] WriteMultipleCoilsRequest complete, address={}, unitId={}, data={}", deviceId, address, unitId, data);
            } catch (Exception e) {
                throw new Exception("WriteMultipleCoilsRequest failed, address=" + address + ", unitId=" + unitId + ", data=" + data, e);
            }
        }
    }

    private void writeRegister(int address, List<?> data, int unitId, int timeout) throws Exception {
        if (data.size() == 1) {
            try {
                master.writeSingleRegisterAsync(unitId, new WriteSingleRegisterRequest(address, (Integer) data.get(0)))
                        .toCompletableFuture().get(timeout, TimeUnit.MILLISECONDS);
                log.trace("[{}] WriteSingleRegisterRequest complete, address={}, unitId={}, data={}", deviceId, address, unitId, data);
            } catch (Exception e) {
                throw new Exception("WriteSingleRegisterRequest failed, address=" + address + ", unitId=" + unitId + ", data=" + data, e);
            }
        } else {
            try {
                byte[] buf = new byte[data.size() << 1];
                for (int i = 0; i < data.size(); i++) {
                    buf[(i << 1)] = (byte)((Integer) data.get(i) >> 8);
                    buf[(i << 1) + 1] = ((Integer) data.get(i)).byteValue();
                }
                master.writeMultipleRegistersAsync(unitId, new WriteMultipleRegistersRequest(address, data.size(), buf))
                        .toCompletableFuture().get(timeout, TimeUnit.MILLISECONDS);
                log.trace("[{}] WriteMultipleRegistersRequest complete, address={}, unitId={}, data={}", deviceId, address, unitId, data);
            } catch (Exception e) {
                throw new Exception("WriteMultipleRegistersRequest failed, address=" + address + ", unitId=" + unitId + ", data=" + data, e);
            }
        }
    }

    public String requestInfo(int address, int length) {
        return "{\"address\":" + address + ", \"length\":" + length + "}";
    }

    public String requestInfo(int address, int length, int unitId) {
        return "{\"address\":" + address + ", \"length\":" + length + ", \"unitId\":" + unitId + "}";
    }

    public String requestInfo(int address, int length, boolean isCoil) {
        return "{\"address\":" + address + ", \"length\":" + length + ", \"isCoil\":" + isCoil + "}";
    }

    public String requestInfo(int address, int length, int unitId, boolean isCoil) {
        return "{\"address\":" + address + ", \"length\":" + length + ", \"unitId\":" + unitId + ", \"isCoil\":" + isCoil + "}";
    }

    public String requestInfo(int address, List<?> values) {
        var s = values.stream().map(Object::toString).collect(Collectors.joining(","));
        return "{\"address\":" + address + ", \"values\":[" + s + "]}";
    }

    public String requestInfo(int address, List<?> values, int unitId) {
        var s = values.stream().map(Object::toString).collect(Collectors.joining(","));
        return "{\"address\":" + address + ", \"values\":[" + s + "], \"unitId\":" + unitId + "}";
    }

    @ToString
    private static class ModbusRead {
        int address;
        int length;
        int unitId;
        Type type;

        ModbusRead(Object address, Object length, Object unitId, int defaultUnitId, Object isCoil) throws Exception {
            var ex = new Exception("creating ModbusRead failed, address=" + address + ", length=" + length + ", unitId=" + unitId + ", isCoil=" + isCoil);
            if (!(address instanceof Integer) || !(length instanceof Integer) || ((Integer) length) < 0 || (unitId != null && !(unitId instanceof Integer)) || (isCoil != null && !(isCoil instanceof Boolean)))
                throw ex;
            this.address = (Integer) address;
            this.length = (Integer) length;
            this.unitId = unitId == null ? defaultUnitId : (Integer) unitId;
            if (isCoil != null && (Boolean) isCoil) {
                type = Type.COIL;
                if (this.address > 0 && this.address + this.length <= 65537)
                    this.address = this.address - 1;
                else
                    throw ex;
            } else if (this.address > 10000 && this.address + this.length <= 20001) {
                type = Type.DISCRETE_INPUT;
                this.address = this.address - 10001;
            } else if (this.address > 100000 && this.address + this.length <= 165537) {
                type = Type.DISCRETE_INPUT;
                this.address = this.address - 100001;
            } else if (this.address > 30000 && this.address + this.length <= 40001) {
                type = Type.INPUT_REGISTER;
                this.address = this.address - 30001;
            } else if (this.address > 300000 && this.address + this.length <= 365537) {
                type = Type.INPUT_REGISTER;
                this.address = this.address - 300001;
            } else if (this.address > 40000 && this.address + this.length <= 50001) {
                type = Type.HOLDING_REGISTER;
                this.address = this.address - 40001;
            } else if (this.address > 400000 && this.address + this.length <= 465537) {
                type = Type.HOLDING_REGISTER;
                this.address = this.address - 400001;
            } else {
                throw ex;
            }
        }

        enum Type {
            COIL,
            DISCRETE_INPUT,
            INPUT_REGISTER,
            HOLDING_REGISTER
        }
    }

    @ToString
    private static class ModbusWrite {
        int address;
        List<?> values;
        int unitId;
        boolean isCoil = false;

        ModbusWrite(Object address, Object values, Object unitId, int defaultUnitId) throws Exception {
            var ex = new Exception("creating ModbusWrite failed, address=" + address + ", values=" + values + ", unitId=" + unitId);
            if (!(address instanceof Integer) || !(values instanceof List<?>) || (unitId != null && !(unitId instanceof Integer)))
                throw ex;
            this.address = (Integer) address;
            this.unitId = unitId == null ? defaultUnitId : (Integer) unitId;
            this.values = (List<?>) values;
            if (this.address <= 0 || this.address + this.values.size() > 65537)
                throw ex;

            this.address = this.address - 1;

            if (!this.values.isEmpty()) {
                if (this.values.get(0) instanceof Integer)
                    isCoil = false;
                else if (this.values.get(0) instanceof Boolean)
                    isCoil = true;
                else
                    throw ex;
            }
        }
    }
}
