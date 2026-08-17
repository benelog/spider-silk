package spidersilk;

/**
 * Called once per request, after the response is complete.
 *
 * <pre>{@code
 * app.requestLogger((ctx, millis) -> logger.info("{} {} -> {} ({}ms)",
 *         ctx.method(), ctx.path(), ctx.res().getStatus(), millis));
 * }</pre>
 *
 * <p>One lambda instead of a logging framework in core: which logger to use, at
 * which level, and in which format is the application's decision.
 */
@FunctionalInterface
public interface RequestLogger {

    /**
     * @param ctx the request, and the response as it was finally answered
     * @param millis how long the whole request took, dispatch and rendering included
     */
    void log(WebContext ctx, long millis);
}
