package net.benelog.spidersilk;

/**
 * Runs after a route handler returns normally, and may replace what it answers.
 *
 * <pre>{@code
 * app.afterRoute("/api/*", (req, res) -> res.header("Cache-Control", "no-store"));
 * }</pre>
 *
 * <p>Return the response passed in to leave it unchanged. Returning null is a
 * programming error and follows the exception-handling path.
 *
 * <p>Only a route that completed normally reaches here: a response from a
 * {@link BeforeFilter}, from an {@link ExceptionHandler}, or from
 * {@link App#error(HttpStatus, Handler)} does not. A header every response has
 * to carry, such as a request id, belongs in a {@link ResponseFilter}, which
 * runs on those as well.
 */
@FunctionalInterface
public interface AfterFilter {

    /**
     * Replaces the response the route returned, or keeps it.
     *
     * @param request  the request as it arrived
     * @param response the response the route returned
     * @return the response to answer with, or the response passed in to keep it
     * @throws Exception anything the filter cannot handle, routed to {@link App#exception}
     */
    WebResponse handle(WebRequest request, WebResponse response) throws Exception;
}
