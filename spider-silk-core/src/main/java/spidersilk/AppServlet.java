package spidersilk;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

        String path = requestPath(req);
        String[] segments = PathPattern.split(path);
        WebContext ctx = new WebContext(app, req, res, Map.of());
        try {
            Router.Match match = app.router.find(req.getMethod(), path);
            if (match != null) {
                ctx = new WebContext(app, req, res, match.pathParams());
                if (!runFilters(app.beforeFilters, segments, ctx)) {
                    match.handler().handle(ctx);
                    runFilters(app.afterFilters, segments, ctx);
                }
            } else if (isReadMethod(req) && serveStatic(path, res)) {
                return;
            } else {
                Set<String> allowed = app.router.allowedMethods(path);
                if (!allowed.isEmpty()) {
                    res.setHeader("Allow", String.join(", ", allowed));
                    fail(ctx, 405, "Method Not Allowed: " + req.getMethod() + " " + path);
                } else {
                    fail(ctx, 404, "Not Found: " + path);
                }
            }
        } catch (Exception e) {
            handleException(e, ctx);
        }
        completeErrorResponse(ctx);
    }

    private String requestPath(HttpServletRequest req) {
        String path = req.getServletPath();
        if (req.getPathInfo() != null) {
            path = path + req.getPathInfo();
        }
        return path.isEmpty() ? "/" : path;
    }

    private boolean isReadMethod(HttpServletRequest req) {
        return "GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod());
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

    private boolean serveStatic(String path, HttpServletResponse res) throws IOException {
        if (app.staticRoot == null || path.contains("..")) {
            return false;
        }
        String resource = app.staticRoot + path;
        try (InputStream in = AppServlet.class.getResourceAsStream(resource)) {
            if (in == null) {
                return false;
            }
            res.setContentType(ContentTypes.byPath(path));
            in.transferTo(res.getOutputStream());
            return true;
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
}
