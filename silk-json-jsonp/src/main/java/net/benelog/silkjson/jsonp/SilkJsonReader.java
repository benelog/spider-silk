package net.benelog.silkjson.jsonp;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

import jakarta.json.JsonArray;
import jakarta.json.JsonConfig.KeyStrategy;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonParser.Event;
import jakarta.json.stream.JsonParsingException;

import net.benelog.silkjson.JsonInput;

/**
 * Reads a document's one value as the immutable tree, straight off a
 * {@link JsonInput} with no events in between, and checks that nothing but
 * whitespace follows it. The read opens the document, which a reader reads
 * once, and one past {@link Documents#STREAM_ABOVE} bytes is read through a
 * {@link StreamingJsonParser} instead.
 */
final class SilkJsonReader implements JsonReader {

    private final Documents.Source document;
    private final Closeable closeable;
    private final KeyStrategy strategy;
    private boolean used;

    /** Takes where the document comes from, which the first read opens, so a failure of the stream is that read's {@link JsonException}. */
    SilkJsonReader(Documents.Source document, Closeable closeable, KeyStrategy strategy) {
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
        Documents.Opened opened = Documents.open(document);
        InputStream rest = opened.rest();
        if (rest != null) {
            return streamed(new StreamingJsonParser(opened.head(), rest, null, strategy, Documents.PIECE), wanted);
        }
        byte[] utf8 = opened.head();
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

    /**
     * A document past {@link Documents#STREAM_ABOVE} bytes, read through a
     * streaming parser: the tree is built from its events, and the bytes of
     * the document are never held at once.
     */
    private static JsonValue streamed(StreamingJsonParser parser, Wanted wanted) {
        Event event = parser.next();
        boolean object = event == Event.START_OBJECT;
        boolean array = event == Event.START_ARRAY;
        String expected = switch (wanted) {
            case OBJECT -> object ? "" : "object";
            case ARRAY -> array ? "" : "array";
            case STRUCTURE -> object || array ? "" : "object or array";
            case ANY -> "";
        };
        if (!expected.isEmpty()) {
            String found = switch (event) {
                case START_OBJECT -> "an object";
                case START_ARRAY -> "an array";
                case VALUE_STRING -> "a string";
                case VALUE_NUMBER -> "a number";
                case VALUE_TRUE -> wanted == Wanted.STRUCTURE ? "a boolean" : "true";
                case VALUE_FALSE -> wanted == Wanted.STRUCTURE ? "a boolean" : "false";
                default -> "null";
            };
            Documents.Location at = (Documents.Location) parser.getLocation();
            throw new JsonParsingException("Near character " + at.offset() + ": Not a JSON " + expected + ": " + found, at);
        }
        JsonValue value = parser.getValue();
        parser.hasNext();
        return value;
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
