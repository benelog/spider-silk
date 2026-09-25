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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import jakarta.servlet.MultipartConfigElement;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.ProtocolHandler;
import org.jspecify.annotations.Nullable;

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

    /** The parts one multipart request may carry, as on Jetty and Undertow. */
    private static final int MAX_PARTS = 1000;

    /** The bytes of headers one part may carry, as on Jetty. */
    private static final int MAX_PART_HEADER_BYTES = 8192;

    private final App app;
    private final List<Consumer<Tomcat>> tomcatCustomizers = new ArrayList<>();
    private final List<Consumer<Context>> contextCustomizers = new ArrayList<>();
    private final List<Consumer<Connector>> connectorCustomizers = new ArrayList<>();

    private int port = DEFAULT_PORT;
    private @Nullable String host;
    private String contextPath = "/";
    private @Nullable Path baseDir;
    private @Nullable Executor executor;
    private @Nullable MultipartConfigElement multipart = defaultMultipartConfig();
    private Duration stopTimeout = DEFAULT_STOP_TIMEOUT;
    private boolean shutdownHook = true;

    private @Nullable Tomcat tomcat;
    private @Nullable Connector connector;
    private @Nullable Thread awaitThread;
    private @Nullable Thread hook;
    private @Nullable Path temporaryBaseDir;

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
    public TomcatServer host(@Nullable String host) {
        this.host = host;
        return this;
    }

    /**
     * The context path the app is mounted under. Defaults to "/". A path
     * without its leading slash, {@code "app"}, is mounted at {@code /app}, as
     * on every server: Jetty took it as given and answered 404 to everything.
     */
    public TomcatServer contextPath(String contextPath) {
        Objects.requireNonNull(contextPath, "contextPath");
        this.contextPath = contextPath.isEmpty() || contextPath.startsWith("/") ? contextPath : "/" + contextPath;
        return this;
    }

    /**
     * Tomcat's working directory, its {@code catalina.base}. The default is a
     * temporary directory created on start and deleted on stop; a directory
     * given here is left alone. Setting one matters because Tomcat writes here
     * — leave it at the default and nothing lands in the working directory.
     */
    public TomcatServer baseDir(Path baseDir) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
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
        this.executor = Objects.requireNonNull(executor, "executor");
        return this;
    }

    /**
     * Multipart limits for {@code req.file(...)} uploads.
     * The default caches to the system temp directory with no size cap and a
     * 1MB in-memory threshold. Pass null to turn multipart handling off.
     */
    public TomcatServer multipart(@Nullable MultipartConfigElement multipart) {
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

    /**
     * Runs against the {@link Connector} before startup.
     *
     * <p>The connector arrives with {@code throwOnFailure} already on, so a port
     * that cannot be bound fails {@link #start()} with an
     * {@link IllegalStateException} instead of being logged while Tomcat starts
     * with nothing listening. Tomcat's own default, taken from
     * {@code org.apache.catalina.startup.EXIT_ON_INIT_FAILURE}, is off; a
     * customizer that turns it back off opts out of that guarantee.
     */
    public TomcatServer customizeConnector(Consumer<Connector> customizer) {
        connectorCustomizers.add(Objects.requireNonNull(customizer, "customizer"));
        return this;
    }

    /** The underlying Tomcat, available once {@link #start()} has run. */
    public @Nullable Tomcat tomcat() {
        return tomcat;
    }

    /**
     * The registered shutdown hook, or null. Package-private and here for the
     * test that asserts {@link #stop()} takes it back out again — reading it
     * off the JVM would mean reflecting into {@code java.lang}.
     */
    @Nullable Thread shutdownHookThread() {
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
            newConnector.setThrowOnFailure(true);
            applyHost(newConnector);
            applyExecutor(newConnector);
            // Tomcat's own defaults are 50 parts, text fields included, and 512
            // bytes of headers per part, which a form with many fields or a file
            // with a long name outgrows. These are Jetty's, and Undertow's count.
            newConnector.setMaxPartCount(MAX_PARTS);
            newConnector.setMaxPartHeaderSize(MAX_PART_HEADER_BYTES);
            // Tomcat reads a form body on POST alone. POST, PUT, and PATCH are
            // what Undertow reads and Jetty is set to, so a form on each reads alike.
            newConnector.setParseBodyMethods("POST,PUT,PATCH");
            connectorCustomizers.forEach(customizer -> customizer.accept(newConnector));

            createContext(candidate, base);
            tomcatCustomizers.forEach(customizer -> customizer.accept(candidate));

            candidate.start();
            tomcat = candidate;
            connector = newConnector;
        } catch (LifecycleException | RuntimeException e) {
            IllegalStateException failure =
                    new IllegalStateException("Failed to start Tomcat on port " + port, e);
            stopQuietly(candidate, failure);
            deleteTemporaryBaseDir();
            throw failure;
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
        if (context instanceof StandardContext standard) {
            // The drain has already waited the stop timeout for requests in flight,
            // so Tomcat's own two-second wait for them on unload would only add to it.
            // Not zero: Tomcat waits a twentieth of it at a time, and wait(0) is forever.
            standard.setUnloadDelay(100);
        }
        Wrapper wrapper = Tomcat.addServlet(context, SERVLET_NAME, new AppServlet(app));
        // Loaded while the context starts rather than on the first request,
        // which is when AppServlet takes the routes and closes registration.
        wrapper.setLoadOnStartup(0);
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
        Tomcat running = Objects.requireNonNull(tomcat);
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
     *
     * <p>Tomcat's own pool is {@code org.apache.tomcat.util.threads.ThreadPoolExecutor},
     * a copy of the JDK's that does not extend it, so both are named here.
     * Checking for the JDK's alone skipped the drain on the default executor,
     * and the stop fell through to Tomcat's two-second {@code unloadDelay}
     * before the request threads were interrupted.
     *
     * <p>Only the pool Tomcat made is shut down. One passed to
     * {@link #executor(Executor)} belongs to the application, and is waited
     * out without being stopped.
     */
    private void drain(@Nullable Connector connector) {
        if (connector == null || stopTimeout.isZero() || stopTimeout.isNegative()) {
            return;
        }
        connector.pause();
        ExecutorService pool = threadPool(connector.getProtocolHandler().getExecutor());
        if (pool == null) {
            return;
        }
        try {
            if (executor == null) {
                pool.shutdown();
                pool.awaitTermination(stopTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } else {
                awaitIdle(pool);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Waits until no thread of a pool the application passed in is running a
     * task, or until the stop timeout. The pool is the caller's: shutting it
     * down would refuse every request after a restart, and every other task
     * the application runs on it, so it is watched rather than stopped.
     */
    private void awaitIdle(ExecutorService pool) throws InterruptedException {
        long deadline = System.nanoTime() + stopTimeout.toNanos();
        while (activeCount(pool) > 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private static int activeCount(ExecutorService pool) {
        return pool instanceof ThreadPoolExecutor jdk
                ? jdk.getActiveCount()
                : ((org.apache.tomcat.util.threads.ThreadPoolExecutor) pool).getActiveCount();
    }

    /**
     * The executor as a pool whose threads the drain can wait out: Tomcat's own
     * or the JDK's thread pool. Null for anything else, such as the
     * virtual-thread executor, which has no threads of its own to wait for.
     */
    private static @Nullable ExecutorService threadPool(@Nullable Executor executor) {
        return executor instanceof ThreadPoolExecutor
                || executor instanceof org.apache.tomcat.util.threads.ThreadPoolExecutor
                ? (ExecutorService) executor
                : null;
    }

    /**
     * Unwinds a start that threw part-way. Deleting the temporary base
     * directory is not enough on its own: a candidate that got as far as
     * binding the connector holds the port against a retry, and its threads
     * stay behind. The cleanup is best-effort, so whatever it fails at is
     * reported on the start failure rather than replacing it.
     */
    private static void stopQuietly(Tomcat candidate, IllegalStateException failure) {
        try {
            candidate.stop();
            candidate.destroy();
        } catch (LifecycleException | RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
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

    private static void joinQuietly(@Nullable Thread thread) {
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
