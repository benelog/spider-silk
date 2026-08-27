package net.benelog.spidersilk;

/**
 * Runs after a route handler returns normally, and may replace what it answers.
 *
 * <pre>{@code
 * app.after((req, res) -> res.header("X-Request-Id", requestId()));
 * }</pre>
 *
 * <p>Returning null keeps the response unchanged, which is what an after-filter
 * that only observes wants.
 *
 * <p>Only a route that completed normally reaches here: a response from a
 * {@link BeforeFilter}, from an {@link ExceptionHandler}, or from
 * {@link App#error(HttpStatus, Handler)} does not.
 */
@FunctionalInterface
public interface AfterFilter {

    /** @return the response to answer with, or null to keep the one passed in */
    WebResponse handle(WebRequest request, WebResponse response) throws Exception;
}
