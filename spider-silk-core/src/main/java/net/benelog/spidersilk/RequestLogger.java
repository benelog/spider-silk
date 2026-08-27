package net.benelog.spidersilk;

/**
 * Called once per request, after the response is complete.
 *
 * <pre>{@code
 * app.requestLogger((req, res, millis) -> logger.info("{} {} -> {} ({}ms)",
 *         req.method(), req.path(), res.status().code(), millis));
 * }</pre>
 *
 * <p>One lambda instead of a logging framework in core: which logger to use, at
 * which level, and in which format is the application's decision.
 */
@FunctionalInterface
public interface RequestLogger {

    /**
     * @param request the request, as it was received
     * @param response the response it was finally answered with
     * @param millis how long the whole request took, dispatch and rendering included
     */
    void log(WebRequest request, WebResponse response, long millis);
}
