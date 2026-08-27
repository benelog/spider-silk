package net.benelog.spidersilk;

/**
 * Runs before the route handler, and may answer instead of it.
 *
 * <pre>{@code
 * app.before("/admin/*", req -> req.sessionAttr("user") == null
 *         ? WebResponse.redirect("/login")     // answers here; the route never runs
 *         : null);                             // carry on
 * }</pre>
 *
 * <p>Returning null continues to the next filter and then to the route.
 * Returning a response ends the request there: a guard that turned the caller
 * away must not be followed by the handler it was guarding. Rejecting with a
 * status and the framework's own body is
 * {@code throw new HttpException(HttpStatus.FORBIDDEN, "...")}.
 */
@FunctionalInterface
public interface BeforeFilter {

    /** @return the response to answer with, or null to continue */
    WebResponse handle(WebRequest request) throws Exception;
}
