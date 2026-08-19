package spidersilk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import spidersilk.server.JettyServer;
import spidersilk.server.WebServer;
import spidersilk.server.WebServerFactory;

/**
 * A Spider Silk application definition.
 * Routes, filters, and exception handlers are registered as lambdas.
 * There is no annotation scanning and no reflection: only what you register runs.
 *
 * <pre>{@code
 * App app = new App()
 *         .templates(new JteTemplates("jte"))
 *         .staticFiles("/public");
 *
 * app.get("/decks/{deckId}", ctx -> {
 *     long deckId = ctx.pathParamLong("deckId");
 *     ctx.render("deck.jte", model);
 * });
 *
 * app.start(8080);
 * }</pre>
 *
 * {@link #start(int)} runs the bundled Jetty. To deploy to an external servlet
 * container instead, skip it and map {@link AppServlet} yourself.
 */
public final class App {

    final Router router = new Router();
    final List<BeforeEntry> beforeFilters = new ArrayList<>();
    final List<AfterEntry> afterFilters = new ArrayList<>();
    final LinkedHashMap<Class<? extends Exception>, ExceptionHandler<? extends Exception>> exceptionHandlers =
            new LinkedHashMap<>();
    final Map<Integer, Handler> errorHandlers = new LinkedHashMap<>();
    final Set<SseStream> openStreams = ConcurrentHashMap.newKeySet();

    TemplateRenderer templates;
    StaticFiles staticFiles;
    RequestLogger requestLogger;

    private WebServerFactory serverFactory = (app, port) -> new JettyServer(app).port(port);
    private WebServer server;

    public App get(String path, Handler handler) {
        router.add("GET", path, handler);
        return this;
    }

    public App post(String path, Handler handler) {
        router.add("POST", path, handler);
        return this;
    }

    public App put(String path, Handler handler) {
        router.add("PUT", path, handler);
        return this;
    }

    public App patch(String path, Handler handler) {
        router.add("PATCH", path, handler);
        return this;
    }

    public App delete(String path, Handler handler) {
        router.add("DELETE", path, handler);
        return this;
    }

    /**
     * A HEAD of its own. Rarely needed: a GET route already answers HEAD with
     * its headers and no body.
     */
    public App head(String path, Handler handler) {
        router.add("HEAD", path, handler);
        return this;
    }

    /**
     * An OPTIONS of its own — a CORS preflight, usually. Without one, OPTIONS
     * is answered with the {@code Allow} header the path's routes imply.
     */
    public App options(String path, Handler handler) {
        router.add("OPTIONS", path, handler);
        return this;
    }

    /**
     * Routes sharing a path prefix. The group is passed in as an argument, so
     * nothing is registered behind your back:
     *
     * <pre>{@code
     * app.path("/api/decks", decks -> {
     *     decks.get("", this::listDecks);           // GET /api/decks
     *     decks.get("/{deckId}", this::showDeck);   // GET /api/decks/{deckId}
     * });
     * }</pre>
     */
    public App path(String prefix, Consumer<RouteGroup> routes) {
        Objects.requireNonNull(routes, "routes")
                .accept(new RouteGroup(this, Objects.requireNonNull(prefix, "prefix")));
        return this;
    }

    /**
     * Every route registered so far, in registration order — which is also the
     * order the router resolves ties in. Routes are an explicit list, so this
     * needs no annotation scanning and no reflection; it is the same list the
     * dispatcher walks, read back as data.
     *
     * <pre>{@code
     * app.get("/_routes", req -> WebResponse.template("routes.jte", Map.of("routes", app.routes())));
     * }</pre>
     *
     * <p>What was registered, and only that: the HEAD and OPTIONS answers
     * {@link AppServlet} derives from a GET route are not listed, because
     * nobody registered them. An overview page or an OpenAPI export is built
     * from this snapshot rather than shipped in core.
     */
    public List<Route> routes() {
        return router.routes();
    }

    /** A filter that runs before every route. */
    public App before(BeforeFilter filter) {
        return before("/*", filter);
    }

    /**
     * A filter that runs before routes whose path matches. A trailing "*"
     * covers the prefix and everything under it, so "/admin/*" guards
     * "/admin" as well as "/admin/users".
     */
    public App before(String path, BeforeFilter filter) {
        beforeFilters.add(new BeforeEntry(new PathPattern(path), filter));
        return this;
    }

    /** A filter that runs after a route completes normally. */
    public App after(AfterFilter filter) {
        return after("/*", filter);
    }

