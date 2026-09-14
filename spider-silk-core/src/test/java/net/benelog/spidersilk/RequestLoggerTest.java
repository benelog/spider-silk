package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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
            assertThat(completion.failed()).isFalse();
            assertThat(completion.failure()).isNull();
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
            assertThat(completion.failed()).isTrue();
            assertThat(completion.failure()).isSameAs(failure);
        });
    }

    @Test
    void aWriteFailureAfterCommitReports200AndItsCause() {
        List<RequestCompletion> logged = new CopyOnWriteArrayList<>();
        IOException failure = new IOException("export interrupted");
        App app = new App().requestLogger((req, completion) -> logged.add(completion))
                .get("/", req -> WebResponse.stream("text/plain", out -> {
                    out.write("partial".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    throw failure;
                }));
        WebTest.test(app, client -> {
            var response = client.get("/");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("partial");
        });
        assertThat(logged).singleElement().satisfies(completion -> {
            assertThat(completion.statusCode()).isEqualTo(200);
            assertThat(completion.failed()).isTrue();
            assertThat(completion.failure()).isSameAs(failure);
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
                .error(HttpStatus.NOT_FOUND, req -> WebResponse.text("gone for good").status(HttpStatus.GONE))
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
