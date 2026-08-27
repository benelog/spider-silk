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
 * App app = new App();   // jte over classpath:/jte, and classpath:/public served at /
 *
 * app.get("/decks/{deckId}", req -> {
 *     long deckId = req.pathParamLong("deckId");
 *     return WebResponse.template("deck", model);   // classpath:/jte/deck.jte
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
    final Map<HttpStatus, Handler> errorHandlers = new LinkedHashMap<>();
    final Set<SseStream> openStreams = ConcurrentHashMap.newKeySet();

    TemplateRenderer templates;
    List<StaticFiles> staticFiles = List.of(new StaticFiles(StaticFiles.DEFAULT_ROOT));
    RequestLogger requestLogger;
    Cors cors;
    Gzip gzip;
    SecurityHeaders securityHeaders;

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
     * app.get("/_routes", req -> WebResponse.template("routes", Map.of("routes", app.routes())));
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
        beforeFilters.add(new BeforeEntry(path, filter));
        return this;
    }

    /** A filter that runs after a route completes normally. */
    public App after(AfterFilter filter) {
        return after("/*", filter);
    }

    /** A filter that runs after matching routes complete normally. */
    public App after(String path, AfterFilter filter) {
        afterFilters.add(new AfterEntry(path, filter));
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
     * {@code WebResponse.empty(HttpStatus.NOT_FOUND)}.
     *
     * <pre>{@code
     * app.error(HttpStatus.NOT_FOUND,
     *         req -> WebResponse.template("not-found", Map.of("path", req.path())));
     * }</pre>
     *
     * <p>A response that already carries a body is left alone. What the handler
     * returns keeps the headers the framework had already worked out, and answers
     * with the registered status unless it sets one of its own.
     */
    public App error(HttpStatus status, Handler handler) {
        errorHandlers.put(Objects.requireNonNull(status, "status"),
                Objects.requireNonNull(handler, "handler"));
        return this;
    }

    /** Shorthand for {@code error(HttpStatus.NOT_FOUND, handler)}. */
    public App notFound(Handler handler) {
        return error(HttpStatus.NOT_FOUND, handler);
    }

    /**
     * Everything registered that runs around a route rather than being one:
     * the {@link #before} and {@link #after} filters, and the
     * {@link #error(HttpStatus, Handler)} bodies. It answers "which guard
     * covers this path", which {@link #routes()} holds no part of.
     *
     * <p>Grouped by when it runs — the before-filters, then the after-filters,
     * then the error handlers — and within each group in registration order,
     * which is the order they run in.
     *
     * <p>A filter's coverage is a pattern and not a path, and it is reported as
     * one: {@code "/admin/*"} stays {@code "/admin/*"} and covers
     * {@code "/admin"} along with everything under it, and the no-path
     * overloads report the {@code "/*"} they register. Matching a request
     * against those patterns is the dispatcher's job, not this list's; an audit
     * of which paths a guard leaves open is built on top of the list, the way
     * an OpenAPI export is built on {@link #routes()}.
     *
     * <p>The {@link #exception(Class, ExceptionHandler)} handlers are not here:
     * an exception handler is scoped to a type, so no path or status describes
     * where it applies.
     */
    public List<Guard> guards() {
        List<Guard> guards = new ArrayList<>(
                beforeFilters.size() + afterFilters.size() + errorHandlers.size());
        beforeFilters.forEach(entry -> guards.add(new Guard.Before(entry.path())));
        afterFilters.forEach(entry -> guards.add(new Guard.After(entry.path())));
        errorHandlers.keySet().forEach(status -> guards.add(new Guard.Error(status)));
        return List.copyOf(guards);
    }

    /**
     * Called after every response, with how long the request took.
     *
     * <pre>{@code
     * app.requestLogger((req, res, millis) -> logger.info("{} {} -> {} ({}ms)",
     *         req.method(), req.path(), res.status().code(), millis));
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

    /**
     * The template engine used by {@link WebResponse#template}, in place of the
     * default — {@link JteTemplates} over {@code classpath:/jte}, appending
     * {@code ".jte"} to the name.
     *
     * <pre>{@code
     * app.templates(new JteTemplates("templates").suffix(".html"));
     * }</pre>
     */
    public App templates(TemplateRenderer renderer) {
        this.templates = Objects.requireNonNull(renderer, "renderer");
        return this;
    }

    /**
     * The template engine, built on first render if nobody supplied one. jte
     * compiles into a directory it creates, so the default is not built for an
     * app that never renders a template.
     */
    synchronized TemplateRenderer templateRenderer() {
        if (templates == null) {
            templates = new JteTemplates(JteTemplates.DEFAULT_ROOT);
        }
        return templates;
    }

    /**
     * The classpath root to serve static files from, in place of the default
     * {@code "/public"}.
     */
    public App staticFiles(String classpathRoot) {
        return staticFiles(new StaticFiles(classpathRoot));
    }

    /**
     * Static files with a hosted path, a cache policy, or a root of their own,
     * replacing the default {@code classpath:/public}.
     *
     * <p>Several roots are read in the order given, and the first that holds
     * the file answers — which is how a directory on disk sits beside the
     * assets that shipped in the jar:
     *
     * <pre>{@code
     * app.staticFiles(
     *         new StaticFiles("/public"),
     *         StaticFiles.directory(uploads).hostedPath("/uploads"));
     * }</pre>
     *
     * <p>Called with no argument at all, nothing is served as a file and every
     * path is left to routing.
     */
    public App staticFiles(StaticFiles... staticFiles) {
        this.staticFiles = List.of(Objects.requireNonNull(staticFiles, "staticFiles"));
        return this;
    }

    /**
     * Which other origins may call this application.
     *
     * <pre>{@code
     * app.cors(Cors.allowOrigin("https://app.example.com").forPath("/api/*"));
     * }</pre>
     *
     * <p>Named here rather than registered as a filter, because the two things
     * CORS has to reach are the two a filter cannot: the {@code OPTIONS} answer
     * for a preflight, which no handler is registered for, and the error
     * responses a cross-origin caller has to be able to read.
     */
    public App cors(Cors cors) {
        this.cors = Objects.requireNonNull(cors, "cors");
        return this;
    }

    /** Compresses every compressible response, with {@link Gzip#defaults()}. */
    public App gzip() {
        return gzip(Gzip.defaults());
    }

    /**
     * Compression, tuned.
     *
     * <pre>{@code
     * app.gzip(Gzip.defaults().minBytes(4096));
     * }</pre>
     *
     * <p>Named here rather than registered as a filter: the largest thing most
     * applications send is a static file, and a static file is answered before
     * any filter runs.
     */
    public App gzip(Gzip gzip) {
        this.gzip = Objects.requireNonNull(gzip, "gzip");
        return this;
    }

    /** The headers of {@link SecurityHeaders#defaults()} on every response. */
    public App securityHeaders() {
        return securityHeaders(SecurityHeaders.defaults());
    }

    /**
     * Security headers, adjusted.
     *
     * <pre>{@code
     * app.securityHeaders(SecurityHeaders.defaults().hsts(Duration.ofDays(365)));
     * }</pre>
     *
     * <p>Named here rather than registered as a filter, because a 404 is a page
     * a browser renders like any other and no after-filter runs for one.
     */
    public App securityHeaders(SecurityHeaders securityHeaders) {
        this.securityHeaders = Objects.requireNonNull(securityHeaders, "securityHeaders");
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
