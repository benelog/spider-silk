package net.benelog.spidersilk.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.App;
import net.benelog.spidersilk.WebResponse;

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
                .get("/hello/{name}", req -> WebResponse.text("Hello " + req.pathParam("name")))
                .start(0);

        HttpResponse<String> response = get("/hello/spider");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("Hello spider");
    }

    @Test
    void pickedPortIsReadable() {
        app = new App().start(0);

        assertThat(app.port()).as("port 0 should be replaced by the bound port").isPositive();
    }

    @Test
    void unmatchedPathFallsBackToNotFound() throws Exception {
        app = new App().start(0);

        assertThat(get("/nope").statusCode()).isEqualTo(404);
    }

    /**
     * Compression is a transform over the {@link WebResponse}, not a feature of
     * the container, and the streamed body is where that claim is testable: it
     * wraps the servlet output stream this server handed over rather than
     * writing a byte array that was finished beforehand.
     */
    @Test
    void gzipCompressesAStreamedBodyOverHttp() throws Exception {
        String csv = "front,back\n".repeat(200);
        app = new App().gzip()
                .get("/export", req -> WebResponse.stream("text/csv; charset=UTF-8",
                        out -> out.write(csv.getBytes(StandardCharsets.UTF_8))))
                .start(0);

        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + app.port() + "/export"))
                .header("Accept-Encoding", "gzip")
                .build();
        HttpResponse<byte[]> response =
                client.send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.headers().firstValue("Content-Encoding")).hasValue("gzip");
        assertThat(response.body().length).isLessThan(csv.length());
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(csv);
        }
    }

    /** Sessions are on by default, so flash works without any extra configuration. */
    @Test
    void sessionsAreEnabledByDefault() throws Exception {
        app = new App()
                .get("/flash", req -> {
                    req.flash("message", "saved");
                    return WebResponse.text("ok");
                })
                .start(0);

        assertThat(get("/flash").statusCode()).isEqualTo(200);
    }

    /** req.file(...) needs a MultipartConfig on the servlet; the default supplies one. */
    @Test
    void multipartUploadsWorkOutOfTheBox() throws Exception {
        app = new App()
                .post("/upload", req -> WebResponse.text(req.file("csv").asText()))
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

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("front,back");
    }

    @Test
    void customizersReachTheRealJettyObjects() throws Exception {
        JettyServer jetty = new JettyServer(new App().get("/", req -> WebResponse.text("ok")))
                .port(0)
                .customizeHttpConfiguration(http -> http.setSendServerVersion(false))
                .customizeServer(server -> server.setAttribute("customized", true));
        jetty.start();
        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + jetty.port() + "/"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Server"))
                    .as("Server header should be suppressed by the HttpConfiguration customizer")
                    .isEmpty();
            assertThat(jetty.jetty().getAttribute("customized"))
                    .as("the Server customizer should have run against the real Server")
                    .isEqualTo(true);
        } finally {
            jetty.stop();
        }
    }

    /** Graceful shutdown: a request already running is finished, not dropped. */
    @Test
    void stopWaitsForARequestInFlight() throws Exception {
        CountDownLatch handlerEntered = new CountDownLatch(1);
        app = new App().get("/slow", req -> {
            handlerEntered.countDown();
            Thread.sleep(300);
            return WebResponse.text("finished");
        }).start(0);

        String url = "http://localhost:" + app.port() + "/slow";
        CompletableFuture<HttpResponse<String>> inFlight = client.sendAsync(
                HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(handlerEntered.await(2, TimeUnit.SECONDS))
                .as("the handler never started")
                .isTrue();

        app.stop();

        HttpResponse<String> response = inFlight.get(2, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("finished");
    }

    @Test
    void gracefulShutdownIsOnByDefault() {
        JettyServer jetty = new JettyServer(new App()).port(0);
        jetty.start();
        try {
            assertThat(jetty.jetty().getStopTimeout())
                    .isEqualTo(JettyServer.DEFAULT_STOP_TIMEOUT.toMillis());
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
            assertThat(jetty.jetty().getStopTimeout()).isEqualTo(0);
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
            assertThat(jetty.jetty().getStopTimeout()).isEqualTo(7_000);
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
            assertThat(jetty.jetty().getStopAtShutdown()).isTrue();
        } finally {
            jetty.stop();
        }
    }

    @Test
    void theShutdownHookCanBeTurnedOff() {
        JettyServer jetty = new JettyServer(new App()).port(0).shutdownHook(false);
        jetty.start();
        try {
            assertThat(jetty.jetty().getStopAtShutdown()).isFalse();
        } finally {
            jetty.stop();
        }
    }

    /** An idle keep-alive connection must not hold the stop timeout hostage. */
    @Test
    void anIdleConnectionDoesNotDelayTheStop() throws Exception {
        app = new App().get("/", req -> WebResponse.text("ok")).start(0);
        assertThat(get("/").statusCode()).isEqualTo(200);   // leaves a keep-alive connection behind

        long startedAt = System.nanoTime();
        app.stop();
        app = null;

        long millis = (System.nanoTime() - startedAt) / 1_000_000;
        assertThat(millis)
                .as("stopping with only an idle connection open")
                .isLessThan(1_000);
    }

    /**
     * The virtual-thread recipe: platform threads keep running the selectors,
     * handlers run on virtual ones. No API of our own — just a thread pool.
     */
    @Test
    void handlersCanRunOnVirtualThreads() throws Exception {
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setVirtualThreadsExecutor(VirtualThreads.getDefaultVirtualThreadsExecutor());
        app = new App()
                .get("/",
                        req -> WebResponse.text(
                                Thread.currentThread().isVirtual() ? "virtual" : "platform"))
                .server((a, port) -> new JettyServer(a).port(port).threadPool(threadPool))
                .start(0);

        assertThat(get("/").body()).isEqualTo("virtual");
    }

    @Test
    void anotherServerCanBePluggedIn() {
        RecordingServer recording = new RecordingServer();
        app = new App().server((a, port) -> recording).start(1234);

        assertThat(recording.started).isTrue();
        assertThat(app.port()).isEqualTo(1234);
    }

    @Test
    void portBeforeStartIsRejected() {
        assertThatThrownBy(() -> new App().port()).isInstanceOf(IllegalStateException.class);
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
