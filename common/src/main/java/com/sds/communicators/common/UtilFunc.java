package com.sds.communicators.common;

import com.sds.communicators.common.struct.Command;
import com.sds.communicators.common.struct.Device;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class UtilFunc {

    private static final Pattern expPattern = Pattern.compile("-?(([0-9]+\\.?[0-9]*)|([0-9]*\\.?[0-9]+))E[-\\+]?[0-9]+");
    private static final Pattern doublePattern = Pattern.compile("-?([0-9]+\\.?[0-9]*)|([0-9]*\\.?[0-9]+)");
    private static final Pattern argPattern = Pattern.compile("(?<!\\\\)(\\\\\\\\)*\\\\$");

    public static String printByteData(byte[] bytes) {
        if (bytes == null)
            return null;
        var hex = new StringBuilder();
        hex.append("\n");
        hex.append("         +-------------------------------------------------+\n");
        hex.append("         |  0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f |\n");
        hex.append("+--------+-------------------------------------------------+----------------+\n");
        for (int i = 0; i < bytes.length; i++) {
            int mod = i & 0xF;
            if (mod == 0) {
                hex.append(String.format("|%08X|", i));
            }
            hex.append(String.format(" %02X", bytes[i]));
            if (mod == 15 || i == bytes.length - 1) {
                hex.append("   ".repeat(16 - (mod + 1)));
                var arr = Arrays.copyOfRange(bytes, i - mod, i + 1);
                for (int c = 0; c < arr.length; c++) {
                    if (arr[c] < 0x20 || arr[c] > 0x7e)
                        arr[c] = 0x2e;
                }
                hex.append(" |").append(String.format("%-16s", new String(arr, StandardCharsets.US_ASCII))).append("|\n");
            }
        }
        hex.append("+--------+-------------------------------------------------+----------------+\n");

        return hex.toString();
    }

    public static String printByteData(Byte[] wrappedBytes) {
        if (wrappedBytes == null)
            return null;
        var bytes = new byte[wrappedBytes.length];
        for (int i=0;i<wrappedBytes.length;i++)
            bytes[i] = wrappedBytes[i];
        return printByteData(bytes);
    }

    /**
     * extract ip and port for ipv4 or ipv6
     * [example] ipv4: "0.0.0.0:8080"       -> ["0.0.0.0", "8080"]
     *           ipv6: "[ff:ff:ff:ff]:8080" -> ["ff:ff:ff:ff", "8080"]
     *
     * @param notation ip and port
     * @return ip, port array
     */
    public static String[] extractIpPort(String notation) {
        int index;
        if (notation.startsWith("[")) {
            index = notation.indexOf(']');
            return new String[] {notation.substring(1, index), notation.substring(index + 2)};
        } else {
            index = notation.lastIndexOf(':');
            return new String[] {notation.substring(0, index), notation.substring(index + 1)};
        }
    }


    /**
     * string split, if delimiter is used for string, escape with '\'
     * [example] str = "a\,b,cd\,,e" delimiter = "," -> {a,b},{cd,},{e}
     *
     * @param str input string
     * @param delimiter split delimiter
     * @return spliced string
     */
    public static List<String> stringSplit(String str, String delimiter) {
        List<String> ret = new ArrayList<>();
        var options = str.split(delimiter, -1);
        StringBuilder concatString = new StringBuilder();
        for (int i = 0; i < options.length - 1; i++) {
            var mat = argPattern.matcher(options[i]);
            if (mat.find()) {
                concatString.append(options[i], 0, options[i].length() - 1).append(delimiter);
            } else {
                concatString.append(options[i]);
                ret.add(concatString.toString());
                concatString = new StringBuilder();
            }
        }
        ret.add(concatString + options[options.length - 1]);
        return ret;
    }

    /**
     * value * 10^decimalPlace
     *
     * @param value input value
     * @param decimalPlace decimal place
     * @return decimal place applied value
     */
    public static String getDecimalPlaced(String value, int decimalPlace) {
        StringBuilder str = new StringBuilder(value.replaceAll("\\s", "").toUpperCase());
        int dp = decimalPlace;
        var mat = expPattern.matcher(str.toString());
        if (mat.find()) {
            String mString = mat.group();
            int idx = mString.indexOf("E");
            str = new StringBuilder(mString.substring(0, idx));
            dp += Integer.parseInt(mString.substring(idx + 1));
        } else {
            mat = doublePattern.matcher(str.toString());
            if (mat.find()) {
                str = new StringBuilder(mat.group());
            } else {
                return value;
            }
        }

        //negative check
        boolean isNegative = false;
        if (str.charAt(0) == '-') {
            isNegative = true;
            str = new StringBuilder(str.substring(1));
        }

        // find dot position
        int idx = str.toString().indexOf('.');
        if (idx != -1)
            str = new StringBuilder(str.substring(0, idx) + str.substring(idx + 1));
        else
            idx = str.length();

        idx += dp;

        //move dot
        boolean hasDot = true;
        int length = str.length();
        if (idx <= 0) {
            for (int i = 0; i < -idx; i++)
                str.insert(0, "0");

            str.insert(0, "0.");
        } else if (idx >= length) {
            hasDot = false;
            str.append("0".repeat(Math.max(0, Math.max(0, idx - length))));
        } else {
            str = new StringBuilder(str.substring(0, idx) + "." + str.substring(idx));
        }

        //remove zeros
        int sidx = 0, eidx = str.length() - 1;
        for (; sidx < str.length(); sidx++) {
            if (str.charAt(sidx) != '0') {
                if (str.charAt(sidx) == '.') sidx--;
                break;
            }
        }
        if (hasDot)
            for (; eidx >= 0; eidx--) {
                if (str.charAt(eidx) != '0') {
                    if (str.charAt(eidx) == '.') eidx--;
                    break;
                }
            }
        if (sidx == str.length() || eidx == -1)
            str = new StringBuilder("0");
        else
            str = new StringBuilder(str.substring(sidx, eidx + 1));

        //add minus symbol
        if (!str.toString().equals("0") && isNegative)
            str.insert(0, '-');

        return str.toString();
    }

    public static <T> int findFirst(T[] source, T[] pattern, boolean reverse) {
        var ret = findArrayPattern(source, pattern, reverse, true);
        if (ret.isEmpty()) return -1;
        else return ret.get(0);
    }

    public static <T> List<Integer> findArrayPattern(T[] source, T[] pattern) {
        return findArrayPattern(source, pattern, false, false);
    }

    public static <T> List<Integer> findArrayPattern(T[] source, T[] pattern, boolean reverse) {
        return findArrayPattern(source, pattern, reverse, false);
    }

    /**
     * find all indexes of pattern array from source array
     * KMP algorithm used
     *
     * @param source array source
     * @param pattern array pattern to find
     * @param reverse reverse order if true
     * @param stopAtFirst stop finding when found first pattern if true
     * @return indexes of found pattern
     */
    public static <T> List<Integer> findArrayPattern(T[] source, T[] pattern, boolean reverse, boolean stopAtFirst) {
        List<Integer> ret = new ArrayList<>();
        if (source == null || pattern == null || source.length == 0 || pattern.length == 0)
            return ret;

        int[] pi = new int[pattern.length];
        if (reverse) {
            for (int i = 0; i < pattern.length; i++)
                pi[i] = pattern.length - 1;
        }
        int j = reverse ? pattern.length - 1 : 0;
        if (reverse) {
            for (int i = pattern.length - 2; i != -1; i--) {
                while (j < pattern.length - 1 && !pattern[i].equals(pattern[j])) j = pi[j + 1];
                if (pattern[i].equals(pattern[j])) pi[i] = --j;
            }
        } else {
            for (int i = 1; i < pattern.length; i++) {
                while (j > 0 && !pattern[i].equals(pattern[j])) j = pi[j - 1];
                if (pattern[i].equals(pattern[j])) pi[i] = ++j;
            }
        }

        j = reverse ? pattern.length - 1 : 0;
        if (reverse) {
            for (int i = source.length - 1; i != -1; i--) {
                while(j < pattern.length - 1 && !source[i].equals(pattern[j])) j = pi[j + 1];
                if (source[i].equals(pattern[j])) {
                    if (j == 0) {
                        ret.add(i);
                        if (stopAtFirst) return ret;
                        j = pi[j];
                    } else {
                        j--;
                    }
                }
            }
        } else {
            for(int i = 0; i < source.length; i++)
            {
                while(j > 0 && !source[i].equals(pattern[j])) j = pi[j - 1];
                if (source[i].equals(pattern[j])) {
                    if (j == pattern.length - 1) {
                        ret.add(i - pattern.length + 1);
                        if (stopAtFirst) return ret;
                        j = pi[j];
                    } else {
                        j++;
                    }
                }
            }
        }
        return ret;
    }

    public static Byte[] combineByteArrayList(List<Byte[]> array) {
        int size = 0;
        for (var bytes : array) size += bytes.length;
        var ret = new Byte[size];
        size = 0;
        for (var bytes : array) {
            System.arraycopy(bytes, 0, ret, size, bytes.length);
            size += bytes.length;
        }
        return ret;
    }

    public static Byte[] arrayWrapper(byte[] array) {
        if (array == null)
            return null;

        Byte[] ret = new Byte[array.length];
        for (int i = 0; i < array.length; i++) ret[i] = array[i];
        return ret;
    }

    public static Short[] arrayWrapper(short[] array) {
        if (array == null)
            return null;

        Short[] ret = new Short[array.length];
        for (int i = 0; i < array.length; i++) ret[i] = array[i];
        return ret;
    }

    public static Integer[] arrayWrapper(int[] array) {
        if (array == null)
            return null;

        Integer[] ret = new Integer[array.length];
        for (int i = 0; i < array.length; i++) ret[i] = array[i];
        return ret;
    }

    public static Long[] arrayWrapper(long[] array) {
        if (array == null)
            return null;

        Long[] ret = new Long[array.length];
        for (int i = 0; i < array.length; i++) ret[i] = array[i];
        return ret;
    }

    public static Float[] arrayWrapper(float[] array) {
        if (array == null)
            return null;

        Float[] ret = new Float[array.length];
        for (int i = 0; i < array.length; i++) ret[i] = array[i];
        return ret;
    }

    public static Double[] arrayWrapper(double[] array) {
        if (array == null)
            return null;

        Double[] ret = new Double[array.length];
        for (int i = 0; i < array.length; i++) ret[i] = array[i];
        return ret;
    }

    /**
     * convert string with ("\x00", "\r", "\n", "\t", "\\") to byte array
     *
     * @param data input string
     * @return processed byte array
     */
    public static byte[] stringToByteArray(String data) {
        if (data == null || data.isEmpty())
            return null;

        int bsCnt = 0;
        int size = 0;
        var ret = new byte[data.getBytes().length];
        for (int i = 0; i < data.length(); i++) {
            if (data.charAt(i) == '\\') {
                bsCnt++;
            } else {
                for (int c = 0; c < (bsCnt >> 1); c++) ret[size++] = '\\';
                if ((bsCnt & 1) == 0) {
                    var src = data.substring(i, i + 1).getBytes();
                    System.arraycopy(src, 0, ret, size, src.length);
                    size += src.length;
                } else {
                    if (data.charAt(i) == 'x' && i + 2 < data.length() && data.substring(i + 1, i + 3).toLowerCase().matches("[0-9a-f]{2}")) {
                        String num = data.substring(i + 1, i + 3).toLowerCase();
                        int c1 = num.charAt(0) <= '9' ? num.charAt(0) - '0' : (num.charAt(0) - 'a') + 10;
                        int c2 = num.charAt(1) <= '9' ? num.charAt(1) - '0' : (num.charAt(1) - 'a') + 10;
                        ret[size++] = (byte)((c1 << 4) + c2);
                        i += 2;
                    } else if (data.charAt(i) == 'r') {
                        ret[size++] = '\r';
                    } else if (data.charAt(i) == 'n') {
                        ret[size++] = '\n';
                    } else if (data.charAt(i) == 't') {
                        ret[size++] = '\t';
                    } else {
                        var src = data.substring(i, i + 1).getBytes();
                        System.arraycopy(src, 0, ret, size, src.length);
                        size += src.length;
                    }
                }
                bsCnt = 0;
            }
        }

        if (bsCnt != 0)
            for (int c = 0; c < (bsCnt >> 1); c++) ret[size++] = '\\';

        if (size > 0)
            return Arrays.copyOfRange(ret, 0, size);
        else
            return null;
    }

    public static String joinCommandId(Set<Command> commands) {
        return commands.stream().map(Command::getId).collect(Collectors.joining(", "));
    }

    public static String joinDeviceId(Set<Device> devices) {
        return devices.stream().map(Device::getId).collect(Collectors.joining(", "));
    }
}
