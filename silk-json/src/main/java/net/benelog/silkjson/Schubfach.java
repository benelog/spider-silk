package net.benelog.silkjson;

import java.math.BigInteger;

/**
 * A double or a float as the decimal {@link Double#toString(double)} and
 * {@link Float#toString(float)} write for it, written as bytes with no string
 * in between, by the algorithm of Raffaello Giulietti, "The Schubfach way to
 * render doubles" (2020), the one those two methods use since JDK 19.
 *
 * <p>A value {@code c × 2^q} has a rounding interval: the reals that read
 * back as it. The decimal written is the shortest in that interval, and of
 * two equally short the one nearer the value; a decimal of one digit may give
 * way to a nearer one of two. The algorithm picks a power {@code 10^k} that
 * puts the value and both ends of its interval within a few units of an
 * integer, {@code 4 × c × 2^q / 10^k}, computed with one 128-bit
 * multiplication each and rounded to odd. Rounding to odd keeps every
 * comparison with an integer exact, so the candidates, the integer on either
 * side and the multiples of ten on either side, are tested against the
 * interval with nothing but integer compares.
 *
 * <p>The table of {@code 10^-k} is computed with {@link BigInteger} when the
 * class is first used, as {@link EiselLemire}'s is.
 */
final class Schubfach {

    /**
     * The most bytes a double or a float takes, as {@code -2.2250738585072014E-308}
     * does; the digits laid out before their trailing zeros go stay inside it too.
     */
    static final int MAX_LENGTH = 24;

    private static final int DOUBLE_STORED_BITS = 52;
    private static final int DOUBLE_PRECISION = 53;
    private static final int DOUBLE_SMALLEST_EXPONENT = -1074;
    private static final int DOUBLE_LARGEST_EXPONENT = 971;
    private static final long DOUBLE_SMALLEST_NORMAL_SIGNIFICAND = 1L << DOUBLE_STORED_BITS;

    /**
     * A subnormal significand below this is too few units for the power
     * {@code 10^k} the algorithm picks, and is taken as ten times as many
     * units of a tenth of the value.
     */
    private static final long DOUBLE_TINY = 3;

    private static final int FLOAT_STORED_BITS = 23;
    private static final int FLOAT_PRECISION = 24;
    private static final int FLOAT_SMALLEST_EXPONENT = -149;
    private static final int FLOAT_SMALLEST_NORMAL_SIGNIFICAND = 1 << FLOAT_STORED_BITS;
    private static final int FLOAT_TINY = 8; // as DOUBLE_TINY

    /** The powers {@code k} the table covers: those of the smallest and the largest double. */
    private static final int SMALLEST_K = floorLog10Pow2(DOUBLE_SMALLEST_EXPONENT);
    private static final int LARGEST_K = floorLog10Pow2(DOUBLE_LARGEST_EXPONENT);

    /** The digits a decimal is laid out with before its trailing zeros are dropped: as many as a double ever needs. */
    private static final int DIGITS = 17;

    /** {@code 10^n} for n from 0 to 17. */
    private static final long[] POWERS_OF_TEN = new long[DIGITS + 1];

    static {
        long power = 1;
        for (int n = 0; n <= DIGITS; n++) {
            POWERS_OF_TEN[n] = power;
            power *= 10;
        }
    }

    private static final long LOW_63 = (1L << 63) - 1;
    private static final long LOW_32 = (1L << 32) - 1;

    /**
     * {@code g = floor(10^-k × 2^-r) + 1} for each k, where r puts g between
     * {@code 2^125} and {@code 2^126}: the high 63 bits, then the low 63.
     */
    private static final long[] POWERS = powers();

    private static final byte[] DIGIT_PAIRS = JsonOutput.DIGIT_PAIRS;

    /** The character 0 in every byte, which turns a digit's value into its character. */
    private static final long ZEROS = 0x3030_3030_3030_3030L;

    private Schubfach() {
    }

    /** Writes a finite double at {@code at}, with {@link #MAX_LENGTH} bytes free there, and answers where it ends. */
    static int write(double value, byte[] buf, int at) {
        long bits = Double.doubleToRawLongBits(value);
        if (bits < 0) {
            buf[at++] = '-';
        }
        long stored = bits & (DOUBLE_SMALLEST_NORMAL_SIGNIFICAND - 1);
        int biased = (int) (bits >>> DOUBLE_STORED_BITS) & 0x7FF;
        if (biased == 0) {
            if (stored == 0) {
                return zero(buf, at);
            }
            return stored < DOUBLE_TINY
                    ? decimal(DOUBLE_SMALLEST_EXPONENT, 10 * stored, -1, buf, at)
                    : decimal(DOUBLE_SMALLEST_EXPONENT, stored, 0, buf, at);
        }
        int q = biased + DOUBLE_SMALLEST_EXPONENT - 1;
        long c = DOUBLE_SMALLEST_NORMAL_SIGNIFICAND | stored;
        if (q < 0 && q > -DOUBLE_PRECISION) {
            long whole = c >> -q;
            if (whole << -q == c) {
                return whole(whole, buf, at); // a whole number below 2^53 is its own shortest decimal
            }
        }
        return decimal(q, c, 0, buf, at);
    }

