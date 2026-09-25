package net.benelog.spidersilk.undertow;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.Cookie;
import io.undertow.server.handlers.GracefulShutdownHandler;
import io.undertow.server.handlers.resource.ResourceManager;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.LoggingExceptionHandler;
import io.undertow.servlet.api.ServletInfo;
import io.undertow.servlet.api.ServletSessionConfig;
import io.undertow.servlet.util.ImmediateInstanceFactory;

import org.jspecify.annotations.Nullable;
import org.xnio.IoUtils;

import net.benelog.spidersilk.App;
import net.benelog.spidersilk.AppServlet;
import net.benelog.spidersilk.server.WebServer;

/**
 * An embedded Undertow running an {@link App}, as an alternative to the Jetty
 * bundled with core.
 *
 * <pre>{@code
 * new App().get("/", req -> WebResponse.text("hi"))
 *          .server((app, port) -> new UndertowServer(app).port(port))
 *          .start(8080);
 * }</pre>
 *
 * <p>The surface mirrors {@code JettyServer} and {@code TomcatServer}, so a
 * switch is one line at the factory. Where Tomcat needed a hand-rolled drain,
 * Undertow has {@code GracefulShutdownHandler} and this only has to wire it up;
 * what it lacks instead is a lifecycle that keeps the JVM alive, so
 * {@link #join()} and the non-daemon thread behind it are this class's own.
 *
 * <p>There is no {@code sessions(boolean)}: Undertow's deployment always gets a
 * session manager. Two customizers cover everything not exposed as a method —
 * one for the servlet deployment, one for the server itself.
 */
public final class UndertowServer implements WebServer {

    /** The port used when none is given. */
    public static final int DEFAULT_PORT = 8080;

    /** How long {@link #stop()} lets requests in flight finish. */
    public static final Duration DEFAULT_STOP_TIMEOUT = Duration.ofSeconds(5);

    private static final String SERVLET_NAME = "spider-silk";
    private static final String DEPLOYMENT_NAME = "spider-silk";
    private static final String DEFAULT_HOST = "0.0.0.0";

    private final App app;
    private final List<Consumer<DeploymentInfo>> deploymentCustomizers = new ArrayList<>();
    private final List<Consumer<Undertow.Builder>> builderCustomizers = new ArrayList<>();

    private int port = DEFAULT_PORT;
    private @Nullable String host;
    private String contextPath = "/";
    private @Nullable Executor executor;
    private @Nullable MultipartConfigElement multipart = defaultMultipartConfig();
    private Duration stopTimeout = DEFAULT_STOP_TIMEOUT;
    private boolean shutdownHook = true;

    private @Nullable Undertow server;
    private @Nullable DeploymentManager deployment;
    private @Nullable GracefulShutdownHandler graceful;
    private @Nullable CountDownLatch stopped;
    private @Nullable Thread awaitThread;
    private @Nullable Thread hook;

    /** A server for the app, on the defaults above until the setters say otherwise. */
    public UndertowServer(App app) {
        this.app = Objects.requireNonNull(app, "app");
    }

    /** The port to bind. 0 picks a free one, which {@link #port()} reports back. */
    public UndertowServer port(int port) {
        this.port = port;
        return this;
    }

    /** The interface to bind. The default, null, binds all of them. */
    public UndertowServer host(@Nullable String host) {
        this.host = host;
        return this;
    }

    /**
     * The context path the app is mounted under. Defaults to "/", and {@code ""}
     * is the root as well, as Jetty and Tomcat take it; Undertow's own path
     * handler refuses an empty prefix. A path without its leading slash,
     * {@code "app"}, is mounted at {@code /app}, as on every server.
     */
    public UndertowServer contextPath(String contextPath) {
        Objects.requireNonNull(contextPath, "contextPath");
        this.contextPath = contextPath.isEmpty() ? "/" : contextPath.startsWith("/") ? contextPath : "/" + contextPath;
        return this;
    }

