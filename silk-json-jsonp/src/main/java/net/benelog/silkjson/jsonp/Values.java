package net.benelog.silkjson.jsonp;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonConfig.KeyStrategy;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.jspecify.annotations.Nullable;

import net.benelog.silkjson.JsonOutput;

/** What the tree, the builders, the generator, and the writer share: a Java value as a JSON-P one, and a JSON-P value written out. */
final class Values {

    private Values() {
    }

    static JsonValue of(boolean value) {
        return value ? JsonValue.TRUE : JsonValue.FALSE;
    }

    /**
     * A Java value as the JSON-P value it stands for, for the builders made
     * from a map or a collection: null, a JSON-P value or builder, a string, a
     * boolean, a number as {@link SilkJsonNumber#of(Number)} takes it, and a
     * collection, a map, or an array of these, told apart with
     * {@code instanceof}.
     *
     * @throws IllegalArgumentException for a value of any other type
     */
    static JsonValue of(@Nullable Object value, KeyStrategy strategy) {
        if (value == null) {
            return JsonValue.NULL;
        }
        return switch (value) {
            case JsonValue json -> json;
            case JsonObjectBuilder builder -> builder.build();
            case JsonArrayBuilder builder -> builder.build();
            case String s -> new SilkJsonString(s);
            case Boolean b -> of(b.booleanValue());
            case Number number -> SilkJsonNumber.of(number);
            case Collection<?> collection -> array(collection, strategy);
            case Map<?, ?> map -> object(map, strategy);
            case Object[] array -> array(Arrays.asList(array), strategy);
            case int[] array -> array(Arrays.stream(array).mapToObj(Integer::valueOf).toList(), strategy);
            case long[] array -> array(Arrays.stream(array).mapToObj(Long::valueOf).toList(), strategy);
            case double[] array -> array(Arrays.stream(array).mapToObj(Double::valueOf).toList(), strategy);
            case boolean[] array -> {
                JsonValue[] values = new JsonValue[array.length];
                for (int i = 0; i < array.length; i++) {
                    values[i] = of(array[i]);
                }
                yield new SilkJsonArray(values, values.length);
            }
            default -> throw new IllegalArgumentException(
                    "Not a value JSON-P builds from: an instance of " + value.getClass().getName());
        };
    }

    /** The elements of a collection, an empty {@link Optional} left out and a present one taken for its value. */
    static SilkJsonArray array(Collection<?> collection, KeyStrategy strategy) {
        JsonValue[] values = new JsonValue[collection.size()];
        int size = 0;
        for (Object element : collection) {
            if (element instanceof Optional<?> optional) {
                if (optional.isEmpty()) {
                    continue;
                }
                element = optional.get();
            }
            values[size++] = of(element, strategy);
        }
        return new SilkJsonArray(values, size);
    }

    /** The members of a map, whose keys are strings, an empty {@link Optional} left out and a present one taken for its value. */
    static Members members(Map<?, ?> map, KeyStrategy strategy) {
        Members members = new Members(strategy);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Optional<?> optional) {
                if (optional.isEmpty()) {
                    continue;
                }
                value = optional.get();
            }
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("A JSON object's keys are strings, not " + entry.getKey());
            }
            members.put(key, of(value, strategy));
        }
        return members;
    }

    private static SilkJsonObject object(Map<?, ?> map, KeyStrategy strategy) {
        return members(map, strategy).build();
    }

    /** The value's JSON text. */
    static String toJson(JsonValue value) {
        JsonOutput out = JsonOutput.inMemory();
        write(value, out);
        return out.toJson();
    }

    /** The value's JSON text as UTF-8. */
    static byte[] toJsonBytes(JsonValue value) {
        JsonOutput out = JsonOutput.inMemory();
        write(value, out);
        return out.toBytes();
    }

    /**
     * Writes any JSON-P value, this module's or another provider's, as the
     * next value of the output. A number goes out as its {@code toString()}
     * says it, which JSON-P makes the decimal's: a whole number of this
     * module's as the long it holds, and any other as its {@link BigDecimal}.
     */
    static void write(JsonValue value, JsonOutput out) {
        switch (value.getValueType()) {
            case OBJECT -> {
                out.object();
                if (value instanceof SilkJsonObject object) {
                    for (int i = 0, size = object.size(); i < size; i++) {
                        out.name(object.keyAt(i));
                        write(object.valueAt(i), out);
                    }
                } else {
                    for (Map.Entry<String, JsonValue> member : ((JsonObject) value).entrySet()) {
                        out.name(member.getKey());
                        write(member.getValue(), out);
                    }
                }
                out.end();
            }
            case ARRAY -> {
                out.array();
                for (JsonValue element : (JsonArray) value) {
                    write(element, out);
                }
                out.end();
            }
            case STRING -> out.value(((JsonString) value).getString());
            case NUMBER -> {
                if (value instanceof SilkJsonNumber.Whole whole) {
                    out.value(whole.value);
                } else {
                    out.value(((JsonNumber) value).bigDecimalValue());
                }
            }
            case TRUE -> out.value(true);
            case FALSE -> out.value(false);
            case NULL -> out.nullValue();
        }
    }
}
