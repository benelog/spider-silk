package net.benelog.spidersilk.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * Builds a value out of parsed JSON. Like {@link JsonWriter}, the mapping is
 * written by hand and uses no reflection.
 *
 * <p>A reader rejects bad input by throwing {@link IllegalArgumentException}
 * or {@link java.time.DateTimeException}, as parameter parsers do.
 * {@code Json}'s own accessors throw {@link JsonException}, a subtype, for
 * a missing key or a value of the wrong type. {@code req.bodyJson(reader)}
 * turns these into a 400, so a reader never has to return a half-built object.
 *
 * <pre>{@code
 * static final JsonReader<NewDeck> NEW_DECK =
 *         JsonReader.object(object -> new NewDeck(object.getString("name")));
 *
 * NewDeck body = req.bodyJson(NEW_DECK);
 * }</pre>
 *
 * <p>{@link #object} reads an object, and {@link #list} reads an array. A
 * reader of any other shape is a lambda over the {@link JsonValue} itself.
 */
@FunctionalInterface
public interface JsonReader<T extends @Nullable Object> {

    T read(JsonValue json);

    /**
     * A reader for an object, built from the function that makes the value out
     * of its fields. The object is taken once, so the function reads each field
     * straight off it:
     *
     * <pre>{@code
     * static final JsonReader<CardDraft> CARD_DRAFT = JsonReader.object(object -> new CardDraft(
     *         object.getString("text"),
     *         object.getString("meaning", ""),
     *         object.getString("tags", "")));
     * }</pre>
     *
     * <p>A value that is not an object throws {@link JsonException}, which
     * {@code req.bodyJson(reader)} answers with 400.
     */
    static <T extends @Nullable Object> JsonReader<T> object(Function<JsonObject, T> fromObject) {
        return json -> fromObject.apply(json.asObject());
    }

    /**
     * A reader for a list, built from the reader for one element. The list
     * cannot be changed, and holds a null wherever the element reader answered
     * one: {@code JsonReader<@Nullable String>} reads {@code ["a", null]} as it
     * stands, where {@code List.copyOf} threw a NullPointerException that
     * answered the request with 500.
     */
    static <T extends @Nullable Object> JsonReader<List<T>> list(JsonReader<T> element) {
        return json -> {
            JsonArray array = json.asArray();
            List<T> values = new ArrayList<>(array.size());
            for (JsonValue value : array) {
                values.add(element.read(value));
            }
            return Collections.unmodifiableList(values);
        };
    }
}
