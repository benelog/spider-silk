package net.benelog.spidersilk.json;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * A parsed number written with a fraction or an exponent. It reads as the
 * nearest {@code double}, which is what {@link JsonValue#asDouble()} promises,
 * and keeps the text it was parsed from, because the double has already lost
 * the digits that decide whether the number is a {@code long}:
 * {@code 9007199254740993.0} rounds to an even double, and
 * {@code 1.0000000000000001} rounds to a whole one.
 *
 * <p>It is written back as that text too, so a parsed tree passed through
 * unchanged sends the number it received: the text passed RFC 8259's grammar
 * in the parser and is finite, so it is always a valid JSON number.
 */
final class JsonDecimal extends Number {

    private static final long serialVersionUID = 1L;

    private final double value;
    private final String text;

    JsonDecimal(double value, String text) {
        this.value = value;
        this.text = text;
    }

    String text() {
        return text;
    }

    /**
     * The number as a {@code long}, converted from the text rather than from
     * the double. Throws {@link ArithmeticException} when the text has a
     * fractional part or lies outside the range of a {@code long}.
     *
     * <p>The answer is worked out from the significant digits before anything
     * is converted. A {@code long} has at most 19 of them, so a mantissa with
     * more is refused without a {@link BigDecimal}, whose cost grows with the
     * square of the digits: a million-digit fraction in a body would otherwise
     * hold the thread for seconds. What is converted is then at most 19 digits,
     * whatever zeros surrounded them in the text.
     */
    long exactLong() {
        int exponentAt = Math.max(text.indexOf('e'), text.indexOf('E'));
        String mantissa = exponentAt < 0 ? text : text.substring(0, exponentAt);
        boolean negative = mantissa.startsWith("-");
        int point = mantissa.indexOf('.');
        String digits = point < 0
                ? mantissa.substring(negative ? 1 : 0)
                : mantissa.substring(negative ? 1 : 0, point) + mantissa.substring(point + 1);
        long scale = point < 0 ? 0 : mantissa.length() - point - 1;

        int end = digits.length();
        while (end > 0 && digits.charAt(end - 1) == '0') {
            end--;
            scale--;
        }
        int start = 0;
        while (start < end && digits.charAt(start) == '0') {
            start++;
        }
        if (start == end) {
            return 0L;
        }
        if (end - start > 19) {
            throw notAnInteger();
        }

        if (exponentAt >= 0) {
            String exponent = text.substring(exponentAt + 1);
            boolean negativeExponent = exponent.startsWith("-");
            String magnitude = exponent.replaceFirst("^[+-]", "").replaceFirst("^0+(?=.)", "");
            if (magnitude.length() > 18) {
                // A nonzero mantissa this far from the point is a fraction
                // below any long, or a whole number beyond one.
                throw notAnInteger();
            }
            long shift = Long.parseLong(magnitude);
            scale += negativeExponent ? shift : -shift;
        }
        // The last digit is nonzero, so a positive scale leaves a fraction, and
        // nineteen or more places to the left make it 10^19 or more.
        if (scale > 0 || scale < -18) {
            throw notAnInteger();
        }
        BigInteger unscaled = new BigInteger(digits.substring(start, end));
        return new BigDecimal(negative ? unscaled.negate() : unscaled, (int) scale).longValueExact();
    }

    private ArithmeticException notAnInteger() {
        return new ArithmeticException("Not an integer: " + text);
    }

    @Override
    public int intValue() {
        return (int) value;
    }

    @Override
    public long longValue() {
        return (long) value;
    }

    @Override
    public float floatValue() {
        return (float) value;
    }

    @Override
    public double doubleValue() {
        return value;
    }

    /**
     * Serialized as the text it was parsed from. The double it reads as wrote
     * {@code 9007199254740993.0} back as {@code 9.007199254740992E15}, a number
     * {@link JsonValue#asLong()} then read as a different long.
     */
    @Override
    public String toString() {
        return text;
    }
}
