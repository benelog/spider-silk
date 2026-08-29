package net.benelog.spidersilk.json;

/**
 * Fills a {@link JsonSink} for a streamed JSON response — the counterpart of
 * {@code StreamWriter} for a body whose values are JSON rather than bytes.
 *
 * <p>One method, so it is a lambda. It runs after the response headers are
 * committed, the same as any other streamed body: anything that can fail in a
 * way the client should hear about belongs before the response is returned.
 */
@FunctionalInterface
public interface JsonStreamWriter {

    void write(JsonSink sink) throws Exception;
}
