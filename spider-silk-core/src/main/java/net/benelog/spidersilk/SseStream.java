package net.benelog.spidersilk;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletResponse;

/**
 * An open Server-Sent Events stream, handed to the lambda of
 * {@link WebResponse#sse(SseWriter)}.
 *
 * <pre>{@code
 * app.get("/events", req -> WebResponse.sse(stream -> {
 *     while (stream.isOpen()) {
 *         stream.id(String.valueOf(counter.incrementAndGet()))
 *               .send("tick", Json.obj().put("at", now()).toJson());
 *         Thread.sleep(1000);
 *     }
 * }));
 * }</pre>
 *
 * <p>Every method writes one complete event and flushes it, so an event is on
 * the wire when the call returns. The methods are synchronized on the stream
 * itself: {@link App#stop()} closes the stream from another thread, and a frame
 * must not be cut in half by that.
 *
 * <p>Once the stream is closed — because the client went away, or because the
 * server is stopping — every further write throws {@link Closed}, which
 * {@link WebResponse#sse(SseWriter)} swallows. A writer that does nothing about
 * it therefore ends quietly; {@link #isOpen()} is the way to end it on purpose.
 *
 * <p>The bytes are UTF-8, which is the only encoding the EventSource protocol
 * has.
 */
public final class SseStream {

    /**
     * The line endings the protocol accepts inside a field's value, each of
     * which becomes a field of its own. Compiled once: an SSE body is the one
     * body written in a tight loop, and String.split recompiles this per call.
     */
    private static final Pattern LINE_BREAK = Pattern.compile("\\r\\n|\\r|\\n");

    private final OutputStream out;

    private boolean open = true;
    private String nextId;

    SseStream(HttpServletResponse res) throws IOException {
        this.out = res.getOutputStream();
    }

    /**
     * The {@code id} of the next event, which the browser sends back as
     * {@code Last-Event-ID} when it reconnects. It applies to the next event
     * only, the way the protocol defines it.
     */
    public synchronized SseStream id(String id) {
        this.nextId = id;
        return this;
    }

    /**
     * The delay the browser waits before it reconnects, written as one
     * {@code retry:} line in milliseconds and flushed on its own. It holds for
     * the rest of the stream, and for the connections that follow it, until
     * another one is sent.
     *
     * <pre>{@code
     * stream.retry(Duration.ofSeconds(2));
     * }</pre>
     *
     * <p>Unlike {@link #id(String)} this is not a label on the next event, so it
     * goes out where it is called rather than waiting for one. The browser
     * applies it as the line arrives, and a stream that sends none reconnects on
     * the browser's own default, which is a few seconds.
     *
     * @throws IllegalArgumentException if the delay is negative, which the
     *         protocol has no meaning for and a browser silently ignores
     */
    public synchronized SseStream retry(Duration delay) {
        Objects.requireNonNull(delay, "delay");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("A reconnection delay cannot be negative: " + delay);
        }
        write("retry: " + delay.toMillis() + "\n\n");
        return this;
    }

    /** Sends an unnamed event, which arrives at {@code EventSource.onmessage}. */
    public SseStream send(String data) {
        return send(null, data);
    }

    /**
     * Sends a named event, which arrives at the listener registered for that
     * name. Data spanning several lines is sent as one {@code data:} line each,
     * which the client joins back together with newlines.
     */
    public synchronized SseStream send(String event, String data) {
        StringBuilder frame = new StringBuilder();
        if (nextId != null) {
            frame.append("id: ").append(nextId).append('\n');
        }
        if (event != null) {
            frame.append("event: ").append(event).append('\n');
        }
        appendLines(frame, "data: ", data);
        write(frame.append('\n').toString());
        nextId = null;
        return this;
    }

    /**
     * Sends a comment, which no listener sees. This is the heartbeat: a proxy
     * or a connector idle timeout cuts a stream that has been quiet, and a
     * comment costs a handful of bytes to keep it from counting as quiet.
     */
    public synchronized SseStream comment(String text) {
        StringBuilder frame = new StringBuilder();
        appendLines(frame, ": ", text);
        write(frame.append('\n').toString());
        return this;
    }

    /** Whether the stream can still be written to. */
    public synchronized boolean isOpen() {
        return open;
    }

    /**
     * Ends the stream. Called for you when the writer returns, and by
     * {@link App#stop()} for every stream still open; doing it twice is a no-op.
     */
    public synchronized void close() {
        if (!open) {
            return;
        }
        open = false;
        try {
            out.close();
        } catch (IOException e) {
            // The client is already gone. There is nothing left to report it to.
        }
    }

    /**
     * One field per line of the text, which is how the protocol carries a value
     * that spans several: the client joins them back with newlines. A comment
     * is framed the same way, under the empty field name.
     */
    private static void appendLines(StringBuilder frame, String prefix, String text) {
        for (String line : LINE_BREAK.split(text, -1)) {
            frame.append(prefix).append(line).append('\n');
        }
    }

    private void write(String frame) {
        if (!open) {
            throw new Closed("The SSE stream is closed");
        }
        try {
            out.write(frame.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            open = false;
            throw new Closed("The SSE client disconnected");
        }
    }

    /**
     * Thrown by a write to a stream that has ended — a client that navigated
     * away, or a server that is stopping. {@link WebResponse#sse(SseWriter)}
     * catches it and finishes the request normally, because neither of those is
     * an error the application did anything about.
     */
    public static final class Closed extends RuntimeException {

        Closed(String message) {
            super(message);
        }
    }
}
