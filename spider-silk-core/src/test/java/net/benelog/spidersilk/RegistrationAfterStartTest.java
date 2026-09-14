package net.benelog.spidersilk;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.server.JettyServer;
import net.benelog.spidersilk.test.WebTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An App is registered before an AppServlet serves it, however the servlet
 * came to be started, and what it serves cannot be changed from outside.
 */
class RegistrationAfterStartTest {

    private final App app = new App().get("/", req -> WebResponse.text("ok"));

    @AfterEach
    void stop() {
        app.stop();
    }

    @Test
    void registeringOnARunningAppIsRefused() {
        app.start(0);

        assertThatIllegalStateException()
                .isThrownBy(() -> app.get("/late", req -> WebResponse.text("late")))
                .withMessageContaining("register before start()");
        assertThatIllegalStateException()
                .isThrownBy(() -> app.path("/api", api -> api.post("/x", req -> WebResponse.empty())));
        assertThatIllegalStateException()
                .isThrownBy(() -> app.beforeRoute(req -> null));
        assertThatIllegalStateException()
                .isThrownBy(() -> app.beforeRequest(req -> null));
        assertThatIllegalStateException()
                .isThrownBy(() -> app.afterRoute((req, res) -> res));
        assertThatIllegalStateException()
                .isThrownBy(() -> app.exception(Exception.class, (req, e) -> WebResponse.empty()));
        assertThatIllegalStateException()
                .isThrownBy(() -> app.error(HttpStatus.NOT_FOUND, req -> WebResponse.text("gone")));
        assertThatIllegalStateException()
                .isThrownBy(() -> app.gzip());
        assertThatIllegalStateException()
                .isThrownBy(() -> app.staticFiles("/assets"));
    }

    @Test
    void stoppingOpensRegistrationAgain() {
        app.start(0);
        app.stop();

        assertThatCode(() -> app.get("/late", req -> WebResponse.text("late")))
                .doesNotThrowAnyException();
    }

    /** A route added between two starts is served by the second one. */
    @Test
    void aRestartServesWhatWasRegisteredWhileStopped() throws Exception {
        app.start(0);
        app.stop();
        app.get("/late", req -> WebResponse.text("late"));
        app.start(0);

        assertThat(get(app.port(), "/late").body()).isEqualTo("late");
    }

    /** No App.start at all: the servlet closes registration whoever started the server. */
    @Test
    void aServerStartedDirectlyClosesRegistrationToo() {
        JettyServer server = new JettyServer(app).port(0);
        server.start();
        try {
            assertThatIllegalStateException()
                    .isThrownBy(() -> app.get("/late", req -> WebResponse.text("late")))
                    .withMessageContaining("register before AppServlet is initialized");
        } finally {
            server.stop();
        }

        assertThatCode(() -> app.get("/late", req -> WebResponse.text("late")))
                .doesNotThrowAnyException();
    }

    /** An external container calls init and destroy, and nothing else of the framework's. */
    @Test
    void aServletContainerClosesRegistrationFromInitToDestroy() throws Exception {
        AppServlet servlet = new AppServlet(app);

        assertThatCode(() -> app.get("/before-init", req -> WebResponse.empty()))
                .doesNotThrowAnyException();

        servlet.init(new NamedConfig());
        assertThatIllegalStateException()
                .isThrownBy(() -> app.get("/late", req -> WebResponse.empty()));

        servlet.destroy();
        assertThatCode(() -> app.get("/late", req -> WebResponse.empty()))
                .doesNotThrowAnyException();
    }

    /** Two servlets over one App: registration reopens only once both are gone. */
    @Test
    void registrationStaysClosedUntilTheLastServletIsDestroyed() throws Exception {
        AppServlet first = new AppServlet(app);
        AppServlet second = new AppServlet(app);
        first.init(new NamedConfig());
        second.init(new NamedConfig());

        first.destroy();
        first.destroy();
        assertThatIllegalStateException()
                .isThrownBy(() -> app.get("/late", req -> WebResponse.empty()));

        second.destroy();
        assertThatCode(() -> app.get("/late", req -> WebResponse.empty()))
                .doesNotThrowAnyException();
    }

    /**
     * A Jetty assembled by hand, as the deployment chapter shows, with the
     * servlet mapped the way a container maps one: the route table it serves is
     * the one registered before it started.
     */
    @Test
    void anExternallyMappedServletServesWhatWasRegisteredBeforeItStarted() throws Exception {
        Server jetty = new Server();
        ServerConnector connector = new ServerConnector(jetty);
        connector.setPort(0);
        jetty.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        ServletHolder holder = new ServletHolder(new AppServlet(app));
        holder.setInitOrder(0);
        context.addServlet(holder, "/*");
        jetty.setHandler(context);
        jetty.start();
        try {
            assertThat(get(connector.getLocalPort(), "/").body()).isEqualTo("ok");
            assertThatIllegalStateException()
                    .isThrownBy(() -> app.get("/late", req -> WebResponse.text("late")))
                    .withMessageContaining("register before AppServlet is initialized");
        } finally {
            jetty.stop();
        }

        assertThatCode(() -> app.get("/late", req -> WebResponse.text("late")))
                .doesNotThrowAnyException();
    }

