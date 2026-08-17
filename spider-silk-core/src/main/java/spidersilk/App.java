package spidersilk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * A Spider Silk application definition.
 * Routes, filters, and exception handlers are registered as lambdas.
 * There is no annotation scanning and no reflection: only what you register runs.
 *
 * <pre>{@code
 * App app = new App()
 *         .templates(new JteTemplates("jte"))
 *         .staticFiles("/public");
 *
 * app.get("/decks/{deckId}", ctx -> {
 *     long deckId = ctx.pathParamLong("deckId");
 *     ctx.render("deck.jte", model);
 * });
 * }</pre>
 *
 * Deploy it to a servlet container with {@link AppServlet}.
 */
public final class App {

    final Router router = new Router();
    final List<Handler> beforeFilters = new ArrayList<>();
    final List<Handler> afterFilters = new ArrayList<>();
    final LinkedHashMap<Class<? extends Exception>, ExceptionHandler<? extends Exception>> exceptionHandlers =
            new LinkedHashMap<>();

    TemplateRenderer templates;
    String staticRoot;
    Handler notFound = ctx -> ctx.status(404).text("Not Found: " + ctx.path());

    public App get(String path, Handler handler) {
        router.add("GET", path, handler);
        return this;
    }

    public App post(String path, Handler handler) {
        router.add("POST", path, handler);
        return this;
    }

    public App put(String path, Handler handler) {
        router.add("PUT", path, handler);
        return this;
    }

    public App patch(String path, Handler handler) {
        router.add("PATCH", path, handler);
        return this;
    }

    public App delete(String path, Handler handler) {
        router.add("DELETE", path, handler);
        return this;
    }

    /** A filter that runs before every route. */
    public App before(Handler filter) {
        beforeFilters.add(filter);
        return this;
    }

    /** A filter that runs after a route completes normally. */
    public App after(Handler filter) {
        afterFilters.add(filter);
        return this;
    }

    /** A per-exception-type handler. The first match in registration order runs. */
    public <E extends Exception> App exception(Class<E> type, ExceptionHandler<E> handler) {
        exceptionHandlers.put(type, handler);
        return this;
    }

    public App notFound(Handler handler) {
        this.notFound = handler;
        return this;
    }

    /** The template engine used by {@link WebContext#render}. */
    public App templates(TemplateRenderer renderer) {
        this.templates = renderer;
        return this;
    }

    /** The classpath root to serve static files from, e.g. "/public". */
    public App staticFiles(String classpathRoot) {
        this.staticRoot = classpathRoot;
        return this;
    }
}
