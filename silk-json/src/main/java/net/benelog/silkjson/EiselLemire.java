package net.benelog.silkjson;

import java.math.BigInteger;

/**
 * The double nearest a decimal {@code w × 10^q}, for a significand of up to
 * 19 digits, by the algorithm of Daniel Lemire, "Number Parsing at a Gigabyte
 * per Second" (2021), after Michael Eisel. The significand, shifted so that
 * its top bit is set, is multiplied by a 128-bit truncation of {@code 5^q},
 * and the top 55 bits of the product are the double's significand with a bit
 * to round on, while the binary exponent of {@code 10^q} follows from
 * {@code q} by one multiplication.
 *
 * <p>The truncation can leave the rounding undecided, in a case the paper
 * shows to be rare; the answer is then NaN, and the caller reads the text the
 * long way. The table is computed with {@link BigInteger} when the class is
 * first used, so a document whose numbers {@link JsonInput} converts with one
 * multiplication or division never builds it.
 */
final class EiselLemire {

    /** Below this power, even {@code (2^64 - 1) × 10^q} is closer to zero than to the smallest double. */
    private static final int SMALLEST_POWER = -342;

    /** Above this power, even {@code 1 × 10^q} is past the largest double. */
    private static final int LARGEST_POWER = 308;

    /** The bits of a double's significand, the leading one left out. */
    private static final int SIGNIFICAND_BITS = 52;

    /** The exponent of a double's smallest normal value, less one, which the biased exponent counts from. */
    private static final int EXPONENT_BIAS = 1023;

    /** The biased exponent of infinity. */
    private static final int INFINITE_EXPONENT = 0x7FF;

    /**
     * How many low bits of the product's high word are below the 55 the
     * double needs: the leading one, the 52 stored, a bit to round on, and a
     * bit lost when the product's top bit is clear.
     */
    private static final int SPARE_BITS = 64 - SIGNIFICAND_BITS - 3;
    private static final long SPARE_MASK = (1L << SPARE_BITS) - 1;

    /**
     * {@code 5^q} for each power from the smallest to the largest, as two
     * words with the top bit of the first set: the high word, then the low.
     */
    private static final long[] POWERS_OF_FIVE = powersOfFive();

    private EiselLemire() {
    }

    /**
     * The double nearest {@code w × 10^q}, w read as unsigned and not zero:
     * zero below the smallest double, infinity past the largest, and NaN when
     * the truncated power leaves the rounding undecided.
     */
    static double nearest(long w, int q) {
        if (q < SMALLEST_POWER) {
            return 0.0;
        }
        if (q > LARGEST_POWER) {
            return Double.POSITIVE_INFINITY;
        }
        int leadingZeros = Long.numberOfLeadingZeros(w);
        long significand = w << leadingZeros;
        int index = (q - SMALLEST_POWER) << 1;
        long powerHigh = POWERS_OF_FIVE[index];
        long high = Math.unsignedMultiplyHigh(significand, powerHigh);
        long low = significand * powerHigh;
        if ((high & SPARE_MASK) == SPARE_MASK) {
            // The bits below the 55 kept are all ones, so a carry out of the
            // product with the power's low word could change them.
            long carry = Math.unsignedMultiplyHigh(significand, POWERS_OF_FIVE[index + 1]);
            long sum = low + carry;
            if (Long.compareUnsigned(sum, low) < 0) {
                high++;
            }
            low = sum;
            // Outside these powers the 128 bits are a truncation of 5^q, and
            // a product this close to a carry may be on either side of it.
            if (low == -1L && (q < -27 || q > 55)) {
                return Double.NaN;
            }
        }
        int upperBit = (int) (high >>> 63);
        int shift = upperBit + SPARE_BITS;
        long mantissa = high >>> shift;
        // (217706 * q) >> 16 is floor(q * log2(10)) for every power in the table.
        int exponent = ((217706 * q) >> 16) + 63 + upperBit - leadingZeros + EXPONENT_BIAS;
        if (exponent <= 0) {
            // A subnormal: the bits below the smallest exponent are shifted out
            // before rounding, and a value that rounds up to the smallest
            // normal one carries into the exponent on its own.
            int subnormalShift = 1 - exponent;
            if (subnormalShift >= 64) {
                return 0.0;
            }
            mantissa >>>= subnormalShift;
            mantissa += mantissa & 1;
            mantissa >>>= 1;
            return Double.longBitsToDouble(mantissa);
        }
        // A product with nothing below the bit to round on is exactly halfway,
        // which rounds to even instead of up. Only these powers have a 5^q a
        // significand of 19 digits can meet that exactly.
        if (Long.compareUnsigned(low, 1) <= 0 && q >= -4 && q <= 23 && (mantissa & 3) == 1
                && (mantissa << shift) == high) {
            mantissa &= ~1L;
        }
        mantissa += mantissa & 1;
        mantissa >>>= 1;
        if (mantissa >= (2L << SIGNIFICAND_BITS)) {
            mantissa = 1L << SIGNIFICAND_BITS; // rounded up to the next power of two
            exponent++;
        }
        if (exponent >= INFINITE_EXPONENT) {
            return Double.POSITIVE_INFINITY;
        }
        return Double.longBitsToDouble(((long) exponent << SIGNIFICAND_BITS) | (mantissa & ~(1L << SIGNIFICAND_BITS)));
    }

    /**
     * The table, as the paper defines it. A non-negative power is {@code 5^q}
     * shifted to 128 bits, and a negative one is the reciprocal
     * {@code 2^b / 5^-q} plus one, also cut to 128 bits: exact for powers down
     * to -27, whose reciprocal fits, and computed to twice the bits below that.
     */
    private static long[] powersOfFive() {
        long[] table = new long[(LARGEST_POWER - SMALLEST_POWER + 1) * 2];
        BigInteger five = BigInteger.valueOf(5);
        BigInteger power = BigInteger.ONE;
        for (int q = 0; q <= LARGEST_POWER; q++) {
            put(table, q, power.bitLength() > 128 ? power.shiftRight(power.bitLength() - 128)
                    : power.shiftLeft(128 - power.bitLength()));
            power = power.multiply(five);
        }
        power = five;
        for (int q = -1; q >= SMALLEST_POWER; q--) {
            int bits = power.bitLength(); // 5^-q is not a power of two, so it lies between 2^(bits - 1) and 2^bits
            int b = q >= -27 ? bits + 127 : 2 * bits + 128;
            BigInteger reciprocal = BigInteger.ONE.shiftLeft(b).divide(power).add(BigInteger.ONE);
            put(table, q, reciprocal.shiftRight(reciprocal.bitLength() - 128));
            power = power.multiply(five);
        }
        return table;
    }

    private static void put(long[] table, int q, BigInteger value) {
        int index = (q - SMALLEST_POWER) << 1;
        table[index] = value.shiftRight(64).longValue();
        table[index + 1] = value.longValue();
    }
}
