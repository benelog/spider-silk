package net.benelog.spidersilk.server;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import jakarta.servlet.MultipartConfigElement;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.jspecify.annotations.Nullable;

import net.benelog.spidersilk.App;
import net.benelog.spidersilk.AppServlet;

/**
 * The embedded Jetty server, bundled with the framework.
 *
 * <p>{@link App#start(int)} builds one of these with defaults, so the short path is:
 *
 * <pre>{@code
 * new App().get("/", ctx -> ctx.text("hi")).start(8080);
 * }</pre>
 *
 * <p>Every setting that usually needs tuning is a method here, and anything not
 * covered is reachable through the three customizers, which run against the real
 * Jetty objects just before startup:
 *
 * <pre>{@code
 * new JettyServer(app)
 *         .port(8443)
 *         .host("127.0.0.1")
 *         .threadPool(new QueuedThreadPool(200, 8))
 *         .multipart(new MultipartConfigElement(tmp, 10_485_760L, 10_485_760L, 1_048_576))
 *         .customizeHttpConfiguration(http -> http.setSendServerVersion(false))
 *         .customizeContext(context -> context.addFilter(MyFilter.class, "/*", null))
 *         .customizeServer(server -> server.setStopTimeout(5_000))
 *         .start();
 * }</pre>
 *
 * <p>Customizers accumulate: calling one twice runs both, in registration order.
 */
public final class JettyServer implements WebServer {

    /** The port used when none is given. */
    public static final int DEFAULT_PORT = 8080;

    private final App app;
    private final List<Consumer<Server>> serverCustomizers = new ArrayList<>();
    private final List<Consumer<ServletContextHandler>> contextCustomizers = new ArrayList<>();
    private final List<Consumer<HttpConfiguration>> httpConfigurationCustomizers =
            new ArrayList<>();

    /** How long {@link #stop()} lets requests in flight finish. */
    public static final Duration DEFAULT_STOP_TIMEOUT = Duration.ofSeconds(5);

    /** How long a connection with nothing on it survives a graceful stop. */
    private static final Duration SHUTDOWN_IDLE_TIMEOUT = Duration.ofMillis(100);

    /**
     * How long a session lives with no request, the thirty minutes Tomcat and
     * Undertow default to. Jetty's own default outside a webapp is never.
     */
    static final int SESSION_MAX_INACTIVE_SECONDS = 1800;

    /**
     * Jetty's default, plus the three ambiguities AppServlet answers itself.
     * Jetty refused an empty segment ({@code /a//b}), a percent-encoded dot
     * segment ({@code %2e%2e}), and an encoded percent ({@code 100%25}) with
     * its own 400 before the servlet ran, so the app's {@code statusPage(400)}
     * never saw the first two, and the third, which Tomcat and Undertow decode
     * to {@code 100%}, was refused outright. An encoded slash, backslash, or
     * NUL is still Jetty's 400, as it is Tomcat's.
     */
    static final UriCompliance URI_COMPLIANCE = UriCompliance.DEFAULT.with("SPIDER_SILK",
            UriCompliance.Violation.AMBIGUOUS_EMPTY_SEGMENT,
            UriCompliance.Violation.AMBIGUOUS_PATH_SEGMENT,
            UriCompliance.Violation.AMBIGUOUS_PATH_ENCODING);

    private static final System.Logger LOG = System.getLogger(JettyServer.class.getName());

    private int port = DEFAULT_PORT;
    private @Nullable String host;
    private String contextPath = "/";
    private boolean sessions = true;
    private @Nullable ThreadPool threadPool;
    private @Nullable MultipartConfigElement multipart = defaultMultipartConfig();
    private Duration stopTimeout = DEFAULT_STOP_TIMEOUT;
    private boolean shutdownHook = true;

    private @Nullable Server server;
    private @Nullable ServerConnector connector;

    /** A server for the app, on the defaults above until the setters say otherwise. */
    public JettyServer(App app) {
        this.app = Objects.requireNonNull(app, "app");
    }

