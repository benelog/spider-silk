package net.benelog.spidersilk;

/**
 * One registered guard — something that runs around a route rather than being
 * one — as {@link App#guards()} reports it.
 *
 * <p>Sealed over the three kinds, because a filter is scoped to a path and an
 * error handler is scoped to a status, and one record with a component that is
 * null half the time would report that dishonestly.
 *
 * <pre>{@code
 * for (Guard guard : app.guards()) {
 *     String scope = switch (guard) {
 *         case Guard.Before before -> "before " + before.path();
 *         case Guard.After after -> "after " + after.path();
 *         case Guard.Error error -> "error " + error.status().code();
 *     };
 * }
 * }</pre>
 *
 * <p>Like {@link Route}, a guard carries where it applies and nothing else. The
 * filter itself is left out for the same reason a route's handler is: it is a
 * lambda, and the only name it has is what reflection would dig out of its
 * synthetic class.
 */
public sealed interface Guard {

    /**
     * A filter registered through {@link App#before(String, BeforeFilter)}, and
     * the paths it covers.
     *
     * <p>{@code path} is the pattern as it was registered, prefixes from
     * {@link RouteGroup} already resolved. It is a pattern and not a path: a
     * trailing {@code "*"} covers the prefix and everything under it, so
     * {@code "/admin/*"} guards {@code "/admin"} as well as {@code "/admin/users"}.
     * {@link App#before(BeforeFilter)} reports {@code "/*"}, which is what it
     * registers.
     */
    record Before(String path) implements Guard {
    }

    /** A filter registered through {@link App#after(String, AfterFilter)}, and the paths it covers. */
    record After(String path) implements Guard {
    }

    /**
     * A body registered through {@link App#error(HttpStatus, Handler)} for
     * responses that ended on this status with no body. It is scoped to a
     * status rather than to a path, so it covers every path.
     *
     * <p>The name mirrors {@link App#error}, as {@code Before} and {@code After}
     * mirror their registrations, and is written {@code Guard.Error} everywhere
     * outside this file, where {@link java.lang.Error} is never referred to.
     */
    @SuppressWarnings("AvoidCommonTypeNames")
    record Error(HttpStatus status) implements Guard {
    }
}
