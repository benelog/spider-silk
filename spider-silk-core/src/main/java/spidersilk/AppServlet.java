package spidersilk;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import jakarta.servlet.http.HttpSession;

/**
 * The entry point that deploys an {@link App} to a servlet container.
 * Mapped to "/*", it takes care of routing, static files, and exception handling.
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

        if ("HEAD".equals(req.getMethod())) {
            HeadResponse head = new HeadResponse(res);
            dispatch(req, head);
            head.finish();
            return;
        }
        dispatch(req, res);
    }

    private void dispatch(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String method = req.getMethod();
        String path = requestPath(req);
        String[] segments = PathPattern.split(path);
        WebContext ctx = new WebContext(app, req, res, Map.of());
        try {
            Router.Match match = routeFor(method, path);
            if (match != null) {
                ctx = new WebContext(app, req, res, match.pathParams());
                if (!runFilters(app.beforeFilters, segments, ctx)) {
                    match.handler().handle(ctx);
                    runFilters(app.afterFilters, segments, ctx);
                }
            } else if (isReadMethod(method) && app.staticFiles != null
                    && app.staticFiles.serve(path, req, res)) {
                return;
            } else {
                Set<String> allowed = allowedMethods(path);
                if (allowed.isEmpty()) {
                    fail(ctx, 404, "Not Found: " + path);
                } else {
                    res.setHeader("Allow", String.join(", ", allowed));
                    if ("OPTIONS".equals(method)) {
                        res.setContentLength(0);
                    } else {
                        fail(ctx, 405, "Method Not Allowed: " + method + " " + path);
                    }
                }
            }
        } catch (Exception e) {
            handleException(e, ctx);
        }
        completeErrorResponse(ctx);
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

    private String requestPath(HttpServletRequest req) {
        String path = req.getServletPath();
        if (req.getPathInfo() != null) {
            path = path + req.getPathInfo();
        }
        return path.isEmpty() ? "/" : path;
    }

    private boolean isReadMethod(String method) {
        return "GET".equals(method) || "HEAD".equals(method);
    }

    /**
     * Runs the matching filters and reports whether one of them answered the
     * request. A before-filter that writes a response — a redirect to the login
     * page, a 403 body — ends the request there: the route handler must not run
     * after a guard has turned the caller away. To reject without writing a body,
     * throw an {@link HttpException}.
     */
    private boolean runFilters(List<Filter> filters, String[] segments, WebContext ctx)
            throws Exception {
        for (Filter filter : filters) {
            if (filter.matches(segments)) {
                filter.handler().handle(ctx);
                if (ctx.bodyWritten()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Moves flash left in the session by the request before the redirect into this request. */
    private void promoteFlash(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) {
            return;
        }
        Object flash = session.getAttribute(WebContext.FLASH_ATTRIBUTE);
        if (flash != null) {
            session.removeAttribute(WebContext.FLASH_ATTRIBUTE);
            req.setAttribute(WebContext.FLASH_ATTRIBUTE, flash);
        }
    }

    private void handleException(Exception e, WebContext ctx) {
        for (var entry : app.exceptionHandlers.entrySet()) {
            if (entry.getKey().isInstance(e)) {
                @SuppressWarnings("unchecked")
                ExceptionHandler<Exception> handler =
                        (ExceptionHandler<Exception>) entry.getValue();
                try {
                    handler.handle(e, ctx);
                    return;
                } catch (Exception handlerFailure) {
                    internalError(handlerFailure, ctx);
                    return;
                }
            }
        }
        if (e instanceof HttpException httpException) {
            fail(ctx, httpException.status(), httpException.getMessage());
            return;
        }
        internalError(e, ctx);
    }

    private void internalError(Exception e, WebContext ctx) {
        log("Error while handling request", e);
        fail(ctx, 500, "Internal Server Error");
    }

    /** Records the status and the body the framework would write by default. */
    private void fail(WebContext ctx, int status, String message) {
        ctx.status(status);
        ctx.errorMessage(message);
    }

    /**
     * Fills in the body of an error response nobody wrote one for, preferring a
     * handler registered through {@link App#error(int, Handler)}.
     */
    private void completeErrorResponse(WebContext ctx) throws IOException {
        HttpServletResponse res = ctx.res();
        if (res.getStatus() < 400 || ctx.bodyWritten() || res.isCommitted()) {
            return;
        }
        Handler handler = app.errorHandlers.get(res.getStatus());
        if (handler != null) {
            try {
                handler.handle(ctx);
                return;
            } catch (Exception e) {
                log("Error handler failed for status " + res.getStatus(), e);
                if (!res.isCommitted()) {
                    res.setStatus(500);
                    res.setContentType("text/plain; charset=UTF-8");
                    res.getWriter().write("Internal Server Error");
                }
                return;
            }
        }
        if (ctx.errorMessage() != null) {
            ctx.text(ctx.errorMessage());
        }
    }

    /**
     * A HEAD answered by the GET handler: every header the handler sets goes
     * through, the body goes nowhere. The bytes are counted, so a handler that
     * did not set {@code Content-Length} itself still reports the length the
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
            return body;
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
    private static final class CountingStream extends ServletOutputStream {

        private long written;

        @Override
        public void write(int b) {
            written++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            written += len;
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
