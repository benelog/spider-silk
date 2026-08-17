package spidersilk;

/**
 * An exception carrying an HTTP status code. Throw it from any handler
 * and the response uses that status code and message.
 */
public class HttpException extends RuntimeException {

    private final int status;

    public HttpException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
