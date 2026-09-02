package com.sds.communicators.driver;

import com.google.common.primitives.Ints;
import com.sds.communicators.common.UtilFunc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** address convention and wire data decoding shared by the modbus client and server */
abstract class DriverProtocolModbus extends DriverProtocol {
    protected String host;
    protected int port;

    static final String ADDRESS_FORMAT =
            "address must be a string of a table digit(0=coil, 1=discrete input, 3=input register, 4=holding register)"
                    + " followed by a one-based offset, for example \"40001\"";

    @Override
    void initialize(String connectionInfo, Map<String, String> option) throws Exception {
        executeProtocolScript();

        var hostPort = UtilFunc.extractIpPort(connectionInfo);
        host = hostPort[0];
        port = Integer.parseInt(hostPort[1]);
    }

    enum ModbusTable {
        COIL,
        DISCRETE_INPUT,
        INPUT_REGISTER,
        HOLDING_REGISTER
    }

    record ModbusAddress(int address, ModbusTable table) {}

    private static ModbusTable tableOf(char digit) {
        return switch (digit) {
            case '0' -> ModbusTable.COIL;
            case '1' -> ModbusTable.DISCRETE_INPUT;
            case '3' -> ModbusTable.INPUT_REGISTER;
            case '4' -> ModbusTable.HOLDING_REGISTER;
            default -> null;
        };
    }

    private static char digitOf(ModbusTable table) {
        return switch (table) {
            case COIL -> '0';
            case DISCRETE_INPUT -> '1';
            case INPUT_REGISTER -> '3';
            case HOLDING_REGISTER -> '4';
        };
    }

    /**
     * resolve a modicon address string covering entries entries into its table and zero-based offset,
     * null when the address names no table or the offset leaves its table.
     * the leading digit selects the table and the remaining digits are the one-based offset inside it,
     * so "00001", "10001", "30001" and "40001" all resolve to offset 0
     */
    static ModbusAddress resolveAddress(Object address, int entries) {
        if (!(address instanceof String text) || text.length() < 2)
            return null;
        var table = tableOf(text.charAt(0));
        if (table == null)
            return null;
        var offset = Ints.tryParse(text.substring(1));
        // an empty request still has to land in the table its address names, so span at least one entry
        int count = Math.max(entries, 1);
        if (offset == null || offset < 1 || offset - 1 + count > 65536)
            return null;
        return new ModbusAddress(offset - 1, table);
    }

    /** canonical address string of a table entry, the inverse of {@link #resolveAddress} */
    static String canonicalAddress(ModbusTable table, int address) {
        return String.format(Locale.ROOT, "%c%05d", digitOf(table), address + 1);
    }

    /** coils and discrete inputs hold single bits, the register tables hold 16-bit words */
    static boolean isBitTable(ModbusTable table) {
        return table == ModbusTable.COIL || table == ModbusTable.DISCRETE_INPUT;
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
}
