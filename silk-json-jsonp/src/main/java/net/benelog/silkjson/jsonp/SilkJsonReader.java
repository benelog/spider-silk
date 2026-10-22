package net.benelog.silkjson.jsonp;

import java.io.Closeable;
import java.io.IOException;
import java.util.function.Supplier;

import jakarta.json.JsonArray;
import jakarta.json.JsonConfig.KeyStrategy;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonParsingException;

import net.benelog.silkjson.JsonInput;

/**
 * Reads a document's one value as the immutable tree, straight off a
 * {@link JsonInput} with no events in between, and checks that nothing but
 * whitespace follows it. The document is read whole by the read, and a reader
 * reads once.
 */
final class SilkJsonReader implements JsonReader {

    private final Supplier<byte[]> document;
    private final Closeable closeable;
    private final KeyStrategy strategy;
    private boolean used;

    /** Takes how to read the document, which the first read does, and a failure of the stream is that read's {@link JsonException}. */
    SilkJsonReader(Supplier<byte[]> document, Closeable closeable, KeyStrategy strategy) {
        this.document = document;
        this.closeable = closeable;
        this.strategy = strategy;
    }

    /** An object or an array: any other value is a {@link JsonParsingException}. */
    @Override
    public JsonStructure read() {
        return (JsonStructure) read(Wanted.STRUCTURE);
    }

    @Override
    public JsonObject readObject() {
        return (JsonObject) read(Wanted.OBJECT);
    }

    @Override
    public JsonArray readArray() {
        return (JsonArray) read(Wanted.ARRAY);
    }

    @Override
    public JsonValue readValue() {
        return read(Wanted.ANY);
    }

    private enum Wanted { OBJECT, ARRAY, STRUCTURE, ANY }

    private JsonValue read(Wanted wanted) {
        if (used) {
            throw new IllegalStateException("A JsonReader reads one value, and this one has been read or closed");
        }
        used = true;
        byte[] utf8 = document.get();
        JsonInput in = JsonInput.of(utf8);
        try {
            JsonValue value = switch (wanted) {
                case OBJECT -> {
                    in.object();
                    yield TreeReader.restOfObject(in, strategy);
                }
                case ARRAY -> {
                    in.array();
                    yield TreeReader.restOfArray(in, strategy);
                }
                case STRUCTURE -> {
                    String found = switch (in.peek()) {
                        case OBJECT, ARRAY -> "";
                        case STRING -> "a string";
                        case NUMBER -> "a number";
                        case BOOLEAN -> "a boolean";
                        case NULL -> "null";
                    };
                    if (!found.isEmpty()) {
                        throw in.error("Not a JSON object or array: " + found);
                    }
                    yield TreeReader.value(in, strategy);
                }
                case ANY -> TreeReader.value(in, strategy);
            };
            in.end();
            return value;
        } catch (net.benelog.silkjson.JsonException e) {
            throw new JsonParsingException(e.getMessage(), new Documents.Locator(utf8).at(in.position()));
        }
    }

    /** Closes the reader or the stream the document came from; a read after it throws. */
    @Override
    public void close() {
        used = true;
        try {
            closeable.close();
        } catch (IOException e) {
            throw new JsonException("Could not close the JSON document's source: " + e.getMessage(), e);
        }
    }
}
