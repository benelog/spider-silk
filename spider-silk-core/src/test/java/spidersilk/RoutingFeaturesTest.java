package spidersilk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import spidersilk.test.WebTest;

/** Status-code error handlers, end to end. */
class RoutingFeaturesTest {

    @Test
    void errorHandlerRendersTheNotFoundBody() {
        App app = new App()
                .error(404, ctx -> ctx.html("<h1>no such page: " + ctx.path() + "</h1>"))
                .get("/", ctx -> ctx.text("ok"));

        WebTest.test(app, client -> {
            var response = client.get("/missing");
            assertEquals(404, response.statusCode());
            assertEquals("<h1>no such page: /missing</h1>", response.body());
        });
    }

    @Test
    void errorHandlerAlsoCoversAStatusSetByAHandler() {
        App app = new App()
                .error(403, ctx -> ctx.text("forbidden page"))
                .get("/secret", ctx -> ctx.status(403));

        WebTest.test(app, client -> assertEquals("forbidden page", client.get("/secret").body()));
    }

    @Test
    void errorHandlerCoversHttpExceptionAndSeesItsMessage() {
        App app = new App()
                .error(400, ctx -> ctx.text("bad request: " + ctx.errorMessage()))
                .get("/decks/{deckId}", ctx -> ctx.text("deck " + ctx.pathParamLong("deckId")));

        WebTest.test(app, client -> {
            var response = client.get("/decks/abc");
            assertEquals(400, response.statusCode());
            assertTrue(response.body().startsWith("bad request: Path variable {deckId}"),
                    "got: " + response.body());
        });
    }

    @Test
    void aHandlerThatWroteABodyIsLeftAlone() {
        App app = new App()
                .error(404, ctx -> ctx.text("replaced"))
                .get("/gone", ctx -> ctx.status(404).text("my own 404"));

        WebTest.test(app, client -> assertEquals("my own 404", client.get("/gone").body()));
    }

    @Test
    void defaultBodiesSurviveWithoutErrorHandlers() {
        App app = new App().get("/", ctx -> ctx.text("ok"));

        WebTest.test(app, client -> {
            assertEquals("Not Found: /missing", client.get("/missing").body());

            var notAllowed = client.post("/");
            assertEquals(405, notAllowed.statusCode());
            assertEquals("GET", notAllowed.headers().firstValue("Allow").orElseThrow());
            assertEquals("Method Not Allowed: POST /", notAllowed.body());
        });
    }

    @Test
    void errorHandlerCoversUncaughtExceptions() {
        App app = new App()
                .error(500, ctx -> ctx.text("something broke"))
                .get("/boom", ctx -> {
                    throw new IllegalStateException("kaboom");
                });

        WebTest.test(app, client -> {
            var response = client.get("/boom");
            assertEquals(500, response.statusCode());
            assertEquals("something broke", response.body());
        });
    }
}
