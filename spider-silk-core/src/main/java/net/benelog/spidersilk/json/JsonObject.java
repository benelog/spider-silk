package net.benelog.spidersilk.json;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

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

    /**
     * How many members an object holds before a lookup goes through an index
     * rather than a scan of its keys. Most objects are records of a handful of
     * fields, and for those two arrays are a third of what a map costs to
     * build, and a scan finds a key as fast as a hash does.
     */
    private static final int INDEXED_FROM = 16;

    /*
     * The members in the order they were put, read directly by JsonOutput. A
     * string or a number put by a builder is held as it is, a String, a Long,
     * or a Double, and wrapped as a JsonPrimitive only when it is read: most
     * built objects are written and never read, and an object of five fields
     * then costs three allocations instead of seven. Everything else is a
     * JsonValue.
     */
    String[] keys = new String[8];
    Object[] values = new Object[8];
    int size;

    /** Each key's position, once the object has grown past {@link #INDEXED_FROM} members. */
    private @Nullable Map<String, Integer> index;

    public JsonObject() {
    }

    public JsonObject put(String key, @Nullable String value) {
        return member(key, value == null ? JsonPrimitive.NULL : value);
    }

    public JsonObject put(String key, long value) {
        return member(key, value);
    }

    /** Throws {@link JsonException} for NaN and for an infinity, which JSON has no syntax for. */
    public JsonObject put(String key, double value) {
        return member(key, Json.finite(value));
    }

    public JsonObject put(String key, boolean value) {
        return member(key, value ? JsonPrimitive.TRUE : JsonPrimitive.FALSE);
    }

    /** Replaces the value of a key that is already there, which keeps its place in the order. */
    public JsonObject put(String key, @Nullable JsonValue value) {
        return member(key, value == null ? JsonPrimitive.NULL : value);
    }

    /** Puts a JsonValue, or a String, a Long, or a Double held as it is. */
    private JsonObject member(String key, Object member) {
        Objects.requireNonNull(key, "key");
        int at = indexOf(key);
        if (at >= 0) {
            values[at] = member;
            return this;
        }
        if (size == keys.length) {
            keys = Arrays.copyOf(keys, size * 2);
            values = Arrays.copyOf(values, size * 2);
        }
        keys[size] = key;
        values[size] = member;
        if (index != null) {
            index.put(key, size);
        } else if (size + 1 == INDEXED_FROM) {
            index = new HashMap<>();
            for (int i = 0; i <= size; i++) {
                index.put(keys[i], i);
            }
        }
        size++;
        return this;
    }

    private int indexOf(String key) {
        if (index != null) {
            Integer at = index.get(key);
            return at == null ? -1 : at;
        }
        for (int i = 0; i < size; i++) {
            if (keys[i].equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private @Nullable JsonValue member(String key) {
        int at = indexOf(key);
        return at < 0 ? null : JsonPrimitive.wrap(values[at]);
    }

    public JsonObject putNull(String key) {
        return put(key, JsonPrimitive.NULL);
    }

    public boolean has(String key) {
        return indexOf(key) >= 0;
    }

    /** How many members the object has. */
    public int size() {
        return size;
    }

    /** The keys, in document order, as a list that cannot be modified. */
    public List<String> keys() {
        return List.of(Arrays.copyOf(keys, size));
    }

    /** Throws {@link JsonException} when the key is missing. */
    public JsonValue get(String key) {
        JsonValue value = member(key);
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
        JsonValue value = member(key);
        return (value == null || value.isNull()) ? defaultValue : value.asString();
    }

    /** An optional number: the default when the key is missing or the value is JSON null. */
    public long getLong(String key, long defaultValue) {
        JsonValue value = member(key);
        return (value == null || value.isNull()) ? defaultValue : value.asLong();
    }

    /** An optional number: the default when the key is missing or the value is JSON null. */
    public double getDouble(String key, double defaultValue) {
        JsonValue value = member(key);
        return (value == null || value.isNull()) ? defaultValue : value.asDouble();
    }

    /** An optional boolean: the default when the key is missing or the value is JSON null. */
    public boolean getBoolean(String key, boolean defaultValue) {
        JsonValue value = member(key);
        return (value == null || value.isNull()) ? defaultValue : value.asBoolean();
    }

    /**
     * An optional object: null when the key is missing or the value is JSON
     * null, and a {@link JsonException} when it is present and not an object.
     * The same shape as {@code req.paramOrNull(name)}.
     */
    public @Nullable JsonObject getObjectOrNull(String key) {
        JsonValue value = member(key);
        return (value == null || value.isNull()) ? null : value.asObject();
    }

    /**
     * An optional array: null when the key is missing or the value is JSON
     * null, and a {@link JsonException} when it is present and not an array.
     */
    public @Nullable JsonArray getArrayOrNull(String key) {
        JsonValue value = member(key);
        return (value == null || value.isNull()) ? null : value.asArray();
    }

    /** Members in document order, read-only: setValue throws rather than reaching the object. */
    @Override
    public Iterator<Map.Entry<String, JsonValue>> iterator() {
        return new Iterator<>() {
            private int next;

            @Override
            public boolean hasNext() {
                return next < size;
            }

            @Override
            public Map.Entry<String, JsonValue> next() {
                if (next >= size) {
                    throw new NoSuchElementException();
                }
                Map.Entry<String, JsonValue> entry = Map.entry(keys[next], JsonPrimitive.wrap(values[next]));
                next++;
                return entry;
            }
        };
    }
}
