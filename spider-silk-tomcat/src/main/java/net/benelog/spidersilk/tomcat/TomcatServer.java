package net.benelog.spidersilk.tomcat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import jakarta.servlet.MultipartConfigElement;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.ProtocolHandler;

import net.benelog.spidersilk.App;
import net.benelog.spidersilk.AppServlet;
import net.benelog.spidersilk.server.WebServer;

/**
 * An embedded Tomcat running an {@link App}, as an alternative to the Jetty
 * bundled with core.
 *
 * <pre>{@code
 * new App().get("/", req -> WebResponse.text("hi"))
 *          .server((app, port) -> new TomcatServer(app).port(port))
 *          .start(8080);
 * }</pre>
 *
 * <p>The surface mirrors {@code JettyServer} method for method, so a switch is
 * one line at the factory. Two differences are Tomcat's, not ours, and are left
 * visible rather than papered over:
 *
 * <ul>
 *   <li>There is no {@code sessions(boolean)}. Tomcat's {@code StandardContext}
 *       installs a session manager on start and offers no way to leave it out.</li>
 *   <li>Graceful shutdown is hand-rolled here — pause the connector, then let
 *       the request threads finish — because Tomcat has no {@code stopTimeout}
 *       of its own. It therefore only applies while the connector runs on a
 *       {@link ThreadPoolExecutor}, which is the default but not what
 *       {@link #executor(Executor)} necessarily hands it.</li>
 * </ul>
 *
 * <p>Anything not covered by a method here is reachable through the three
 * customizers, which run against the real Tomcat objects just before startup.
 */
public final class TomcatServer implements WebServer {

    /** The port used when none is given. */
    public static final int DEFAULT_PORT = 8080;

    /** How long {@link #stop()} lets requests in flight finish. */
    public static final Duration DEFAULT_STOP_TIMEOUT = Duration.ofSeconds(5);

    private static final String SERVLET_NAME = "spider-silk";

    private final App app;
    private final List<Consumer<Tomcat>> tomcatCustomizers = new ArrayList<>();
    private final List<Consumer<Context>> contextCustomizers = new ArrayList<>();
    private final List<Consumer<Connector>> connectorCustomizers = new ArrayList<>();

    private int port = DEFAULT_PORT;
    private String host;
    private String contextPath = "/";
    private Path baseDir;
    private Executor executor;
    private MultipartConfigElement multipart = defaultMultipartConfig();
    private Duration stopTimeout = DEFAULT_STOP_TIMEOUT;
    private boolean shutdownHook = true;

    private Tomcat tomcat;
    private Connector connector;
    private Thread awaitThread;
    private Thread hook;
    private Path temporaryBaseDir;

    /** A server for the app, on the defaults above until the setters say otherwise. */
    public TomcatServer(App app) {
        this.app = Objects.requireNonNull(app, "app");
    }

    /** The port to bind. 0 picks a free one, which {@link #port()} reports back. */
    public TomcatServer port(int port) {
        this.port = port;
        return this;
    }

    /** The interface to bind. The default, null, binds all of them. */
    public TomcatServer host(String host) {
        this.host = host;
        return this;
    }

    /** The context path the app is mounted under. Defaults to "/". */
    public TomcatServer contextPath(String contextPath) {
        this.contextPath = Objects.requireNonNull(contextPath, "contextPath");
        return this;
    }

    /**
     * Tomcat's working directory, its {@code catalina.base}. The default is a
     * temporary directory created on start and deleted on stop; a directory
     * given here is left alone. Setting one matters because Tomcat writes here
     * — leave it at the default and nothing lands in the working directory.
     */
    public TomcatServer baseDir(Path baseDir) {
        this.baseDir = baseDir;
        return this;
    }

