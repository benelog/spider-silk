package net.benelog.spidersilk;

import java.time.Duration;

/**
 * Called once per request, after the response is complete.
 *
 * <pre>{@code
 * app.requestLogger((req, res, took) -> logger.info("{} {} -> {} ({}ms)",
 *         req.method(), req.path(), res.status().code(), took.toMillis()));
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
     * @param took how long the whole request took, dispatch and rendering included
     */
    void log(WebRequest request, WebResponse response, Duration took);
}
