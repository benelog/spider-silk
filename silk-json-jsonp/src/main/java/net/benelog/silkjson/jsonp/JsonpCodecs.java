package net.benelog.silkjson.jsonp;

import java.util.function.Function;

import jakarta.json.JsonArray;
import jakarta.json.JsonConfig.KeyStrategy;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonParser;

import org.jspecify.annotations.Nullable;

import net.benelog.silkjson.JsonException;
import net.benelog.silkjson.JsonInput;
import net.benelog.silkjson.JsonOutput;
import net.benelog.silkjson.JsonReader;
import net.benelog.silkjson.JsonWriter;

/**
 * The JSON-P tree wherever silk-json takes a {@link JsonWriter} or a
 * {@link JsonReader}: in Spider Silk, {@code WebResponse.json(value, writer)},
 * {@code req.bodyJson(reader)}, and a {@code JsonSink}. Nothing is looked up
 * and no provider is involved.
 *
 * <pre>{@code
 * JsonObject patch = req.bodyJson(JsonpCodecs.OBJECT);
 * JsonObject patched = Json.createMergePatch(patch).apply(deck).asJsonObject();
 * return WebResponse.json(patched, JsonpCodecs.WRITER);
 * }</pre>
 *
 * <p>{@link #WRITER} walks the value through the JSON-P interfaces into the
 * {@link JsonOutput} it is handed, so it writes another provider's tree as
 * well as this module's. The readers build this module's tree straight off
 * the {@link JsonInput}, and a document they cannot read is a
 * {@link JsonException}, which {@code req.bodyJson(reader)} answers with 400.
 * A key that repeats keeps its last value, JSON-P's default.
 */
public final class JsonpCodecs {

    /** Writes any JSON-P value, of this provider's tree or another's. */
    public static final JsonWriter<JsonValue> WRITER = (value, out) -> Values.write(value, out);

    /** Reads any value: an object, an array, or a scalar such as {@code JsonValue.NULL}. */
    public static final JsonReader<JsonValue> VALUE = in -> TreeReader.value(in, KeyStrategy.LAST);

    /** Reads an object, and refuses any other value with a {@link JsonException}. */
    public static final JsonReader<JsonObject> OBJECT = in -> {
        in.object();
        return TreeReader.restOfObject(in, KeyStrategy.LAST);
    };

    /** Reads an array, and refuses any other value with a {@link JsonException}. */
    public static final JsonReader<JsonArray> ARRAY = in -> {
        in.array();
        return TreeReader.restOfArray(in, KeyStrategy.LAST);
    };

    private JsonpCodecs() {
    }

    /**
     * A generator of the next value in the output, for code written against
     * JSON-P to take part in a document being written: after a name, as an
     * element, or as the document. {@code close()} checks that one whole value
     * was written, and leaves the output open for what comes after it.
     */
    public static JsonGenerator generator(JsonOutput out) {
        return new SilkJsonGenerator(out);
    }

    /**
     * A parser of the value that comes next in the input, which moves past
     * it: the value's bytes are the parser's document, so code written against
     * JSON-P can read as much of it as it likes and leave the input in place
     * for what comes after it. A value that repeats a key keeps the last.
     */
    public static JsonParser parser(JsonInput in) {
        return new SilkJsonParser(in.readValueBytes(), null, KeyStrategy.LAST);
    }

    /**
     * A reader of an object, built from the function that makes the value out
     * of it, the shape of silk-json's {@code JsonReader.object}:
     *
     * <pre>{@code
     * static final JsonReader<NewDeck> NEW_DECK = JsonpCodecs.object(object -> new NewDeck(
     *         object.getString("name"),
     *         object.getInt("limit", 20)));
     * }</pre>
     *
     * <p>JSON-P's accessors answer a missing key with
     * {@link NullPointerException}, a value of another kind with
     * {@link ClassCastException}, an index past an array with
     * {@link IndexOutOfBoundsException}, and a fraction or a number too large
     * for an exact read with {@link ArithmeticException}. Thrown inside the
     * function, these are the document's fault rather than the program's, and
     * become a {@link JsonException}, which Spider Silk answers with 400. The
     * message is the tree's own, which names the key and the kind found, or a
     * general one for an exception the tree did not throw, since it goes back
     * to the client.
     */
    public static <T extends @Nullable Object> JsonReader<T> object(Function<JsonObject, T> fromObject) {
        return in -> {
            JsonObject object = OBJECT.read(in);
            try {
                return fromObject.apply(object);
            } catch (NullPointerException | ClassCastException | IndexOutOfBoundsException | ArithmeticException e) {
                if (e instanceof ArithmeticException) {
                    throw new JsonException("Not a whole number in range: " + e.getMessage());
                }
                throw new JsonException(e instanceof Shape
                        ? String.valueOf(e.getMessage())
                        : "The JSON object is not the shape this reader takes");
            }
        };
    }
}
