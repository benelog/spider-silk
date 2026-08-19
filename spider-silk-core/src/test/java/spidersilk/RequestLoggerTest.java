package spidersilk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import spidersilk.test.WebTest;

/** The one lambda core offers instead of a logging framework. */
class RequestLoggerTest {

    @Test
    void everyRequestIsReportedWithItsStatus() {
        List<String> logged = new ArrayList<>();
        App app = new App()
                .requestLogger((req, res, millis) -> logged.add(
                        req.method() + " " + req.path() + " -> " + res.status().code()))
                .get("/decks", req -> WebResponse.text("list"));

        WebTest.test(app, client -> {
            client.get("/decks");
            client.post("/decks");
        });

        assertEquals(List.of("GET /decks -> 200", "POST /decks -> 405"), logged);
    }

    /** The status has to be the one that was sent, not the one before the error handler ran. */
    @Test
    void theStatusIsTheOneTheErrorHandlerLeftBehind() {
        List<HttpStatus> statuses = new ArrayList<>();
        App app = new App()
                .requestLogger((req, res, millis) -> statuses.add(res.status()))
                .error(HttpStatus.NOT_FOUND, req -> WebResponse.text("gone for good").status(HttpStatus.GONE))
                .get("/", req -> WebResponse.text("ok"));

        WebTest.test(app, client -> client.get("/missing"));

        assertEquals(List.of(HttpStatus.GONE), statuses);
    }

    @Test
    void anUncaughtExceptionIsStillReported() {
        List<HttpStatus> statuses = new ArrayList<>();
        App app = new App()
                .requestLogger((req, res, millis) -> statuses.add(res.status()))
                .get("/boom", req -> {
                    throw new IllegalStateException("kaboom");
                });

        WebTest.test(app, client -> client.get("/boom"));

        assertEquals(List.of(HttpStatus.INTERNAL_SERVER_ERROR), statuses);
    }

    @Test
    void theElapsedTimeIsReported() {
        List<Long> times = new ArrayList<>();
        App app = new App()
                .requestLogger((req, res, millis) -> times.add(millis))
                .get("/slow", req -> {
                    Thread.sleep(15);
                    return WebResponse.text("done");
                });

        WebTest.test(app, client -> client.get("/slow"));

        assertEquals(1, times.size());
        assertTrue(times.get(0) >= 10, "expected at least 10ms, got " + times.get(0));
    }

    /** Logging happens after the response is sent, so a broken logger cannot break it. */
    @Test
    void aLoggerThatThrowsDoesNotAffectTheResponse() {
        App app = new App()
                .requestLogger((req, res, millis) -> {
                    throw new IllegalStateException("logger is broken");
                })
                .get("/", req -> WebResponse.text("ok"));

        WebTest.test(app, client -> {
            var response = client.get("/");
            assertEquals(200, response.statusCode());
            assertEquals("ok", response.body());
        });
    }
}
