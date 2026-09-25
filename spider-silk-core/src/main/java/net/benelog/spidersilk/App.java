package net.benelog.spidersilk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import net.benelog.spidersilk.server.JettyServer;
import net.benelog.spidersilk.server.WebServer;
import net.benelog.spidersilk.server.WebServerFactory;

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
 *
 * <p>Everything is registered before the application serves: a route, a filter,
 * or a setting added while an {@link AppServlet} is serving it throws
 * {@link IllegalStateException}. That covers {@code start}, a server started
 * directly, and an external container alike, because the servlet takes what it
 * serves when it is initialized and gives it back when it is destroyed.
 *
 * <p>A setting object — {@link Cors}, {@link Gzip}, {@link SecurityHeaders},
 * {@link StaticFiles}, {@link BodyLimits} — is copied when it is registered. Changing the one you
 * passed afterwards changes nothing this application does.
 */
public final class App {

    final Router router = new Router();
    final List<BeforeEntry> requestFilters = new ArrayList<>();
    final List<BeforeEntry> beforeFilters = new ArrayList<>();
    final List<AfterEntry> afterFilters = new ArrayList<>();
    final List<ResponseFilter> responseFilters = new ArrayList<>();
    final LinkedHashMap<Class<? extends Exception>, ExceptionHandler<? extends Exception>> exceptionHandlers =
            new LinkedHashMap<>();
    final Map<HttpStatus, Handler> statusPages = new LinkedHashMap<>();
    final Set<SseStream> openStreams = ConcurrentHashMap.newKeySet();

    volatile @Nullable TemplateRenderer templates;
    List<StaticFiles> staticFiles = List.of(new StaticFiles(StaticFiles.DEFAULT_ROOT));
    @Nullable RequestLogger requestLogger;
    @Nullable Cors cors;
    @Nullable Gzip gzip;
    @Nullable SecurityHeaders securityHeaders;
    BodyLimits bodyLimits = BodyLimits.defaults();

    /** Guards the one lazy assignment in {@link #templateRenderer()}. */
    private final Object templatesLock = new Object();

    /**
     * Held for every registration and for every deployment that opens or
     * closes, so that a route cannot be half-added while a servlet copies the
     * table, and a check that registration is open cannot go stale before the
     * change it allowed is made. Registration happens at startup, so nothing
     * on the request path ever waits for it.
     */
    private final Object registrationLock = new Object();

    /** How many {@link AppServlet}s are initialized over this application and not yet destroyed. */
    private int deployments;

    /**
     * Closes the open SSE streams when the JVM shuts down, for as long as a
     * deployment serves this application: registered by the first
     * {@link #deploy()} and removed by the last {@link #undeploy()}.
     *
     * <p>The server's own hook — Jetty's {@code setStopAtShutdown}, or the one
     * {@code TomcatServer} and {@code UndertowServer} register — stops the
     * server without going through {@link #stop()}, and a stream is a request
     * that never ends on its own, so Ctrl-C or SIGTERM would wait out the whole
     * stop timeout. The JVM runs its hooks at once, so this one closes the
     * streams while the server's starts to drain, and the drain then ends with
     * them. An external container stopped by a signal gets the same.
     */
    private @Nullable Thread streamCloser;

    private WebServerFactory serverFactory = (app, port) -> new JettyServer(app).port(port);
    private @Nullable WebServer server;

    public App get(String path, Handler handler) {
        register(() -> router.add("GET", path, handler));
        return this;
    }

    /**
     * The same route, with one line saying what it is for, which
     * {@link #routes()} reports and an OpenAPI export can carry:
     *
     * <pre>{@code
     * app.get("/api/decks", "List every deck", this::listDecks);
     * }</pre>
     *
     * <p>Every registration method has this overload. The description sits
     * between the path and the handler so the handler stays the last argument,
     * where a lambda reads. It is documentation written at the registration
     * site rather than dug out of the handler, which is what keeps it a string
     * argument instead of an annotation.
     */
    public App get(String path, String description, Handler handler) {
        register(() -> router.add("GET", path, description, handler));
        return this;
    }

