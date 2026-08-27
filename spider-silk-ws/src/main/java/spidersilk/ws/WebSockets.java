package spidersilk.ws;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.websocket.core.server.WebSocketMappings;
import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;
import org.eclipse.jetty.websocket.server.WebSocketCreator;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;

import spidersilk.server.JettyServer;

/**
 * WebSocket endpoints alongside an app's HTTP routes, on Jetty.
 *
 * <p>It is a {@link Consumer} of Jetty's {@link Server}, which is what
 * {@link JettyServer#customizeServer} takes:
 *
 * <pre>{@code
 * new App()
 *         .get("/", req -> WebResponse.text("hi"))
 *         .server((app, port) -> new JettyServer(app).port(port)
 *                 .customizeServer(new WebSockets()
 *                         .at("/echo", (request, response) -> new EchoSocket())
 *                         .idleTimeout(Duration.ofMinutes(5))))
 *         .start(8080);
 * }</pre>
 *
 * <p>The upgrade handler is inserted ahead of the servlet, so a request whose
 * path matches a mapping below is upgraded and everything else falls through to
 * the app exactly as before. Paths are relative to the server's context path,
 * the same as a route is.
 *
 * <h2>What does not reach a socket</h2>
 *
 * <p>An upgrade leaves servlet dispatch for good, so none of the framework
 * follows it: not the router, not {@code before}/{@code after}, not
 * {@code error(status, ...)}, not the request logger, not {@code routes()}, and
 * not {@code WebTest}. That is why this is a module rather than a method on
 * {@code App} — a socket is Jetty's, and the module's name says so.
 *
 * <h2>Paths</h2>
 *
 * <p>A path is Jetty's own spec syntax, not core's router syntax: an exact
 * path, {@code /rooms/*} for a prefix, or {@code *.ws} for a suffix. There is
 * no {@code {name}} segment — a variable path is a prefix mapping, with
 * {@code Request.getPathInContext(request)} read in the
 * {@link WebSocketFactory}.
 */
public final class WebSockets implements Consumer<Server> {

    private final Map<String, PathSpec> paths = new LinkedHashMap<>();
    private final Map<String, WebSocketFactory> factories = new LinkedHashMap<>();
    private final List<Consumer<ServerWebSocketContainer>> containerCustomizers = new ArrayList<>();

    private Duration idleTimeout;
    private Long maxTextMessageSize;
    private Long maxBinaryMessageSize;

    /** An empty set of mappings, which {@link #at} fills in. */
    public WebSockets() {
    }

    /**
     * Maps a path to the factory that answers its upgrades. The path is parsed
     * here rather than at startup, so a spec Jetty does not recognise fails
     * where it was written.
     */
    public WebSockets at(String path, WebSocketFactory factory) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(factory, "factory");
        if (paths.containsKey(path)) {
            throw new IllegalArgumentException("Path is already mapped: " + path);
        }
        paths.put(path, WebSocketMappings.parsePathSpec(path));
        factories.put(path, factory);
        return this;
    }

    /**
     * How long a connection with nothing on it survives. Jetty's default is
     * 30 seconds, which a socket kept open across idle stretches outlives only
     * if the two ends ping each other.
     */
    public WebSockets idleTimeout(Duration idleTimeout) {
        this.idleTimeout = Objects.requireNonNull(idleTimeout, "idleTimeout");
        return this;
    }

    /** The largest text message accepted, in bytes. A larger one fails the connection. */
    public WebSockets maxTextMessageSize(long maxTextMessageSize) {
        this.maxTextMessageSize = maxTextMessageSize;
        return this;
    }

    /** The largest binary message accepted, in bytes. A larger one fails the connection. */
    public WebSockets maxBinaryMessageSize(long maxBinaryMessageSize) {
        this.maxBinaryMessageSize = maxBinaryMessageSize;
        return this;
    }

    /**
     * Runs against the {@link ServerWebSocketContainer} on every start, for the
     * settings not named above — frame size, buffer sizes, auto-fragmenting,
     * session listeners.
     */
    public WebSockets customizeContainer(Consumer<ServerWebSocketContainer> customizer) {
        containerCustomizers.add(Objects.requireNonNull(customizer, "customizer"));
        return this;
    }

    /**
     * Inserts the upgrade handler into the servlet context Jetty is about to
     * start. Called by {@link JettyServer#customizeServer}, after the context
     * is linked to the server and before either is started.
     */
    @Override
    public void accept(Server server) {
        Objects.requireNonNull(server, "server");
        ServletContextHandler context = server.getDescendant(ServletContextHandler.class);
        if (context == null) {
            throw new IllegalStateException(
                    "No ServletContextHandler under the server: WebSockets needs the one "
                            + "JettyServer builds, so pass it to customizeServer(...)");
        }
        context.insertHandler(WebSocketUpgradeHandler.from(server, context, this::configure));
    }

    private void configure(ServerWebSocketContainer container) {
        if (idleTimeout != null) {
            container.setIdleTimeout(idleTimeout);
        }
        if (maxTextMessageSize != null) {
            container.setMaxTextMessageSize(maxTextMessageSize);
        }
        if (maxBinaryMessageSize != null) {
            container.setMaxBinaryMessageSize(maxBinaryMessageSize);
        }
        containerCustomizers.forEach(customizer -> customizer.accept(container));
        paths.forEach((path, spec) -> container.addMapping(spec, creator(factories.get(path))));
    }

    private static WebSocketCreator creator(WebSocketFactory factory) {
        return (request, response, callback) -> {
            WebSocketHandler handler = factory.create(request, response);
            if (handler != null) {
                return new SessionListener(handler);
            }
            // Jetty leaves a refused upgrade to the creator: answer it here, or
            // the handshake never completes and the client waits for nothing.
            int status = response.getStatus();
            Response.writeError(request, response, callback,
                    status >= HttpStatus.BAD_REQUEST_400 ? status : HttpStatus.FORBIDDEN_403);
            return null;
        };
    }
}
