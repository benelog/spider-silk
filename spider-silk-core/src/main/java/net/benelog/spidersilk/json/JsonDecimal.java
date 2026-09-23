package net.benelog.spidersilk.json;

import java.math.BigDecimal;

/**
 * A parsed number written with a fraction or an exponent. It reads as the
 * nearest {@code double}, which is what {@link JsonValue#asDouble()} promises,
 * and keeps the text it was parsed from, because the double has already lost
 * the digits that decide whether the number is a {@code long}:
 * {@code 9007199254740993.0} rounds to an even double, and
 * {@code 1.0000000000000001} rounds to a whole one.
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
     */
    long exactLong() {
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(text);
        } catch (NumberFormatException e) {
            // Only an exponent past the range of an int reaches here. A large
            // one on a nonzero mantissa was already rejected as out of range,
            // so the number is either zero or a fraction too small to write.
            if (value == 0.0 && zeroMantissa()) {
                return 0L;
            }
            throw new ArithmeticException("Not an integer: " + text);
        }
        // longValueExact rejects a fraction and an overflow before it scales,
        // so an exponent such as 1e-999999999 costs nothing to refuse.
        return decimal.longValueExact();
    }

    private boolean zeroMantissa() {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 'e' || c == 'E') {
                return true;
            }
            if (c >= '1' && c <= '9') {
                return false;
            }
        }
        return true;
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

    /** Serialized as the double it reads as, the way a parsed decimal always was. */
    @Override
    public String toString() {
        return Double.toString(value);
    }
}