    /** A filter that runs after matching routes complete normally. */
    public App after(String path, AfterFilter filter) {
        afterFilters.add(new AfterEntry(new PathPattern(path), filter));
        return this;
    }

    /** A per-exception-type handler. The first match in registration order runs. */
    public <E extends Exception> App exception(Class<E> type, ExceptionHandler<E> handler) {
        exceptionHandlers.put(type, handler);
        return this;
    }

    /**
     * Renders the body for a response that ended on this status with no body —
     * one place for a styled 404 or 500, whether the status came from the router,
     * from an {@link HttpException}, or from a handler that answered with
     * {@code WebResponse.empty(404)}.
     *
     * <pre>{@code
     * app.error(404, req -> WebResponse.template("not-found.jte", Map.of("path", req.path())));
     * }</pre>
     *
     * <p>A response that already carries a body is left alone. What the handler
     * returns keeps the headers the framework had already worked out, and answers
     * with the registered status unless it sets one of its own.
     */
    public App error(int status, Handler handler) {
        errorHandlers.put(status, Objects.requireNonNull(handler, "handler"));
        return this;
    }

    /** Shorthand for {@code error(404, handler)}. */
    public App notFound(Handler handler) {
        return error(404, handler);
    }

    /**
     * Called after every response, with how long the request took.
     *
     * <pre>{@code
     * app.requestLogger((req, res, millis) -> logger.info("{} {} -> {} ({}ms)",
     *         req.method(), req.path(), res.status(), millis));
     * }</pre>
     *
     * One lambda, and no logging framework in core. A logger that throws is
     * reported to the servlet log and does not affect the response, which has
     * already been sent by then.
     */
    public App requestLogger(RequestLogger logger) {
        this.requestLogger = Objects.requireNonNull(logger, "logger");
        return this;
    }

    /** The template engine used by {@link WebResponse#render}. */
    public App templates(TemplateRenderer renderer) {
        this.templates = renderer;
        return this;
    }

    /** The classpath root to serve static files from, e.g. "/public". */
    public App staticFiles(String classpathRoot) {
        return staticFiles(new StaticFiles(classpathRoot));
    }

    /** Static files with a hosted path or a cache policy of their own. */
    public App staticFiles(StaticFiles staticFiles) {
        this.staticFiles = Objects.requireNonNull(staticFiles, "staticFiles");
        return this;
    }

    // ---- Server ----

    /**
     * Replaces the server {@link #start(int)} runs. The default builds a
     * {@link JettyServer}; this is the seam for a different server, or for a
     * Jetty tuned beyond the defaults.
     *
     * <pre>{@code
     * app.server((a, port) -> new JettyServer(a).port(port).sessions(false))
     *    .start(9000);
     * }</pre>
     */
    public App server(WebServerFactory factory) {
        this.serverFactory = Objects.requireNonNull(factory, "factory");
        return this;
    }

    /** Starts on {@link JettyServer#DEFAULT_PORT}. */
    public App start() {
        return start(JettyServer.DEFAULT_PORT);
    }

    /** Starts the server. Port 0 picks a free port, readable through {@link #port()}. */
    public App start(int port) {
        if (server != null) {
            throw new IllegalStateException("Already started on port " + port());
        }
        WebServer started = serverFactory.create(this, port);
        started.start();
        server = started;
        return this;
    }

    /**
     * Stops the server. Doing this while stopped is a no-op.
     *
     * <p>Open SSE streams are closed first. Each of them is a request that has
     * been in flight since it started and would never finish on its own, so the
     * graceful stop would wait out its whole timeout and then report a failure
     * to drain — a stream is closed before Jetty is asked to drain anything.
     */
    public App stop() {
        closeOpenStreams();
        if (server != null) {
            WebServer running = server;
            server = null;
            running.stop();
        }
        return this;
    }

    /** Blocks until the server stops. */
    public App join() {
        requireStarted().join();
        return this;
    }

    /** The port actually bound. */
    public int port() {
        return requireStarted().port();
    }

    /** The running server, for implementation-specific access. */
    public WebServer server() {
        return requireStarted();
    }

    private void closeOpenStreams() {
        for (SseStream stream : List.copyOf(openStreams)) {
            openStreams.remove(stream);
            stream.close();
        }
    }

    private WebServer requireStarted() {
        if (server == null) {
            throw new IllegalStateException("Not started. Call App.start() first.");
        }
        return server;
    }
}