    /** A start that failed never served anything, so registration is still open after it. */
    @Test
    void aFailedStartLeavesRegistrationOpen() throws Exception {
        try (ServerSocket taken = new ServerSocket(0)) {
            assertThatThrownBy(() -> app.start(taken.getLocalPort()))
                    .isInstanceOf(IllegalStateException.class);
        }

        assertThatCode(() -> app.get("/late", req -> WebResponse.text("late")))
                .doesNotThrowAnyException();
    }

    /**
     * Stopping drains the requests in flight before it destroys the servlet, and
     * registration stays closed until then: a route added while a request is
     * still being answered would change a table that request is reading.
     */
    @Test
    void registrationStaysClosedWhileRequestsDrain() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        app.get("/slow", req -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return WebResponse.text("done");
        });
        app.start(0);
        int port = app.port();

        CompletableFuture<HttpResponse<String>> slow = CompletableFuture.supplyAsync(() -> get(port, "/slow"));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> stopping = CompletableFuture.runAsync(app::stop);
        awaitStopBegun();

        assertThatIllegalStateException()
                .isThrownBy(() -> app.get("/late", req -> WebResponse.text("late")));

        release.countDown();
        assertThat(slow.get(5, TimeUnit.SECONDS).body()).isEqualTo("done");
        stopping.get(10, TimeUnit.SECONDS);
        assertThatCode(() -> app.get("/late", req -> WebResponse.text("late")))
                .doesNotThrowAnyException();
    }

    // ---- A setting object is copied when it is registered ----

    @Test
    void aGzipChangedAfterRegistrationChangesNothing() {
        Gzip gzip = Gzip.defaults();
        App small = new App().gzip(gzip).get("/", req -> WebResponse.text("short"));
        gzip.minBytes(0);

        WebTest.test(small, client -> assertThat(client.send(builder -> builder
                        .uri(URI.create(client.url("/")))
                        .header("Accept-Encoding", "gzip")).headers().firstValue("Content-Encoding"))
                .isEmpty());
    }

    @Test
    void aCorsChangedAfterRegistrationChangesNothing() {
        Cors cors = Cors.allowOrigin("https://app.example.com");
        App api = new App().cors(cors).get("/", req -> WebResponse.text("ok"));
        cors.allowCredentials().exposeHeaders("X-Secret");

        WebTest.test(api, client -> {
            var headers = client.send(builder -> builder
                    .uri(URI.create(client.url("/")))
                    .header("Origin", "https://app.example.com")).headers();
            assertThat(headers.firstValue("Access-Control-Allow-Origin")).hasValue("https://app.example.com");
            assertThat(headers.firstValue("Access-Control-Allow-Credentials")).isEmpty();
            assertThat(headers.firstValue("Access-Control-Expose-Headers")).isEmpty();
        });
    }

    @Test
    void securityHeadersChangedAfterRegistrationChangeNothing() {
        SecurityHeaders headers = SecurityHeaders.defaults();
        App site = new App().securityHeaders(headers).get("/", req -> WebResponse.text("ok"));
        headers.frameOptions("SAMEORIGIN").header("X-Extra", "1");

        WebTest.test(site, client -> {
            var sent = client.get("/").headers();
            assertThat(sent.firstValue("X-Frame-Options")).hasValue("DENY");
            assertThat(sent.firstValue("X-Extra")).isEmpty();
        });
    }

    @Test
    void staticFilesChangedAfterRegistrationChangeNothing() {
        StaticFiles files = new StaticFiles("/public");
        App site = new App().staticFiles(files);
        files.hostedPath("/elsewhere").cacheControl("private");

        WebTest.test(site, client -> {
            var response = client.get("/style.css");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-cache");
        });
    }

    // ---- Helpers ----

    /** {@code App.stop} clears the running server before it asks the server to drain. */
    private void awaitStopBegun() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try {
                app.server();
            } catch (IllegalStateException stopped) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("App.stop() never began");
    }

    private static HttpResponse<String> get(int port, String path) {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** What a container hands {@code init}: a name, and nothing this servlet reads. */
    private static final class NamedConfig implements ServletConfig {

        @Override
        public String getServletName() {
            return "app";
        }

        @Override
        public ServletContext getServletContext() {
            return null;
        }

        @Override
        public String getInitParameter(String name) {
            return null;
        }

        @Override
        public Enumeration<String> getInitParameterNames() {
            return Collections.emptyEnumeration();
        }
    }
}