    // ---- Settings ----

    /** The port to bind. 0 picks a free one, which {@link #port()} reports back. */
    public JettyServer port(int port) {
        this.port = port;
        return this;
    }

    /** The interface to bind. The default, null, binds all of them. */
    public JettyServer host(@Nullable String host) {
        this.host = host;
        return this;
    }

    /**
     * The context path the app is mounted under. Defaults to "/". A path
     * without its leading slash, {@code "app"}, is mounted at {@code /app}, as
     * on every server: Jetty took it as given and answered 404 to everything.
     */
    public JettyServer contextPath(String contextPath) {
        Objects.requireNonNull(contextPath, "contextPath");
        this.contextPath = contextPath.isEmpty() || contextPath.startsWith("/") ? contextPath : "/" + contextPath;
        return this;
    }

    /**
     * Whether to install an HTTP session handler. On by default, because
     * {@code req.session().set(...)} and {@code req.flash(...)} need one.
     */
    public JettyServer sessions(boolean sessions) {
        this.sessions = sessions;
        return this;
    }

    /**
     * The thread pool. The default is Jetty's own {@code QueuedThreadPool}.
     *
     * <p>This is also where virtual threads come in — platform threads keep
     * running the selectors, handlers run on virtual ones:
     *
     * <pre>{@code
     * QueuedThreadPool pool = new QueuedThreadPool();
     * pool.setVirtualThreadsExecutor(VirtualThreads.getDefaultVirtualThreadsExecutor());
     * app.server((a, port) -> new JettyServer(a).port(port).threadPool(pool)).start(8080);
     * }</pre>
     *
     * <p>A recipe rather than a method of ours: it is two lines of Jetty's own
     * API, and wrapping it would only hide which Jetty knob was turned. Note
     * that a virtual thread is only worth it if the handlers block — on I/O, on
     * a database. Since JDK 24 a {@code synchronized} block around that call
     * no longer pins the carrier thread; a blocking native call still does.
     */
    public JettyServer threadPool(ThreadPool threadPool) {
        this.threadPool = Objects.requireNonNull(threadPool, "threadPool");
        return this;
    }

    /**
     * Multipart limits for {@code ctx.file(...)} uploads.
     * The default caches to the system temp directory with no size cap and a
     * 1MB in-memory threshold. Pass null to turn multipart handling off.
     */
    public JettyServer multipart(@Nullable MultipartConfigElement multipart) {
        this.multipart = multipart;
        return this;
    }

    /**
     * How long {@link #stop()} waits for requests in flight before dropping
     * them. Five seconds by default. Idle connections do not hold this up —
     * only requests that are actually running. {@link Duration#ZERO} turns
     * graceful shutdown off and stops immediately.
     */
    public JettyServer stopTimeout(Duration stopTimeout) {
        this.stopTimeout = Objects.requireNonNull(stopTimeout, "stopTimeout");
        return this;
    }

    /**
     * Whether a JVM shutdown hook stops the server on Ctrl-C or SIGTERM. On by
     * default, so an app that only calls {@code start()} still shuts down
     * gracefully. This is Jetty's own {@code stopAtShutdown}: one hook for the
     * whole JVM, taken back out when the server stops, so a process that starts
     * a server per test does not accumulate any.
     */
    public JettyServer shutdownHook(boolean shutdownHook) {
        this.shutdownHook = shutdownHook;
        return this;
    }

    // ---- Escape hatches into Jetty ----

    /** Runs against the {@link Server} after the handler is set, before startup. */
    public JettyServer customizeServer(Consumer<Server> customizer) {
        serverCustomizers.add(Objects.requireNonNull(customizer, "customizer"));
        return this;
    }

    /** Runs against the {@link ServletContextHandler} after the servlet is mapped. */
    public JettyServer customizeContext(Consumer<ServletContextHandler> customizer) {
        contextCustomizers.add(Objects.requireNonNull(customizer, "customizer"));
        return this;
    }

