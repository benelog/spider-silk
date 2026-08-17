package spidersilk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import spidersilk.test.WebTest;

/** Path-scoped filters, route groups, and status-code error handlers, end to end. */
class RoutingFeaturesTest {

    @Test
    void pathScopedFilterRunsOnlyOnMatchingPaths() {
        List<String> visited = new ArrayList<>();
        App app = new App()
                .before("/admin/*", ctx -> visited.add("filter:" + ctx.path()))
                .get("/admin", ctx -> ctx.text("admin"))
                .get("/admin/users", ctx -> ctx.text("users"))
                .get("/public", ctx -> ctx.text("public"));

        WebTest.test(app, client -> {
            client.get("/admin");
            client.get("/admin/users");
            client.get("/public");
        });

        assertEquals(List.of("filter:/admin", "filter:/admin/users"), visited);
    }

    /** A guard that answered the request must stop the handler from also answering it. */
    @Test
    void aFilterThatWritesAResponseEndsTheRequest() {
        App app = new App()
                .before("/admin/*", ctx -> ctx.status(401).text("Unauthorized"))
                .get("/admin/users", ctx -> ctx.text("secret"));

        WebTest.test(app, client -> {
            var response = client.get("/admin/users");
            assertEquals(401, response.statusCode());
            assertEquals("Unauthorized", response.body());
        });
    }

    @Test
    void aRedirectingFilterAlsoEndsTheRequest() {
        App app = new App()
                .before("/admin/*", ctx -> ctx.redirect("/login"))
                .get("/admin/users", ctx -> ctx.text("secret"));

        WebTest.test(app, client -> {
            var response = client.get("/admin/users");
            assertEquals(302, response.statusCode());
            assertEquals("", response.body());
        });
    }

    /** Rejecting without writing a body: throw, and let the error handler render it. */
    @Test
    void aFilterCanRejectByThrowingHttpException() {
        App app = new App()
                .error(401, ctx -> ctx.text("login required"))
                .before("/admin/*", ctx -> {
                    throw new HttpException(401, "no session");
                })
                .get("/admin/users", ctx -> ctx.text("secret"));

        WebTest.test(app, client -> {
            var response = client.get("/admin/users");
            assertEquals(401, response.statusCode());
            assertEquals("login required", response.body());
        });
    }

    @Test
    void globalFiltersStillRunOnEveryRoute() {
        List<String> visited = new ArrayList<>();
        App app = new App()
                .before(ctx -> visited.add("before"))
                .after(ctx -> visited.add("after"))
                .get("/", ctx -> ctx.text("ok"));

        WebTest.test(app, client -> client.get("/"));

        assertEquals(List.of("before", "after"), visited);
    }

    @Test
    void routeGroupPrefixesEveryRegistration() {
        App app = new App().path("/api/decks", decks -> {
            decks.get("", ctx -> ctx.text("list"));
            decks.post("", ctx -> ctx.text("created"));
            decks.get("/{deckId}", ctx -> ctx.text("deck " + ctx.pathParam("deckId")));
        });

        WebTest.test(app, client -> {
            assertEquals("list", client.get("/api/decks").body());
            assertEquals("created", client.post("/api/decks").body());
            assertEquals("deck 7", client.get("/api/decks/7").body());
        });
    }

    @Test
    void nestedGroupsAppendPrefixes() {
        App app = new App().path("/api", api ->
                api.path("/decks", decks -> decks.get("/{deckId}/cards", ctx -> ctx.text("cards"))));

        WebTest.test(app, client -> assertEquals("cards", client.get("/api/decks/3/cards").body()));
    }

    @Test
    void aGroupFilterCoversThePrefixAndEverythingUnderIt() {
        List<String> visited = new ArrayList<>();
        App app = new App().path("/api", api -> {
            api.before(ctx -> visited.add(ctx.path()));
            api.get("", ctx -> ctx.text("root"));
            api.get("/decks", ctx -> ctx.text("decks"));
        });
        app.get("/other", ctx -> ctx.text("other"));

        WebTest.test(app, client -> {
            client.get("/api");
            client.get("/api/decks");
            client.get("/other");
        });

        assertEquals(List.of("/api", "/api/decks"), visited);
    }

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

    @Test
    void wildcardIsOnlyAllowedAsTheLastSegment() {
        assertThrows(IllegalArgumentException.class, () -> new App().before("/*/edit", ctx -> {
        }));
    }
}
