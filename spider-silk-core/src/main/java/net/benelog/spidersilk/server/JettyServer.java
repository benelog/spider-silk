package net.benelog.spidersilk.server;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import jakarta.servlet.MultipartConfigElement;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.ThreadPool;

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

    private int port = DEFAULT_PORT;
    private String host;
    private String contextPath = "/";
    private boolean sessions = true;
    private ThreadPool threadPool;
    private MultipartConfigElement multipart = defaultMultipartConfig();
    private Duration stopTimeout = DEFAULT_STOP_TIMEOUT;
    private boolean shutdownHook = true;

    private Server server;
    private ServerConnector connector;

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
    public JettyServer host(String host) {
        this.host = host;
        return this;
    }

    /** The context path the app is mounted under. Defaults to "/". */
    public JettyServer contextPath(String contextPath) {
        this.contextPath = Objects.requireNonNull(contextPath, "contextPath");
        return this;
    }

    /**
     * Whether to install an HTTP session handler. On by default, because
     * {@code ctx.sessionAttr(...)} and {@code ctx.flash(...)} need one.
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
     * a database — and that a {@code synchronized} block around that blocking
     * call pins the carrier thread and undoes the benefit.
     */
    public JettyServer threadPool(ThreadPool threadPool) {
        this.threadPool = threadPool;
        return this;
    }

    /**
     * Multipart limits for {@code ctx.file(...)} uploads.
     * The default caches to the system temp directory with no size cap and a
     * 1MB in-memory threshold. Pass null to turn multipart handling off.
     */
    public JettyServer multipart(MultipartConfigElement multipart) {
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
    public Server jetty() {
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
            connector = createConnector(candidate);
            candidate.addConnector(connector);
            candidate.setHandler(createContext());
            candidate.setStopTimeout(stopTimeout.toMillis());
            candidate.setStopAtShutdown(shutdownHook);
            serverCustomizers.forEach(customizer -> customizer.accept(candidate));
            candidate.start();
            server = candidate;
        } catch (Exception e) {
            connector = null;
            throw new IllegalStateException("Failed to start Jetty on port " + port, e);
        }
    }

    @Override
    public void stop() {
        if (server == null) {
            return;
        }
        try {
            server.stop();
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

    private ServerConnector createConnector(Server server) {
        HttpConfiguration httpConfiguration = new HttpConfiguration();
        httpConfigurationCustomizers.forEach(customizer -> customizer.accept(httpConfiguration));

        ServerConnector connector =
                new ServerConnector(server, new HttpConnectionFactory(httpConfiguration));
        connector.setPort(port);
        // Without this, a graceful stop waits out the whole stop timeout for
        // keep-alive connections that are sitting idle, and then reports a
        // timeout. The drain is for requests in flight, not for open sockets.
        connector.setShutdownIdleTimeout(SHUTDOWN_IDLE_TIMEOUT.toMillis());
        if (host != null) {
            connector.setHost(host);
        }
        return connector;
    }

    private ServletContextHandler createContext() {
        ServletContextHandler context = new ServletContextHandler(
                sessions ? ServletContextHandler.SESSIONS : 0);
        context.setContextPath(contextPath);

        ServletHolder holder = new ServletHolder(new AppServlet(app));
        if (multipart != null) {
            holder.getRegistration().setMultipartConfig(multipart);
        }
        context.addServlet(holder, "/*");

        contextCustomizers.forEach(customizer -> customizer.accept(context));
        return context;
    }

    private static MultipartConfigElement defaultMultipartConfig() {
        return new MultipartConfigElement(
                System.getProperty("java.io.tmpdir"), -1L, -1L, 1024 * 1024);
    }
}
