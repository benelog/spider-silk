package net.benelog.spidersilk;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;

/**
 * An open Server-Sent Events stream, handed to the lambda of
 * {@link WebResponse#sse(SseWriter)}.
 *
 * <pre>{@code
 * app.get("/events", req -> WebResponse.sse(stream -> {
 *     while (stream.isOpen()) {
 *         stream.id(String.valueOf(counter.incrementAndGet()))
 *               .send("tick", Json.object().put("at", now()).toJson());
 *         Thread.sleep(1000);
 *     }
 * }));
 * }</pre>
 *
 * <p>Every method writes one complete event and flushes it, so an event is on
 * the wire when the call returns. The writes hold a lock on the stream:
 * {@link App#stop()} closes the stream from another thread, and a frame must not
 * be cut in half by that. Closing does not wait for a write that is blocked,
 * though. A client that stops reading holds the flush until the connector gives
 * up on it, so a close that waited would hold {@code App.stop()} as long. It
 * marks the stream closed instead, and the write closes the output when it
 * returns, or when the server's stop timeout ends it.
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
    private final ReentrantLock lock = new ReentrantLock();

    /** Read without the lock, so that neither isOpen nor close waits behind a blocked write. */
    private volatile boolean open = true;
    private boolean outputClosed;
    private @Nullable String nextId;

    SseStream(HttpServletResponse res) throws IOException {
        this.out = res.getOutputStream();
    }

    /**
     * The {@code id} of the next event, which the browser sends back as
     * {@code Last-Event-ID} when it reconnects. It applies to the next event
     * only, the way the protocol defines it.
     *
     * @throws IllegalArgumentException if the id holds a line break, which
     *         would end the field and start another the handler never wrote,
     *         or a NUL, for which a browser drops the id
     */
    public SseStream id(String id) {
        Objects.requireNonNull(id, "id");
        requireOneLine("An event id", id);
        if (id.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("An event id cannot hold a NUL: a browser ignores such an id");
        }
        lock.lock();
        try {
            this.nextId = id;
        } finally {
            lock.unlock();
        }
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
    public SseStream retry(Duration delay) {
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
     *
     * @throws IllegalArgumentException if the event name holds a line break,
     *         which would end the field and start another; nothing is written
     */
    public SseStream send(@Nullable String event, String data) {
        if (event != null) {
            requireOneLine("An event name", event);
        }
        StringBuilder frame = new StringBuilder();
        if (event != null) {
            frame.append("event: ").append(event).append('\n');
        }
        appendLines(frame, "data: ", data);
        frame.append('\n');
        lock.lock();
        try {
            if (nextId != null) {
                frame.insert(0, "id: " + nextId + "\n");
            }
            write(frame.toString());
            nextId = null;
        } finally {
            lock.unlock();
        }
        return this;
    }

    /**
     * Sends a comment, which no listener sees. This is the heartbeat: a proxy
     * or a connector idle timeout cuts a stream that has been quiet, and a
     * comment costs a handful of bytes to keep it from counting as quiet.
     */
    public SseStream comment(String text) {
        StringBuilder frame = new StringBuilder();
        appendLines(frame, ": ", text);
        write(frame.append('\n').toString());
        return this;
    }

    /** Whether the stream can still be written to. */
    public boolean isOpen() {
        return open;
    }

    /**
     * Ends the stream. Called for you when the writer returns, and by
     * {@link App#stop()} for every stream still open; doing it twice is a no-op.
     */
    public void close() {
        open = false;
        closeOutputIfFree();
    }

    /**
     * Closes the output once, unless a write holds the lock. That write may be
     * blocked on a client that stopped reading, and it closes the output itself
     * when it returns, so there is no waiting here.
     */
    private void closeOutputIfFree() {
        if (!lock.tryLock()) {
            return;
        }
        try {
            if (outputClosed) {
                return;
            }
            outputClosed = true;
            out.close();
        } catch (IOException e) {
            // The client is already gone. There is nothing left to report it to.
        } finally {
            lock.unlock();
        }
    }

    /**
     * Refuses a value that has to fit on one field line. Data is split across
     * lines instead, but an id or an event name has no such form, and a CR or
     * an LF inside one would let text from anywhere write fields of its own.
     */
    private static void requireOneLine(String what, String value) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(what + " cannot hold a line break: it is one field line");
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
        lock.lock();
        try {
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
        } finally {
            lock.unlock();
            // A close that came while this write was under way left the output
            // to it. Checked after the unlock, so that a close either sees the
            // lock free or has already cleared the flag this reads.
            if (!open) {
                closeOutputIfFree();
            }
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