    /** Writes a finite float at {@code at}, with {@link #MAX_LENGTH} bytes free there, and answers where it ends. */
    static int write(float value, byte[] buf, int at) {
        int bits = Float.floatToRawIntBits(value);
        if (bits < 0) {
            buf[at++] = '-';
        }
        int stored = bits & (FLOAT_SMALLEST_NORMAL_SIGNIFICAND - 1);
        int biased = (bits >>> FLOAT_STORED_BITS) & 0xFF;
        if (biased == 0) {
            if (stored == 0) {
                return zero(buf, at);
            }
            return stored < FLOAT_TINY
                    ? decimal(FLOAT_SMALLEST_EXPONENT, 10 * stored, -1, buf, at)
                    : decimal(FLOAT_SMALLEST_EXPONENT, stored, 0, buf, at);
        }
        int q = biased + FLOAT_SMALLEST_EXPONENT - 1;
        int c = FLOAT_SMALLEST_NORMAL_SIGNIFICAND | stored;
        if (q < 0 && q > -FLOAT_PRECISION) {
            int whole = c >> -q;
            if (whole << -q == c) {
                return whole(whole, buf, at);
            }
        }
        return decimal(q, c, 0, buf, at);
    }

    /**
     * The decimal for the double {@code c × 2^q}, or for {@code c / 10 × 2^q}
     * when {@code dk} is -1. The interval's ends are {@code (c ± 1/2) × 2^q},
     * or {@code c - 1/4} below a power of two, where the doubles below are
     * spaced half as far apart; an even c rounds its ends to itself, so they
     * belong to its interval, and an odd c does not.
     */
    private static int decimal(int q, long c, int dk, byte[] buf, int at) {
        // The names are the paper's: cb is 4c, cbl and cbr the interval's ends
        // in the same quarter units, and vb, vbl, and vbr the three over 10^k.
        int open = (int) c & 1;
        long cb = c << 2;
        long cbr = cb + 2;
        long cbl;
        int k;
        if (c != DOUBLE_SMALLEST_NORMAL_SIGNIFICAND || q == DOUBLE_SMALLEST_EXPONENT) {
            cbl = cb - 2;
            k = floorLog10Pow2(q);
        } else {
            cbl = cb - 1;
            k = floorLog10ThreeQuartersPow2(q);
        }
        int h = q + floorLog2Pow10(-k) + 2;
        int index = (k - SMALLEST_K) << 1;
        long g1 = POWERS[index];
        long g0 = POWERS[index + 1];
        long vb = roundToOdd(g1, g0, cb << h);
        long vbl = roundToOdd(g1, g0, cbl << h);
        long vbr = roundToOdd(g1, g0, cbr << h);

        // vb is four times the value over 10^k, so s is its integer part: 16 or 17 digits for a normal double.
        long s = vb >> 2;
        if (s >= 100) {
            // A digit shorter: the multiples of ten on either side, when exactly one of them is in the interval.
            long below = s / 10 * 10;
            long above = below + 10;
            boolean belowIn = vbl + open <= below << 2;
            boolean aboveIn = (above << 2) + open <= vbr;
            if (belowIn != aboveIn) {
                return layOut(belowIn ? below : above, k + dk, buf, at);
            }
        }
        long t = s + 1;
        boolean sIn = vbl + open <= s << 2;
        boolean tIn = (t << 2) + open <= vbr;
        if (sIn != tIn) {
            return layOut(sIn ? s : t, k + dk, buf, at);
        }
        // Both are in: the nearer, and of two as near the even one.
        long side = vb - ((s + t) << 1);
        return layOut(side < 0 || (side == 0 && (s & 1) == 0) ? s : t, k + dk, buf, at);
    }

