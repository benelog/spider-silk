package spidersilk;

/** Handles a single request. Registered as a lambda, so no reflection is needed. */
@FunctionalInterface
public interface Handler {

    void handle(WebContext ctx) throws Exception;
}
