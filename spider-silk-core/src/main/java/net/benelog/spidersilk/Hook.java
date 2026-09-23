package net.benelog.spidersilk;

/**
 * One registered hook — something that runs around a route rather than being
 * one — as {@link App#hooks()} reports it.
 *
 * <p>Sealed over the five kinds, because a filter is scoped to a path, a status
 * page to a status, and a response filter to every response, and one record
 * with a component that is null half the time would report that dishonestly.
 * Each record is named for its registration, and carries the scope that
 * registration took.
 *
 * <pre>{@code
 * for (Hook hook : app.hooks()) {
 *     String scope = switch (hook) {
 *         case Hook.BeforeRequest before -> "before request " + before.path();
 *         case Hook.BeforeRoute before -> "before " + before.path();
 *         case Hook.AfterRoute after -> "after " + after.path();
 *         case Hook.StatusPage page -> "status page " + page.status().code();
 *         case Hook.EveryResponse ignored -> "every response";
 *     };
 * }
 * }</pre>
 *
 * <p>Not every hook guards anything: a before-filter can turn a request away,
 * while a status page and a response filter only shape what is answered. The
 * name is for where they run, around a route, and not for what they do there.
 *
 * <p>Like {@link Route}, a hook carries where it applies and nothing else. The
 * filter itself is left out for the same reason a route's handler is: it is a
 * lambda, and the only name it has is what reflection would dig out of its
 * synthetic class.
 */
public sealed interface Hook {

    /** A filter registered through {@link App#beforeRequest(String, BeforeFilter)}, before routing. */
    record BeforeRequest(String path) implements Hook {
    }

    /**
     * A filter registered through {@link App#beforeRoute(String, BeforeFilter)}, and
     * the paths it covers.
     *
     * <p>{@code path} is the pattern as it was registered, prefixes from
     * {@link RouteGroup} already resolved. It is a pattern and not a path: a
     * trailing {@code "*"} covers the prefix and everything under it, so
     * {@code "/admin/*"} guards {@code "/admin"} as well as {@code "/admin/users"}.
     * {@link App#beforeRoute(BeforeFilter)} reports {@code "/*"}, which is what it
     * registers.
     */
    record BeforeRoute(String path) implements Hook {
    }

    /** A filter registered through {@link App#afterRoute(String, AfterFilter)}, and the paths it covers. */
    record AfterRoute(String path) implements Hook {
    }

    /**
     * A body registered through {@link App#statusPage(HttpStatus, Handler)} for
     * responses that ended on this status with no body. It is scoped to a
     * status rather than to a path, so it covers every path.
     */
    record StatusPage(HttpStatus status) implements Hook {
    }

    /**
     * A filter registered through {@link App#responseFilter(ResponseFilter)}.
     * It has no scope to report, because it runs on every response: the ones
     * the router, a before-filter, an exception handler, and the static files
     * answer, as well as a route's. Two registrations are two entries.
     */
    record EveryResponse() implements Hook {
    }
}
