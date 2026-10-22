package net.benelog.silkjson.jsonp;

import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;

import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.jspecify.annotations.Nullable;

/** An immutable JSON array: a {@link List} over one array of its elements, which refuses every change. */
final class SilkJsonArray extends AbstractList<JsonValue> implements JsonArray, RandomAccess {

    private final JsonValue[] values;
    private final int size;

    /** Takes the array as it is, which nothing else writes to again. */
    SilkJsonArray(JsonValue[] values, int size) {
        this.values = values;
        this.size = size;
    }

    @Override
    public JsonValue get(int index) {
        if (index < 0 || index >= size) {
            throw new Shape.OutOfRange(index, size);
        }
        return values[index];
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public JsonObject getJsonObject(int index) {
        JsonValue value = get(index);
        if (value instanceof JsonObject object) {
            return object;
        }
        throw new Shape.Mismatch("object", at(index), value);
    }

    @Override
    public JsonArray getJsonArray(int index) {
        JsonValue value = get(index);
        if (value instanceof JsonArray array) {
            return array;
        }
        throw new Shape.Mismatch("array", at(index), value);
    }

    @Override
    public JsonNumber getJsonNumber(int index) {
        JsonValue value = get(index);
        if (value instanceof JsonNumber number) {
            return number;
        }
        throw new Shape.Mismatch("number", at(index), value);
    }

    @Override
    public JsonString getJsonString(int index) {
        JsonValue value = get(index);
        if (value instanceof JsonString string) {
            return string;
        }
        throw new Shape.Mismatch("string", at(index), value);
    }

    /** This array as a list of that type, unchecked, as JSON-P has it: an element of another type fails where it is read. */
    @Override
    @SuppressWarnings("unchecked")
    public <T extends JsonValue> List<T> getValuesAs(Class<T> type) {
        return (List<T>) this;
    }

    @Override
    public String getString(int index) {
        return getJsonString(index).getString();
    }

    @Override
    public @Nullable String getString(int index, @Nullable String defaultValue) {
        return index >= 0 && index < size && values[index] instanceof JsonString string
                ? string.getString()
                : defaultValue;
    }

    @Override
    public int getInt(int index) {
        return getJsonNumber(index).intValue();
    }

    @Override
    public int getInt(int index, int defaultValue) {
        return index >= 0 && index < size && values[index] instanceof JsonNumber number
                ? number.intValue()
                : defaultValue;
    }

    @Override
    public boolean getBoolean(int index) {
        JsonValue value = get(index);
        return switch (value.getValueType()) {
            case TRUE -> true;
            case FALSE -> false;
            default -> throw new Shape.Mismatch("boolean", at(index), value);
        };
    }

    @Override
    public boolean getBoolean(int index, boolean defaultValue) {
        if (index < 0 || index >= size) {
            return defaultValue;
        }
        return switch (values[index].getValueType()) {
            case TRUE -> true;
            case FALSE -> false;
            default -> defaultValue;
        };
    }

    @Override
    public boolean isNull(int index) {
        return get(index).getValueType() == ValueType.NULL;
    }

    private static String at(int index) {
        return "at index " + index;
    }

    @Override
    public ValueType getValueType() {
        return ValueType.ARRAY;
    }

    /** Resolved by this module's pointer: the interface's default looks a provider up for every call. */
    @Override
    public JsonValue getValue(String jsonPointer) {
        return new SilkJsonPointer(jsonPointer).getValue(this);
    }

    /** The array as JSON text. */
    @Override
    public String toString() {
        return Values.toJson(this);
    }
}