    public App post(String path, Handler handler) {
        register(() -> router.add("POST", path, handler));
        return this;
    }

    /** {@link #get(String, String, Handler)}, for POST. */
    public App post(String path, String description, Handler handler) {
        register(() -> router.add("POST", path, description, handler));
        return this;
    }

    public App put(String path, Handler handler) {
        register(() -> router.add("PUT", path, handler));
        return this;
    }

    /** {@link #get(String, String, Handler)}, for PUT. */
    public App put(String path, String description, Handler handler) {
        register(() -> router.add("PUT", path, description, handler));
        return this;
    }

    public App patch(String path, Handler handler) {
        register(() -> router.add("PATCH", path, handler));
        return this;
    }

    /** {@link #get(String, String, Handler)}, for PATCH. */
    public App patch(String path, String description, Handler handler) {
        register(() -> router.add("PATCH", path, description, handler));
        return this;
    }

    public App delete(String path, Handler handler) {
        register(() -> router.add("DELETE", path, handler));
        return this;
    }

    /** {@link #get(String, String, Handler)}, for DELETE. */
    public App delete(String path, String description, Handler handler) {
        register(() -> router.add("DELETE", path, description, handler));
        return this;
    }

    /**
     * A HEAD of its own. Rarely needed: a GET route already answers HEAD with
     * its headers and no body.
     */
    public App head(String path, Handler handler) {
        register(() -> router.add("HEAD", path, handler));
        return this;
    }

    /** {@link #get(String, String, Handler)}, for HEAD. */
    public App head(String path, String description, Handler handler) {
        register(() -> router.add("HEAD", path, description, handler));
        return this;
    }

    /**
     * An OPTIONS of its own — a CORS preflight, usually. Without one, OPTIONS
     * is answered with the {@code Allow} header the path's routes imply.
     */
    public App options(String path, Handler handler) {
        register(() -> router.add("OPTIONS", path, handler));
        return this;
    }

