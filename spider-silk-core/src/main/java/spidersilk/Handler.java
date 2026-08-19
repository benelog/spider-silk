package spidersilk;

/**
 * Handles a single request and answers with a response. Registered as a lambda,
 * so no reflection is needed.
 *
 * <pre>{@code
 * app.get("/decks/{deckId}", req -> WebResponse.template("deck", model));
 * }</pre>
 *
 * <p>Returning the response rather than writing it means the compiler checks
 * that every branch answers, and that nothing answers twice.
 */
@FunctionalInterface
public interface Handler {

    WebResponse handle(WebRequest request) throws Exception;
}
