package net.benelog.spidersilk;

import java.time.Duration;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * The outcome reported after the framework finishes writing a response.
 *
 * <p>A failure after commitment can leave statusCode at 200. The status and
 * the write failure must be inspected independently. Completion does not
 * confirm that the remote client received the entire body.
 *
 * <p>Two exceptions are reported, one for each half of a request, and each is
 * named for its half. {@code thrown} is what interrupted the request while the
 * answer was being worked out, and the response is the answer to it: the one an
 * exception handler returned, or the framework's 500. {@code writeFailure} is
 * what broke afterwards, while that answer was being sent. A logger that records
 * failures reads {@code thrown} together with {@code statusCode}, since an
 * exception mapped to a 400 is the caller's mistake and one answered with a 500
 * is the application's.
 *
 * <p>Both are a {@link Throwable}, because an {@link Error} such as an
 * {@link AssertionError} or a {@link StackOverflowError} is answered and
 * reported the way an exception is.
 *
 * @param response the response definition, after decoration; a raw writer or a
 *                 write failure may change what the servlet actually sends
 * @param statusCode the final status reported by the servlet response
 * @param took the elapsed time, including dispatch, rendering, and writing
 * @param writeFailure an exception during decoration or writing, or null when the
 *                     response was written; an application exception handled
 *                     during dispatch is represented by its response, not here
 * @param thrown what a handler, a filter, or a template threw, whether an
 *               exception handler answered it or the framework's 500 did; null
 *               when nothing threw, and for an {@link HttpException}, which is
 *               a status the handler chose rather than a failure
 */
public record RequestCompletion(WebResponse response, int statusCode, Duration took,
        @Nullable Throwable writeFailure, @Nullable Throwable thrown) {

    public RequestCompletion {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(took, "took");
    }

    /** A completion of a request nothing threw in. */
    public RequestCompletion(WebResponse response, int statusCode, Duration took,
            @Nullable Throwable writeFailure) {
        this(response, statusCode, took, writeFailure, null);
    }

    /** Whether decoration or writing failed, independently of the status code. */
    public boolean writeFailed() {
        return writeFailure != null;
    }

    /** Whether the request was answered for an exception, independently of the status code. */
    public boolean threw() {
        return thrown != null;
    }
}