    /** {@link #get(String, String, Handler)}, for OPTIONS. */
    public App options(String path, String description, Handler handler) {
        register(() -> router.add("OPTIONS", path, description, handler));
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
     * needs no annotation scanning and no reflection; it is the list the
     * dispatcher's copy is taken from when {@link AppServlet} is initialized,
     * read back as data, and registration is closed while that copy is served.
     *
     * <pre>{@code
     * app.get("/_routes", req -> WebResponse.template("routes", Map.of("routes", app.routes())));
     * }</pre>
     *
     * <p>What was registered, and only that: the HEAD and OPTIONS answers
     * {@link AppServlet} derives from a GET route are not listed, because
     * nobody registered them. Each {@link Route} carries the description its
     * registration passed, or {@code ""} where none was. An overview page or an
     * OpenAPI export is built from this snapshot rather than shipped in core.
     */
    public List<Route> routes() {
        return router.routes();
    }

    /** A filter that runs before every request reaches routing or static files. */
    public App beforeRequest(BeforeFilter filter) {
        return beforeRequest("/*", filter);
    }

    /**
     * Runs before routing for requests matching the path pattern, including
     * static files, missing routes, and automatic OPTIONS. Return null to
     * continue, or a response to stop. Path variables are not available yet.
     * Exceptions and early responses use the normal error and response filters.
     */
    public App beforeRequest(String path, BeforeFilter filter) {
        Objects.requireNonNull(filter, "filter");
        register(() -> requestFilters.add(new BeforeEntry(path, filter)));
        return this;
    }

    /** A filter that runs before every matched route, with its path variables. */
    public App beforeRoute(BeforeFilter filter) {
        return beforeRoute("/*", filter);
    }

    /**
     * A filter that runs before routes whose path matches. A trailing "*"
     * covers the prefix and everything under it, so "/admin/*" guards
     * "/admin" as well as "/admin/users".
     */
    public App beforeRoute(String path, BeforeFilter filter) {
        Objects.requireNonNull(filter, "filter");
        register(() -> beforeFilters.add(new BeforeEntry(path, filter)));
        return this;
    }

    /** A filter that runs after a route completes normally. */
    public App afterRoute(AfterFilter filter) {
        return afterRoute("/*", filter);
    }

    /** A filter that runs after matching routes complete normally. */
    public App afterRoute(String path, AfterFilter filter) {
        Objects.requireNonNull(filter, "filter");
        register(() -> afterFilters.add(new AfterEntry(path, filter)));
        return this;
    }

    /**
     * A filter that runs on every response, the ones no after-filter sees
     * included: a before-filter's early answer, an exception handler's, a 404, a
     * 405, the automatic {@code OPTIONS} answer, and a static file.
     *
     * <pre>{@code
     * app.responseFilter((req, res) -> res.header("X-Request-Id", requestId()));
     * }</pre>
     *
     * <p>Several run in registration order, each on what the one before it
     * answered. They run once the error body is filled in and the template is
     * rendered, and before {@link #cors}, {@link #securityHeaders}, and
     * {@link #gzip} are applied, so a filter can neither undo those nor be
     * compressed away from them. {@link ResponseFilter} describes what a filter
     * that throws answers.
     */
    public App responseFilter(ResponseFilter filter) {
        Objects.requireNonNull(filter, "filter");
        register(() -> responseFilters.add(filter));
        return this;
    }

    /**
     * A per-exception-type handler. The handler for the most specific type the
     * exception is an instance of runs, whatever order the handlers were
     * registered in: with handlers for {@code IllegalArgumentException} and for
     * {@code JsonException}, a body that failed to parse reaches the second
     * one. Registering a type twice replaces the first handler.
     */
    public <E extends Exception> App exception(Class<E> type, ExceptionHandler<E> handler) {
        register(() -> {
            exceptionHandlers.put(Objects.requireNonNull(type, "type"),
                    Objects.requireNonNull(handler, "handler"));
        });
        return this;
    }

    /**
     * Renders the body for a response that ended on this status with no body —
     * one place for a styled 404 or 500, whether the status came from the router,
     * from an {@link HttpException}, or from a handler that answered with
     * {@code WebResponse.empty(HttpStatus.NOT_FOUND)}.
     *
     * <pre>{@code
     * app.statusPage(HttpStatus.NOT_FOUND,
     *         req -> WebResponse.template("not-found", Map.of("path", req.path())));
     * }</pre>
     *
     * <p>A response that already carries a body is left alone. What the handler
     * returns keeps the headers the framework had already worked out, and answers
     * with the registered status unless it sets one of its own.
     */
    public App statusPage(HttpStatus status, Handler handler) {
        register(() -> {
            statusPages.put(Objects.requireNonNull(status, "status"),
                    Objects.requireNonNull(handler, "handler"));
        });
        return this;
    }

    /**
     * Everything registered that runs around a route rather than being one:
     * the {@link #beforeRequest}, {@link #beforeRoute}, and {@link #afterRoute} filters, the
     * {@link #responseFilter} filters, and the
     * {@link #statusPage(HttpStatus, Handler)} bodies. It answers "which filter
     * covers this path", which {@link #routes()} holds no part of.
     *
     * <p>Grouped by when it runs — request filters, before-route filters, after-route filters,
     * then the status pages, then the response filters — and within each group
     * in registration order, which is the order they run in.
     *
     * <p>A filter's coverage is a pattern and not a path, and it is reported as
     * one: {@code "/admin/*"} stays {@code "/admin/*"} and covers
     * {@code "/admin"} along with everything under it, and the no-path
     * overloads report the {@code "/*"} they register. Matching a request
     * against those patterns is the dispatcher's job, not this list's; an audit
     * of which paths a filter leaves open is built on top of the list, the way
     * an OpenAPI export is built on {@link #routes()}.
     *
     * <p>The {@link #exception(Class, ExceptionHandler)} handlers are not here:
     * an exception handler is scoped to a type, so no path or status describes
     * where it applies.
     */
    public List<Hook> hooks() {
        List<Hook> hooks = new ArrayList<>(
                requestFilters.size() + beforeFilters.size() + afterFilters.size()
                        + statusPages.size() + responseFilters.size());
        requestFilters.forEach(entry -> hooks.add(new Hook.BeforeRequest(entry.path())));
        beforeFilters.forEach(entry -> hooks.add(new Hook.BeforeRoute(entry.path())));
        afterFilters.forEach(entry -> hooks.add(new Hook.AfterRoute(entry.path())));
        statusPages.keySet().forEach(status -> hooks.add(new Hook.StatusPage(status)));
        responseFilters.forEach(filter -> hooks.add(new Hook.EveryResponse()));
        return List.copyOf(hooks);
    }

    /**
     * Called after writing, with the servlet status, elapsed time, and any write failure.
     *
     * <pre>{@code
     * app.requestLogger((req, completion) -> logger.info("{} {} -> {} ({}ms)",
     *         req.method(), req.path(), completion.statusCode(), completion.took().toMillis()));
     * }</pre>
     *
     * One lambda, and no logging framework in core. A logger that throws, an
     * {@link Error} included, is reported to the servlet log and does not
     * affect the response, which has been answered by then.
     */
    public App requestLogger(RequestLogger logger) {
        register(() -> this.requestLogger = Objects.requireNonNull(logger, "logger"));
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
        register(() -> this.templates = Objects.requireNonNull(renderer, "renderer"));
        return this;
    }

    /**
     * The template engine, built on first render if nobody supplied one. jte
     * compiles into a directory it creates, so the default is not built for an
     * app that never renders a template.
     *
     * <p>Every render of a {@link WebResponse.Template} calls this, so the
     * lazy assignment is behind a double check on a volatile field rather than
     * behind a lock every request has to take. A monitor here would serialise
     * concurrent HTML responses for one assignment that happens once, and a
     * handler on a virtual thread would pin its carrier waiting for it.
     */
    TemplateRenderer templateRenderer() {
        TemplateRenderer renderer = templates;
        if (renderer == null) {
            synchronized (templatesLock) {
                renderer = templates;
                if (renderer == null) {
                    renderer = new JteTemplates(JteTemplates.DEFAULT_ROOT);
                    templates = renderer;
                }
            }
        }
        return renderer;
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
     * <p>Each is copied as it is now, so changing one afterwards changes nothing
     * this application serves.
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
        List<StaticFiles> copies = Arrays.stream(Objects.requireNonNull(staticFiles, "staticFiles"))
                .map(files -> Objects.requireNonNull(files, "staticFiles").copy())
                .toList();
        register(() -> this.staticFiles = copies);
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
     *
     * <p>The value is copied as it is now, so changing it afterwards changes
     * nothing this application answers.
     */
    public App cors(Cors cors) {
        Cors copy = Objects.requireNonNull(cors, "cors").copy();
        register(() -> this.cors = copy);
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
     * applications send is a static file, and a static file bypasses
     * route filters.
     *
     * <p>The value is copied as it is now, so changing it afterwards changes
     * nothing this application compresses.
     */
    public App gzip(Gzip gzip) {
        Gzip copy = Objects.requireNonNull(gzip, "gzip").copy();
        register(() -> this.gzip = copy);
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
     *
     * <p>The value is copied as it is now, so changing it afterwards changes
     * nothing this application sends.
     */
    public App securityHeaders(SecurityHeaders securityHeaders) {
        SecurityHeaders copy = Objects.requireNonNull(securityHeaders, "securityHeaders").copy();
        register(() -> this.securityHeaders = copy);
        return this;
    }

    /**
     * How much of a request body {@code body()} and {@code bodyNdjson(...)}
     * hold in memory, in place of {@link BodyLimits#defaults()} — 1MB for the
     * body, and 1MB for one NDJSON line.
     *
     * <pre>{@code
     * app.bodyLimits(BodyLimits.defaults().maxBytes(8 * 1024 * 1024));
     * }</pre>
     *
     * <p>Named here rather than on the server, because the reads it bounds are
     * the framework's, and it therefore applies under every server and in an
     * external container alike.
     *
     * <p>The value is copied as it is now, so changing it afterwards changes
     * nothing this application reads.
     */
    public App bodyLimits(BodyLimits bodyLimits) {
        BodyLimits copy = Objects.requireNonNull(bodyLimits, "bodyLimits").copy();
        register(() -> this.bodyLimits = copy);
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
        register(() -> this.serverFactory = Objects.requireNonNull(factory, "factory"));
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

    // ---- Deployment ----

    /**
     * Closes registration and hands a servlet what it is to serve. Called from
     * {@link AppServlet#init}, so a server started by {@link #start}, one started
     * directly, and an external container all go through it.
     */
    Deployment deploy() {
        synchronized (registrationLock) {
            deployments++;
            if (streamCloser == null) {
                Thread closer = new Thread(this::closeOpenStreams, "spider-silk-close-streams");
                try {
                    Runtime.getRuntime().addShutdownHook(closer);
                    streamCloser = closer;
                } catch (IllegalStateException e) {
                    // The JVM is already shutting down, and nothing will be served for long.
                }
            }
            return new Deployment(router.copy(), requestFilters, beforeFilters, afterFilters, responseFilters, exceptionHandlers,
                    statusPages, staticFiles, requestLogger, cors, gzip, securityHeaders, bodyLimits);
        }
    }

    /**
     * Gives back what {@link #deploy()} took. Called from
     * {@link AppServlet#destroy}, which a container calls once the requests in
     * flight have finished, so registration reopens only after the last of them.
     */
    void undeploy() {
        synchronized (registrationLock) {
            if (deployments > 0) {
                deployments--;
            }
            if (deployments == 0 && streamCloser != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(streamCloser);
                } catch (IllegalStateException e) {
                    // The JVM is shutting down: the hook is running, or has run.
                }
                streamCloser = null;
            }
        }
    }

    /**
     * The hook that closes the open streams at JVM shutdown, or null while
     * nothing serves this application. Package-private and here for the test
     * that runs it beside the server's own hook, as the JVM does.
     */
    @Nullable Thread shutdownHookThread() {
        synchronized (registrationLock) {
            return streamCloser;
        }
    }

    /**
     * Stops the server. Doing this while stopped is a no-op.
     *
     * <p>Open SSE streams are closed first. Each of them is a request that has
     * been in flight since it started and would never finish on its own, so the
     * graceful stop would wait out its whole timeout and then report a failure
     * to drain — a stream is closed before Jetty is asked to drain anything.
     * A JVM shutdown, which stops the server through its own hook rather than
     * through this method, closes them through a hook of this application's.
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

    /**
     * The running server, for implementation-specific access. Named apart from
     * {@link #server(WebServerFactory)}, which chooses the server before start,
     * because this one exists only once {@link #start(int)} has returned.
     */
    public WebServer runningServer() {
        return requireStarted();
    }

    private void closeOpenStreams() {
        for (SseStream stream : List.copyOf(openStreams)) {
            openStreams.remove(stream);
            stream.close();
        }
    }

    /**
     * Makes one registration, if registration is open.
     *
     * <p>It closes while any {@link AppServlet} serves this application. Each
     * servlet serves a copy of the table taken when it was initialized, so a
     * change made now would never reach it, and {@link #routes()} — documented as
     * the list the dispatcher walks — would describe routes nothing answers.
     * {@link #stop()} opens it again, once the server has destroyed its servlet.
     *
     * <p>While a stop drains its requests, the server is already detached from
     * this {@code App} and the servlet not yet destroyed, so a registration then
     * is refused with the external-container message rather than the port.
     */
    private void register(Runnable change) {
        synchronized (registrationLock) {
            if (deployments > 0) {
                WebServer running = server;
                throw new IllegalStateException(running != null
                        ? "Already started on port " + running.port() + ": register before start()"
                        : "Already serving in a servlet container: register before AppServlet is initialized");
            }
            change.run();
        }
    }

    private WebServer requireStarted() {
        if (server == null) {
            throw new IllegalStateException("Not started. Call App.start() first.");
        }
        return server;
    }
}
