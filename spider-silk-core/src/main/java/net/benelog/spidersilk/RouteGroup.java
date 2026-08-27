package net.benelog.spidersilk;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Routes sharing a path prefix, handed to the lambda passed to
 * {@link App#path(String, Consumer)}:
 *
 * <pre>{@code
 * app.path("/api/decks", decks -> {
 *     decks.before(req -> requireApiKey(req));   // guards /api/decks and everything under it
 *     decks.get("", this::listDecks);            // GET  /api/decks
 *     decks.post("", this::createDeck);          // POST /api/decks
 *     decks.get("/{deckId}", this::showDeck);    // GET  /api/decks/{deckId}
 * });
 * }</pre>
 *
 * <p>The group is an argument, not ambient state. Spark's static-import
 * equivalent keeps the prefix in a thread local, which rules out two
 * applications in one JVM; passing the registrar costs one lambda parameter
 * and rules out nothing.
 */
public final class RouteGroup {

    private final App app;
    private final String prefix;

    RouteGroup(App app, String prefix) {
        this.app = app;
        this.prefix = prefix;
    }

    public RouteGroup get(String path, Handler handler) {
        app.get(resolve(path), handler);
        return this;
    }

    /**
     * The same route, with one line saying what it is for — the group's
     * counterpart of {@link App#get(String, String, Handler)}, which every
     * registration method here has too:
     *
     * <pre>{@code
     * decks.get("/{deckId}", "One deck and its cards", this::showDeck);
     * }</pre>
     */
    public RouteGroup get(String path, String description, Handler handler) {
        app.get(resolve(path), description, handler);
        return this;
    }

    public RouteGroup post(String path, Handler handler) {
        app.post(resolve(path), handler);
        return this;
    }

    /** {@link #get(String, String, Handler)}, for POST. */
    public RouteGroup post(String path, String description, Handler handler) {
        app.post(resolve(path), description, handler);
        return this;
    }

    public RouteGroup put(String path, Handler handler) {
        app.put(resolve(path), handler);
        return this;
    }

    /** {@link #get(String, String, Handler)}, for PUT. */
    public RouteGroup put(String path, String description, Handler handler) {
        app.put(resolve(path), description, handler);
        return this;
    }

    public RouteGroup patch(String path, Handler handler) {
        app.patch(resolve(path), handler);
        return this;
    }

    /** {@link #get(String, String, Handler)}, for PATCH. */
    public RouteGroup patch(String path, String description, Handler handler) {
        app.patch(resolve(path), description, handler);
        return this;
    }

    public RouteGroup delete(String path, Handler handler) {
        app.delete(resolve(path), handler);
        return this;
    }

    /** {@link #get(String, String, Handler)}, for DELETE. */
    public RouteGroup delete(String path, String description, Handler handler) {
        app.delete(resolve(path), description, handler);
        return this;
    }

    public RouteGroup head(String path, Handler handler) {
        app.head(resolve(path), handler);
        return this;
    }

    /** {@link #get(String, String, Handler)}, for HEAD. */
    public RouteGroup head(String path, String description, Handler handler) {
        app.head(resolve(path), description, handler);
        return this;
    }

    public RouteGroup options(String path, Handler handler) {
        app.options(resolve(path), handler);
        return this;
    }

    /** {@link #get(String, String, Handler)}, for OPTIONS. */
    public RouteGroup options(String path, String description, Handler handler) {
        app.options(resolve(path), description, handler);
        return this;
    }

    /** A filter over the whole group: the prefix itself and everything below it. */
    public RouteGroup before(BeforeFilter filter) {
        return before("/*", filter);
    }

    public RouteGroup before(String path, BeforeFilter filter) {
        app.before(resolve(path), filter);
        return this;
    }

    /** A filter over the whole group: the prefix itself and everything below it. */
    public RouteGroup after(AfterFilter filter) {
        return after("/*", filter);
    }

    public RouteGroup after(String path, AfterFilter filter) {
        app.after(resolve(path), filter);
        return this;
    }

    /** A nested group, its prefix appended to this one's. */
    public RouteGroup path(String path, Consumer<RouteGroup> routes) {
        Objects.requireNonNull(routes, "routes").accept(new RouteGroup(app, resolve(path)));
        return this;
    }

    /**
     * The full path a group-relative path registers under. Private: it is how
     * the group builds the argument it passes to {@link App}, not something a
     * caller holding a group has any use for.
     */
    private String resolve(String path) {
        Objects.requireNonNull(path, "path");
        if (path.isEmpty() || path.equals("/")) {
            return prefix;
        }
        String suffix = path.startsWith("/") ? path : "/" + path;
        return prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) + suffix
                : prefix + suffix;
    }
}
