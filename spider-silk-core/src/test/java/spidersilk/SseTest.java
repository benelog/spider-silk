package spidersilk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import spidersilk.test.WebTest;

/** Server-Sent Events: framing over the response that was already there. */
class SseTest {

    @Test
    void eventsAreFramedTheWayTheProtocolDefinesThem() {
        App app = new App().get("/events", req -> WebResponse.sse(stream -> {
            stream.id("7").send("tick", "first");
            stream.send("line one\nline two");
            stream.comment("keep-alive");
        }));

        WebTest.test(app, client -> {
            var response = client.get("/events");

            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElseThrow()
                    .startsWith("text/event-stream"));
            assertEquals("no-cache", response.headers().firstValue("Cache-Control").orElseThrow());
            // The id belongs to the event that follows it, and to no later one.
            assertEquals("""
                    id: 7
                    event: tick
                    data: first

                    data: line one
                    data: line two

                    : keep-alive

                    """, response.body());
        });
    }

    /** The whole reason SSE is in core and WebSocket is not: it is an ordinary route. */
    @Test
    void anSseEndpointIsAnOrdinaryRoute() {
        List<String> filtered = new ArrayList<>();
        List<Integer> logged = new ArrayList<>();
        App app = new App()
                .requestLogger((req, res, millis) -> logged.add(res.status()))
                .before("/events", req -> {
                    filtered.add("before " + req.path());
                    return null;
                })
                .get("/events", req -> WebResponse.sse(stream -> stream.send("one")));

        assertEquals(List.of(new Route("GET", "/events")), app.routes());

        WebTest.test(app, client -> assertEquals("data: one\n\n", client.get("/events").body()));

        assertEquals(List.of("before /events"), filtered);
        assertEquals(List.of(200), logged);
    }

    /** A stream with the body thrown away would never end, so HEAD stops at the headers. */
    @Test
    void headAnswersWithTheHeadersAndNeverOpensAStream() {
        AtomicBoolean opened = new AtomicBoolean();
        App app = new App().get("/events", req -> WebResponse.sse(stream -> {
            opened.set(true);
            stream.send("never sent");
        }));

        WebTest.test(app, client -> {
            var response = client.head("/events");

            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElseThrow()
                    .startsWith("text/event-stream"));
            assertEquals("", response.body());
        });

        assertFalse(opened.get(), "HEAD must not run the stream handler");
    }

    /**
     * The registry earning its keep: an open stream is a request in flight that
     * never finishes on its own, so a graceful stop would wait out its whole
     * five-second timeout and then report a failure to drain.
     */
    @Test
    void stoppingTheServerClosesTheStreamsItLeftOpen() throws Exception {
        CountDownLatch handlerEnded = new CountDownLatch(1);
        App app = ticker(handlerEnded);
        app.start(0);
        try (Socket socket = new Socket("localhost", app.port())) {
            socket.setSoTimeout(5_000);
            requestEvents(socket, app.port());
            readUntil(socket, "data: tick");

            long startedAt = System.nanoTime();
            app.stop();
            long millis = (System.nanoTime() - startedAt) / 1_000_000;

            assertTrue(handlerEnded.await(1, TimeUnit.SECONDS), "the handler should have ended");
            assertTrue(app.openStreams.isEmpty(), "the registry should be empty");
            assertTrue(millis < 3_000, "stopping took " + millis + "ms");
        } finally {
            app.stop();
        }
    }

    /** A client that navigated away is not an error: the next write ends the handler. */
    @Test
    void aClientThatDisconnectsEndsTheHandler() throws Exception {
        CountDownLatch handlerEnded = new CountDownLatch(1);
        App app = ticker(handlerEnded);
        app.start(0);
        try {
            Socket socket = new Socket("localhost", app.port());
            socket.setSoTimeout(5_000);
            requestEvents(socket, app.port());
            readUntil(socket, "data: tick");
            socket.close();

            assertTrue(handlerEnded.await(5, TimeUnit.SECONDS), "the handler should have ended");
            assertTrue(app.openStreams.isEmpty(), "the registry should be empty");
        } finally {
            app.stop();
        }
    }

    /** An endless stream, which only a closed connection or a stopped server ends. */
    private App ticker(CountDownLatch handlerEnded) {
        return new App().get("/events", req -> WebResponse.sse(stream -> {
            try {
                while (stream.isOpen()) {
                    stream.send("tick");
                    Thread.sleep(20);
                }
            } finally {
                handlerEnded.countDown();
            }
        }));
    }

    private void requestEvents(Socket socket, int port) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(("GET /events HTTP/1.1\r\nHost: localhost:" + port + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void readUntil(Socket socket, String marker) throws IOException {
        InputStream in = socket.getInputStream();
        StringBuilder received = new StringBuilder();
        byte[] buffer = new byte[256];
        while (!received.toString().contains(marker)) {
            int read = in.read(buffer);
            if (read < 0) {
                throw new IOException("Stream ended before \"" + marker + "\": " + received);
            }
            received.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
    }
}
