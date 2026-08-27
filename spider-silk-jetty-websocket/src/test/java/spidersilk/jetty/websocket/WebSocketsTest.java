package spidersilk.jetty.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import spidersilk.App;
import spidersilk.WebResponse;
import spidersilk.server.JettyServer;

/**
 * The acceptance tests for the module, against the container it is named after.
 * {@code WebTest} cannot reach a socket — an upgrade leaves servlet dispatch —
 * so each test starts a real Jetty on a free port and talks to it with the
 * JDK's own WebSocket client.
 */
@Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class WebSocketsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void aTextMessageMakesItToTheHandlerAndBack() {
        run(new WebSockets().at("/echo", (request, response) -> new EchoSocket()), server -> {
            Collector collector = new Collector();
            WebSocket socket = connect(server, "/echo", collector);

            socket.sendText("hello", true).join();

            assertThat(collector.await(1)).isEqualTo(List.of("hello"));
        });
    }

    @Test
    void aBinaryMessageArrivesWhole() {
        WebSockets sockets = new WebSockets().at("/binary", (request, response) ->
                new WebSocketHandler() {
                    @Override
                    public void onBinary(Session session, ByteBuffer message) {
                        byte[] bytes = new byte[message.remaining()];
                        message.get(bytes);
                        session.sendText(new String(bytes, StandardCharsets.UTF_8), Callback.NOOP);
                    }
                });

        run(sockets, server -> {
            Collector collector = new Collector();
            WebSocket socket = connect(server, "/binary", collector);

            socket.sendBinary(ByteBuffer.wrap("bytes".getBytes(StandardCharsets.UTF_8)), true)
                    .join();

            assertThat(collector.await(1)).isEqualTo(List.of("bytes"));
        });
    }

    /** Two mappings on one server, which is the case a single-mapping shortcut would miss. */
    @Test
    void everyMappedPathGetsItsOwnHandler() {
        WebSockets sockets = new WebSockets()
                .at("/one", (request, response) -> new LabelSocket("one"))
                .at("/two", (request, response) -> new LabelSocket("two"));

        run(sockets, server -> {
            Collector first = new Collector();
            connect(server, "/one", first).sendText("x", true).join();
            Collector second = new Collector();
            connect(server, "/two", second).sendText("x", true).join();

            assertThat(first.await(1)).isEqualTo(List.of("one: x"));
            assertThat(second.await(1)).isEqualTo(List.of("two: x"));
        });
    }

    /** The whole point of inserting the handler ahead of the servlet rather than instead of it. */
    @Test
    void everyOtherPathStillReachesTheApp() {
        App app = new App().get("/hello", req -> WebResponse.text("hi"));
        WebSockets sockets = new WebSockets().at("/echo", (request, response) -> new EchoSocket());

        run(app, sockets, server -> {
            assertThat(http(server, "/hello").body()).isEqualTo("hi");

            Collector collector = new Collector();
            connect(server, "/echo", collector).sendText("hello", true).join();
            assertThat(collector.await(1)).isEqualTo(List.of("hello"));
        });
    }

    /** The other half of the argument that keeps WebSocket out of core. */
    @Test
    void neitherAFilterNorTheRequestLoggerSeesTheUpgrade() {
        List<String> filtered = new ArrayList<>();
        List<String> logged = new ArrayList<>();
        App app = new App()
                .requestLogger((req, res, millis) -> logged.add(req.path()))
                .before("/echo", req -> {
                    filtered.add(req.path());
                    return null;
                });
        WebSockets sockets = new WebSockets().at("/echo", (request, response) -> new EchoSocket());

        run(app, sockets, server -> {
            Collector collector = new Collector();
            connect(server, "/echo", collector).sendText("hello", true).join();

            assertThat(collector.await(1)).isEqualTo(List.of("hello"));
            assertThat(filtered).isEmpty();
            assertThat(logged).isEmpty();
        });
    }

    /** The upgrade request is still an ordinary HTTP request, headers and all. */
    @Test
    void theFactorySeesTheUpgradeRequest() {
        AtomicReference<String> seen = new AtomicReference<>();
        WebSockets sockets = new WebSockets().at("/echo", (request, response) -> {
            seen.set(request.getHeaders().get("X-Token"));
            return new EchoSocket();
        });

        run(sockets, server -> {
            Collector collector = new Collector();
            connect(server, "/echo", collector, builder -> builder.header("X-Token", "abc"))
                    .sendText("hello", true)
                    .join();

            assertThat(collector.await(1)).isEqualTo(List.of("hello"));
            assertThat(seen.get()).isEqualTo("abc");
        });
    }

    /** Jetty leaves a refused upgrade unanswered, so the module answers it rather than hang. */
    @Test
    void aFactoryReturningNullRefusesTheUpgrade() {
        run(new WebSockets().at("/closed", (request, response) -> null), server ->
                assertThatThrownBy(() -> connect(server, "/closed", new Collector()))
                        .rootCause()
                        .hasMessageContaining("403"));
    }

    @Test
    void aRefusalKeepsAStatusTheFactoryChose() {
        WebSockets sockets = new WebSockets().at("/closed", (request, response) -> {
            response.setStatus(401);
            return null;
        });

        run(sockets, server -> assertThatThrownBy(() -> connect(server, "/closed", new Collector()))
                .rootCause()
                .hasMessageContaining("401"));
    }

    @Test
    void closingTheClientEndTellsTheHandler() {
        CountDownLatch closed = new CountDownLatch(1);
        List<Integer> statuses = new CopyOnWriteArrayList<>();
        WebSockets sockets = new WebSockets().at("/echo", (request, response) ->
                new WebSocketHandler() {
                    @Override
                    public void onClose(Session session, int status, String reason) {
                        statuses.add(status);
                        closed.countDown();
                    }
                });

        run(sockets, server -> {
            WebSocket socket = connect(server, "/echo", new Collector());

            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();

            assertThat(awaitLatch(closed)).isTrue();
            assertThat(statuses).isEqualTo(List.of(WebSocket.NORMAL_CLOSURE));
        });
    }

    /** Stopping the server closes what is still open, without waiting out the drain. */
    @Test
    void stoppingTheServerClosesAnOpenSocket() {
        CountDownLatch closed = new CountDownLatch(1);
        WebSockets sockets = new WebSockets().at("/echo", (request, response) ->
                new WebSocketHandler() {
                    @Override
                    public void onClose(Session session, int status, String reason) {
                        closed.countDown();
                    }
                });

        App app = new App();
        JettyServer server = new JettyServer(app).port(0).shutdownHook(false)
                .customizeServer(sockets);
        server.start();
        try {
            connect(server, "/echo", new Collector());
        } finally {
            server.stop();
        }

        assertThat(awaitLatch(closed)).isTrue();
    }

    @Test
    void aPathIsRejectedWhereItIsWritten() {
        assertThatThrownBy(() -> new WebSockets().at("chat", (request, response) -> null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebSockets()
                .at("/chat", (request, response) -> null)
                .at("/chat", (request, response) -> null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/chat");
    }

    /** The customizer is the escape hatch for everything the named methods do not cover. */
    @Test
    void theContainerIsReachableForSettingsWithNoMethodOfTheirOwn() {
        AtomicReference<Duration> idle = new AtomicReference<>();
        WebSockets sockets = new WebSockets()
                .at("/echo", (request, response) -> new EchoSocket())
                .idleTimeout(Duration.ofSeconds(42))
                .maxTextMessageSize(1024)
                .customizeContainer(container -> idle.set(container.getIdleTimeout()));

        run(sockets, server -> assertThat(idle.get()).isEqualTo(Duration.ofSeconds(42)));
    }

    @Test
    void aServerWithNoServletContextIsRefused() {
        assertThatThrownBy(() -> new WebSockets().accept(new org.eclipse.jetty.server.Server()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("customizeServer");
    }

    private static final class EchoSocket implements WebSocketHandler {
        @Override
        public void onText(Session session, String message) {
            session.sendText(message, Callback.NOOP);
        }
    }

    private record LabelSocket(String label) implements WebSocketHandler {
        @Override
        public void onText(Session session, String message) {
            session.sendText(label + ": " + message, Callback.NOOP);
        }
    }

    /** Collects the text the server sends back, so a test can wait for a count of it. */
    private static final class Collector implements WebSocket.Listener {

        private final List<String> messages = new CopyOnWriteArrayList<>();
        private final CountDownLatch received = new CountDownLatch(1);

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            messages.add(data.toString());
            received.countDown();
            socket.request(1);
            return null;
        }

        List<String> await(int count) {
            awaitLatch(received);
            List<String> copy = new ArrayList<>(messages);
            assertThat(copy).hasSize(count);
            return copy;
        }
    }

    private static void run(WebSockets sockets, Consumer<JettyServer> body) {
        run(new App(), sockets, body);
    }

    private static void run(App app, WebSockets sockets, Consumer<JettyServer> body) {
        JettyServer server = new JettyServer(app).port(0).shutdownHook(false)
                .customizeServer(sockets);
        server.start();
        try {
            body.accept(server);
        } finally {
            server.stop();
        }
    }

    private static WebSocket connect(JettyServer server, String path, WebSocket.Listener listener) {
        return connect(server, path, listener, builder -> {
        });
    }

    private static WebSocket connect(JettyServer server, String path, WebSocket.Listener listener,
            Consumer<WebSocket.Builder> customizer) {
        WebSocket.Builder builder = HttpClient.newHttpClient().newWebSocketBuilder();
        customizer.accept(builder);
        return builder.buildAsync(URI.create("ws://localhost:" + server.port() + path), listener)
                .join();
    }

    private static HttpResponse<String> http(JettyServer server, String path) {
        try {
            return HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean awaitLatch(CountDownLatch latch) {
        try {
            return latch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
