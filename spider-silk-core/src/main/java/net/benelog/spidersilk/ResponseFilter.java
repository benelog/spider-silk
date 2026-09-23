package net.benelog.spidersilk;

/**
 * Runs on every response the framework answers, and may replace it.
 *
 * <pre>{@code
 * app.responseFilter((req, res) -> res.header("X-Request-Id", requestId()));
 * }</pre>
 *
 * <p>Every response means the ones an {@link AfterFilter} never sees as well:
 * a {@link BeforeFilter}'s early answer, an {@link ExceptionHandler}'s answer,
 * a 404 or 405 from the router, the automatic {@code OPTIONS} answer, and a
 * static file. It runs once the response has been worked out — the error body
 * filled in and the template rendered — and before CORS, the security headers,
 * and compression are applied to it.
 *
 * <p>Return the response passed in to leave it unchanged. Returning null is a
 * programming error. A replacement carrying a
 * template body is rendered like any other. An exception thrown here is routed
 * to {@link App#exception} and {@link App#statusPage(HttpStatus, Handler)}, and the
 * answer to it is not filtered again.
 *
 * <p>It shapes a response and does not guard a request: a response filter runs
 * after the handler already has, so authorization belongs in a
 * {@link BeforeFilter}. What a {@link WebResponse#raw(ServletWriter)} writer
 * writes to the servlet response, and anything that fails once the response is
 * committed, happens after this and is out of its reach.
 */
@FunctionalInterface
public interface ResponseFilter {

    /**
     * Replaces the response about to be sent, or keeps it.
     *
     * @param request  the request, with the path variables of the route that matched, if one did
     * @param response the response the framework is about to send
     * @return the response to answer with, or the response passed in to keep it
     * @throws Exception anything the filter cannot handle, routed to {@link App#exception}
     */
    WebResponse handle(WebRequest request, WebResponse response) throws Exception;
}
