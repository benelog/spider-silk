package net.benelog.spidersilk;

import java.time.Duration;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * The outcome reported after the framework finishes writing a response.
 *
 * <p>A failure after commitment can leave statusCode at 200. The status and
 * failure must be inspected independently. Completion does not confirm that the
 * remote client received the entire body.
 *
 * <p>Two exceptions are reported, and they are different things. {@code failure}
 * is what broke while the answer was being sent. {@code exception} is what
 * interrupted the request while the answer was being worked out, and the
 * response is the answer to it: the one an exception handler returned, or the
 * framework's 500. A logger that records failures reads {@code exception}
 * together with {@code statusCode}, since an exception mapped to a 400 is the
 * caller's mistake and one answered with a 500 is the application's.
 *
 * @param response the response definition, after decoration; a raw writer or a
 *                 write failure may change what the servlet actually sends
 * @param statusCode the final status reported by the servlet response
 * @param took the elapsed time, including dispatch, rendering, and writing
 * @param failure an exception during decoration or writing, or null on completion;
 *                an application exception handled during dispatch is represented
 *                by its response, not by a transmission failure
 * @param exception what a handler, a filter, or a template threw, whether an
 *                  exception handler answered it or the framework's 500 did; null
 *                  when nothing threw, and for an {@link HttpException}, which is
 *                  a status the handler chose rather than a failure
 */
public record RequestCompletion(WebResponse response, int statusCode, Duration took,
        @Nullable Exception failure, @Nullable Exception exception) {

    public RequestCompletion {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(took, "took");
    }

    /** A completion of a request nothing threw in. */
    public RequestCompletion(WebResponse response, int statusCode, Duration took,
            @Nullable Exception failure) {
        this(response, statusCode, took, failure, null);
    }

    /** Whether decoration or writing failed, independently of the status code. */
    public boolean failed() {
        return failure != null;
    }

    /** Whether the request was answered for an exception, independently of the status code. */
    public boolean threw() {
        return exception != null;
    }
}
