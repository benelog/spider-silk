package spidersilk;

import java.io.IOException;
import java.io.InputStream;
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
        WebContext ctx = new WebContext(app, req, res, Map.of());
        try {
            Router.Match match = app.router.find(req.getMethod(), path);
            if (match != null) {
                ctx = new WebContext(app, req, res, match.pathParams());
                for (Handler filter : app.beforeFilters) {
                    filter.handle(ctx);
                }
                match.handler().handle(ctx);
                for (Handler filter : app.afterFilters) {
                    filter.handle(ctx);
                }
                return;
            }
            if (isReadMethod(req) && serveStatic(path, res)) {
                return;
            }
            Set<String> allowed = app.router.allowedMethods(path);
            if (!allowed.isEmpty()) {
                res.setHeader("Allow", String.join(", ", allowed));
                ctx.status(405).text("Method Not Allowed: " + req.getMethod() + " " + path);
                return;
            }
            app.notFound.handle(ctx);
        } catch (Exception e) {
            handleException(e, ctx, res);
        }
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

    private void handleException(Exception e, WebContext ctx, HttpServletResponse res)
            throws IOException {
        for (var entry : app.exceptionHandlers.entrySet()) {
            if (entry.getKey().isInstance(e)) {
                @SuppressWarnings("unchecked")
                ExceptionHandler<Exception> handler =
                        (ExceptionHandler<Exception>) entry.getValue();
                try {
                    handler.handle(e, ctx);
                    return;
                } catch (Exception handlerFailure) {
                    internalError(handlerFailure, res);
                    return;
                }
            }
        }
        if (e instanceof HttpException httpException) {
            res.setStatus(httpException.status());
            res.setContentType("text/plain; charset=UTF-8");
            res.getWriter().write(httpException.getMessage());
            return;
        }
        internalError(e, res);
    }

    private void internalError(Exception e, HttpServletResponse res) throws IOException {
        log("Error while handling request", e);
        if (!res.isCommitted()) {
            res.setStatus(500);
            res.setContentType("text/plain; charset=UTF-8");
            res.getWriter().write("Internal Server Error");
        }
    }
}
