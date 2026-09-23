package net.benelog.spidersilk.json;

import org.jspecify.annotations.Nullable;

/** A string, number, boolean, or null. */
public final class JsonPrimitive implements JsonValue {

    static final JsonPrimitive NULL = new JsonPrimitive(null);
    static final JsonPrimitive TRUE = new JsonPrimitive(true);
    static final JsonPrimitive FALSE = new JsonPrimitive(false);

    private final @Nullable Object value;   // String | Long | Double | Boolean | null

    JsonPrimitive(@Nullable Object value) {
        this.value = value;
    }

    @Nullable Object value() {
        return value;
    }

    @Override
    public void write(StringBuilder sb) {
        if (value instanceof String s) {
            Json.writeString(sb, s);
        } else {
            sb.append(value);   // null, true/false, numbers
        }
    }
}
