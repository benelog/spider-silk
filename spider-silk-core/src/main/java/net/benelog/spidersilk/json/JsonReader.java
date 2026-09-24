package net.benelog.spidersilk.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
 *         json -> new NewDeck(json.asObject().getString("name"));
 *
 * NewDeck body = req.bodyJson(NEW_DECK);
 * }</pre>
 */
@FunctionalInterface
public interface JsonReader<T extends @Nullable Object> {

    T read(JsonValue json);

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
