package net.benelog.spidersilk;

/**
 * Called once per request, after the response is complete.
 *
 * <pre>{@code
 * app.requestLogger((req, completion) -> logger.info("{} {} -> {} ({}ms)",
 *         req.method(), req.path(), completion.statusCode(), completion.took().toMillis()));
 * }</pre>
 *
 * <p>One lambda instead of a logging framework in core: which logger to use, at
 * which level, and in which format is the application's decision.
 */
@FunctionalInterface
public interface RequestLogger {

    /**
     * Records one completed request.
     *
     * @param request the request, as it was received
     * @param completion the servlet status, elapsed time, response definition, and write failure
     */
    void log(WebRequest request, RequestCompletion completion);
}
