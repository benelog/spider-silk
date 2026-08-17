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
                .requestLogger((ctx, millis) -> logged.add(
                        ctx.method() + " " + ctx.path() + " -> " + ctx.res().getStatus()))
                .get("/decks", ctx -> ctx.text("list"));

        WebTest.test(app, client -> {
            client.get("/decks");
            client.post("/decks");
        });

        assertEquals(List.of("GET /decks -> 200", "POST /decks -> 405"), logged);
    }

    /** The status has to be the one that was sent, not the one before the error handler ran. */
    @Test
    void theStatusIsTheOneTheErrorHandlerLeftBehind() {
        List<Integer> statuses = new ArrayList<>();
        App app = new App()
                .requestLogger((ctx, millis) -> statuses.add(ctx.res().getStatus()))
                .error(404, ctx -> ctx.status(410).text("gone for good"))
                .get("/", ctx -> ctx.text("ok"));

        WebTest.test(app, client -> client.get("/missing"));

        assertEquals(List.of(410), statuses);
    }

    @Test
    void anUncaughtExceptionIsStillReported() {
        List<Integer> statuses = new ArrayList<>();
        App app = new App()
                .requestLogger((ctx, millis) -> statuses.add(ctx.res().getStatus()))
                .get("/boom", ctx -> {
                    throw new IllegalStateException("kaboom");
                });

        WebTest.test(app, client -> client.get("/boom"));

        assertEquals(List.of(500), statuses);
    }

    @Test
    void theElapsedTimeIsReported() {
        List<Long> times = new ArrayList<>();
        App app = new App()
                .requestLogger((ctx, millis) -> times.add(millis))
                .get("/slow", ctx -> {
                    Thread.sleep(15);
                    ctx.text("done");
                });

        WebTest.test(app, client -> client.get("/slow"));

        assertEquals(1, times.size());
        assertTrue(times.get(0) >= 10, "expected at least 10ms, got " + times.get(0));
    }

    /** Logging happens after the response is sent, so a broken logger cannot break it. */
    @Test
    void aLoggerThatThrowsDoesNotAffectTheResponse() {
        App app = new App()
                .requestLogger((ctx, millis) -> {
                    throw new IllegalStateException("logger is broken");
                })
                .get("/", ctx -> ctx.text("ok"));

        WebTest.test(app, client -> {
            var response = client.get("/");
            assertEquals(200, response.statusCode());
            assertEquals("ok", response.body());
        });
    }
}
