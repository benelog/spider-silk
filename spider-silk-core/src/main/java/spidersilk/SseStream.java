package spidersilk;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

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
        for (String line : data.split("\r\n|\r|\n", -1)) {
            frame.append("data: ").append(line).append('\n');
        }
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
        for (String line : text.split("\r\n|\r|\n", -1)) {
            frame.append(": ").append(line).append('\n');
        }
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
