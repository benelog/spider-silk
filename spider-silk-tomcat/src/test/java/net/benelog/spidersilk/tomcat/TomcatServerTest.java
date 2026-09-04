package net.benelog.spidersilk.tomcat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.benelog.spidersilk.App;
import net.benelog.spidersilk.StaticFiles;
import net.benelog.spidersilk.WebResponse;

/**
 * The Jetty acceptance tests, run against Tomcat. What core promises has to
 * hold on either server, so this file deliberately mirrors {@code JettyServerTest}.
 */
class TomcatServerTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private App app;
    private TomcatServer server;

    @AfterEach
    void stopApp() {
        if (app != null) {
            app.stop();
            app = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private App startOnTomcat(App app) {
        this.app = app.server((a, port) -> new TomcatServer(a).port(port)).start(0);
        return this.app;
    }

    @Test
    void servesRoutesOverHttp() throws Exception {
        startOnTomcat(new App()
                .get("/hello/{name}", req -> WebResponse.text("Hello " + req.pathParam("name"))));

        HttpResponse<String> response = get("/hello/spider");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("Hello spider");
    }

    @Test
    void pickedPortIsReadable() {
        startOnTomcat(new App());

        assertThat(app.port()).as("port 0 should be replaced by the bound port").isPositive();
    }

    @Test
    void unmatchedPathFallsBackToNotFound() throws Exception {
        startOnTomcat(new App());

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
        startOnTomcat(new App().gzip()
                .get("/export", req -> WebResponse.stream("text/csv; charset=UTF-8",
                        out -> out.write(csv.getBytes(StandardCharsets.UTF_8)))));

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

    /** Sessions come with Tomcat's context, so flash works with no configuration. */
    @Test
    void sessionsWork() throws Exception {
        startOnTomcat(new App().get("/flash", req -> {
            req.flash("message", "saved");
            return WebResponse.text("ok");
        }));

        assertThat(get("/flash").statusCode()).isEqualTo(200);
    }

    /** req.file(...) needs a MultipartConfig on the servlet; the default supplies one. */
    @Test
    void multipartUploadsWorkOutOfTheBox() throws Exception {
        startOnTomcat(new App()
                .post("/upload", req -> WebResponse.text(req.file("csv").asText())));

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

    /** writeTo and files(name) are core's promises about an upload, on this container. */
    @Test
    void anUploadIsWrittenToDiskAndOneFieldCarriesSeveral(@TempDir Path dir) throws Exception {
        Path saved = dir.resolve("saved.csv");
        startOnTomcat(new App()
                .post("/upload", req -> {
                    req.files("csv").get(1).writeTo(saved);
                    return WebResponse.text(
                            req.files("csv").size() + " " + req.file("csv").fileName());
                }));

        HttpResponse<String> response = client.send(upload(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.body()).isEqualTo("2 one.csv");
        assertThat(Files.readString(saved)).isEqualTo("hola,hello");
    }

    /** Two files under one field name, the shape {@code files("csv")} reads. */
    private HttpRequest upload() {
        String boundary = "spidersilkboundary";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"csv\"; filename=\"one.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n"
                + "front,back\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"csv\"; filename=\"two.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n"
                + "hola,hello\r\n"
                + "--" + boundary + "--\r\n";
        return HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    /**
     * SSE is framing over the servlet response and nothing else, which is the
     * reason core writes it that way. This is that claim, checked on the other
     * server: one thread per open stream, flushed as it goes.
     */
    @Test
    void serverSentEventsStreamOverTomcat() throws Exception {
        startOnTomcat(new App().get("/events", req -> WebResponse.sse(stream -> {
            stream.send("tick", "1");
            stream.send("tick", "2");
            stream.close();
        })));

        HttpResponse<String> response = get("/events");

        assertThat(response.statusCode()).isEqualTo(200);
        // Tomcat spells the charset "UTF-8" where Jetty writes "utf-8".
        assertThat(response.headers().firstValue("Content-Type").orElse("").replace(" ", "").toLowerCase(Locale.ROOT))
                .isEqualTo("text/event-stream;charset=utf-8");
        assertThat(response.body()).contains("event: tick");
        assertThat(response.body()).contains("data: 1");
        assertThat(response.body()).contains("data: 2");
    }

    /** Static files are read off the classpath by core, so they travel too. */
    @Test
    void staticFilesAreServed() throws Exception {
        startOnTomcat(new App());

        HttpResponse<String> response = get("/hello.txt");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body().strip()).isEqualTo("a static file");
    }

    /** A directory root travels too, and so does the guard that keeps it enclosed. */
    @Test
    void aDirectoryRootIsServedAndCannotBeWalkedOutOf(@TempDir Path parent) throws Exception {
        Path root = Files.createDirectory(parent.resolve("uploads"));
        Files.writeString(root.resolve("avatar.txt"), "an upload\n");
        Files.writeString(parent.resolve("secret.txt"), "not yours\n");
        startOnTomcat(new App().staticFiles(StaticFiles.directory(root)));

        assertThat(get("/avatar.txt").body().strip()).isEqualTo("an upload");
        assertThat(get("/missing.txt").statusCode()).isEqualTo(404);

        HttpResponse<String> traversal = get("/../secret.txt");
        assertThat(traversal.statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(traversal.body()).doesNotContain("not yours");
    }

    @Test
    void aContextPathMountsTheAppUnderIt() throws Exception {
        server = new TomcatServer(new App().get("/", req -> WebResponse.text("ok")))
                .port(0)
                .contextPath("/app");
        server.start();

        assertThat(getFrom(server.port(), "/app/").statusCode()).isEqualTo(200);
        assertThat(getFrom(server.port(), "/").statusCode()).isEqualTo(404);
    }

    @Test
    void customizersReachTheRealTomcatObjects() throws Exception {
        AtomicReference<Tomcat> customizedTomcat = new AtomicReference<>();
        AtomicReference<Context> customizedContext = new AtomicReference<>();
        server = new TomcatServer(new App().get("/", req -> WebResponse.text("ok")))
                .port(0)
                .customizeConnector(connector -> connector.setProperty("server", "silk"))
                .customizeContext(customizedContext::set)
                .customizeTomcat(customizedTomcat::set);
        server.start();

        HttpResponse<String> response = getFrom(server.port(), "/");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Server").orElse(null))
                .as("the Connector customizer should have set the Server header")
                .isEqualTo("silk");
        assertThat(customizedTomcat.get())
                .as("the Tomcat customizer should have run against the real Tomcat")
                .isSameAs(server.tomcat());
        assertThat(customizedContext.get().getPath())
                .as("the Context customizer should have run against the mounted context")
                .isEqualTo("");
    }

    /** Graceful shutdown: a request already running is finished, not dropped. */
    @Test
    void stopWaitsForARequestInFlight() throws Exception {
        CountDownLatch handlerEntered = new CountDownLatch(1);
        startOnTomcat(new App().get("/slow", req -> {
            handlerEntered.countDown();
            Thread.sleep(300);
            return WebResponse.text("finished");
        }));

        String url = "http://localhost:" + app.port() + "/slow";
        CompletableFuture<HttpResponse<String>> inFlight = client.sendAsync(
                HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(handlerEntered.await(2, TimeUnit.SECONDS))
                .as("the handler never started")
                .isTrue();

        app.stop();
        app = null;

        HttpResponse<String> response = inFlight.get(5, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("finished");
    }

    /** An idle keep-alive connection must not hold the stop timeout hostage. */
    @Test
    void anIdleConnectionDoesNotDelayTheStop() throws Exception {
        startOnTomcat(new App().get("/", req -> WebResponse.text("ok")));
        assertThat(get("/").statusCode()).isEqualTo(200);   // leaves a keep-alive connection behind

        long startedAt = System.nanoTime();
        app.stop();
        app = null;

        long millis = (System.nanoTime() - startedAt) / 1_000_000;
        assertThat(millis)
                .as("stopping with only an idle connection open")
                .isLessThan(2_000);
    }

    /** A suite that starts a server per test would rather not wait for the drain. */
    @Test
    void aZeroStopTimeoutStopsImmediately() throws Exception {
        server = new TomcatServer(new App().get("/", req -> WebResponse.text("ok")))
                .port(0)
                .stopTimeout(Duration.ZERO);
        server.start();
        assertThat(getFrom(server.port(), "/").statusCode()).isEqualTo(200);

        long startedAt = System.nanoTime();
        server.stop();
        server = null;

        long millis = (System.nanoTime() - startedAt) / 1_000_000;
        assertThat(millis).isLessThan(2_000);
    }

    /** Ctrl-C stops the server, and the hook goes away again with it. */
    @Test
    void theShutdownHookIsRemovedOnStop() {
        server = new TomcatServer(new App()).port(0);
        server.start();
        Thread registered = server.shutdownHookThread();
        assertThat(registered).as("a shutdown hook should be registered by default").isNotNull();

        server.stop();
        server = null;

        assertThat(Runtime.getRuntime().removeShutdownHook(registered))
                .as("starting a server per test must not accumulate shutdown hooks")
                .isFalse();
    }

    @Test
    void theShutdownHookCanBeTurnedOff() {
        server = new TomcatServer(new App()).port(0).shutdownHook(false);
        server.start();

        assertThat(server.shutdownHookThread()).isNull();
    }

    /** The default base directory is temporary, so nothing lands next to the build. */
    @Test
    void nothingIsWrittenToTheWorkingDirectory() {
        server = new TomcatServer(new App()).port(0);
        server.start();

        String base = server.tomcat().getServer().getCatalinaBase().getAbsolutePath();
        assertThat(base)
                .as("the base directory should be a temporary one, but was " + base)
                .startsWith(System.getProperty("java.io.tmpdir"));
    }

    /** The virtual-thread recipe: an executor, no API of our own. */
    @Test
    void handlersCanRunOnVirtualThreads() throws Exception {
        this.app = new App()
                .get("/",
                        req -> WebResponse.text(
                                Thread.currentThread().isVirtual() ? "virtual" : "platform"))
                .server((a, port) -> new TomcatServer(a)
                        .port(port)
                        .executor(Executors.newVirtualThreadPerTaskExecutor()))
                .start(0);

        assertThat(get("/").body()).isEqualTo("virtual");
    }

    @Test
    void theRunningTomcatIsReachable() {
        server = new TomcatServer(new App()).port(0);
        server.start();

        assertThat(server.tomcat()).as("tomcat() should hand out the running server").isNotNull();
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return getFrom(app.port(), path);
    }

    private HttpResponse<String> getFrom(int port, String path)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + path))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

}