    /** {@link #decimal(int, long, int, byte[], int)} for a float, for which the high 63 bits of g are enough. */
    private static int decimal(int q, int c, int dk, byte[] buf, int at) {
        int open = c & 1;
        long cb = (long) c << 2;
        long cbr = cb + 2;
        long cbl;
        int k;
        if (c != FLOAT_SMALLEST_NORMAL_SIGNIFICAND || q == FLOAT_SMALLEST_EXPONENT) {
            cbl = cb - 2;
            k = floorLog10Pow2(q);
        } else {
            cbl = cb - 1;
            k = floorLog10ThreeQuartersPow2(q);
        }
        int h = q + floorLog2Pow10(-k) + 33;
        long g = POWERS[(k - SMALLEST_K) << 1] + 1;
        int vb = roundToOdd(g, cb << h);
        int vbl = roundToOdd(g, cbl << h);
        int vbr = roundToOdd(g, cbr << h);

        int s = vb >> 2;
        if (s >= 100) {
            int below = s / 10 * 10;
            int above = below + 10;
            boolean belowIn = vbl + open <= below << 2;
            boolean aboveIn = (above << 2) + open <= vbr;
            if (belowIn != aboveIn) {
                return layOut(belowIn ? below : above, k + dk, buf, at);
            }
        }
        int t = s + 1;
        boolean sIn = vbl + open <= s << 2;
        boolean tIn = (t << 2) + open <= vbr;
        if (sIn != tIn) {
            return layOut(sIn ? s : t, k + dk, buf, at);
        }
        int side = vb - ((s + t) << 1);
        return layOut(side < 0 || (side == 0 && (s & 1) == 0) ? s : t, k + dk, buf, at);
    }

    /**
     * {@code g × cp / 2^127}, g being {@code g1 × 2^63 + g0}, rounded to odd:
     * the integer part, with its lowest bit set when anything is left over.
     */
    private static long roundToOdd(long g1, long g0, long cp) {
        long x1 = Math.multiplyHigh(g0, cp);
        long y0 = g1 * cp;
        long y1 = Math.multiplyHigh(g1, cp);
        long z = (y0 >>> 1) + x1;
        long integer = y1 + (z >>> 63);
        return integer | ((z & LOW_63) + LOW_63) >>> 63;
    }

    /** {@code g × cp / 2^95} rounded to odd, for a float. */
    private static int roundToOdd(long g, long cp) {
        long x1 = Math.multiplyHigh(g, cp);
        long integer = x1 >>> 31;
        return (int) (integer | ((x1 & LOW_32) + LOW_32) >>> 32);
    }

    // ---- the layout ----

    /**
     * The decimal {@code f × 10^e} as {@link Double#toString(double)} lays it
     * out: plain from {@code 10^-3} up to {@code 10^7}, with a digit after the
     * point at least, and as one digit, a point, the rest, and an exponent
     * otherwise.
     */
    private static int layOut(long f, int e, byte[] buf, int at) {
        int length = length(f);
        int point = e + length; // the value is 0.ddd × 10^point
        long scaled = f * POWERS_OF_TEN[DIGITS - length];
        if (point > 0 && point <= 7) {
            int digits = digits(scaled, buf, at + 1);
            if (digits <= point) {
                for (int i = 0; i < digits; i++) {
                    buf[at + i] = buf[at + 1 + i];
                }
                for (int i = digits; i < point; i++) {
                    buf[at + i] = '0';
                }
                buf[at + point] = '.';
                buf[at + point + 1] = '0';
                return at + point + 2;
            }
            for (int i = 0; i < point; i++) {
                buf[at + i] = buf[at + 1 + i];
            }
            buf[at + point] = '.';
            return at + 1 + digits;
        }
        if (point > -3 && point <= 0) {
            buf[at] = '0';
            buf[at + 1] = '.';
            int zeros = -point;
            for (int i = 0; i < zeros; i++) {
                buf[at + 2 + i] = '0';
            }
            int start = at + 2 + zeros;
            return start + digits(scaled, buf, start);
        }
        int digits = digits(scaled, buf, at + 1);
        buf[at] = buf[at + 1];
        buf[at + 1] = '.';
        int end = at + 1 + digits;
        if (digits == 1) {
            buf[end++] = '0';
        }
        buf[end++] = 'E';
        int exponent = point - 1;
        if (exponent < 0) {
            buf[end++] = '-';
            exponent = -exponent;
        }
        if (exponent >= 100) {
            buf[end++] = (byte) ('0' + exponent / 100);
            exponent %= 100;
            buf[end++] = DIGIT_PAIRS[exponent * 2];
            buf[end++] = DIGIT_PAIRS[exponent * 2 + 1];
        } else if (exponent >= 10) {
            buf[end++] = DIGIT_PAIRS[exponent * 2];
            buf[end++] = DIGIT_PAIRS[exponent * 2 + 1];
        } else {
            buf[end++] = (byte) ('0' + exponent);
        }
        return end;
    }

