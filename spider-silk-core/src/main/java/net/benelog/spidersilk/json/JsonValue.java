package net.benelog.spidersilk.json;

/**
 * A parsed or built JSON value: an object, an array, or a primitive.
 *
 * <p>The {@code as*} accessors read the value as one kind and throw
 * {@link JsonException} when it is another, so a {@link JsonReader} that reads
 * the wrong shape is a 400 rather than a half-built object.
 */
public sealed interface JsonValue permits JsonObject, JsonArray, JsonPrimitive {

    void write(StringBuilder sb);

    default String toJson() {
        StringBuilder sb = new StringBuilder();
        write(sb);
        return sb.toString();
    }

    default JsonObject asObject() {
        if (this instanceof JsonObject object) {
            return object;
        }
        throw new JsonException("Not a JSON object: " + toJson());
    }

    default JsonArray asArray() {
        if (this instanceof JsonArray array) {
            return array;
        }
        throw new JsonException("Not a JSON array: " + toJson());
    }

    default String asString() {
        if (this instanceof JsonPrimitive primitive && primitive.value() instanceof String s) {
            return s;
        }
        throw new JsonException("Not a JSON string: " + toJson());
    }

    /**
     * The value as a {@code long}. A number with a fractional part is not
     * one, and is rejected rather than truncated: {@code 1.5} is not 1.
     * {@code 1e3} and {@code 2.0} are whole and read as 1000 and 2.
     * A parsed number is converted from its text, not from the nearest
     * double, so {@code 9007199254740993.0} reads as exactly that and
     * {@code 1.0000000000000001} is rejected as a fraction.
     */
    default long asLong() {
        if (this instanceof JsonPrimitive primitive && primitive.value() instanceof Number n) {
            if (n instanceof Long whole) {
                return whole;
            }
            if (n instanceof JsonDecimal decimal) {
                try {
                    return decimal.exactLong();
                } catch (ArithmeticException e) {
                    throw new JsonException("Not a JSON integer: " + decimal.text());
                }
            }
            double d = n.doubleValue();
            if (d == Math.rint(d) && d >= -0x1p63 && d < 0x1p63) {
                return (long) d;
            }
            throw new JsonException("Not a JSON integer: " + toJson());
        }
        throw new JsonException("Not a JSON number: " + toJson());
    }

    default double asDouble() {
        if (this instanceof JsonPrimitive primitive && primitive.value() instanceof Number n) {
            return n.doubleValue();
        }
        throw new JsonException("Not a JSON number: " + toJson());
    }

    default boolean asBoolean() {
        if (this instanceof JsonPrimitive primitive && primitive.value() instanceof Boolean b) {
            return b;
        }
        throw new JsonException("Not a JSON boolean: " + toJson());
    }

    default boolean isNull() {
        return this instanceof JsonPrimitive primitive && primitive.value() == null;
    }

    /** True for a JSON string, so a value of either type is told apart before it is read. */
    default boolean isString() {
        return this instanceof JsonPrimitive primitive && primitive.value() instanceof String;
    }

    /** True for a JSON number, whether it is whole or has a fractional part. */
    default boolean isNumber() {
        return this instanceof JsonPrimitive primitive && primitive.value() instanceof Number;
    }

    /** True for {@code true} and for {@code false}. */
    default boolean isBoolean() {
        return this instanceof JsonPrimitive primitive && primitive.value() instanceof Boolean;
    }
}
