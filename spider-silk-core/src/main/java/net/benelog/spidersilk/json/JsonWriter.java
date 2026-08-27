package net.benelog.spidersilk.json;

import java.util.List;

/**
 * Turns a value into JSON. The mapping is written by hand, so the wire format
 * changes only when someone edits it — there is no reflection anywhere.
 *
 * <p>One method, so a writer is a lambda:
 *
 * <pre>{@code
 * static final JsonWriter<Deck> DECK = deck -> Json.obj()
 *         .put("id", deck.id())
 *         .put("name", deck.name());
 *
 * ctx.json(deck, DECK);
 * }</pre>
 */
@FunctionalInterface
public interface JsonWriter<T> {

    Json.JsonValue write(T value);

    /** A writer for a list, built from the writer for one element. */
    static <T> JsonWriter<List<T>> list(JsonWriter<T> element) {
        return values -> {
            Json.JsonArray array = Json.arr();
            for (T value : values) {
                array.add(element.write(value));
            }
            return array;
        };
    }
}
