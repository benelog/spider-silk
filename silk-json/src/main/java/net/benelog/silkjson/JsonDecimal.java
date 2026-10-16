package net.benelog.silkjson;

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
        return exactLong(text);
    }

    /** {@link #exactLong()} over a number token's text, which a pull parser holds without the double. */
    static long exactLong(String text) {
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
            throw notAnInteger(text);
        }

        if (exponentAt >= 0) {
            String exponent = text.substring(exponentAt + 1);
            boolean negativeExponent = exponent.startsWith("-");
            String magnitude = exponent.replaceFirst("^[+-]", "").replaceFirst("^0+(?=.)", "");
            if (magnitude.length() > 18) {
                // A nonzero mantissa this far from the point is a fraction
                // below any long, or a whole number beyond one.
                throw notAnInteger(text);
            }
            long shift = Long.parseLong(magnitude);
            scale += negativeExponent ? shift : -shift;
        }
        // The last digit is nonzero, so a positive scale leaves a fraction, and
        // nineteen or more places to the left make it 10^19 or more.
        if (scale > 0 || scale < -18) {
            throw notAnInteger(text);
        }
        BigInteger unscaled = new BigInteger(digits.substring(start, end));
        return new BigDecimal(negative ? unscaled.negate() : unscaled, (int) scale).longValueExact();
    }

    /**
     * Whether two numbers a tree holds, a Long, a Double, or a parsed decimal,
     * are the same number as written: {@code 1}, {@code 1.0}, and {@code 1e0}
     * are one number, and a double is the decimal {@link Double#toString}
     * writes for it, so a built {@code 0.1} is a parsed {@code 0.1}.
     *
     * <p>Two numbers that are one decimal round to one double, so numbers
     * whose doubles differ are told apart with no other work, and the rest
     * are compared by their significant digits and exponent, worked out in
     * one pass over the text with no {@link BigDecimal}, whose cost grows
     * with the square of the digits.
     */
    static boolean same(Number a, Number b) {
        if (a instanceof Long x && b instanceof Long y) {
            return x.longValue() == y.longValue();
        }
        if (a.doubleValue() != b.doubleValue()) {
            return false;
        }
        if (a instanceof Double && b instanceof Double) {
            return true;
        }
        return Canonical.of(text(a)).equals(Canonical.of(text(b)));
    }

    /** A hash that agrees with {@link #same}: the double the number rounds to, either zero hashed as one. */
    static int hash(Number number) {
        double value = number.doubleValue();
        return value == 0 ? 0 : Double.hashCode(value);
    }

    private static String text(Number number) {
        return number instanceof Double d ? Double.toString(d) : number.toString();
    }

    /** A number as its sign, its significant digits with no zero at either end, and the power of ten they are scaled by; zero is one form whatever its sign. */
    private record Canonical(boolean negative, String digits, BigInteger exponent) {

        private static final Canonical ZERO = new Canonical(false, "", BigInteger.ZERO);

        /** From a number's text in JSON's grammar, or as {@link Long#toString} or {@link Double#toString} writes it. */
        static Canonical of(String text) {
            int exponentAt = Math.max(text.indexOf('e'), text.indexOf('E'));
            int mantissaEnd = exponentAt < 0 ? text.length() : exponentAt;
            boolean negative = text.startsWith("-");
            StringBuilder digits = new StringBuilder(mantissaEnd);
            long fractionDigits = 0;
            boolean fraction = false;
            for (int i = negative ? 1 : 0; i < mantissaEnd; i++) {
                char c = text.charAt(i);
                if (c == '.') {
                    fraction = true;
                } else {
                    if (c != '0' || digits.length() > 0) {
                        digits.append(c);
                    }
                    if (fraction) {
                        fractionDigits++;
                    }
                }
            }
            int end = digits.length();
            while (end > 0 && digits.charAt(end - 1) == '0') {
                end--;
            }
            if (end == 0) {
                return ZERO;
            }
            long trailingZeros = (long) digits.length() - end;
            BigInteger exponent = exponentAt < 0 ? BigInteger.ZERO
                    : new BigInteger(text.substring(exponentAt + 1));
            exponent = exponent.add(BigInteger.valueOf(trailingZeros - fractionDigits));
            return new Canonical(negative, digits.substring(0, end), exponent);
        }
    }

    private static ArithmeticException notAnInteger(String text) {
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
