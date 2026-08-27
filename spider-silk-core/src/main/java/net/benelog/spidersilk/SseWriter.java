package net.benelog.spidersilk;

/**
 * Fills an open {@link SseStream} for as long as the stream should last.
 * Reached through {@link WebResponse#sse(SseWriter)}.
 *
 * <p>Declared to throw, because an SSE loop usually sleeps between events and
 * {@code Thread.sleep} is checked — the same reason {@link Handler} throws.
 *
 * <p>A body-filling interface like {@link StreamWriter} and
 * {@link ServletWriter}, not a request-handling one: it produces the body of a
 * response that has already been decided, and answers nothing itself.
 */
@FunctionalInterface
public interface SseWriter {

    void write(SseStream stream) throws Exception;
}