    /**
     * The executor that runs the handlers. The default is Tomcat's own pool,
     * sized by the connector's {@code maxThreads}.
     *
     * <p>This is also where virtual threads come in:
     *
     * <pre>{@code
     * app.server((a, port) -> new TomcatServer(a)
     *                 .port(port)
     *                 .executor(Executors.newVirtualThreadPerTaskExecutor()))
     *    .start(8080);
     * }</pre>
     *
     * <p>A recipe rather than a method of ours, for the same reason as on Jetty:
     * it is one line of the JDK's own API, and it only pays off when the
     * handlers block. Note that an executor which is not a
     * {@link ThreadPoolExecutor} — the virtual-thread one included — leaves
     * {@link #stopTimeout(Duration)} with nothing to wait on, so the drain
     * becomes a no-op.
     */
    public TomcatServer executor(Executor executor) {
        this.executor = executor;
        return this;
    }

    /**
     * Multipart limits for {@code req.file(...)} uploads.
     * The default caches to the system temp directory with no size cap and a
     * 1MB in-memory threshold. Pass null to turn multipart handling off.
     */
    public TomcatServer multipart(MultipartConfigElement multipart) {
        this.multipart = multipart;
        return this;
    }

    /**
     * How long {@link #stop()} waits for requests in flight before dropping
     * them. Five seconds by default. Idle keep-alive connections do not hold
     * this up — they hold no thread. {@link Duration#ZERO} stops immediately.
     */
    public TomcatServer stopTimeout(Duration stopTimeout) {
        this.stopTimeout = Objects.requireNonNull(stopTimeout, "stopTimeout");
        return this;
    }

    /**
     * Whether a JVM shutdown hook stops the server on Ctrl-C or SIGTERM. On by
     * default. Unlike Jetty's {@code setStopAtShutdown}, this hook is ours; it
     * is removed again on {@link #stop()}, so a process that starts a server
     * per test does not accumulate any.
     */
    public TomcatServer shutdownHook(boolean shutdownHook) {
        this.shutdownHook = shutdownHook;
        return this;
    }

    /** Runs against the {@link Tomcat} after the context is set up, before startup. */
    public TomcatServer customizeTomcat(Consumer<Tomcat> customizer) {
        tomcatCustomizers.add(Objects.requireNonNull(customizer, "customizer"));
        return this;
    }

    /** Runs against the {@link Context} after the servlet is mapped. */
    public TomcatServer customizeContext(Consumer<Context> customizer) {
        contextCustomizers.add(Objects.requireNonNull(customizer, "customizer"));
        return this;
    }

    /** Runs against the {@link Connector} before startup. */
    public TomcatServer customizeConnector(Consumer<Connector> customizer) {
        connectorCustomizers.add(Objects.requireNonNull(customizer, "customizer"));
        return this;
    }

    /** The underlying Tomcat, available once {@link #start()} has run. */
    public Tomcat tomcat() {
        return tomcat;
    }

    /**
     * The registered shutdown hook, or null. Package-private and here for the
     * test that asserts {@link #stop()} takes it back out again — reading it
     * off the JVM would mean reflecting into {@code java.lang}.
     */
    Thread shutdownHookThread() {
        return hook;
    }

    @Override
    public void start() {
        if (tomcat != null) {
            throw new IllegalStateException("Tomcat is already running on port " + port());
        }
        Tomcat candidate = new Tomcat();
        try {
            Path base = resolveBaseDir();
            candidate.setBaseDir(base.toString());
            candidate.setPort(port);

            Connector newConnector = candidate.getConnector();
            applyHost(newConnector);
            applyExecutor(newConnector);
            connectorCustomizers.forEach(customizer -> customizer.accept(newConnector));

            createContext(candidate, base);
            tomcatCustomizers.forEach(customizer -> customizer.accept(candidate));

            candidate.start();
            tomcat = candidate;
            connector = newConnector;
        } catch (LifecycleException | RuntimeException e) {
            deleteTemporaryBaseDir();
            throw new IllegalStateException("Failed to start Tomcat on port " + port, e);
        }
        startAwaitThread();
        if (shutdownHook) {
            registerShutdownHook();
        }
    }

    @Override
    public void stop() {
        if (tomcat == null) {
            return;
        }
        Tomcat running = tomcat;
        Connector runningConnector = connector;
        Thread runningAwait = awaitThread;
        tomcat = null;
        connector = null;
        awaitThread = null;
        removeShutdownHook();
        try {
            drain(runningConnector);
            running.stop();
            running.destroy();
            joinQuietly(runningAwait);
        } catch (LifecycleException e) {
            throw new IllegalStateException("Failed to stop Tomcat", e);
        } finally {
            deleteTemporaryBaseDir();
        }
    }

