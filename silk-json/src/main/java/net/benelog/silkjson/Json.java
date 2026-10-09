package net.benelog.silkjson;

/**
 * A JSON builder and parser without reflection.
 * Instead of mapping objects automatically, you state in code what goes out.
 *
 * <pre>{@code
 * String json = Json.object()
 *         .put("id", deck.id())
 *         .put("name", deck.name())
 *         .put("tags", Json.array().addAll(tagNames))
 *         .toJson();
 *
 * JsonValue body = Json.parse(text);
 * String name = body.asObject().getString("name");
 * }</pre>
 *
 * <p>The tree is {@link JsonValue} and its three kinds, {@link JsonObject},
 * {@link JsonArray}, and {@link JsonPrimitive}; this class is where a tree is
 * started or parsed. A document is written without a tree through a
 * {@link JsonWriter} and read without one through a {@link JsonReader}, both
 * over the same {@link JsonOutput} and {@link JsonInput} a tree goes through.
 */
public final class Json {

    private Json() {
    }

    /** An empty object, to be filled with {@link JsonObject#put}. */
    public static JsonObject object() {
        return new JsonObject();
    }

    /** An empty array, to be filled with {@link JsonArray#add}. */
    public static JsonArray array() {
        return new JsonArray();
    }

    /**
     * Parses JSON text. Throws {@link JsonException} on invalid syntax, on
     * objects and arrays nested deeper than 256 levels, and on a number too
     * large for a {@code double} to hold, which would otherwise read back as an
     * infinity and serialize as text no JSON parser accepts.
     *
     * <p>An integer outside the range of a {@code long} is still a number, and
     * is kept the way a fraction is: {@link JsonValue#asDouble()} reads it, and
     * {@link JsonValue#asLong()} refuses it rather than answer another number.
     *
     * <p>The syntax is RFC 8259's, with no extension: a number such as
     * {@code 01}, {@code +1}, {@code .5}, or {@code 1.} is rejected, and so is
     * a control character written into a string without an escape.
     */
    public static JsonValue parse(String text) {
        return parse(JsonInput.of(text));
    }

    /**
     * Parses a document's UTF-8 bytes, on the terms of {@link #parse(String)}.
     * A request body arrives as bytes, and a malformed UTF-8 sequence in a
     * string is a {@link JsonException} rather than a replacement character.
     */
    public static JsonValue parse(byte[] utf8) {
        return parse(JsonInput.of(utf8));
    }

    private static JsonValue parse(JsonInput in) {
        JsonValue value = in.readValue();
        in.end();
        return value;
    }

    /**
     * The value itself, unless it is NaN or an infinity. JSON's grammar has no
     * syntax for either, so a document holding one is not JSON and this file's
     * own parser rejects it. A double is checked where it enters the tree, so
     * the failure names the call that made the value rather than turning up as
     * a response body no client can read.
     */
    static double finite(double value) {
        if (!Double.isFinite(value)) {
            throw new JsonException("Not a JSON number: " + value);
        }
        return value;
    }
}
