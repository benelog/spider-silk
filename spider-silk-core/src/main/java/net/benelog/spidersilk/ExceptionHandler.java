package net.benelog.spidersilk;

/**
 * Turns a specific exception type into a response.
 *
 * <pre>{@code
 * app.exception(NoSuchDeckException.class,
 *         (req, e) -> WebResponse.text(e.getMessage()).status(HttpStatus.NOT_FOUND));
 * }</pre>
 *
 * <p>The request comes first, as it does in {@link Handler}, {@link BeforeFilter},
 * {@link AfterFilter}, and {@link RequestLogger}: what the handler is answering
 * is the first argument, and what it is answering it with follows.
 */
@FunctionalInterface
public interface ExceptionHandler<E extends Exception> {

    WebResponse handle(WebRequest request, E exception) throws Exception;
}
