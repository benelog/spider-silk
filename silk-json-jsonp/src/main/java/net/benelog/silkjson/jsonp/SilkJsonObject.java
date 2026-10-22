package net.benelog.silkjson.jsonp;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.BiConsumer;

import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.jspecify.annotations.Nullable;

/**
 * An immutable JSON object: a {@link Map} in the order its members were put
 * or parsed, which refuses every change.
 *
 * <p>The members are held as two arrays, the keys and the values, and a key
 * is found by a scan of the keys until the object has
 * {@link Members#INDEXED_FROM} of them, from where an index of each key's
 * place comes with it. Most objects are records of a handful of fields, for
 * which two arrays cost a fraction of a map and a scan finds a key as fast.
 */
final class SilkJsonObject extends AbstractMap<String, JsonValue> implements JsonObject {

    private final String[] keys;
    private final JsonValue[] values;
    private final int size;
    private final @Nullable Map<String, Integer> index;

    /** Takes the arrays as they are, which nothing else writes to again. */
    SilkJsonObject(String[] keys, JsonValue[] values, int size, @Nullable Map<String, Integer> index) {
        this.keys = keys;
        this.values = values;
        this.size = size;
        this.index = index;
    }

    String keyAt(int i) {
        return keys[i];
    }

    JsonValue valueAt(int i) {
        return values[i];
    }

    private int indexOf(@Nullable Object key) {
        Map<String, Integer> places = index;
        if (places != null) {
            Integer at = places.get(key);
            return at == null ? -1 : at;
        }
        for (int i = 0; i < size; i++) {
            if (keys[i].equals(key)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public @Nullable JsonValue get(@Nullable Object key) {
        int at = indexOf(key);
        return at < 0 ? null : values[at];
    }

    @Override
    public boolean containsKey(@Nullable Object key) {
        return indexOf(key) >= 0;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public void forEach(BiConsumer<? super String, ? super JsonValue> action) {
        for (int i = 0; i < size; i++) {
            action.accept(keys[i], values[i]);
        }
    }

    /** The members in order, as entries that refuse {@code setValue}; the set and its iterator refuse removal. */
    @Override
    public Set<Entry<String, JsonValue>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public int size() {
                return size;
            }

            @Override
            public Iterator<Entry<String, JsonValue>> iterator() {
                return new Iterator<>() {
                    private int next;

                    @Override
                    public boolean hasNext() {
                        return next < size;
                    }

                    @Override
                    public Entry<String, JsonValue> next() {
                        if (next >= size) {
                            throw new NoSuchElementException();
                        }
                        Entry<String, JsonValue> entry = Map.entry(keys[next], values[next]);
                        next++;
                        return entry;
                    }
                };
            }
        };
    }

    @Override
    public @Nullable JsonArray getJsonArray(String name) {
        JsonValue value = get(name);
        if (value == null || value instanceof JsonArray) {
            return (JsonArray) value;
        }
        throw new Shape.Mismatch("array", under(name), value);
    }

    @Override
    public @Nullable JsonObject getJsonObject(String name) {
        JsonValue value = get(name);
        if (value == null || value instanceof JsonObject) {
            return (JsonObject) value;
        }
        throw new Shape.Mismatch("object", under(name), value);
    }

    @Override
    public @Nullable JsonNumber getJsonNumber(String name) {
        JsonValue value = get(name);
        if (value == null || value instanceof JsonNumber) {
            return (JsonNumber) value;
        }
        throw new Shape.Mismatch("number", under(name), value);
    }

    @Override
    public @Nullable JsonString getJsonString(String name) {
        JsonValue value = get(name);
        if (value == null || value instanceof JsonString) {
            return (JsonString) value;
        }
        throw new Shape.Mismatch("string", under(name), value);
    }

    @Override
    public String getString(String name) {
        JsonValue value = member(name);
        if (value instanceof JsonString string) {
            return string.getString();
        }
        throw new Shape.Mismatch("string", under(name), value);
    }

    @Override
    public @Nullable String getString(String name, @Nullable String defaultValue) {
        return get(name) instanceof JsonString string ? string.getString() : defaultValue;
    }

    @Override
    public int getInt(String name) {
        JsonValue value = member(name);
        if (value instanceof JsonNumber number) {
            return number.intValue();
        }
        throw new Shape.Mismatch("number", under(name), value);
    }

    @Override
    public int getInt(String name, int defaultValue) {
        return get(name) instanceof JsonNumber number ? number.intValue() : defaultValue;
    }

    @Override
    public boolean getBoolean(String name) {
        JsonValue value = member(name);
        return switch (value.getValueType()) {
            case TRUE -> true;
            case FALSE -> false;
            default -> throw new Shape.Mismatch("boolean", under(name), value);
        };
    }

    @Override
    public boolean getBoolean(String name, boolean defaultValue) {
        JsonValue value = get(name);
        if (value == null) {
            return defaultValue;
        }
        return switch (value.getValueType()) {
            case TRUE -> true;
            case FALSE -> false;
            default -> defaultValue;
        };
    }

    @Override
    public boolean isNull(String name) {
        return member(name).getValueType() == ValueType.NULL;
    }

    private JsonValue member(String name) {
        JsonValue value = get(name);
        if (value == null) {
            throw new Shape.Missing(name);
        }
        return value;
    }

    private static String under(String name) {
        return "under key \"" + name + "\"";
    }

    @Override
    public ValueType getValueType() {
        return ValueType.OBJECT;
    }

    /** Resolved by this module's pointer: the interface's default looks a provider up for every call. */
    @Override
    public JsonValue getValue(String jsonPointer) {
        return new SilkJsonPointer(jsonPointer).getValue(this);
    }

    /** The object as JSON text. */
    @Override
    public String toString() {
        return Values.toJson(this);
    }
}
