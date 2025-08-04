package com.sds.communicators.driver;

import com.digitalpetri.modbus.master.ModbusTcpMaster;
import com.digitalpetri.modbus.master.ModbusTcpMasterConfig;
import com.digitalpetri.modbus.requests.*;
import com.digitalpetri.modbus.responses.ReadCoilsResponse;
import com.digitalpetri.modbus.responses.ReadDiscreteInputsResponse;
import com.digitalpetri.modbus.responses.ReadHoldingRegistersResponse;
import com.digitalpetri.modbus.responses.ReadInputRegistersResponse;
import com.sds.communicators.common.UtilFunc;
import com.sds.communicators.common.struct.Response;
import io.netty.buffer.ByteBuf;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.python.core.PyFunction;
import org.python.core.PyList;
import org.python.core.PyObject;

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
    private ModbusTcpMaster master;
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

        var config = new ModbusTcpMasterConfig.Builder(host).setPort(port).setTimeout(Duration.ofMillis(socketTimeout)).build();
        master = new ModbusTcpMaster(config);
    }

    @Override
    void requestConnect() throws Exception {
        log.info("[{}] host={}, port={}, socket-timeout={}", deviceId, host, port, socketTimeout);
        master.connect().get();
    }

    @Override
    void requestDisconnect() throws Exception {
        master.disconnect().get();
    }

    @Override
    List<Response> requestCommand(String cmdId, String requestInfo, int timeout, boolean isReadCommand, PyFunction function, PyObject initialValue, Object nonPeriodicObject) throws Exception {
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
                return driverCommand.processCommandFunction(new PyList(input), function, ZonedDateTime.now().toInstant().toEpochMilli(), initialValue);
            } else {
                var input = new ArrayList<PyList>();
                for (var result : results)
                    input.add(new PyList(result));
                return driverCommand.processCommandFunction(new PyList(input), function, ZonedDateTime.now().toInstant().toEpochMilli(), initialValue);
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
                var result = (ReadCoilsResponse)master.sendRequest(new ReadCoilsRequest(address, length), unitId).get(timeout, TimeUnit.MILLISECONDS);
                try {
                    return readBits(result.getCoilStatus(), length);
                } finally {
                    result.release();
                }
            } catch (Exception e) {
                throw new Exception("ReadCoilsRequest failed, address=" + address + ", length=" + length + ", unitId=" + unitId, e);
            }
        } else if (type == ModbusRead.Type.DISCRETE_INPUT) {
            try {
                var result = (ReadDiscreteInputsResponse)master.sendRequest(new ReadDiscreteInputsRequest(address, length), unitId).get(timeout, TimeUnit.MILLISECONDS);
                try {
                    return readBits(result.getInputStatus(), length);
                } finally {
                    result.release();
                }
            } catch (Exception e) {
                throw new Exception("ReadDiscreteInputsRequest failed, address=" + address + ", length=" + length + ", unitId=" + unitId, e);
            }
        } else if (type == ModbusRead.Type.INPUT_REGISTER) {
            try {
                var result = (ReadInputRegistersResponse)master.sendRequest(new ReadInputRegistersRequest(address, length), unitId).get(timeout, TimeUnit.MILLISECONDS);
                try {
                    return readRegister(result.getRegisters());
                } finally {
                    result.release();
                }
            } catch (Exception e) {
                throw new Exception("ReadInputRegistersRequest failed, address=" + address + ", length=" + length + ", unitId=" + unitId, e);
            }
        } else {
            try {
                var result = (ReadHoldingRegistersResponse)master.sendRequest(new ReadHoldingRegistersRequest(address, length), unitId).get(timeout, TimeUnit.MILLISECONDS);
                try {
                    return readRegister(result.getRegisters());
                } finally {
                    result.release();
                }
            } catch (Exception e) {
                throw new Exception("ReadHoldingRegistersRequest failed, address=" + address + ", length=" + length + ", unitId=" + unitId, e);
            }
        }
    }

    static List<Boolean> readBits(ByteBuf registers, int length) {
        List<Boolean> ret = new ArrayList<>();
        int bitIndex = 0;
        while (registers != null && registers.isReadable()) {
            var val = registers.readByte();
            for (int i = 0; i < 8 && bitIndex++ < length; i++)
                ret.add((val & (1 << i)) != 0);
        }
        return ret;
    }

    static List<Integer> readRegister(ByteBuf registers) {
        var result = new ArrayList<Integer>();
        while(registers.isReadable())
            result.add(registers.readUnsignedShort());
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
                master.sendRequest(new WriteSingleCoilRequest(address, (Boolean) data.get(0)), unitId).get(timeout, TimeUnit.MILLISECONDS);
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
                master.sendRequest(new WriteMultipleCoilsRequest(address, data.size(), buf), unitId).get(timeout, TimeUnit.MILLISECONDS);
                log.trace("[{}] WriteMultipleCoilsRequest complete, address={}, unitId={}, data={}", deviceId, address, unitId, data);
            } catch (Exception e) {
                throw new Exception("WriteMultipleCoilsRequest failed, address=" + address + ", unitId=" + unitId + ", data=" + data, e);
            }
        }
    }

    private void writeRegister(int address, List<?> data, int unitId, int timeout) throws Exception {
        if (data.size() == 1) {
            try {
                master.sendRequest(new WriteSingleRegisterRequest(address, (Integer) data.get(0)), unitId).get(timeout, TimeUnit.MILLISECONDS);
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
                master.sendRequest(new WriteMultipleRegistersRequest(address, data.size(), buf), unitId).get(timeout, TimeUnit.MILLISECONDS);
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