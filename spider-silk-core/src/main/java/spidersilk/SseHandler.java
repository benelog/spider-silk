package spidersilk;

/**
 * Fills an open {@link SseStream} for as long as the stream should last.
 * Declared to throw, because an SSE loop usually sleeps between events and
 * {@code Thread.sleep} is checked — the same reason {@link Handler} throws.
 */
@FunctionalInterface
public interface SseHandler {

    void handle(SseStream stream) throws Exception;
}
