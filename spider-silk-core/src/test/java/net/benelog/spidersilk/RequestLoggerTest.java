package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.test.WebTest;

/** The one lambda core offers instead of a logging framework. */
class RequestLoggerTest {

    @Test
    void rawWritersReportTheServletStatusSeparatelyFromTheDefinition() {
        List<RequestCompletion> logged = new CopyOnWriteArrayList<>();
        App app = new App().requestLogger((req, completion) -> logged.add(completion))
                .get("/", req -> WebResponse.raw((request, response) -> {
                    response.setStatus(202);
                    response.getWriter().write("accepted");
                }));
        WebTest.test(app, client -> assertThat(client.get("/").statusCode()).isEqualTo(202));
        assertThat(logged).singleElement().satisfies(completion -> {
            assertThat(completion.statusCode()).isEqualTo(202);
            assertThat(completion.response().status()).isEqualTo(HttpStatus.OK);
            assertThat(completion.writeFailed()).isFalse();
            assertThat(completion.writeFailure()).isNull();
        });
    }

    @Test
    void aWriteFailureBeforeCommitReports500AndItsCause() {
        List<RequestCompletion> logged = new CopyOnWriteArrayList<>();
        IOException failure = new IOException("export failed");
        App app = new App().requestLogger((req, completion) -> logged.add(completion))
                .get("/", req -> WebResponse.stream("text/plain", out -> { throw failure; }));
        WebTest.test(app, client -> assertThat(client.get("/").statusCode()).isEqualTo(500));
        assertThat(logged).singleElement().satisfies(completion -> {
            assertThat(completion.statusCode()).isEqualTo(500);
            assertThat(completion.response().status()).isEqualTo(HttpStatus.OK);
            assertThat(completion.writeFailed()).isTrue();
            assertThat(completion.writeFailure()).isSameAs(failure);
        });
    }

    /**
     * The client sees the transfer cut short, and the logger still sees the
     * status that went out and the failure that cut it.
     */
    @Test
    void aWriteFailureAfterCommitAbortsTheTransferAndReports200AndItsCause() {
        List<RequestCompletion> logged = new CopyOnWriteArrayList<>();
        IOException failure = new IOException("export interrupted");
        App app = new App().requestLogger((req, completion) -> logged.add(completion))
                .get("/", req -> WebResponse.stream("text/plain", out -> {
                    out.write("partial".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    throw failure;
                }));
        WebTest.test(app, client -> assertThatThrownBy(() -> client.get("/"))
                .isInstanceOf(UncheckedIOException.class));
        assertThat(logged).singleElement().satisfies(completion -> {
            assertThat(completion.statusCode()).isEqualTo(200);
            assertThat(completion.writeFailed()).isTrue();
            assertThat(completion.writeFailure()).isSameAs(failure);
        });
    }

    @Test
    void everyRequestIsReportedWithItsStatus() {
        List<String> logged = new ArrayList<>();
        App app = new App()
                .requestLogger((req, completion) -> logged.add(
                        req.method() + " " + req.path() + " -> " + completion.statusCode()))
                .get("/decks", req -> WebResponse.text("list"));

        WebTest.test(app, client -> {
            client.get("/decks");
            client.post("/decks");
        });

        assertThat(logged).isEqualTo(List.of("GET /decks -> 200", "POST /decks -> 405"));
    }

    /** The status has to be the one that was sent, not the one before the error handler ran. */
    @Test
    void theStatusIsTheOneTheErrorHandlerLeftBehind() {
        List<Integer> statuses = new ArrayList<>();
        App app = new App()
                .requestLogger((req, completion) -> statuses.add(completion.statusCode()))
                .statusPage(HttpStatus.NOT_FOUND, req -> WebResponse.text("gone for good").status(HttpStatus.GONE))
                .get("/", req -> WebResponse.text("ok"));

        WebTest.test(app, client -> client.get("/missing"));

        assertThat(statuses).isEqualTo(List.of(410));
    }

    @Test
    void anUncaughtExceptionIsStillReported() {
        List<Integer> statuses = new ArrayList<>();
        App app = new App()
                .requestLogger((req, completion) -> statuses.add(completion.statusCode()))
                .get("/boom", req -> {
                    throw new IllegalStateException("kaboom");
                });

        WebTest.test(app, client -> client.get("/boom"));

        assertThat(statuses).isEqualTo(List.of(500));
    }

    /**
     * The exception itself is reported, not only the 500 it became: a logger or
     * a tracing span that records failures needs the stack trace, and the
     * servlet log is not where an application looks for it.
     */
    @Test
    void theExceptionThatBecameA500IsReported() {
        List<RequestCompletion> logged = new CopyOnWriteArrayList<>();
        IllegalStateException thrown = new IllegalStateException("kaboom");
        App app = new App()
                .requestLogger((req, completion) -> logged.add(completion))
                .get("/boom", req -> {
                    throw thrown;
                });

        WebTest.test(app, client -> client.get("/boom"));

        assertThat(logged).singleElement().satisfies(completion -> {
            assertThat(completion.statusCode()).isEqualTo(500);
            assertThat(completion.threw()).isTrue();
            assertThat(completion.thrown()).isSameAs(thrown);
            assertThat(completion.writeFailed()).isFalse();
        });
    }

    /**
     * An Error is answered and reported the way an exception is. Left to the
     * container, it went out as the container's page without the security
     * headers, and the logger was never called.
     */
    @Test
    void anErrorFromAHandlerIsAnsweredAndReportedAsAnExceptionIs() {
        List<RequestCompletion> logged = new CopyOnWriteArrayList<>();
        AssertionError thrown = new AssertionError("invariant broken");
        App app = new App()
                .requestLogger((req, completion) -> logged.add(completion))
                .securityHeaders()
                .get("/assert", req -> {
                    throw thrown;
                });

        WebTest.test(app, client -> {
            var response = client.get("/assert");
            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body()).isEqualTo("Internal Server Error");
            assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        });

        assertThat(logged).singleElement().satisfies(completion -> {
            assertThat(completion.statusCode()).isEqualTo(500);
            assertThat(completion.thrown()).isSameAs(thrown);
            assertThat(completion.writeFailed()).isFalse();
        });
    }

