package spidersilk.tomcat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import spidersilk.App;
import spidersilk.WebResponse;

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

        assertEquals(200, response.statusCode());
        assertEquals("Hello spider", response.body());
    }

    @Test
    void pickedPortIsReadable() {
        startOnTomcat(new App());

        assertTrue(app.port() > 0, "port 0 should be replaced by the bound port");
    }

    @Test
    void unmatchedPathFallsBackToNotFound() throws Exception {
        startOnTomcat(new App());

        assertEquals(404, get("/nope").statusCode());
    }

    /** Sessions come with Tomcat's context, so flash works with no configuration. */
    @Test
    void sessionsWork() throws Exception {
        startOnTomcat(new App().get("/flash", req -> {
            req.flash("message", "saved");
            return WebResponse.text("ok");
        }));

        assertEquals(200, get("/flash").statusCode());
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

        assertEquals(200, response.statusCode());
        assertEquals("front,back", response.body());
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

        assertEquals(200, response.statusCode());
        // Tomcat spells the charset "UTF-8" where Jetty writes "utf-8".
        assertEquals("text/event-stream;charset=utf-8",
                response.headers().firstValue("Content-Type").orElse("")
                        .replace(" ", "").toLowerCase(Locale.ROOT));
        assertTrue(response.body().contains("event: tick"), response.body());
        assertTrue(response.body().contains("data: 1"), response.body());
        assertTrue(response.body().contains("data: 2"), response.body());
    }

    /** Static files are read off the classpath by core, so they travel too. */
    @Test
    void staticFilesAreServed() throws Exception {
        startOnTomcat(new App());

        HttpResponse<String> response = get("/hello.txt");

        assertEquals(200, response.statusCode());
        assertEquals("a static file", response.body().strip());
    }

    @Test
    void aContextPathMountsTheAppUnderIt() throws Exception {
        server = new TomcatServer(new App().get("/", req -> WebResponse.text("ok")))
                .port(0)
                .contextPath("/app");
        server.start();

        assertEquals(200, getFrom(server.port(), "/app/").statusCode());
        assertEquals(404, getFrom(server.port(), "/").statusCode());
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

        assertEquals(200, response.statusCode());
        assertEquals("silk", response.headers().firstValue("Server").orElse(null),
                "the Connector customizer should have set the Server header");
        assertSame(server.tomcat(), customizedTomcat.get(),
                "the Tomcat customizer should have run against the real Tomcat");
        assertEquals("", customizedContext.get().getPath(),
                "the Context customizer should have run against the mounted context");
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
        assertTrue(handlerEntered.await(2, TimeUnit.SECONDS), "the handler never started");

        app.stop();
        app = null;

        HttpResponse<String> response = inFlight.get(5, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertEquals("finished", response.body());
    }

    /** An idle keep-alive connection must not hold the stop timeout hostage. */
    @Test
    void anIdleConnectionDoesNotDelayTheStop() throws Exception {
        startOnTomcat(new App().get("/", req -> WebResponse.text("ok")));
        assertEquals(200, get("/").statusCode());   // leaves a keep-alive connection behind

        long startedAt = System.nanoTime();
        app.stop();
        app = null;

        long millis = (System.nanoTime() - startedAt) / 1_000_000;
        assertTrue(millis < 2_000, "stop took " + millis + "ms with only an idle connection open");
    }

    /** A suite that starts a server per test would rather not wait for the drain. */
    @Test
    void aZeroStopTimeoutStopsImmediately() throws Exception {
        server = new TomcatServer(new App().get("/", req -> WebResponse.text("ok")))
                .port(0)
                .stopTimeout(Duration.ZERO);
        server.start();
        assertEquals(200, getFrom(server.port(), "/").statusCode());

        long startedAt = System.nanoTime();
        server.stop();
        server = null;

        long millis = (System.nanoTime() - startedAt) / 1_000_000;
        assertTrue(millis < 2_000, "an immediate stop took " + millis + "ms");
    }

    /** Ctrl-C stops the server, and the hook goes away again with it. */
    @Test
    void theShutdownHookIsRemovedOnStop() {
        server = new TomcatServer(new App()).port(0);
        server.start();
        Thread registered = server.shutdownHookThread();
        assertNotNull(registered, "a shutdown hook should be registered by default");

        server.stop();
        server = null;

        assertFalse(Runtime.getRuntime().removeShutdownHook(registered),
                "starting a server per test must not accumulate shutdown hooks");
    }

    @Test
    void theShutdownHookCanBeTurnedOff() {
        server = new TomcatServer(new App()).port(0).shutdownHook(false);
        server.start();

        assertNull(server.shutdownHookThread());
    }

    /** The default base directory is temporary, so nothing lands next to the build. */
    @Test
    void nothingIsWrittenToTheWorkingDirectory() {
        server = new TomcatServer(new App()).port(0);
        server.start();

        String base = server.tomcat().getServer().getCatalinaBase().getAbsolutePath();
        assertTrue(base.startsWith(System.getProperty("java.io.tmpdir")),
                "the base directory should be a temporary one, but was " + base);
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

        assertEquals("virtual", get("/").body());
    }

    @Test
    void theRunningTomcatIsReachable() {
        server = new TomcatServer(new App()).port(0);
        server.start();

        assertNotNull(server.tomcat(), "tomcat() should hand out the running server");
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
