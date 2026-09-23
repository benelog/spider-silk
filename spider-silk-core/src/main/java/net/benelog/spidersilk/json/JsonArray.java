package net.benelog.spidersilk.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.jspecify.annotations.Nullable;

/** A JSON array. Iterable, so a parsed array reads with for-each: {@code for (JsonValue v : array)}. */
public final class JsonArray implements JsonValue, Iterable<JsonValue> {

    private final List<JsonValue> values = new ArrayList<>();

    public JsonArray add(@Nullable String value) {
        return add(value == null ? JsonPrimitive.NULL : new JsonPrimitive(value));
    }

    public JsonArray add(long value) {
        return add(new JsonPrimitive(value));
    }

    /** Throws {@link JsonException} for NaN and for an infinity, which JSON has no syntax for. */
    public JsonArray add(double value) {
        return add(new JsonPrimitive(Json.finite(value)));
    }

    public JsonArray add(boolean value) {
        return add(value ? JsonPrimitive.TRUE : JsonPrimitive.FALSE);
    }

    public JsonArray add(@Nullable JsonValue value) {
        values.add(value == null ? JsonPrimitive.NULL : value);
        return this;
    }

    public JsonArray addAll(Iterable<String> strings) {
        for (String s : strings) {
            add(s);
        }
        return this;
    }

    public int size() {
        return values.size();
    }

    public JsonValue get(int index) {
        return values.get(index);
    }

    public List<JsonValue> values() {
        return List.copyOf(values);
    }

    /** Elements in order, read-only: remove throws rather than reaching the array. */
    @Override
    public Iterator<JsonValue> iterator() {
        return Collections.unmodifiableList(values).iterator();
    }

    @Override
    public void write(StringBuilder sb) {
        sb.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            values.get(i).write(sb);
        }
        sb.append(']');
    }
}
