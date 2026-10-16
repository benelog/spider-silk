package net.benelog.silkjson;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/** A string, number, boolean, or null. */
public final class JsonPrimitive implements JsonValue {

    static final JsonPrimitive NULL = new JsonPrimitive(null);
    static final JsonPrimitive TRUE = new JsonPrimitive(true);
    static final JsonPrimitive FALSE = new JsonPrimitive(false);

    private final @Nullable Object value; // String | Long | Double | JsonDecimal | Boolean | null

    JsonPrimitive(@Nullable Object value) {
        this.value = value;
    }

    @Nullable Object value() {
        return value;
    }

    /** The same value: a string, a boolean, or null equal to itself, and a number to the same number however it is written, {@code 1} to {@code 1.0}. */
    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof JsonPrimitive primitive && same(value, primitive.value));
    }

    @Override
    public int hashCode() {
        return hash(value);
    }

    /** The value as JSON text: {@link #toJson()}. */
    @Override
    public String toString() {
        return toJson();
    }

    /**
     * Whether two members or elements as an object or an array holds them,
     * a JsonValue or a String, a Long, or a Double held as it is, are the
     * same value.
     */
    static boolean same(@Nullable Object a, @Nullable Object b) {
        Object x = a instanceof JsonPrimitive primitive ? primitive.value : a;
        Object y = b instanceof JsonPrimitive primitive ? primitive.value : b;
        if (x instanceof Number m && y instanceof Number n) {
            return JsonDecimal.same(m, n);
        }
        return Objects.equals(x, y);
    }

    /** A hash of a member or an element as it is held, which agrees with {@link #same}. */
    static int hash(@Nullable Object held) {
        Object value = held instanceof JsonPrimitive primitive ? primitive.value : held;
        if (value instanceof Number number) {
            return JsonDecimal.hash(number);
        }
        return Objects.hashCode(value);
    }

    /**
     * A member or an element as a JsonValue: itself when it is one, and a
     * primitive around it when it is a String, a Long, or a Double that a
     * builder put in as it is.
     */
    static JsonValue wrap(Object held) {
        return held instanceof JsonValue value ? value : new JsonPrimitive(held);
    }
}
