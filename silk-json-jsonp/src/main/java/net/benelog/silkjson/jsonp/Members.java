package net.benelog.silkjson.jsonp;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import jakarta.json.JsonConfig.KeyStrategy;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import org.jspecify.annotations.Nullable;

/**
 * The members of an object being built or parsed, in the order they were
 * put. A key put twice keeps its first place, and which value it keeps is the
 * {@link KeyStrategy}'s to say: the last, the first, or neither, when the
 * second put throws.
 */
final class Members {

    /** How many members an object holds before a key is found through an index rather than a scan of the keys. */
    static final int INDEXED_FROM = 16;

    private final KeyStrategy strategy;
    private String[] keys;
    private JsonValue[] values;
    private int size;
    private @Nullable Map<String, Integer> index;

    Members(KeyStrategy strategy) {
        this.strategy = strategy;
        this.keys = new String[8];
        this.values = new JsonValue[8];
    }

    /** Starts with the members of an object, of this module's or any other. */
    Members(KeyStrategy strategy, JsonObject object) {
        this(strategy);
        object.forEach(this::put);
    }

    boolean has(String key) {
        return indexOf(key) >= 0;
    }

    /** Puts a member, or puts a key that is already there as the strategy says. */
    void put(String key, JsonValue value) {
        int at = indexOf(key);
        if (at >= 0) {
            if (strategy == KeyStrategy.NONE) {
                throw new JsonException("Duplicate key in a JSON object: " + key);
            }
            if (strategy == KeyStrategy.LAST) {
                values[at] = value;
            }
            return;
        }
        if (size == keys.length) {
            keys = Arrays.copyOf(keys, size * 2);
            values = Arrays.copyOf(values, size * 2);
        }
        keys[size] = key;
        values[size] = value;
        Map<String, Integer> places = index;
        if (places != null) {
            places.put(key, size);
        } else if (size + 1 == INDEXED_FROM) {
            index = indexOfKeys(size + 1);
        }
        size++;
    }

    void remove(String key) {
        int at = indexOf(key);
        if (at < 0) {
            return;
        }
        int after = size - at - 1;
        System.arraycopy(keys, at + 1, keys, at, after);
        System.arraycopy(values, at + 1, values, at, after);
        size--;
        index = size >= INDEXED_FROM ? indexOfKeys(size) : null;
    }

    /** The object, which takes these arrays; this starts again empty. */
    SilkJsonObject build() {
        SilkJsonObject object = new SilkJsonObject(keys, values, size, index);
        keys = new String[8];
        values = new JsonValue[8];
        size = 0;
        index = null;
        return object;
    }

    private int indexOf(String key) {
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

    private Map<String, Integer> indexOfKeys(int count) {
        Map<String, Integer> places = HashMap.newHashMap(count * 2);
        for (int i = 0; i < count; i++) {
            places.put(keys[i], i);
        }
        return places;
    }
}
