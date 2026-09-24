package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.server.JettyServer;
import net.benelog.spidersilk.test.WebTest;

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

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                    .startsWith("text/event-stream");
            assertThat(response.headers().firstValue("Cache-Control").orElseThrow())
                    .isEqualTo("no-cache");
            // The id belongs to the event that follows it, and to no later one.
            assertThat(response.body()).isEqualTo("""
                    id: 7
                    event: tick
                    data: first

                    data: line one
                    data: line two

                    : keep-alive

                    """);
        });
    }

    /**
     * An id or an event name is one field line, so a line break in either
     * would write fields the handler never did. It is refused before anything
     * is written, and the stream goes on.
     */
    @Test
    void aLineBreakInAnIdOrAnEventNameIsRefusedAndWritesNothing() {
        List<String> refused = new ArrayList<>();
        App app = new App().get("/events", req -> WebResponse.sse(stream -> {
            List<Runnable> attempts = List.of(
                    () -> stream.id("7\ndata: injected"),
                    () -> stream.id("7\r"),
                    () -> stream.id("7\0"),
                    () -> stream.send("tick\ndata: injected", "real"));
            for (Runnable attempt : attempts) {
                try {
                    attempt.run();
                } catch (IllegalArgumentException e) {
                    refused.add(e.getMessage());
                }
            }
            stream.send("tick", "real");
        }));

        WebTest.test(app, client ->
                assertThat(client.get("/events").body()).isEqualTo("event: tick\ndata: real\n\n"));

        assertThat(refused).hasSize(4);
    }

    /** The reconnection delay is a stream-level setting, so it goes out on its own. */
    @Test
    void retryWritesTheReconnectionDelayInMilliseconds() {
        App app = new App().get("/events", req -> WebResponse.sse(stream -> {
            stream.retry(Duration.ofSeconds(2));
            stream.send("tick", "first");
        }));

        WebTest.test(app, client -> assertThat(client.get("/events").body()).isEqualTo("""
                retry: 2000

                event: tick
                data: first

                """));
    }

    /** A negative delay is a line no browser would apply, so it fails at the call. */
    @Test
    void aNegativeReconnectionDelayIsRejectedAndLeavesTheStreamOpen() {
        AtomicBoolean rejected = new AtomicBoolean();
        App app = new App().get("/events", req -> WebResponse.sse(stream -> {
            try {
                stream.retry(Duration.ofSeconds(-1));
            } catch (IllegalArgumentException e) {
                rejected.set(true);
            }
            stream.send("still open");
        }));

        WebTest.test(app, client ->
                assertThat(client.get("/events").body()).isEqualTo("data: still open\n\n"));

        assertThat(rejected.get()).as("a negative delay should have been rejected").isTrue();
    }

    /** The whole reason SSE is in core and WebSocket is not: it is an ordinary route. */
    @Test
    void anSseEndpointIsAnOrdinaryRoute() {
        List<String> filtered = new ArrayList<>();
        List<Integer> logged = new ArrayList<>();
        App app = new App()
                .requestLogger((req, completion) -> logged.add(completion.statusCode()))
                .beforeRoute("/events", req -> {
                    filtered.add("before " + req.path());
                    return null;
                })
                .get("/events", req -> WebResponse.sse(stream -> stream.send("one")));

        assertThat(app.routes()).isEqualTo(List.of(new Route("GET", "/events")));

        WebTest.test(app, client ->
                assertThat(client.get("/events").body()).isEqualTo("data: one\n\n"));

        assertThat(filtered).isEqualTo(List.of("before /events"));
        assertThat(logged).isEqualTo(List.of(200));
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

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                    .startsWith("text/event-stream");
            assertThat(response.body()).isEmpty();
        });

        assertThat(opened.get()).as("HEAD must not run the stream handler").isFalse();
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

            assertThat(handlerEnded.await(1, TimeUnit.SECONDS))
                    .as("the handler should have ended")
                    .isTrue();
            assertThat(app.openStreams).as("the registry should be empty").isEmpty();
            assertThat(millis).isLessThan(3_000);
        } finally {
            app.stop();
        }
    }

    /**
     * Ctrl-C and SIGTERM stop the server through its own hook, not through
     * {@code app.stop()}. The JVM runs every hook at once, so the application's
     * runs beside Jetty's and closes the stream the drain would otherwise wait
     * out for the whole stop timeout.
     */
    @Test
    void aJvmShutdownClosesTheStreamsBesideTheServersOwnHook() throws Exception {
        CountDownLatch handlerEnded = new CountDownLatch(1);
        App app = ticker(handlerEnded);
        app.start(0);
        try (Socket socket = new Socket("localhost", app.port())) {
            socket.setSoTimeout(5_000);
            requestEvents(socket, app.port());
            readUntil(socket, "data: tick");
            Thread hook = app.shutdownHookThread();
            assertThat(hook).as("a deployed application holds a hook").isNotNull();

            long startedAt = System.nanoTime();
            // Run as a Runnable on another thread: starting the registered hook
            // itself would leave the JVM a started thread to start again at exit.
            CompletableFuture<Void> closing = CompletableFuture.runAsync(hook);
            // What Jetty's setStopAtShutdown hook does: stop the server, with a drain.
            ((JettyServer) app.runningServer()).jetty().stop();
            closing.get(5, TimeUnit.SECONDS);
            long millis = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(handlerEnded.await(1, TimeUnit.SECONDS))
                    .as("the handler should have ended")
                    .isTrue();
            assertThat(millis).as("the drain should not wait out its timeout").isLessThan(3_000);
            assertThat(app.shutdownHookThread())
                    .as("the hook goes when the last deployment does")
                    .isNull();
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

            assertThat(handlerEnded.await(5, TimeUnit.SECONDS))
                    .as("the handler should have ended")
                    .isTrue();
            assertThat(app.openStreams).as("the registry should be empty").isEmpty();
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