    /** Runs against the {@link HttpConfiguration} before the connector is built. */
    public JettyServer customizeHttpConfiguration(Consumer<HttpConfiguration> customizer) {
        httpConfigurationCustomizers.add(Objects.requireNonNull(customizer, "customizer"));
        return this;
    }

    /** The underlying Jetty server, available once {@link #start()} has run. */
    public @Nullable Server jetty() {
        return server;
    }

    // ---- Lifecycle ----

    @Override
    public void start() {
        if (server != null) {
            throw new IllegalStateException("Jetty is already running on port " + port());
        }
        Server candidate = threadPool != null ? new Server(threadPool) : new Server();
        try {
            DrainingConnector draining = createConnector(candidate);
            connector = draining;
            candidate.addConnector(draining);
            candidate.setHandler(draining.tracking(createContext()));
            candidate.setStopTimeout(stopTimeout.toMillis());
            candidate.setStopAtShutdown(shutdownHook);
            serverCustomizers.forEach(customizer -> customizer.accept(candidate));
            candidate.start();
            server = candidate;
        } catch (Exception e) {
            connector = null;
            IllegalStateException failure =
                    new IllegalStateException("Failed to start Jetty on port " + port, e);
            stopQuietly(candidate, threadPool, failure);
            throw failure;
        }
    }

