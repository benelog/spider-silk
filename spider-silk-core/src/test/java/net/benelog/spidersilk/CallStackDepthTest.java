package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.test.WebTest;

/**
 * How much of Spider Silk a handler stands on: two frames, {@code AppServlet.service}
 * and {@code AppServlet.dispatch}, between {@code HttpServlet.service} and the handler.
 *
 * <p>The number is what the framework claims about itself, so the build asserts it.
 * It does not move with the container — Jetty, Tomcat, and Undertow each call the
 * same {@code HttpServlet.service} — and it does not move with what the application
 * registers, because every filter has returned before the handler is called.
 */
class CallStackDepthTest {

    private static final AtomicReference<List<StackWalker.StackFrame>> CAPTURED = new AtomicReference<>();

    /** Answers the request, and records the stack it was answered on. */
    private static WebResponse capture() {
        CAPTURED.set(StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames.toList()));
        return WebResponse.text("ok");
    }

    /**
     * The framework's own frames: what stands between the handler and the servlet
     * method the specification defines, less the test's own lambda.
     */
    private static List<String> frameworkFrames() {
        return CAPTURED.get().stream()
                .takeWhile(frame -> !frame.getClassName().equals("jakarta.servlet.http.HttpServlet"))
                .filter(frame -> frame.getClassName().startsWith("net.benelog.spidersilk."))
                .filter(frame -> !frame.getClassName().startsWith(CallStackDepthTest.class.getName()))
                .map(frame -> frame.getClassName() + "." + frame.getMethodName())
                .toList();
    }

    @Test
    void aHandlerStandsOnTwoFramesOfTheFramework() {
        App app = new App().get("/x", req -> capture());

        WebTest.test(app, client -> client.get("/x"));

        assertThat(frameworkFrames()).containsExactly(
                "net.benelog.spidersilk.AppServlet.dispatch",
                "net.benelog.spidersilk.AppServlet.service");
    }

    /**
     * Filters do not nest around the handler the way a servlet {@code Filter} chain
     * does: each one has answered, or declined to, before the handler is called.
     */
    @Test
    void whatTheApplicationRegistersAddsNoFrame() {
        App app = new App()
                .beforeRequest(req -> null)
                .beforeRoute(req -> null)
                .get("/x", req -> capture())
                .afterRoute((req, res) -> res)
                .responseFilter((req, res) -> res)
                .cors(Cors.allowOrigin("https://example.com"))
                .securityHeaders(SecurityHeaders.defaults())
                .gzip(Gzip.defaults())
                .requestLogger((req, completion) -> { });

        WebTest.test(app, client -> client.get("/x"));

        assertThat(frameworkFrames()).containsExactly(
                "net.benelog.spidersilk.AppServlet.dispatch",
                "net.benelog.spidersilk.AppServlet.service");
    }

    /** An exception handler is one deeper, because the handler that threw is unwound first. */
    @Test
    void anExceptionHandlerStandsOnThree() {
        App app = new App()
                .get("/x", req -> {
                    throw new IllegalStateException("boom");
                })
                .exception(IllegalStateException.class, (req, e) -> capture());

        WebTest.test(app, client -> client.get("/x"));

        assertThat(frameworkFrames()).containsExactly(
                "net.benelog.spidersilk.AppServlet.handleException",
                "net.benelog.spidersilk.AppServlet.dispatch",
                "net.benelog.spidersilk.AppServlet.service");
    }

    /**
     * How deep the framework is when it calls into the application, everywhere it
     * does. Four is the deepest, and the fourth frame there is the lambda the
     * application itself handed to {@link WebResponse#jsonArray}.
     *
     * <p>Object construction does not show up: a {@link WebRequest} is built in
     * {@code service} before {@code dispatch} is called, and a {@link WebResponse}
     * is built by the handler, so neither constructor is on the stack while the
     * application runs.
     */
    @Test
    void nothingTheFrameworkCallsGoesDeeperThanFour() {
        Map<String, Integer> depth = new LinkedHashMap<>();
        App app = new App()
                .beforeRequest(req -> {
                    depth.put("beforeRequest", frameCount());
                    return null;
                })
                .beforeRoute(req -> {
                    depth.put("beforeRoute", frameCount());
                    return null;
                })
                .get("/json", req -> {
                    depth.put("handler", frameCount());
                    return WebResponse.jsonArray(sink -> depth.put("streamedJson", frameCount()));
                })
                .get("/stream", req -> WebResponse.stream("text/plain",
                        out -> depth.put("streamedBody", frameCount())))
                .get("/boom", req -> {
                    throw new IllegalStateException("boom");
                })
                .afterRoute((req, res) -> {
                    depth.put("afterRoute", frameCount());
                    return res;
                })
                .responseFilter((req, res) -> {
                    depth.put("responseFilter", frameCount());
                    return res;
                })
                .exception(IllegalStateException.class, (req, e) -> {
                    depth.put("exception", frameCount());
                    return WebResponse.text("handled");
                })
                .requestLogger((req, completion) -> depth.put("requestLogger", frameCount()));

        WebTest.test(app, client -> {
            client.get("/json");
            client.get("/stream");
            client.get("/boom");
        });

        assertThat(depth).containsOnly(
                entry("beforeRequest", 3),
                entry("beforeRoute", 3),
                entry("handler", 2),
                entry("afterRoute", 3),
                entry("responseFilter", 3),
                entry("streamedJson", 4),
                entry("streamedBody", 3),
                entry("exception", 3),
                entry("requestLogger", 2));
        assertThat(depth.values()).allMatch(frames -> frames <= 4);
    }

    /** The framework's frames under the caller, however it was called into. */
    private static int frameCount() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> (int) frames
                        .takeWhile(frame -> !frame.getClassName().equals("jakarta.servlet.http.HttpServlet"))
                        .filter(frame -> frame.getClassName().startsWith("net.benelog.spidersilk."))
                        .filter(frame -> !frame.getClassName().startsWith(CallStackDepthTest.class.getName()))
                        .count());
    }
}
