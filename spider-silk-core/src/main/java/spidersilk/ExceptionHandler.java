package spidersilk;

/** Turns a specific exception type into a response. */
@FunctionalInterface
public interface ExceptionHandler<E extends Exception> {

    WebResponse handle(E exception, WebRequest request) throws Exception;
}