    /** A whole number below {@code 2^53} as it is written: plain with {@code .0} below {@code 10^7}, with an exponent from there. */
    private static int whole(long value, byte[] buf, int at) {
        if (value >= 10_000_000) {
            return layOut(value, 0, buf, at);
        }
        int length = length(value);
        int end = at + length;
        int i = end;
        int rest = (int) value;
        while (rest >= 100) {
            int quotient = rest / 100;
            int pair = (rest - quotient * 100) * 2;
            rest = quotient;
            buf[--i] = DIGIT_PAIRS[pair + 1];
            buf[--i] = DIGIT_PAIRS[pair];
        }
        if (rest >= 10) {
            buf[--i] = DIGIT_PAIRS[rest * 2 + 1];
            buf[--i] = DIGIT_PAIRS[rest * 2];
        } else {
            buf[--i] = (byte) ('0' + rest);
        }
        buf[end] = '.';
        buf[end + 1] = '0';
        return end + 2;
    }

    private static int zero(byte[] buf, int at) {
        buf[at] = '0';
        buf[at + 1] = '.';
        buf[at + 2] = '0';
        return at + 3;
    }

    /**
     * The 17 digits of f written at {@code at}, and how many of them are left
     * once the trailing zeros are dropped. They are written as a leading digit
     * and two runs of eight, a word each, and a run of zeros at the end is not
     * written; the zeros a run ends with are counted in its word, not in the buffer.
     */
    private static int digits(long f, byte[] buf, int at) {
        long upper = f / 100_000_000; // the leading nine digits
        int lower = (int) (f - upper * 100_000_000);
        int first = (int) (upper / 100_000_000);
        int middle = (int) (upper - first * 100_000_000L);
        buf[at] = (byte) ('0' + first);
        if (lower != 0) {
            LittleEndian.putLong(buf, at + 1, eight(middle) + ZEROS);
            long digits = eight(lower);
            LittleEndian.putLong(buf, at + 9, digits + ZEROS);
            return 17 - (Long.numberOfLeadingZeros(digits) >>> 3);
        }
        if (middle != 0) {
            long digits = eight(middle);
            LittleEndian.putLong(buf, at + 1, digits + ZEROS);
            return 9 - (Long.numberOfLeadingZeros(digits) >>> 3);
        }
        return 1;
    }

    /**
     * Eight digits, leading zeros included, as the values 0 to 9 a byte each,
     * the first in the lowest byte. The number is split into halves of four
     * digits, each half into pairs, and each pair into digits, every split
     * done in all lanes of the word at once by a multiplication that divides
     * exactly over the values a lane holds: by 100 as {@code × 10486 / 2^20}
     * below 10,000, and by 10 as {@code × 103 / 2^10} below 100.
     */
    private static long eight(int value) {
        int high = value / 10_000;
        long halves = high | ((long) (value - high * 10_000) << 32);
        long hundreds = ((halves * 10_486) >>> 20) & 0x0000_007F_0000_007FL;
        long pairs = hundreds | ((halves - hundreds * 100) << 16);
        long tens = ((pairs * 103) >>> 10) & 0x000F_000F_000F_000FL;
        return tens | ((pairs - tens * 10) << 8);
    }

    /** How many digits a positive f below {@code 10^17} has. */
    private static int length(long f) {
        int length = floorLog10Pow2(Long.SIZE - Long.numberOfLeadingZeros(f));
        return f >= POWERS_OF_TEN[length] ? length + 1 : length;
    }

    // ---- logarithms by one multiplication, exact over the exponents a double has ----

    /** {@code floor(q × log10(2))}. */
    private static int floorLog10Pow2(int q) {
        return (int) ((q * 661_971_961_083L) >> 41);
    }

    /** {@code floor(log10(3/4 × 2^q))}. */
    private static int floorLog10ThreeQuartersPow2(int q) {
        return (int) ((q * 661_971_961_083L - 274_743_187_321L) >> 41);
    }

    /** {@code floor(e × log2(10))}. */
    private static int floorLog2Pow10(int e) {
        return (int) ((e * 913_124_641_741L) >> 38);
    }

    private static long[] powers() {
        long[] table = new long[(LARGEST_K - SMALLEST_K + 1) * 2];
        BigInteger ten = BigInteger.TEN;
        for (int k = SMALLEST_K; k <= LARGEST_K; k++) {
            int e = -k;
            int shift = 125 - floorLog2Pow10(e); // -r
            BigInteger g;
            if (e >= 0) {
                BigInteger power = ten.pow(e);
                g = shift >= 0 ? power.shiftLeft(shift) : power.shiftRight(-shift);
            } else {
                g = BigInteger.ONE.shiftLeft(shift).divide(ten.pow(-e));
            }
            g = g.add(BigInteger.ONE);
            int index = (k - SMALLEST_K) << 1;
            table[index] = g.shiftRight(63).longValue();
            table[index + 1] = g.longValue() & LOW_63;
        }
        return table;
    }
}
