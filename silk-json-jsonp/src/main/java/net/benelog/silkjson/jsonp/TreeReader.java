package net.benelog.silkjson.jsonp;

import java.util.Arrays;

import jakarta.json.JsonConfig.KeyStrategy;
import jakarta.json.JsonValue;

import net.benelog.silkjson.JsonInput;

/**
 * Builds the immutable tree out of a {@link JsonInput}, each value read with
 * the method for the kind {@link JsonInput#peek()} says comes next. Every
 * failure is the input's {@link net.benelog.silkjson.JsonException}, a
 * repeated key under {@link KeyStrategy#NONE} included, so that the caller
 * names the place it happened.
 */
final class TreeReader {

    private TreeReader() {
    }

    static JsonValue value(JsonInput in, KeyStrategy strategy) {
        return switch (in.peek()) {
            case OBJECT -> {
                in.object();
                yield restOfObject(in, strategy);
            }
            case ARRAY -> {
                in.array();
                yield restOfArray(in, strategy);
            }
            case STRING -> new SilkJsonString(in.readString());
            case NUMBER -> SilkJsonNumber.parsed(in.readNumber());
            case BOOLEAN -> Values.of(in.readBoolean());
            case NULL -> {
                in.readNull();
                yield JsonValue.NULL;
            }
        };
    }

    /** The members of the object the input has just entered, up to its closing brace. */
    static SilkJsonObject restOfObject(JsonInput in, KeyStrategy strategy) {
        Members members = new Members(strategy);
        while (in.nextKey()) {
            String key = in.key();
            if (strategy == KeyStrategy.NONE && members.has(key)) {
                throw in.error("Duplicate key in a JSON object: " + shortened(key));
            }
            members.put(key, value(in, strategy));
        }
        return members.build();
    }

    /** The elements of the array the input has just entered, up to its closing bracket. */
    static SilkJsonArray restOfArray(JsonInput in, KeyStrategy strategy) {
        JsonValue[] values = new JsonValue[8];
        int size = 0;
        while (in.nextElement()) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value(in, strategy);
        }
        return new SilkJsonArray(values, size);
    }

    /** A key's start, since the message of a rejection may travel back to the client. */
    static String shortened(String key) {
        return key.length() <= 32 ? key : key.substring(0, 32) + "...";
    }
}
