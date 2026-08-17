package spidersilk.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import spidersilk.App;

class JettyServerTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private App app;

    @AfterEach
    void stopApp() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void servesRoutesOverHttp() throws Exception {
        app = new App()
                .get("/hello/{name}", ctx -> ctx.text("Hello " + ctx.pathParam("name")))
                .start(0);

        HttpResponse<String> response = get("/hello/spider");

        assertEquals(200, response.statusCode());
        assertEquals("Hello spider", response.body());
    }

    @Test
    void pickedPortIsReadable() {
        app = new App().start(0);

        assertTrue(app.port() > 0, "port 0 should be replaced by the bound port");
    }

    @Test
    void unmatchedPathFallsBackToNotFound() throws Exception {
        app = new App().start(0);

        assertEquals(404, get("/nope").statusCode());
    }

    /** Sessions are on by default, so flash works without any extra configuration. */
    @Test
    void sessionsAreEnabledByDefault() throws Exception {
        app = new App()
                .get("/flash", ctx -> {
                    ctx.flash("message", "saved");
                    ctx.text("ok");
                })
                .start(0);

        assertEquals(200, get("/flash").statusCode());
    }

    /** ctx.file(...) needs a MultipartConfig on the servlet; the default supplies one. */
    @Test
    void multipartUploadsWorkOutOfTheBox() throws Exception {
        app = new App()
                .post("/upload", ctx -> ctx.text(ctx.file("csv").asText()))
                .start(0);

        String boundary = "spidersilkboundary";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"csv\"; filename=\"deck.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n"
                + "front,back\r\n"
                + "--" + boundary + "--\r\n";
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + app.port() + "/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("front,back", response.body());
    }

    @Test
    void customizersReachTheRealJettyObjects() throws Exception {
        JettyServer jetty = new JettyServer(new App().get("/", ctx -> ctx.text("ok")))
                .port(0)
                .customizeHttpConfiguration(http -> http.setSendServerVersion(false))
                .customizeServer(server -> server.setAttribute("customized", true));
        jetty.start();
        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + jetty.port() + "/"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Server").isEmpty(),
                    "Server header should be suppressed by the HttpConfiguration customizer");
            assertEquals(true, jetty.jetty().getAttribute("customized"),
                    "the Server customizer should have run against the real Server");
        } finally {
            jetty.stop();
        }
    }

    /** Graceful shutdown: a request already running is finished, not dropped. */
    @Test
    void stopWaitsForARequestInFlight() throws Exception {
        CountDownLatch handlerEntered = new CountDownLatch(1);
        app = new App().get("/slow", ctx -> {
            handlerEntered.countDown();
            Thread.sleep(300);
            ctx.text("finished");
        }).start(0);

        String url = "http://localhost:" + app.port() + "/slow";
        CompletableFuture<HttpResponse<String>> inFlight = client.sendAsync(
                HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(handlerEntered.await(2, TimeUnit.SECONDS), "the handler never started");

        app.stop();

        HttpResponse<String> response = inFlight.get(2, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertEquals("finished", response.body());
    }

    @Test
    void gracefulShutdownIsOnByDefault() {
        JettyServer jetty = new JettyServer(new App()).port(0);
        jetty.start();
        try {
            assertEquals(JettyServer.DEFAULT_STOP_TIMEOUT.toMillis(),
                    jetty.jetty().getStopTimeout());
        } finally {
            jetty.stop();
        }
    }

    /** A suite that starts a server per test would rather not wait for the drain. */
    @Test
    void aZeroStopTimeoutTurnsGracefulShutdownOff() {
        JettyServer jetty = new JettyServer(new App()).port(0).stopTimeout(Duration.ZERO);
        jetty.start();
        try {
            assertEquals(0, jetty.jetty().getStopTimeout());
        } finally {
            jetty.stop();
        }
    }

    /** Customizers run last, so they can still overrule a setting method. */
    @Test
    void customizeServerOverrulesTheStopTimeout() {
        JettyServer jetty = new JettyServer(new App())
                .port(0)
                .stopTimeout(Duration.ofSeconds(2))
                .customizeServer(server -> server.setStopTimeout(7_000));
        jetty.start();
        try {
            assertEquals(7_000, jetty.jetty().getStopTimeout());
        } finally {
            jetty.stop();
        }
    }

    /** Ctrl-C stops the server: Jetty's own hook, one per JVM rather than one per server. */
    @Test
    void aShutdownHookIsRegisteredByDefault() {
        JettyServer jetty = new JettyServer(new App()).port(0);
        jetty.start();
        try {
            assertTrue(jetty.jetty().getStopAtShutdown());
        } finally {
            jetty.stop();
        }
    }

    @Test
    void theShutdownHookCanBeTurnedOff() {
        JettyServer jetty = new JettyServer(new App()).port(0).shutdownHook(false);
        jetty.start();
        try {
            assertFalse(jetty.jetty().getStopAtShutdown());
        } finally {
            jetty.stop();
        }
    }

    /** An idle keep-alive connection must not hold the stop timeout hostage. */
    @Test
    void anIdleConnectionDoesNotDelayTheStop() throws Exception {
        app = new App().get("/", ctx -> ctx.text("ok")).start(0);
        assertEquals(200, get("/").statusCode());   // leaves a keep-alive connection behind

        long startedAt = System.nanoTime();
        app.stop();
        app = null;

        long millis = (System.nanoTime() - startedAt) / 1_000_000;
        assertTrue(millis < 1_000, "stop took " + millis + "ms with only an idle connection open");
    }

    @Test
    void anotherServerCanBePluggedIn() {
        RecordingServer recording = new RecordingServer();
        app = new App().server((a, port) -> recording).start(1234);

        assertTrue(recording.started);
        assertEquals(1234, app.port());
    }

    @Test
    void portBeforeStartIsRejected() {
        assertThrows(IllegalStateException.class, () -> new App().port());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + app.port() + path))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static final class RecordingServer implements WebServer {

        boolean started;

        @Override
        public void start() {
            started = true;
        }

        @Override
        public void stop() {
            started = false;
        }

        @Override
        public void join() {
        }

        @Override
        public int port() {
            return 1234;
        }
    }
}
