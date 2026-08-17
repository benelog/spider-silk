package spidersilk;

/**
 * One registered route, as {@link App#routes()} reports it.
 *
 * <p>Method and path, and nothing else. The handler is deliberately absent: it
 * is a lambda, so the only name it has is the one reflection would dig out of
 * its synthetic class, and this framework does not do that. What a route is
 * <em>for</em> is documentation, and documentation attached at the registration
 * site is an annotation with the reflection taken out — so it stays out of core.
 * Anything richer is built on top of this list, which is plain data.
 *
 * <p>{@code path} is the pattern as it was registered, prefixes from
 * {@link RouteGroup} already resolved: {@code "/api/decks/{deckId}/cards"}. That
 * is OpenAPI's path-template syntax verbatim, so an export needs no translation.
 */
public record Route(String method, String path) {
}
