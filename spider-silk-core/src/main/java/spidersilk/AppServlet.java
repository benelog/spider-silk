package spidersilk;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import jakarta.servlet.http.HttpSession;

/**
 * The entry point that deploys an {@link App} to a servlet container.
 * Mapped to "/*", it takes care of routing, static files, and exception handling.
 *
 * <p>Handlers answer with a {@link WebResponse} rather than writing one, so this
 * class runs in two halves: {@link #dispatch} works out what the answer is, and
 * {@link #write} puts it on the wire. Everything that can be routed to
 * {@link App#exception} happens in the first half — template rendering included,
 * which is why a {@link WebResponse.Template} is materialized before the second
 * half begins.
 */
public class AppServlet extends HttpServlet {

    private final App app;

    public AppServlet(App app) {
        this.app = app;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
        req.setCharacterEncoding("UTF-8");
        promoteFlash(req);

        long startedAt = System.nanoTime();
        WebRequest request = new WebRequest(req, Map.of());
        WebResponse response = dispatch(request);
        try {
            write(response, req, res, "HEAD".equals(req.getMethod()));
        } catch (Exception e) {
            writeFailed(e, res);
        } finally {
            logRequest(request, response, startedAt);
        }
    }

    /** Reports the finished request, with the response it was finally answered with. */
    private void logRequest(WebRequest request, WebResponse response, long startedAt) {
        if (app.requestLogger == null) {
            return;
        }
        try {
            app.requestLogger.log(request, response, (System.nanoTime() - startedAt) / 1_000_000);
        } catch (Exception e) {
            log("Request logger failed", e);
        }
    }

    // ---- Working out the answer ----

    /** Never throws and never returns null: every path here ends in a response. */
    private WebResponse dispatch(WebRequest request) {
        HttpServletRequest req = request.raw();
        String method = req.getMethod();
        String path = request.path();
        String[] segments = PathPattern.split(path);
        WebRequest current = request;
        WebResponse response;
        try {
            Router.Match match = routeFor(method, path);
            if (match != null) {
                current = request.withPathParams(match.pathParams());
                WebResponse answered = runBefore(segments, current);
                if (answered != null) {
                    response = answered;
                } else {
                    response = required(match.handler().handle(current), "Handler", method, path);
                    response = runAfter(segments, current, response);
                }
            } else if (isReadMethod(method) && app.staticFiles != null) {
                WebResponse file = app.staticFiles.resolve(path, req);
                if (file != null) {
                    return file;
                }
                response = noRoute(current, method, path);
            } else {
                response = noRoute(current, method, path);
            }
            response = renderTemplate(response);
        } catch (Exception e) {
            response = handleException(e, current);
        }
        return completeErrorResponse(response, current);
    }

    /** A HEAD with no route of its own is answered by the GET route, minus the body. */
    private Router.Match routeFor(String method, String path) {
        Router.Match match = app.router.find(method, path);
        if (match == null && "HEAD".equals(method)) {
            return app.router.find("GET", path);
        }
        return match;
    }

    /**
     * The answer when nothing is registered for this path and method: 404 when
     * the path is unknown, and otherwise an {@code Allow} header with either an
     * OPTIONS answer or a 405 behind it.
     */
    private WebResponse noRoute(WebRequest request, String method, String path) {
        Set<String> allowed = allowedMethods(path);
        if (allowed.isEmpty()) {
            return fail(request, 404, "Not Found: " + path);
        }
        String allow = String.join(", ", allowed);
        if ("OPTIONS".equals(method)) {
            return WebResponse.empty().header("Allow", allow).header("Content-Length", "0");
        }
        return fail(request, 405, "Method Not Allowed: " + method + " " + path)
                .header("Allow", allow);
    }

    /**
     * What the path answers to, for the {@code Allow} header of a 405 or an
     * OPTIONS response. HEAD and OPTIONS are in there because this servlet
     * answers them without a route being registered for either.
     */
    private Set<String> allowedMethods(String path) {
        Set<String> registered = app.router.allowedMethods(path);
        if (registered.isEmpty()) {
            return registered;
        }
        Set<String> allowed = new LinkedHashSet<>(registered);
        if (allowed.contains("GET")) {
            allowed.add("HEAD");
        }
        allowed.add("OPTIONS");
        return allowed;
    }

    private boolean isReadMethod(String method) {
        return "GET".equals(method) || "HEAD".equals(method);
    }

    /**
     * Runs the matching before-filters and reports whether one of them answered.
     * A filter that returns a response — a redirect to the login page, a 403 —
     * ends the request there: the route handler must not run after a guard has
     * turned the caller away. To reject with the framework's own body, throw an
     * {@link HttpException}.
     */
    private WebResponse runBefore(String[] segments, WebRequest request) throws Exception {
        for (BeforeEntry entry : app.beforeFilters) {
            if (entry.matches(segments)) {
                WebResponse answered = entry.filter().handle(request);
                if (answered != null) {
                    return answered;
                }
            }
        }
        return null;
    }

