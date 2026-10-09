package net.benelog.silkjson;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * A byte array read and written eight or four bytes at a time, through the
 * JDK's byte-array view: the first byte is the low byte of the word. The view
 * checks its index as an array access does, and the compiler turns each call
 * into one load or one store, so a string is scanned a word at a time with
 * no {@code sun.misc.Unsafe} under it.
 *
 * <p>A scan finds the bytes it stops at with the arithmetic of
 * {@link #special(long)}: a word whose bytes are all plain string characters
 * answers zero, and otherwise the lowest flag marks the first byte that is not.
 */
final class LittleEndian {

    private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private static final long ONES = 0x0101010101010101L;
    private static final long HIGH_BITS = 0x8080808080808080L;
    private static final long QUOTES = 0x2222222222222222L;
    private static final long BACKSLASHES = 0x5C5C5C5C5C5C5C5CL;
    private static final long SPACES = 0x2020202020202020L;

    private LittleEndian() {
    }

    static long getLong(byte[] bytes, int index) {
        return (long) LONG.get(bytes, index);
    }

    static void putLong(byte[] bytes, int index, long value) {
        LONG.set(bytes, index, value);
    }

    static int getInt(byte[] bytes, int index) {
        return (int) INT.get(bytes, index);
    }

    /**
     * The high bit of each byte in the word that a JSON string does not hold
     * as itself: a quote, a backslash, a control character, or a byte of a
     * character outside ASCII. A borrow can set a flag above a byte that
     * really is one of these, never below it, so the lowest flag is exact:
     * {@code Long.numberOfTrailingZeros(special) >>> 3} is that byte's index.
     */
    static long special(long word) {
        long quotes = word ^ QUOTES;
        long backslashes = word ^ BACKSLASHES;
        return (((quotes - ONES) & ~quotes) | ((backslashes - ONES) & ~backslashes) | (word - SPACES) | word) & HIGH_BITS;
    }

    /**
     * How many of the word's bytes, from the first, are ASCII digits, eight
     * when all are. A byte is a digit when its high nibble is 3 and adding 6
     * to it leaves the high nibble 3; a carry out of a byte that is not a
     * digit reaches only the bytes above it, which come after it.
     */
    static int digits(long word) {
        long nibbles = (word & 0xF0F0F0F0F0F0F0F0L) | (((word + 0x0606060606060606L) & 0xF0F0F0F0F0F0F0F0L) >>> 4);
        return Long.numberOfTrailingZeros(nibbles ^ 0x3333333333333333L) >>> 3;
    }

    /**
     * The number the first digits of the word make, one to eight of them, with
     * three multiplications instead of one per digit: the digits are shifted
     * to the top of the word, below them is zeros, and pairs, then fours,
     * then the eight are combined.
     */
    static long digitsValue(long word, int digits) {
        long value = (word - 0x3030303030303030L) << ((8 - digits) << 3);
        value = value * 10 + (value >>> 8);
        return (((value & 0x000000FF000000FFL) * (100 + (1000000L << 32))
                + ((value >>> 16) & 0x000000FF000000FFL) * (1 + (10000L << 32))) >>> 32);
    }

    /** How many bytes of the word come before the first one {@link #special(long)} flags. */
    static int plainBytes(long special) {
        return Long.numberOfTrailingZeros(special) >>> 3;
    }

    /** The bytes eight to a word, the last word filled out with zeros. */
    static long[] words(byte[] bytes) {
        return words(bytes, 0, bytes.length);
    }

    /** The bytes in that range eight to a word, the last word filled out with zeros. */
    static long[] words(byte[] bytes, int from, int to) {
        long[] words = new long[(to - from + 7) >>> 3];
        for (int i = from; i < to; i++) {
            int index = i - from;
            words[index >>> 3] |= (bytes[i] & 0xFFL) << ((index & 7) << 3);
        }
        return words;
    }
}
