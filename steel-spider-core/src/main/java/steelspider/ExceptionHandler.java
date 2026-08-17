package steelspider;

/** Turns a specific exception type into a response. */
@FunctionalInterface
public interface ExceptionHandler<E extends Exception> {

    void handle(E exception, WebContext ctx) throws Exception;
}