    /** An Error from an exception handler ends in the framework's 500, as an exception from one does. */
    @Test
    void anErrorFromAnExceptionHandlerAnswers500() {
        List<RequestCompletion> logged = new CopyOnWriteArrayList<>();
        IllegalStateException thrown = new IllegalStateException("first");
        App app = new App()
                .requestLogger((req, completion) -> logged.add(completion))
                .exception(IllegalStateException.class, (req, e) -> {
                    throw new StackOverflowError();
                })
                .get("/", req -> {
                    throw thrown;
                });

        WebTest.test(app, client -> assertThat(client.get("/").statusCode()).isEqualTo(500));

        assertThat(logged).singleElement().satisfies(completion ->
                assertThat(completion.thrown()).isSameAs(thrown));
    }

    /** An Error while writing is a write failure, where the logger used to hear of a 200 that succeeded. */
    @Test
    void anErrorWhileWritingIsReportedAsAWriteFailure() {
        List<RequestCompletion> logged = new CopyOnWriteArrayList<>();
        StackOverflowError failure = new StackOverflowError();
        App app = new App().requestLogger((req, completion) -> logged.add(completion))
                .get("/", req -> WebResponse.stream("text/plain", out -> { throw failure; }));

        WebTest.test(app, client -> assertThat(client.get("/").statusCode()).isEqualTo(500));

        assertThat(logged).singleElement().satisfies(completion -> {
            assertThat(completion.statusCode()).isEqualTo(500);
            assertThat(completion.writeFailed()).isTrue();
            assertThat(completion.writeFailure()).isSameAs(failure);
        });
    }

    /** A status page that throws turns a 404 into a 500, and the logger hears what threw. */
    @Test
    void aStatusPageThatThrowsIsReported() {
        List<RequestCompletion> logged = new CopyOnWriteArrayList<>();
        IllegalStateException broken = new IllegalStateException("broken page");
        App app = new App()
                .requestLogger((req, completion) -> logged.add(completion))
                .statusPage(HttpStatus.NOT_FOUND, req -> {
                    throw broken;
                });

        WebTest.test(app, client -> assertThat(client.get("/missing").statusCode()).isEqualTo(500));

        assertThat(logged).singleElement().satisfies(completion -> {
            assertThat(completion.threw()).isTrue();
            assertThat(completion.thrown()).isSameAs(broken);
        });
    }

