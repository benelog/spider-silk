package net.benelog.spidersilk;

import java.util.Objects;

/**
 * One registered route, as {@link App#routes()} reports it.
 *
 * <p>Method, path, and the description passed at registration. The handler is
 * deliberately absent: it is a lambda, so the only name it has is the one
 * reflection would dig out of its synthetic class, and this framework does not
 * do that. Anything richer is built on top of this list, which is plain data.
 *
 * <p>{@code path} is the pattern as it was registered, prefixes from
 * {@link RouteGroup} already resolved: {@code "/api/decks/{deckId}/cards"}. That
 * is OpenAPI's path-template syntax verbatim, so an export needs no translation.
 *
 * <p>{@code description} is what the route is <em>for</em>, in one line, and it
 * is written at the registration site because nothing else knows it. A route
 * registered without one reports {@code ""} rather than null: an undescribed
 * route is the same kind of thing as a described one, merely undocumented, so a
 * reader can print it without asking whether the component is there.
 */
public record Route(String method, String path, String description) {

    public Route {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(description, "description");
    }

    /** A route registered without a description. */
    public Route(String method, String path) {
        this(method, path, "");
    }
}
