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
 * @param response the response definition, after decoration; a raw writer or a
 *                 write failure may change what the servlet actually sends
 * @param statusCode the final status reported by the servlet response
 * @param took the elapsed time, including dispatch, rendering, and writing
 * @param failure an exception during decoration or writing, or null on completion;
 *                an application exception handled during dispatch is represented
 *                by its response, not by a transmission failure
 */
public record RequestCompletion(WebResponse response, int statusCode, Duration took, @Nullable Exception failure) {

    public RequestCompletion {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(took, "took");
    }

    /** Whether decoration or writing failed, independently of the status code. */
    public boolean failed() {
        return failure != null;
    }
}