    @Override
    public void join() {
        Thread thread = awaitThread;
        if (thread == null) {
            throw new IllegalStateException("Tomcat is not running");
        }
        try {
            thread.join();
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

    private void createContext(Tomcat tomcat, Path base) {
        // Tomcat spells the root context "", not "/", and wants a docBase that
        // exists even though every byte we serve comes off the classpath.
        String path = "/".equals(contextPath) ? "" : contextPath;
        Path docBase = base.resolve("webapp");
        try {
            Files.createDirectories(docBase);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create the Tomcat docBase " + docBase, e);
        }

        Context context = tomcat.addContext(path, docBase.toString());
        Wrapper wrapper = Tomcat.addServlet(context, SERVLET_NAME, new AppServlet(app));
        if (multipart != null) {
            wrapper.setMultipartConfigElement(multipart);
        }
        context.addServletMappingDecoded("/*", SERVLET_NAME);

        contextCustomizers.forEach(customizer -> customizer.accept(context));
    }

    private void applyHost(Connector connector) {
        if (host == null) {
            return;
        }
        ProtocolHandler handler = connector.getProtocolHandler();
        if (handler instanceof AbstractProtocol<?> protocol) {
            try {
                protocol.setAddress(InetAddress.getByName(host));
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Unknown host: " + host, e);
            }
        }
    }

    private void applyExecutor(Connector connector) {
        if (executor != null) {
            connector.getProtocolHandler().setExecutor(executor);
        }
    }

    /**
     * Tomcat's own threads are daemons, so a process that only called
     * {@code start()} would exit the moment main returned. This non-daemon
     * thread parked in {@code Server.await()} is what keeps the JVM up, the
     * way Jetty's own non-daemon threads do. {@code stop()} ends the await, so
     * the thread is also what {@link #join()} joins.
     */
    private void startAwaitThread() {
        Tomcat running = tomcat;
        Thread thread = new Thread(() -> running.getServer().await(), "spider-silk-tomcat");
        thread.setDaemon(false);
        thread.setContextClassLoader(getClass().getClassLoader());
        awaitThread = thread;
        thread.start();
    }

    /**
     * Graceful shutdown by hand: Tomcat has no {@code stopTimeout}. Pausing the
     * connector stops it taking new connections while the requests already
     * running keep their threads, and shutting the pool down then waits for
     * exactly those to finish. Idle keep-alive connections hold no thread, so
     * they do not delay this.
     */
    private void drain(Connector connector) {
        if (connector == null || stopTimeout.isZero() || stopTimeout.isNegative()) {
            return;
        }
        connector.pause();
        if (connector.getProtocolHandler().getExecutor() instanceof ThreadPoolExecutor pool) {
            pool.shutdown();
            try {
                pool.awaitTermination(stopTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void registerShutdownHook() {
        hook = new Thread(this::stop, "spider-silk-tomcat-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
    }

    private void removeShutdownHook() {
        if (hook == null) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // The hook is running: the JVM is already shutting down.
        }
        hook = null;
    }

    private Path resolveBaseDir() {
        if (baseDir != null) {
            return baseDir;
        }
        try {
            temporaryBaseDir = Files.createTempDirectory("spider-silk-tomcat");
            return temporaryBaseDir;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create a base directory for Tomcat", e);
        }
    }

    private void deleteTemporaryBaseDir() {
        if (temporaryBaseDir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(temporaryBaseDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // A leftover file in a temp directory is not worth failing a stop over.
                }
            });
        } catch (IOException e) {
            // Same.
        }
        temporaryBaseDir = null;
    }

    private static void joinQuietly(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(TimeUnit.SECONDS.toMillis(1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static MultipartConfigElement defaultMultipartConfig() {
        return new MultipartConfigElement(
                System.getProperty("java.io.tmpdir"), -1L, -1L, 1024 * 1024);
    }
}
