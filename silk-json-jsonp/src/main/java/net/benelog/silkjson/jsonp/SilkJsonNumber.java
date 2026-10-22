package net.benelog.silkjson.jsonp;

import java.math.BigDecimal;
import java.math.BigInteger;

import jakarta.json.JsonNumber;

import org.jspecify.annotations.Nullable;

/**
 * A JSON number, with the {@link BigDecimal} semantics JSON-P gives one: two
 * numbers are equal when their decimals are, scale included, so {@code 1} and
 * {@code 1.0} are two numbers, and {@link #toString()} is the decimal's.
 *
 * <p>A whole number a {@code long} holds, which is what a document's ids and
 * counts are, is kept as a {@code long} and makes no decimal until one is
 * asked for. Any other number is kept as the text it was parsed from or as the
 * decimal it was built from, and the decimal is made from the text when it is
 * first wanted: a document's {@code 2.5} read with {@link #doubleValue()}
 * never makes one.
 */
abstract sealed class SilkJsonNumber implements JsonNumber permits SilkJsonNumber.Whole, SilkJsonNumber.Decimal {

    static JsonNumber of(long value) {
        return new Whole(value);
    }

    static JsonNumber of(BigDecimal value) {
        return new Decimal(value, null);
    }

    static JsonNumber of(BigInteger value) {
        return new Decimal(new BigDecimal(value), null);
    }

    /** The decimal {@link BigDecimal#valueOf(double)} makes, as JSON-P has it; NaN and the infinities throw {@link NumberFormatException}. */
    static JsonNumber of(double value) {
        return new Decimal(BigDecimal.valueOf(finite(value)), null);
    }

    /**
     * Any {@link Number}: a whole number of a primitive type as a long, a
     * {@code Double} as {@link #of(double)} takes it, and any other number,
     * a {@code Float} included, as the decimal its {@code toString()} writes,
     * so that {@code 0.1f} is {@code 0.1}.
     */
    static JsonNumber of(Number number) {
        return switch (number) {
            case Integer i -> of(i.longValue());
            case Long l -> of(l.longValue());
            case Short s -> of(s.longValue());
            case Byte b -> of(b.longValue());
            case Double d -> of(d.doubleValue());
            case BigInteger i -> of(i);
            case BigDecimal d -> of(d);
            default -> of(new BigDecimal(number.toString()));
        };
    }

    /** A number as {@code JsonInput.readNumber()} answers it: text the grammar has already checked. */
    static JsonNumber parsed(String text) {
        if (isWhole(text) && text.length() - (text.charAt(0) == '-' ? 1 : 0) <= 18) {
            return new Whole(Long.parseLong(text));
        }
        return new Decimal(null, text);
    }

    /** What JSON-P's {@code write(double)} and {@code add(double)} ask of a double. */
    static double finite(double value) {
        if (!Double.isFinite(value)) {
            throw new NumberFormatException("Not a JSON number: " + value);
        }
        return value;
    }

    /** Whether a number's text has neither a fraction nor an exponent. */
    static boolean isWhole(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.' || c == 'e' || c == 'E') {
                return false;
            }
        }
        return true;
    }

    @Override
    public ValueType getValueType() {
        return ValueType.NUMBER;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (this instanceof Whole whole && other instanceof Whole that) {
            return whole.value == that.value;
        }
        return other instanceof JsonNumber number && bigDecimalValue().equals(number.bigDecimalValue());
    }

    @Override
    public int hashCode() {
        return bigDecimalValue().hashCode();
    }

    /** A whole number that a {@code long} holds. */
    static final class Whole extends SilkJsonNumber {

        final long value;

        Whole(long value) {
            this.value = value;
        }

        @Override
        public boolean isIntegral() {
            return true;
        }

        @Override
        public int intValue() {
            return (int) value;
        }

        @Override
        public int intValueExact() {
            return Math.toIntExact(value);
        }

        @Override
        public long longValue() {
            return value;
        }

        @Override
        public long longValueExact() {
            return value;
        }

        @Override
        public BigInteger bigIntegerValue() {
            return BigInteger.valueOf(value);
        }

        @Override
        public BigInteger bigIntegerValueExact() {
            return BigInteger.valueOf(value);
        }

        @Override
        public double doubleValue() {
            return value;
        }

        @Override
        public BigDecimal bigDecimalValue() {
            return BigDecimal.valueOf(value);
        }

        @Override
        public Number numberValue() {
            return value;
        }

        @Override
        public String toString() {
            return Long.toString(value);
        }
    }

    /** Any other number: a decimal, or a whole number past what a long holds, as its text or its decimal. */
    static final class Decimal extends SilkJsonNumber {

        private final @Nullable String text;
        private @Nullable BigDecimal decimal;

        Decimal(@Nullable BigDecimal decimal, @Nullable String text) {
            this.decimal = decimal;
            this.text = text;
        }

        @Override
        public BigDecimal bigDecimalValue() {
            BigDecimal value = decimal;
            if (value == null) {
                // Two threads that race here make two equal decimals, and either will do.
                value = new BigDecimal(text());
                decimal = value;
            }
            return value;
        }

        private String text() {
            String value = text;
            if (value == null) {
                throw new IllegalStateException("A decimal number holds its text or its BigDecimal");
            }
            return value;
        }

        @Override
        public boolean isIntegral() {
            return bigDecimalValue().scale() == 0;
        }

        @Override
        public int intValue() {
            return bigDecimalValue().intValue();
        }

        @Override
        public int intValueExact() {
            return bigDecimalValue().intValueExact();
        }

        @Override
        public long longValue() {
            return bigDecimalValue().longValue();
        }

        @Override
        public long longValueExact() {
            return bigDecimalValue().longValueExact();
        }

        @Override
        public BigInteger bigIntegerValue() {
            return bigDecimalValue().toBigInteger();
        }

        @Override
        public BigInteger bigIntegerValueExact() {
            return bigDecimalValue().toBigIntegerExact();
        }

        /** The nearest double, read from the text where there is one: the double the decimal would round to, with no decimal made. */
        @Override
        public double doubleValue() {
            BigDecimal value = decimal;
            return value != null ? value.doubleValue() : Double.parseDouble(text());
        }

        @Override
        public Number numberValue() {
            return bigDecimalValue();
        }

        @Override
        public String toString() {
            return bigDecimalValue().toString();
        }
    }
}
