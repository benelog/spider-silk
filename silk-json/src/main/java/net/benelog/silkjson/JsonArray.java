package net.benelog.silkjson;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import org.jspecify.annotations.Nullable;

/** A JSON array. Iterable, so a parsed array reads with for-each: {@code for (JsonValue v : array)}. */
public final class JsonArray implements JsonValue, Iterable<JsonValue> {

    /**
     * The elements in order. A string or a number added by a builder is held
     * as it is and wrapped only when it is read, the same as in a
     * {@link JsonObject}.
     */
    private Object[] values;
    private int size;

    public JsonArray() {
        this(8);
    }

    /** An array with room for that many elements, for a writer that knows how many it will add. */
    JsonArray(int capacity) {
        values = new Object[Math.max(capacity, 1)];
    }

    public JsonArray add(@Nullable String value) {
        return element(value == null ? JsonPrimitive.NULL : value);
    }

    public JsonArray add(long value) {
        return element(value);
    }

    /** Throws {@link JsonException} for NaN and for an infinity, which JSON has no syntax for. */
    public JsonArray add(double value) {
        return element(Json.finite(value));
    }

    public JsonArray add(boolean value) {
        return element(value ? JsonPrimitive.TRUE : JsonPrimitive.FALSE);
    }

    public JsonArray add(@Nullable JsonValue value) {
        return element(value == null ? JsonPrimitive.NULL : value);
    }

    /** Adds a JsonValue, or a String, a Long, a Double, or a parsed decimal held as it is. */
    JsonArray element(Object element) {
        if (size == values.length) {
            values = Arrays.copyOf(values, size * 2);
        }
        values[size++] = element;
        return this;
    }

    public JsonArray addAll(Iterable<String> strings) {
        for (String s : strings) {
            add(s);
        }
        return this;
    }

    public int size() {
        return size;
    }

    /**
     * The element at that index.
     *
     * @throws JsonException if the array has no element there, the way
     *         {@link JsonObject#get(String)} throws for a missing key, so that a
     *         reader given a short array is answered, in Spider Silk, with a 400
     *         rather than a 500
     */
    public JsonValue get(int index) {
        if (index < 0 || index >= size) {
            throw new JsonException("Index %d is out of bounds for a JSON array of %d elements"
                    .formatted(index, size));
        }
        return JsonPrimitive.wrap(values[index]);
    }

    /** The element at that index as it is held, unwrapped and unchecked: for JsonOutput, which stays within {@link #size()}. */
    Object at(int index) {
        return values[index];
    }

    public List<JsonValue> values() {
        JsonValue[] elements = new JsonValue[size];
        for (int i = 0; i < size; i++) {
            elements[i] = JsonPrimitive.wrap(values[i]);
        }
        return List.of(elements);
    }

    /** The same elements in the same order, each compared as {@link JsonPrimitive#equals} and the containers' own equals compare them. */
    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof JsonArray array) || array.size != size) {
            return false;
        }
        for (int i = 0; i < size; i++) {
            if (!JsonPrimitive.same(values[i], array.values[i])) {
                return false;
            }
        }
        return true;
    }

    /** A hash of the elements in order, as {@link List#hashCode()} combines them. */
    @Override
    public int hashCode() {
        int hash = 1;
        for (int i = 0; i < size; i++) {
            hash = 31 * hash + JsonPrimitive.hash(values[i]);
        }
        return hash;
    }

    /** The array as JSON text: {@link #toJson()}. */
    @Override
    public String toString() {
        return toJson();
    }

    /** Elements in order, read-only: remove throws rather than reaching the array. */
    @Override
    public Iterator<JsonValue> iterator() {
        return new Iterator<>() {
            private int next;

            @Override
            public boolean hasNext() {
                return next < size;
            }

            @Override
            public JsonValue next() {
                if (next >= size) {
                    throw new NoSuchElementException();
                }
                return JsonPrimitive.wrap(values[next++]);
            }
        };
    }
}
