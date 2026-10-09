package net.benelog.spidersilk.json;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.RandomAccess;
import java.util.function.Function;

/**
 * Turns a value into JSON. The mapping is written by hand, or generated at
 * compile time from a type's annotations, so the wire format changes only
 * when someone edits it — there is no reflection anywhere.
 *
 * <p>One method, so a writer is a lambda. It writes straight into the
 * {@link JsonOutput} it is handed, with no tree in between:
 *
 * <pre>{@code
 * static final JsonWriter<Deck> DECK = (deck, out) -> out.object()
 *         .put("id", deck.id())
 *         .put("name", deck.name())
 *         .end();
 *
 * WebResponse.json(deck, DECK);
 * }</pre>
 */
@FunctionalInterface
public interface JsonWriter<T> {

    void write(T value, JsonOutput out);

    /** A writer for a list, built from the writer for one element. */
    static <T> JsonWriter<List<T>> list(JsonWriter<T> element) {
        return (values, out) -> {
            out.array();
            if (values instanceof RandomAccess) {
                for (int i = 0, size = values.size(); i < size; i++) {
                    element.write(values.get(i), out);
                }
            } else {
                for (T value : values) {
                    element.write(value, out);
                }
            }
            out.end();
        };
    }

    /**
     * A writer that builds a tree and writes it: the shape a writer had before
     * it was handed an output, kept for a mapping that is easier to state as a
     * {@link Json#object()} than as a sequence of puts.
     */
    static <T> JsonWriter<T> tree(Function<T, JsonValue> toTree) {
        return (value, out) -> out.value(toTree.apply(value));
    }

    /** The value's document as UTF-8: what a response body carries. */
    default byte[] toJsonBytes(T value) {
        JsonOutput out = JsonOutput.inMemory();
        write(value, out);
        return out.toBytes();
    }

    /** The value's document as text, for an SSE event or a log line. */
    default String toJson(T value) {
        return new String(toJsonBytes(value), StandardCharsets.UTF_8);
    }
}