    /**
     * The executor that runs the handlers. The default is Undertow's own XNIO
     * worker, and this replaces it for servlet dispatch only — the I/O threads
     * stay Undertow's either way.
     *
     * <pre>{@code
     * app.server((a, port) -> new UndertowServer(a)
     *                 .port(port)
     *                 .executor(Executors.newVirtualThreadPerTaskExecutor()))
     *    .start(8080);
     * }</pre>
     *
     * <p>Unlike on Tomcat, this does not disturb the drain:
     * {@link #stopTimeout(Duration)} is Undertow's own request counter, not a
     * thread pool being shut down, so it still works whatever runs the handlers.
     */
    public UndertowServer executor(Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
        return this;
    }

    /**
     * Multipart limits for {@code req.file(...)} uploads.
     * The default caches to the system temp directory with no size cap and a
     * 1MB in-memory threshold. Pass null to turn multipart handling off.
     */
    public UndertowServer multipart(@Nullable MultipartConfigElement multipart) {
        this.multipart = multipart;
        return this;
    }

    /**
     * How long {@link #stop()} waits for requests in flight before dropping
     * them. Five seconds by default. Idle keep-alive connections do not hold
     * this up — Undertow counts requests, not connections.
     * {@link Duration#ZERO} stops immediately.
     */
    public UndertowServer stopTimeout(Duration stopTimeout) {
        this.stopTimeout = Objects.requireNonNull(stopTimeout, "stopTimeout");
        return this;
    }

    /**
     * Whether a JVM shutdown hook stops the server on Ctrl-C or SIGTERM. On by
     * default. The hook is ours and is removed again on {@link #stop()}, so a
     * process that starts a server per test does not accumulate any.
     */
    public UndertowServer shutdownHook(boolean shutdownHook) {
        this.shutdownHook = shutdownHook;
        return this;
    }

    /** Runs against the {@link DeploymentInfo} before it is deployed. */
    public UndertowServer customizeDeployment(Consumer<DeploymentInfo> customizer) {
        deploymentCustomizers.add(Objects.requireNonNull(customizer, "customizer"));
        return this;
    }

    /** Runs against the {@link Undertow.Builder} before the server is built. */
    public UndertowServer customizeBuilder(Consumer<Undertow.Builder> customizer) {
        builderCustomizers.add(Objects.requireNonNull(customizer, "customizer"));
        return this;
    }

    /** The underlying Undertow, available once {@link #start()} has run. */
    public @Nullable Undertow undertow() {
        return server;
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
        if (server != null) {
            throw new IllegalStateException("Undertow is already running on port " + port());
        }
        DeploymentManager manager = Servlets.newContainer().addDeployment(createDeployment());
        // Everything from the deployment on runs under one guard. Starting the
        // deployment initializes AppServlet, which closes the App's registration
        // and registers its SSE hook, and only undeploying opens them again: a
        // failure left outside the guard kept the App closed for good.
        Undertow candidate = null;
        try {
            manager.deploy();
            graceful = Handlers.gracefulShutdown(
                    Handlers.path().addPrefixPath(contextPath, manager.start()));

            Undertow.Builder builder = Undertow.builder()
                    .addHttpListener(port, host != null ? host : DEFAULT_HOST)
                    .setHandler(checkingRequestTarget(keepingSameSite(graceful)));
            builderCustomizers.forEach(customizer -> customizer.accept(builder));

            candidate = builder.build();
            candidate.start();
        } catch (ServletException | RuntimeException e) {
            IllegalStateException failure =
                    new IllegalStateException("Failed to start Undertow on port " + port, e);
            if (candidate != null) {
                stopQuietly(candidate, failure);
            }
            undeployQuietly(manager);
            graceful = null;
            throw failure;
        }
        server = candidate;
        deployment = manager;

        startAwaitThread();
        if (shutdownHook) {
            registerShutdownHook();
        }
    }

    @Override
    public void stop() {
        if (server == null) {
            return;
        }
        Undertow running = server;
        DeploymentManager runningDeployment = deployment;
        GracefulShutdownHandler runningGraceful = graceful;
        CountDownLatch runningStopped = stopped;
        Thread runningAwait = awaitThread;
        server = null;
        deployment = null;
        graceful = null;
        stopped = null;
        awaitThread = null;
        removeShutdownHook();

        drain(runningGraceful);
        running.stop();
        undeployQuietly(runningDeployment);
        if (runningStopped != null) {
            runningStopped.countDown();
        }
        joinQuietly(runningAwait);
    }

