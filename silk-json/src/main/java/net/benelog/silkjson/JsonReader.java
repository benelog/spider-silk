package net.benelog.silkjson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * Builds a value out of JSON. Like {@link JsonWriter}, the mapping is
 * written by hand or generated at compile time, and uses no reflection.
 *
 * <p>A reader rejects bad input by throwing {@link IllegalArgumentException}
 * or {@link java.time.DateTimeException}, as parameter parsers do.
 * {@link JsonInput} and the tree's accessors throw {@link JsonException}, a
 * subtype, for a syntax error, a missing key, or a value of the wrong type.
 * Spider Silk's {@code req.bodyJson(reader)} turns these into a 400, so a
 * reader never has to return a half-built object.
 *
 * <pre>{@code
 * static final JsonReader<NewDeck> NEW_DECK =
 *         JsonReader.object(object -> new NewDeck(object.getString("name")));
 *
 * NewDeck body = req.bodyJson(NEW_DECK);
 * }</pre>
 *
 * <p>{@link #object} reads an object as a tree and {@link #list} reads an
 * array an element at a time, which is how a hand-written reader is usually
 * built. A reader written straight against the {@link JsonInput}, as a
 * generated codec is, never builds a tree at all.
 */
@FunctionalInterface
public interface JsonReader<T extends @Nullable Object> {

    T read(JsonInput in);

    /**
     * A reader for an object, built from the function that makes the value out
     * of its fields. The object is parsed as a tree and taken once, so the
     * function reads each field straight off it:
     *
     * <pre>{@code
     * static final JsonReader<CardDraft> CARD_DRAFT = JsonReader.object(object -> new CardDraft(
     *         object.getString("text"),
     *         object.getString("meaning", ""),
     *         object.getString("tags", "")));
     * }</pre>
     *
     * <p>A value that is not an object throws {@link JsonException}, which
     * Spider Silk's {@code req.bodyJson(reader)} answers with 400.
     */
    static <T extends @Nullable Object> JsonReader<T> object(Function<JsonObject, T> fromObject) {
        return in -> fromObject.apply(in.readValue().asObject());
    }

    /**
     * A reader over the whole value as a tree, for a shape that is neither an
     * object nor a list of one: {@code JsonReader.tree(json -> json.asString())}.
     */
    static <T extends @Nullable Object> JsonReader<T> tree(Function<JsonValue, T> fromTree) {
        return in -> fromTree.apply(in.readValue());
    }

    /**
     * A reader for a list, built from the reader for one element. The list
     * cannot be changed, and holds a null wherever the element reader answered
     * one: {@code JsonReader<@Nullable String>} reads {@code ["a", null]} as it
     * stands, where {@code List.copyOf} threw a NullPointerException that
     * answered the request with 500.
     */
    static <T extends @Nullable Object> JsonReader<List<T>> list(JsonReader<T> element) {
        return in -> {
            in.array();
            List<T> values = new ArrayList<>();
            while (in.nextElement()) {
                values.add(element.read(in));
            }
            return Collections.unmodifiableList(values);
        };
    }

    /** The value read out of a document's text, which must hold that one value and nothing after it. */
    default T fromJson(String text) {
        return fromJson(JsonInput.of(text));
    }

    /** The value read out of a document's UTF-8 bytes. */
    default T fromJsonBytes(byte[] utf8) {
        return fromJson(JsonInput.of(utf8));
    }

    private T fromJson(JsonInput in) {
        T value = read(in);
        in.end();
        return value;
    }
}
