package net.benelog.silkjson;

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

    /**
     * A member or an element as a JsonValue: itself when it is one, and a
     * primitive around it when it is a String, a Long, or a Double that a
     * builder put in as it is.
     */
    static JsonValue wrap(Object held) {
        return held instanceof JsonValue value ? value : new JsonPrimitive(held);
    }
}
