package net.benelog.spidersilk.json;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a value out of parsed JSON. Like {@link JsonWriter}, the mapping is
 * written by hand and uses no reflection.
 *
 * <p>A reader rejects bad input by throwing {@link IllegalArgumentException}.
 * {@code Json}'s own accessors throw {@link Json.JsonException}, a subtype, for
 * a missing key or a value of the wrong type. {@code req.bodyJson(reader)}
 * turns either into a 400, so a reader never has to return a half-built object.
 *
 * <pre>{@code
 * static final JsonReader<NewDeck> NEW_DECK =
 *         json -> new NewDeck(json.asObject().getString("name"));
 *
 * NewDeck body = req.bodyJson(NEW_DECK);
 * }</pre>
 */
@FunctionalInterface
public interface JsonReader<T> {

    T read(Json.JsonValue json);

    /** A reader for a list, built from the reader for one element. */
    static <T> JsonReader<List<T>> list(JsonReader<T> element) {
        return json -> {
            List<T> values = new ArrayList<>();
            for (Json.JsonValue value : json.asArray().values()) {
                values.add(element.read(value));
            }
            return List.copyOf(values);
        };
    }
}