    /**
     * Unwinds a start that threw part-way. Jetty may already have bound the
     * connector and started pool threads, so a candidate left as it is would
     * hold the port against a retry and keep its non-daemon threads running.
     * A thread pool the caller passed in is stopped with the server, as on any
     * stop, but not destroyed: a destroyed pool cannot start again, and the
     * caller may well retry with it. The cleanup is best-effort: whatever it
     * fails at is reported on the start failure rather than replacing it.
     */
    private static void stopQuietly(
            Server candidate, @Nullable ThreadPool callersPool, IllegalStateException failure) {
        try {
            candidate.stop();
            if (callersPool != null) {
                candidate.removeBean(callersPool);
            }
            candidate.destroy();
        } catch (Exception cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    /**
     * Stops the server. A request that outlasts the stop timeout is dropped
     * and logged, and the stop returns as it does on Tomcat and Undertow, so
     * cleanup written after {@code app.stop()} still runs.
     */
    @Override
    public void stop() {
        if (server == null) {
            return;
        }
        try {
            server.stop();
        } catch (TimeoutException e) {
            if (e.getSuppressed().length > 0) {
                throw new IllegalStateException("Failed to stop Jetty", e);
            }
            LOG.log(System.Logger.Level.WARNING, "Requests in flight outlasted the stop timeout of "
                    + stopTimeout.toMillis() + "ms and were dropped");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to stop Jetty", e);
        } finally {
            server = null;
            connector = null;
        }
    }

    @Override
    public void join() {
        if (server == null) {
            throw new IllegalStateException("Jetty is not running");
        }
        try {
            server.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public int port() {
        if (connector != null && connector.getLocalPort() > 0) {
            return connector.getLocalPort();
        }
        return port;
    }

    private DrainingConnector createConnector(Server server) {
        HttpConfiguration httpConfiguration = new HttpConfiguration();
        // Jetty reads a form body on POST and PUT; Tomcat and Undertow are set
        // to the same three methods, so a PATCH form reads alike on each.
        httpConfiguration.addFormEncodedMethod("PATCH");
        httpConfiguration.setUriCompliance(URI_COMPLIANCE);
        httpConfigurationCustomizers.forEach(customizer -> customizer.accept(httpConfiguration));

        DrainingConnector connector =
                new DrainingConnector(server, new HttpConnectionFactory(httpConfiguration));
        connector.setPort(port);
        if (host != null) {
            connector.setHost(host);
        }
        return connector;
    }

    private ServletContextHandler createContext() {
        ServletContextHandler context = new ServletContextHandler(
                sessions ? ServletContextHandler.SESSIONS : 0);
        context.setContextPath(contextPath);
        // The other half of URI_COMPLIANCE: the servlet layer refuses an
        // ambiguous path again on its own, with the same 400 of Jetty's.
        context.getServletHandler().setDecodeAmbiguousURIs(true);
        if (sessions) {
            // Jetty's default outside a webapp's webdefault.xml leaves the
            // session cookie readable by a script; Tomcat marks it HttpOnly.
            context.getSessionHandler().setHttpOnly(true);
            // Jetty's default is -1, never, and its default cache does not
            // evict, so a session would stay on the heap until the JVM exits.
            context.getSessionHandler().setMaxInactiveInterval(SESSION_MAX_INACTIVE_SECONDS);
        }

        ServletHolder holder = new ServletHolder(new AppServlet(app));
        // Initialized while the server starts rather than on the first request,
        // which is when AppServlet takes the routes and closes registration.
        holder.setInitOrder(0);
        if (multipart != null) {
            holder.getRegistration().setMultipartConfig(multipart);
        } else {
            holder.setInitParameter(AppServlet.NO_MULTIPART_PARAMETER, "true");
        }
        context.addServlet(holder, "/*");

        contextCustomizers.forEach(customizer -> customizer.accept(context));
        return context;
    }

    private static MultipartConfigElement defaultMultipartConfig() {
        return new MultipartConfigElement(
                System.getProperty("java.io.tmpdir"), -1L, -1L, 1024 * 1024);
    }

    /**
     * A connector whose graceful stop closes idle connections at once and
     * leaves busy ones their full idle timeout.
     *
     * <p>Without the first half, a graceful stop waits out the whole stop
     * timeout for keep-alive connections that are sitting idle, and then
     * reports a timeout. Jetty's own {@code shutdownIdleTimeout} does that,
     * but it shortens the idle timeout of every connection, so a slow upload
     * or download in flight would be cut off within a fraction of a second
     * rather than given the stop timeout to finish. The drain is for requests
     * in flight, not for open sockets, so the short timeout goes only to
     * connections with no request in the handler.
     */
    private static final class DrainingConnector extends ServerConnector {

        private final Set<EndPoint> busy = ConcurrentHashMap.newKeySet();
        private volatile boolean draining;

        DrainingConnector(Server server, HttpConnectionFactory factory) {
            super(server, factory);
            // Jetty's own shortening would reach the busy connections too; shutdown() does it for the idle ones.
            setShutdownIdleTimeout(-1);
        }

        @Override
        public CompletableFuture<Void> shutdown() {
            draining = true;
            CompletableFuture<Void> done = super.shutdown();
            for (EndPoint endPoint : getConnectedEndPoints()) {
                if (!busy.contains(endPoint)) {
                    endPoint.setIdleTimeout(SHUTDOWN_IDLE_TIMEOUT.toMillis());
                }
            }
            return done;
        }

        @Override
        protected void doStart() throws Exception {
            draining = false;
            super.doStart();
        }

        /**
         * Wraps the handler so that a connection counts as busy from the
         * moment its request reaches the handler until its response has been
         * written in full. HTTP/1.1 runs one request at a time on a
         * connection, so a set of connections is enough. A response that
         * completes during the drain leaves an idle connection, which then
         * gets the short timeout the idle ones got when the drain began.
         * Jetty calls a completion listener before the connection moves on to
         * its next request, so the mark never comes off that request.
         */
        Handler tracking(Handler handler) {
            return new Handler.Wrapper(handler) {
                @Override
                public boolean handle(Request request, Response response, Callback callback)
                        throws Exception {
                    EndPoint endPoint = request.getConnectionMetaData().getConnection().getEndPoint();
                    busy.add(endPoint);
                    Request.addCompletionListener(request, failure -> {
                        busy.remove(endPoint);
                        if (draining) {
                            endPoint.setIdleTimeout(SHUTDOWN_IDLE_TIMEOUT.toMillis());
                        }
                    });
                    return super.handle(request, response, callback);
                }
            };
        }
    }
}
