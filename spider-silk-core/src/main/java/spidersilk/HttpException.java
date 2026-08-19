package spidersilk;

import java.util.Objects;

/**
 * An exception carrying an HTTP status. Throw it from any handler
 * and the response uses that status and message.
 */
public class HttpException extends RuntimeException {

    private final HttpStatus status;

    public HttpException(HttpStatus status, String message) {
        super(message);
        this.status = Objects.requireNonNull(status, "status");
    }

    public HttpStatus status() {
        return status;
    }
}
