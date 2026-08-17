package spidersilk;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Writes a response straight to the servlet API, for the cases this framework
 * has no shape for. Reached through {@link WebResponse#raw(RawHandler)}.
 */
@FunctionalInterface
public interface RawHandler {

    void handle(HttpServletRequest req, HttpServletResponse res) throws Exception;
}