    /** When the 500 page itself throws, the handler's exception stays the one reported, with the page's beside it. */
    @Test
    void aFiveHundredPageThatThrowsKeepsTheHandlersExceptionFirst() {
        List<RequestCompletion> logged = new CopyOnWriteArrayList<>();
        IllegalStateException first = new IllegalStateException("handler broke");
        IllegalStateException page = new IllegalStateException("page broke");
        App app = new App()
                .requestLogger((req, completion) -> logged.add(completion))
                .statusPage(HttpStatus.INTERNAL_SERVER_ERROR, req -> {
                    throw page;
                })
                .get("/", req -> {
                    throw first;
                });

        WebTest.test(app, client -> assertThat(client.get("/").statusCode()).isEqualTo(500));

        assertThat(logged).singleElement().satisfies(completion -> {
            assertThat(completion.thrown()).isSameAs(first);
            assertThat(completion.thrown().getSuppressed()).containsExactly(page);
        });
    }

    /** An exception a handler mapped is reported too, with the status it was mapped to: the logger decides what counts. */
    @Test
    void anExceptionAnExceptionHandlerAnsweredIsReportedWithItsStatus() {
        List<RequestCompletion> logged = new CopyOnWriteArrayList<>();
        IllegalArgumentException thrown = new IllegalArgumentException("rating must be between 1 and 5");
        App app = new App()
                .requestLogger((req, completion) -> logged.add(completion))
                .exception(IllegalArgumentException.class,
                        (req, e) -> WebResponse.text(e.getMessage()).status(HttpStatus.BAD_REQUEST))
                .post("/reviews", req -> {
                    throw thrown;
                });

        WebTest.test(app, client -> client.post("/reviews"));

        assertThat(logged).singleElement().satisfies(completion -> {
            assertThat(completion.statusCode()).isEqualTo(400);
            assertThat(completion.thrown()).isSameAs(thrown);
        });
    }

    /** A status a handler chose by throwing HttpException is a status, and nothing went wrong. */
    @Test
    void anHttpExceptionIsNotReportedAsAnException() {
        List<RequestCompletion> logged = new CopyOnWriteArrayList<>();
        App app = new App()
                .requestLogger((req, completion) -> logged.add(completion))
                .get("/decks/{id}", req -> {
                    throw new HttpException(HttpStatus.NOT_FOUND, "No deck 7");
                })
                .get("/ok", req -> WebResponse.text("ok"));

        WebTest.test(app, client -> {
            client.get("/decks/7");
            client.get("/ok");
        });

        assertThat(logged).hasSize(2).allSatisfy(completion -> {
            assertThat(completion.threw()).isFalse();
            assertThat(completion.thrown()).isNull();
        });
        assertThat(logged.get(0).statusCode()).isEqualTo(404);
    }

    /** A response filter that throws is answered like a handler that throws, and reported the same way. */
    @Test
    void anExceptionFromAResponseFilterIsReported() {
        List<RequestCompletion> logged = new CopyOnWriteArrayList<>();
        IllegalStateException thrown = new IllegalStateException("filter broke");
        App app = new App()
                .requestLogger((req, completion) -> logged.add(completion))
                .responseFilter((req, res) -> {
                    throw thrown;
                })
                .get("/", req -> WebResponse.text("ok"));

        WebTest.test(app, client -> client.get("/"));

        assertThat(logged).singleElement().satisfies(completion -> {
            assertThat(completion.statusCode()).isEqualTo(500);
            assertThat(completion.thrown()).isSameAs(thrown);
        });
    }

    @Test
    void theElapsedTimeIsReported() {
        List<Duration> times = new ArrayList<>();
        App app = new App()
                .requestLogger((req, completion) -> times.add(completion.took()))
                .get("/slow", req -> {
                    Thread.sleep(15);
                    return WebResponse.text("done");
                });

        WebTest.test(app, client -> client.get("/slow"));

        assertThat(times).hasSize(1);
        assertThat(times.get(0)).isGreaterThanOrEqualTo(Duration.ofMillis(10));
    }

    /** A Duration keeps what the nanosecond clock measured, so a fast request is not reported as zero. */
    @Test
    void aRequestFasterThanAMillisecondIsStillReported() {
        List<Duration> times = new ArrayList<>();
        App app = new App()
                .requestLogger((req, completion) -> times.add(completion.took()))
                .get("/", req -> WebResponse.text("ok"));

        WebTest.test(app, client -> client.get("/"));

        assertThat(times).hasSize(1);
        assertThat(times.get(0)).isPositive();
    }

    /** Logging happens after the response is sent, so a broken logger cannot break it. */
    @Test
    void aLoggerThatThrowsDoesNotAffectTheResponse() {
        App app = new App()
                .requestLogger((req, completion) -> {
                    throw new IllegalStateException("logger is broken");
                })
                .get("/", req -> WebResponse.text("ok"));

        WebTest.test(app, client -> {
            var response = client.get("/");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("ok");
        });
    }
}
