package spidersilk.undertow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import io.undertow.Undertow;
import io.undertow.servlet.api.DeploymentInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import spidersilk.App;
import spidersilk.WebResponse;

/**
 * The Jetty and Tomcat acceptance tests, run against Undertow. What core
 * promises has to hold on every server, so this file deliberately mirrors
 * {@code JettyServerTest} and {@code TomcatServerTest}.
 */
class UndertowServerTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private App app;
    private UndertowServer server;

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

    private App startOnUndertow(App app) {
        this.app = app.server((a, port) -> new UndertowServer(a).port(port)).start(0);
        return this.app;
    }

    @Test
    void servesRoutesOverHttp() throws Exception {
        startOnUndertow(new App()
                .get("/hello/{name}", req -> WebResponse.text("Hello " + req.pathParam("name"))));

        HttpResponse<String> response = get("/hello/spider");

        assertEquals(200, response.statusCode());
        assertEquals("Hello spider", response.body());
    }

    @Test
    void pickedPortIsReadable() {
        startOnUndertow(new App());

        assertTrue(app.port() > 0, "port 0 should be replaced by the bound port");
    }

    @Test
    void unmatchedPathFallsBackToNotFound() throws Exception {
        startOnUndertow(new App());

        assertEquals(404, get("/nope").statusCode());
    }

    /** Sessions come with Undertow's deployment, so flash works with no configuration. */
    @Test
    void sessionsWork() throws Exception {
        startOnUndertow(new App().get("/flash", req -> {
            req.flash("message", "saved");
            return WebResponse.text("ok");
        }));

        assertEquals(200, get("/flash").statusCode());
    }

    /** req.file(...) needs a MultipartConfig on the servlet; the default supplies one. */
    @Test
    void multipartUploadsWorkOutOfTheBox() throws Exception {
        startOnUndertow(new App()
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

    /** SSE is framing over the servlet response, so it travels to a third server too. */
    @Test
    void serverSentEventsStreamOverUndertow() throws Exception {
        startOnUndertow(new App().get("/events", req -> WebResponse.sse(stream -> {
            stream.send("tick", "1");
            stream.send("tick", "2");
            stream.close();
        })));

        HttpResponse<String> response = get("/events");

        assertEquals(200, response.statusCode());
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
        startOnUndertow(new App());

        HttpResponse<String> response = get("/hello.txt");

        assertEquals(200, response.statusCode());
        assertEquals("a static file", response.body().strip());
    }

    @Test
    void aContextPathMountsTheAppUnderIt() throws Exception {
        server = new UndertowServer(new App().get("/", req -> WebResponse.text("ok")))
                .port(0)
                .contextPath("/app");
        server.start();

        assertEquals(200, getFrom(server.port(), "/app/").statusCode());
        assertEquals(404, getFrom(server.port(), "/").statusCode());
    }

    @Test
    void customizersReachTheRealUndertowObjects() throws Exception {
        AtomicReference<DeploymentInfo> customizedDeployment = new AtomicReference<>();
        AtomicReference<Undertow.Builder> customizedBuilder = new AtomicReference<>();
        server = new UndertowServer(new App().get("/", req -> WebResponse.text("ok")))
                .port(0)
                .customizeDeployment(customizedDeployment::set)
                .customizeBuilder(builder -> {
                    customizedBuilder.set(builder);
                    builder.setServerOption(io.undertow.UndertowOptions.ALWAYS_SET_DATE, false);
                });
        server.start();

        HttpResponse<String> response = getFrom(server.port(), "/");

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Date").isEmpty(),
                "the Builder customizer should have suppressed the Date header");
        assertNotNull(customizedBuilder.get(), "the Builder customizer should have run");
        assertEquals("/", customizedDeployment.get().getContextPath(),
                "the Deployment customizer should have run against the real deployment");
    }

    /** Graceful shutdown: a request already running is finished, not dropped. */
    @Test
    void stopWaitsForARequestInFlight() throws Exception {
        CountDownLatch handlerEntered = new CountDownLatch(1);
        startOnUndertow(new App().get("/slow", req -> {
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
        startOnUndertow(new App().get("/", req -> WebResponse.text("ok")));
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
        server = new UndertowServer(new App().get("/", req -> WebResponse.text("ok")))
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
        server = new UndertowServer(new App()).port(0);
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
        server = new UndertowServer(new App()).port(0).shutdownHook(false);
        server.start();

        assertNull(server.shutdownHookThread());
    }

    /**
     * The virtual-thread recipe. Unlike on Tomcat, this does not disable the
     * drain — Undertow counts requests rather than shutting a pool down.
     */
    @Test
    void handlersCanRunOnVirtualThreads() throws Exception {
        this.app = new App()
                .get("/",
                        req -> WebResponse.text(
                                Thread.currentThread().isVirtual() ? "virtual" : "platform"))
                .server((a, port) -> new UndertowServer(a)
                        .port(port)
                        .executor(Executors.newVirtualThreadPerTaskExecutor()))
                .start(0);

        assertEquals("virtual", get("/").body());
    }

    /** Two servers in one JVM: the deployment name must not collide. */
    @Test
    void twoServersCanRunAtOnce() throws Exception {
        server = new UndertowServer(new App().get("/", req -> WebResponse.text("first")))
                .port(0);
        server.start();
        UndertowServer second =
                new UndertowServer(new App().get("/", req -> WebResponse.text("second"))).port(0);
        second.start();
        try {
            assertEquals("first", getFrom(server.port(), "/").body());
            assertEquals("second", getFrom(second.port(), "/").body());
        } finally {
            second.stop();
        }
    }

    @Test
    void theRunningUndertowIsReachable() {
        server = new UndertowServer(new App()).port(0);
        server.start();

        assertNotNull(server.undertow(), "undertow() should hand out the running server");
        assertFalse(server.undertow().getListenerInfo().isEmpty(),
                "the running server should have a listener bound");
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