    /** Lets each matching after-filter replace the response, or leave it alone by returning null. */
    private WebResponse runAfter(String[] segments, WebRequest request, WebResponse response)
            throws Exception {
        WebResponse current = response;
        for (AfterEntry entry : app.afterFilters) {
            if (entry.matches(segments)) {
                WebResponse replaced = entry.filter().handle(request, current);
                if (replaced != null) {
                    current = replaced;
                }
            }
        }
        return current;
    }

    /**
     * Renders a {@link WebResponse.Template} into the text it produces, while the
     * exception handling around the handler still applies. A template that throws
     * therefore reaches {@link App#exception} rather than the container, and a
     * HEAD of a rendered page reports the length the GET would have sent.
     */
    private WebResponse renderTemplate(WebResponse response) {
        if (!(response.body() instanceof WebResponse.Template template)) {
            return response;
        }
        if (app.templates == null) {
            throw new IllegalStateException(
                    "No template engine configured. Call App.templates(...).");
        }
        StringWriter out = new StringWriter();
        app.templates.render(template.name(), template.model(), out);
        return response.body(new WebResponse.Text(out.toString()));
    }

    /** Moves flash left in the session by the request before the redirect into this request. */
    private void promoteFlash(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) {
            return;
        }
        Object flash = session.getAttribute(WebRequest.FLASH_ATTRIBUTE);
        if (flash != null) {
            session.removeAttribute(WebRequest.FLASH_ATTRIBUTE);
            req.setAttribute(WebRequest.FLASH_ATTRIBUTE, flash);
        }
    }

    private WebResponse handleException(Exception e, WebRequest request) {
        for (var entry : app.exceptionHandlers.entrySet()) {
            if (entry.getKey().isInstance(e)) {
                @SuppressWarnings("unchecked")
                ExceptionHandler<Exception> handler =
                        (ExceptionHandler<Exception>) entry.getValue();
                try {
                    return renderTemplate(required(handler.handle(e, request),
                            "Exception handler", request.method(), request.path()));
                } catch (Exception handlerFailure) {
                    return internalError(handlerFailure, request);
                }
            }
        }
        if (e instanceof HttpException httpException) {
            return fail(request, httpException.status(), httpException.getMessage());
        }
        return internalError(e, request);
    }

    private WebResponse internalError(Exception e, WebRequest request) {
        log("Error while handling request", e);
        return fail(request, 500, "Internal Server Error");
    }

    /** Records the body the framework would answer with by default, and the status. */
    private WebResponse fail(WebRequest request, int status, String message) {
        request.errorMessage(message);
        return WebResponse.empty(status);
    }

    private WebResponse required(WebResponse response, String what, String method, String path) {
        if (response == null) {
            throw new NullPointerException(
                    "%s returned no response for %s %s".formatted(what, method, path));
        }
        return response;
    }

    /**
     * Fills in the body of an error response nobody wrote one for, preferring a
     * handler registered through {@link App#error(int, Handler)}. Whatever the
     * handler returns keeps the headers already set — the {@code Allow} of a 405,
     * say — and answers with the registered status unless it set one itself.
     */
    private WebResponse completeErrorResponse(WebResponse response, WebRequest request) {
        if (response.status() < 400 || !(response.body() instanceof WebResponse.Empty)) {
            return response;
        }
        int status = response.status();
        Handler handler = app.errorHandlers.get(status);
        if (handler != null) {
            try {
                WebResponse answered = renderTemplate(required(handler.handle(request),
                        "Error handler", request.method(), request.path()));
                return answered.over(response).status(
                        answered.hasStatus() ? answered.status() : status);
            } catch (Exception e) {
                log("Error handler failed for status " + status, e);
                return WebResponse.text("Internal Server Error").status(500);
            }
        }
        if (request.errorMessage() != null) {
            return WebResponse.text(request.errorMessage()).over(response).status(status);
        }
        return response;
    }

    // ---- Putting the answer on the wire ----

    private void write(WebResponse response, HttpServletRequest req, HttpServletResponse res,
            boolean head) throws Exception {
        res.setStatus(response.status());
        for (Map.Entry<String, String> header : response.headers().entrySet()) {
            if ("Content-Type".equalsIgnoreCase(header.getKey())) {
                res.setContentType(header.getValue());
            } else {
                res.setHeader(header.getKey(), header.getValue());
            }
        }
        for (Cookie cookie : response.cookies()) {
            res.addCookie(cookie);
        }

        switch (response.body()) {
            case WebResponse.Empty ignored -> {
                // The status and the headers are the whole answer.
            }
            case WebResponse.Text text ->
                    writeBytes(text.content().getBytes(StandardCharsets.UTF_8), res, head);
            case WebResponse.Bytes bytes -> writeBytes(bytes.data(), res, head);
            case WebResponse.Stream stream -> writeStream(stream.writer(), res, head);
            case WebResponse.Sse sse -> writeSse(sse.writer(), res, head);
            case WebResponse.Raw raw -> writeRaw(raw.writer(), req, res, head);
            case WebResponse.Template ignored -> throw new IllegalStateException(
                    "A template should have been rendered before the response was written");
        }
    }

    /** A body already in memory: a HEAD knows its length without producing it. */
    private void writeBytes(byte[] data, HttpServletResponse res, boolean head) throws IOException {
        if (head) {
            setLengthIfUnset(res, data.length);
            return;
        }
        res.getOutputStream().write(data);
    }

    /**
     * A body produced as it goes. A HEAD still runs the writer, because only
     * running it says how long the body would have been — and because the writer
     * is what closes whatever it opened.
     */
    private void writeStream(StreamWriter writer, HttpServletResponse res, boolean head)
            throws Exception {
        if (head) {
            CountingStream counted = new CountingStream();
            writer.write(counted);
            setLengthIfUnset(res, counted.written);
            return;
        }
        writer.write(res.getOutputStream());
    }

    /**
     * An SSE stream, which occupies this thread until it ends. A HEAD answers
     * with the headers and never runs the writer: a stream whose body is thrown
     * away would never end.
     */
    private void writeSse(SseWriter writer, HttpServletResponse res, boolean head)
            throws Exception {
        if (head) {
            res.flushBuffer();
            return;
        }
        SseStream stream = new SseStream(res);
        app.openStreams.add(stream);
        try {
            res.flushBuffer();  // commit the headers, so the client opens before the first event
            writer.write(stream);
        } catch (SseStream.Closed e) {
            // The client left, or the server is stopping. Both end the request normally.
        } finally {
            app.openStreams.remove(stream);
            stream.close();
        }
    }

    /** The escape hatch. A HEAD counts what it wrote and throws the bytes away. */
    private void writeRaw(ServletWriter writer, HttpServletRequest req, HttpServletResponse res,
            boolean head) throws Exception {
        if (!head) {
            writer.write(req, res);
            return;
        }
        HeadResponse counted = new HeadResponse(res);
        writer.write(req, counted);
        counted.finish();
    }

    private void setLengthIfUnset(HttpServletResponse res, long length) {
        if (length > 0 && !res.isCommitted() && res.getHeader("Content-Length") == null) {
            res.setContentLengthLong(length);
        }
    }

    /**
     * A body that failed halfway. The status and the headers may already be on
     * the wire by then, so this is best-effort: a 500 when nothing has been sent,
     * and a log entry either way. Nothing is rethrown into the container, which
     * would only turn a broken page into a broken page plus a stack trace.
     */
    private void writeFailed(Exception e, HttpServletResponse res) throws IOException {
        log("Failed while writing the response", e);
        if (res.isCommitted()) {
            return;
        }
        res.reset();
        res.setStatus(500);
        res.setContentType("text/plain; charset=UTF-8");
        res.getWriter().write("Internal Server Error");
    }

    /**
     * A HEAD answered by a {@link WebResponse.Raw} handler: every header it sets
     * goes through, the body goes nowhere. The bytes are counted, so a handler
     * that did not set {@code Content-Length} itself still reports the length the
     * GET would have sent.
     */
    private static final class HeadResponse extends HttpServletResponseWrapper {

        private final CountingStream body = new CountingStream();
        private PrintWriter writer;

        HeadResponse(HttpServletResponse res) {
            super(res);
        }

        @Override
        public ServletOutputStream getOutputStream() {
            return new ServletStream(body);
        }

        @Override
        public PrintWriter getWriter() {
            if (writer == null) {
                writer = new PrintWriter(new OutputStreamWriter(body, charset()));
            }
            return writer;
        }

        private Charset charset() {
            String encoding = getCharacterEncoding();
            return encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        }

        void finish() {
            if (writer != null) {
                writer.flush();
            }
            if (body.written > 0 && !isCommitted() && getHeader("Content-Length") == null) {
                setContentLengthLong(body.written);
            }
        }
    }

    /** Counts what a HEAD would have sent, and throws it away. */
    private static final class CountingStream extends OutputStream {

        private long written;

        @Override
        public void write(int b) {
            written++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            written += len;
        }
    }

    /** The same counting, in the shape the servlet API asks for. */
    private static final class ServletStream extends ServletOutputStream {

        private final CountingStream counted;

        ServletStream(CountingStream counted) {
            this.counted = counted;
        }

        @Override
        public void write(int b) {
            counted.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            counted.write(b, off, len);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            throw new UnsupportedOperationException("HEAD responses are not written asynchronously");
        }
    }
}
