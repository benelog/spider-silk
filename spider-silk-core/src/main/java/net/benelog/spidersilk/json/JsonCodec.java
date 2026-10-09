package net.benelog.spidersilk.json;

import java.util.List;

/**
 * Both directions for one type, for the case where a value goes out and comes
 * back in. Most types only go out, which is why the two halves are separate
 * interfaces: a write-only mapping is a {@link JsonWriter} lambda rather than a
 * codec with an unimplementable {@code read}.
 *
 * <pre>{@code
 * static final JsonCodec<Deck> DECK = JsonCodec.of(
 *         (deck, out) -> out.object().put("id", deck.id()).put("name", deck.name()).end(),
 *         JsonReader.object(object -> new Deck(object.getLong("id"), object.getString("name"))));
 * }</pre>
 */
public interface JsonCodec<T> extends JsonWriter<T>, JsonReader<T> {

    /** Pairs the two halves. A codec has two methods, so it is not a lambda itself. */
    static <T> JsonCodec<T> of(JsonWriter<T> writer, JsonReader<T> reader) {
        return new JsonCodec<>() {
            @Override
            public void write(T value, JsonOutput out) {
                writer.write(value, out);
            }

            @Override
            public T read(JsonInput in) {
                return reader.read(in);
            }
        };
    }

    /** A codec for a list, built from the codec for one element. */
    static <T> JsonCodec<List<T>> list(JsonCodec<T> element) {
        return of(JsonWriter.list(element), JsonReader.list(element));
    }
}
