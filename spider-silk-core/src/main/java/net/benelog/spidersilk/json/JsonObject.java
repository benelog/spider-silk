package net.benelog.spidersilk.json;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * A JSON object. Iterable, so an object whose keys are not known in advance reads with
 * for-each: {@code for (var member : object)}, each member a
 * {@link Map.Entry} of the key and its value.
 *
 * <p>Members keep the order they were put in, and a parsed object keeps
 * document order.
 */
public final class JsonObject implements JsonValue, Iterable<Map.Entry<String, JsonValue>> {

    private final Map<String, JsonValue> members = new LinkedHashMap<>();

    public JsonObject put(String key, @Nullable String value) {
        return put(key, value == null ? JsonPrimitive.NULL : new JsonPrimitive(value));
    }

    public JsonObject put(String key, long value) {
        return put(key, new JsonPrimitive(value));
    }

    /** Throws {@link JsonException} for NaN and for an infinity, which JSON has no syntax for. */
    public JsonObject put(String key, double value) {
        return put(key, new JsonPrimitive(Json.finite(value)));
    }

    public JsonObject put(String key, boolean value) {
        return put(key, value ? JsonPrimitive.TRUE : JsonPrimitive.FALSE);
    }

    public JsonObject put(String key, @Nullable JsonValue value) {
        members.put(key, value == null ? JsonPrimitive.NULL : value);
        return this;
    }

    public JsonObject putNull(String key) {
        return put(key, JsonPrimitive.NULL);
    }

    public boolean has(String key) {
        return members.containsKey(key);
    }

    /** How many members the object has. */
    public int size() {
        return members.size();
    }

    /** The keys, in document order, as a list that cannot be modified. */
    public List<String> keys() {
        return List.copyOf(members.keySet());
    }

    /** Throws {@link JsonException} when the key is missing. */
    public JsonValue get(String key) {
        JsonValue value = members.get(key);
        if (value == null) {
            throw new JsonException("Missing key in JSON object: " + key);
        }
        return value;
    }

    public String getString(String key) {
        return get(key).asString();
    }

    public long getLong(String key) {
        return get(key).asLong();
    }

    public double getDouble(String key) {
        return get(key).asDouble();
    }

    public boolean getBoolean(String key) {
        return get(key).asBoolean();
    }

    public JsonObject getObject(String key) {
        return get(key).asObject();
    }

    public JsonArray getArray(String key) {
        return get(key).asArray();
    }

    /**
     * An optional string: the default when the key is missing or the value is
     * JSON null, and a {@link JsonException} when it is present and not a string.
     * The same shape as {@code req.param(name, defaultValue)}.
     */
    public String getString(String key, String defaultValue) {
        JsonValue value = members.get(key);
        return (value == null || value.isNull()) ? defaultValue : value.asString();
    }

    /** An optional number: the default when the key is missing or the value is JSON null. */
    public long getLong(String key, long defaultValue) {
        JsonValue value = members.get(key);
        return (value == null || value.isNull()) ? defaultValue : value.asLong();
    }

    /** An optional number: the default when the key is missing or the value is JSON null. */
    public double getDouble(String key, double defaultValue) {
        JsonValue value = members.get(key);
        return (value == null || value.isNull()) ? defaultValue : value.asDouble();
    }

    /** An optional boolean: the default when the key is missing or the value is JSON null. */
    public boolean getBoolean(String key, boolean defaultValue) {
        JsonValue value = members.get(key);
        return (value == null || value.isNull()) ? defaultValue : value.asBoolean();
    }

    /**
     * An optional object: null when the key is missing or the value is JSON
     * null, and a {@link JsonException} when it is present and not an object.
     * The same shape as {@code req.paramOrNull(name)}.
     */
    public @Nullable JsonObject getObjectOrNull(String key) {
        JsonValue value = members.get(key);
        return (value == null || value.isNull()) ? null : value.asObject();
    }

    /**
     * An optional array: null when the key is missing or the value is JSON
     * null, and a {@link JsonException} when it is present and not an array.
     */
    public @Nullable JsonArray getArrayOrNull(String key) {
        JsonValue value = members.get(key);
        return (value == null || value.isNull()) ? null : value.asArray();
    }

    /** Members in document order, read-only: setValue throws rather than reaching the object. */
    @Override
    public Iterator<Map.Entry<String, JsonValue>> iterator() {
        return Collections.unmodifiableMap(members).entrySet().iterator();
    }

    @Override
    public void write(StringBuilder sb) {
        sb.append('{');
        boolean first = true;
        for (var entry : members.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            Json.writeString(sb, entry.getKey());
            sb.append(':');
            entry.getValue().write(sb);
        }
        sb.append('}');
    }
}
