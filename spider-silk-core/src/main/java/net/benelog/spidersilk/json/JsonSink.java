package net.benelog.spidersilk.json;

/**
 * Where a streamed JSON response is written, one value at a time. The framing
 * around those values — the brackets and commas of an array, the newlines of
 * NDJSON — belongs to the response that opened the sink, so a writer says only
 * what the next value is.
 *
 * <p>Each value is serialized and handed to the socket as it arrives, which is
 * the point: a million rows never exist as one {@code Json.JsonArray}. Build one
 * element at a time and the memory an answer costs is its largest element, not
 * the whole of it.
 *
 * <pre>{@code
 * WebResponse.ndjson(sink -> cardService.eachCard(deckId, card -> sink.write(card, Codecs.CARD)));
 * }</pre>
 *
 * <p>Writing throws {@link java.io.UncheckedIOException} rather than a checked
 * {@code IOException}, which is what lets {@code sink.write(...)} sit inside a
 * plain {@link java.util.function.Consumer} — a row callback over a database
 * cursor, which is where the values usually come from. The stream is the
 * response body: a write that fails has lost the client, and there is nothing a
 * handler could have done about it anyway.
 */
public interface JsonSink {

    /** Writes one value, already built as a tree. */
    void write(Json.JsonValue value);

    /** Writes one value through a hand-written writer — the usual form. */
    default <T> void write(T value, JsonWriter<T> writer) {
        write(writer.write(value));
    }
}