    @Override
    public void join() {
        Thread thread = awaitThread;
        if (thread == null) {
            throw new IllegalStateException("Undertow is not running");
        }
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public int port() {
        if (server != null) {
            List<Undertow.ListenerInfo> listeners = server.getListenerInfo();
            if (!listeners.isEmpty()) {
                SocketAddress address = listeners.get(0).getAddress();
                if (address instanceof InetSocketAddress inet && inet.getPort() > 0) {
                    return inet.getPort();
                }
            }
        }
        return port;
    }

    private DeploymentInfo createDeployment() {
        ServletInfo servlet = Servlets
                .servlet(SERVLET_NAME, AppServlet.class,
                        new ImmediateInstanceFactory<>(new AppServlet(app)))
                .addMapping("/*")
                // Initialized when the deployment starts rather than on the first
                // request, which is when AppServlet takes the routes and closes
                // registration.
                .setLoadOnStartup(0);
        if (multipart != null) {
            servlet.setMultipartConfig(multipart);
        }

        DeploymentInfo info = Servlets.deployment()
                .setClassLoader(getClass().getClassLoader())
                .setContextPath(contextPath)
                .setDeploymentName(DEPLOYMENT_NAME)
                // Static files are read by core's own StaticFiles, classpath
                // root or directory alike, so Undertow is never asked for one.
                .setResourceManager(ResourceManager.EMPTY_RESOURCE_MANAGER)
                .setExceptionHandler(UndertowServer::abortStartedResponse)
                // Undertow leaves the session cookie readable by a script, where
                // Tomcat marks it HttpOnly.
                .setServletSessionConfig(new ServletSessionConfig().setHttpOnly(true))
                .addServlet(servlet);
        if (executor != null) {
            info.setExecutor(executor);
        }

        deploymentCustomizers.forEach(customizer -> customizer.accept(info));
        return info;
    }

    /**
     * What a servlet failure after the response has started does to the
     * connection. AppServlet rethrows a body that failed once its status was on
     * the wire, so the container can cut the transfer short. Jetty and Tomcat
     * abort it; Undertow, left to itself, finishes the response as if the body
     * were whole, ending a chunked stream with its last chunk. Closing the
     * connection first leaves the client a transfer it can tell was cut short.
     * A failure before the response started is left to Undertow's own handling.
     */
    private static boolean abortStartedResponse(HttpServerExchange exchange,
            ServletRequest request, ServletResponse response, Throwable failure) {
        boolean handled = LoggingExceptionHandler.DEFAULT
                .handleThrowable(exchange, request, response, failure);
        if (exchange.isResponseStarted()) {
            IoUtils.safeClose(exchange.getConnection());
        }
        return handled;
    }

    /**
     * Graceful shutdown, and the one place Undertow is the easiest of the three:
     * {@code GracefulShutdownHandler} counts the requests it has let through, so
     * stopping means refusing new ones and waiting for that count to reach zero.
     * No thread pool is involved, which is why {@link #executor(Executor)} does
     * not affect it.
     */
    private void drain(@Nullable GracefulShutdownHandler graceful) {
        if (graceful == null || stopTimeout.isZero() || stopTimeout.isNegative()) {
            return;
        }
        graceful.shutdown();
        try {
            graceful.awaitShutdown(stopTimeout.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Undertow's worker threads do not hold the JVM up on their own, so this
     * non-daemon thread parked on a latch does it, the way Jetty's own
     * non-daemon threads do. {@code stop()} counts the latch down, so the
     * thread is also what {@link #join()} joins.
     */
    private void startAwaitThread() {
        CountDownLatch latch = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "spider-silk-undertow");
        thread.setDaemon(false);
        stopped = latch;
        awaitThread = thread;
        thread.start();
    }

    private void registerShutdownHook() {
        hook = new Thread(this::stop, "spider-silk-undertow-shutdown");
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

    /**
     * The request-targets Jetty and Tomcat refuse before the servlet runs, and
     * Undertow does not.
     *
     * <ul>
     *   <li>A target that is not a path, the {@code *} of {@code OPTIONS *},
     *       answers 404, as on Jetty. Undertow's servlet path matcher threw for
     *       it, which answered a bare 500 and logged a stack trace at ERROR.</li>
     *   <li>An encoded slash answers 400. Undertow decodes every other escape
     *       but leaves {@code %2F} as it is, so {@code /v/a%2Fb} reached a path
     *       variable as {@code a%2Fb}, the same value {@code /v/a%252Fb} decodes
     *       to: two requests read as one.</li>
     *   <li>An escape whose bytes are not UTF-8 answers 400. Undertow decoded
     *       {@code a%FF} as {@code a\uFFFD}, a value the client never sent, and
     *       the same one {@code a%EF%BF%BD} decodes to.</li>
     * </ul>
     */
    private static HttpHandler checkingRequestTarget(HttpHandler next) {
        return exchange -> {
            if (!exchange.getRequestPath().startsWith("/")) {
                exchange.setStatusCode(404);
                exchange.endExchange();
                return;
            }
            String uri = exchange.getRequestURI();
            if (uri.contains("%2F") || uri.contains("%2f") || !escapesAreUtf8(uri)) {
                exchange.setStatusCode(400);
                exchange.endExchange();
                return;
            }
            next.handleRequest(exchange);
        };
    }

    /**
     * Sends the {@code SameSite} attribute a servlet cookie carries. Undertow's
     * servlet adaptor answers the attribute as the cookie's mode but reports the
     * cookie as having none, and its serialiser then leaves the attribute out,
     * so {@code cookie(name, value)} went out without the {@code SameSite=Lax}
     * it promises, and a cookie set to {@code SameSite=None} without that either.
     * The cookies are marked just before the headers are written, when every
     * one of them has been added.
     */
    private static HttpHandler keepingSameSite(HttpHandler next) {
        return exchange -> {
            exchange.addResponseCommitListener(committing -> {
                for (Cookie cookie : committing.responseCookies()) {
                    String mode = cookie.getSameSiteMode();
                    if (!cookie.isSameSite() && mode != null && !mode.isEmpty()) {
                        cookie.setSameSite(true);
                    }
                }
            });
            next.handleRequest(exchange);
        };
    }

    /**
     * Whether the percent-escapes of a request URI decode as UTF-8, strictly,
     * as the query string is decoded. An escape that is not two hex digits is
     * left to Undertow, which refuses it on its own.
     */
    private static boolean escapesAreUtf8(String uri) {
        if (uri.indexOf('%') < 0) {
            return true;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int start = 0;
        for (int i = 0; i + 2 < uri.length(); i++) {
            if (uri.charAt(i) == '%') {
                int high = Character.digit(uri.charAt(i + 1), 16);
                int low = Character.digit(uri.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    bytes.writeBytes(uri.substring(start, i).getBytes(StandardCharsets.UTF_8));
                    bytes.write(high << 4 | low);
                    i += 2;
                    start = i + 1;
                }
            }
        }
        bytes.writeBytes(uri.substring(start).getBytes(StandardCharsets.UTF_8));
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /**
     * Unwinds a start that threw part-way. Undeploying the servlet is not
     * enough on its own: a listener that did bind holds the port against a
     * retry, and Undertow's XNIO worker keeps its threads. The cleanup is
     * best-effort, so whatever it fails at is reported on the start failure
     * rather than replacing it.
     */
    private static void stopQuietly(Undertow candidate, IllegalStateException failure) {
        try {
            candidate.stop();
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static void undeployQuietly(@Nullable DeploymentManager manager) {
        if (manager == null) {
            return;
        }
        try {
            manager.stop();
        } catch (ServletException e) {
            // Nothing left to do about it; the server is going away regardless.
        }
        manager.undeploy();
    }

    private static void joinQuietly(@Nullable Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(1_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static MultipartConfigElement defaultMultipartConfig() {
        return new MultipartConfigElement(
                System.getProperty("java.io.tmpdir"), -1L, -1L, 1024 * 1024);
    }
}
