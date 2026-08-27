package net.benelog.spidersilk;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Writes a response body straight to the servlet API, for the cases this
 * framework has no shape for. Reached through
 * {@link WebResponse#raw(ServletWriter)}.
 *
 * <p>A body-filling interface like {@link StreamWriter} and {@link SseWriter},
 * not a request-handling one: it produces bytes for a response that has already
 * been decided, and answers nothing itself. {@link Handler} is the interface
 * that answers a request.
 */
@FunctionalInterface
public interface ServletWriter {

    void write(HttpServletRequest req, HttpServletResponse res) throws Exception;
}
