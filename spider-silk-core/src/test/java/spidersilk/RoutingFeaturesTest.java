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
                .before("/admin/*", req -> {
                    visited.add("filter:" + req.path());
                    return null;
                })
                .get("/admin", req -> WebResponse.text("admin"))
                .get("/admin/users", req -> WebResponse.text("users"))
                .get("/public", req -> WebResponse.text("public"));

        WebTest.test(app, client -> {
            client.get("/admin");
            client.get("/admin/users");
            client.get("/public");
        });

        assertEquals(List.of("filter:/admin", "filter:/admin/users"), visited);
    }

    /** A guard that answered the request must stop the handler from also answering it. */
    @Test
    void aFilterThatAnswersEndsTheRequest() {
        App app = new App()
                .before("/admin/*", req -> WebResponse.text("Unauthorized").status(HttpStatus.UNAUTHORIZED))
                .get("/admin/users", req -> WebResponse.text("secret"));

        WebTest.test(app, client -> {
            var response = client.get("/admin/users");
            assertEquals(401, response.statusCode());
            assertEquals("Unauthorized", response.body());
        });
    }

    @Test
    void aRedirectingFilterAlsoEndsTheRequest() {
        App app = new App()
                .before("/admin/*", req -> WebResponse.redirect("/login"))
                .get("/admin/users", req -> WebResponse.text("secret"));

        WebTest.test(app, client -> {
            var response = client.get("/admin/users");
            assertEquals(302, response.statusCode());
            assertEquals("", response.body());
        });
    }

    /** Rejecting without a body of its own: throw, and let the error handler render it. */
    @Test
    void aFilterCanRejectByThrowingHttpException() {
        App app = new App()
                .error(HttpStatus.UNAUTHORIZED, req -> WebResponse.text("login required"))
                .before("/admin/*", req -> {
                    throw new HttpException(HttpStatus.UNAUTHORIZED, "no session");
                })
                .get("/admin/users", req -> WebResponse.text("secret"));

        WebTest.test(app, client -> {
            var response = client.get("/admin/users");
            assertEquals(401, response.statusCode());
            assertEquals("login required", response.body());
        });
    }

    /** A filter that returns null carries on: before to the route, after to the response. */
    @Test
    void globalFiltersStillRunOnEveryRoute() {
        List<String> visited = new ArrayList<>();
        App app = new App()
                .before(req -> {
                    visited.add("before");
                    return null;
                })
                .after((req, res) -> {
                    visited.add("after");
                    return null;
                })
                .get("/", req -> WebResponse.text("ok"));

        WebTest.test(app, client -> assertEquals("ok", client.get("/").body()));

        assertEquals(List.of("before", "after"), visited);
    }

    /** An after-filter answers with what it returns, which is how it rewrites a response. */
    @Test
    void anAfterFilterCanReplaceTheResponse() {
        App app = new App()
                .after((req, res) -> res.header("X-Served-By", "spider-silk"))
                .get("/", req -> WebResponse.text("ok"));

        WebTest.test(app, client -> {
            var response = client.get("/");
            assertEquals("ok", response.body());
            assertEquals("spider-silk", response.headers().firstValue("X-Served-By").orElseThrow());
        });
    }

    @Test
    void routeGroupPrefixesEveryRegistration() {
        App app = new App().path("/api/decks", decks -> {
            decks.get("", req -> WebResponse.text("list"));
            decks.post("", req -> WebResponse.text("created"));
            decks.get("/{deckId}", req -> WebResponse.text("deck " + req.pathParam("deckId")));
        });

        WebTest.test(app, client -> {
            assertEquals("list", client.get("/api/decks").body());
            assertEquals("created", client.post("/api/decks").body());
            assertEquals("deck 7", client.get("/api/decks/7").body());
        });
    }

    @Test
    void nestedGroupsAppendPrefixes() {
        App app = new App().path("/api", api -> api.path("/decks",
                decks -> decks.get("/{deckId}/cards", req -> WebResponse.text("cards"))));

        WebTest.test(app, client -> assertEquals("cards", client.get("/api/decks/3/cards").body()));
    }

    @Test
    void aGroupFilterCoversThePrefixAndEverythingUnderIt() {
        List<String> visited = new ArrayList<>();
        App app = new App().path("/api", api -> {
            api.before(req -> {
                visited.add(req.path());
                return null;
            });
            api.get("", req -> WebResponse.text("root"));
            api.get("/decks", req -> WebResponse.text("decks"));
        });
        app.get("/other", req -> WebResponse.text("other"));

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
                .error(HttpStatus.NOT_FOUND, req -> WebResponse.html("<h1>no such page: " + req.path() + "</h1>"))
                .get("/", req -> WebResponse.text("ok"));

        WebTest.test(app, client -> {
            var response = client.get("/missing");
            assertEquals(404, response.statusCode());
            assertEquals("<h1>no such page: /missing</h1>", response.body());
        });
    }

    @Test
    void errorHandlerAlsoCoversAStatusSetByAHandler() {
        App app = new App()
                .error(HttpStatus.FORBIDDEN, req -> WebResponse.text("forbidden page"))
                .get("/secret", req -> WebResponse.empty(HttpStatus.FORBIDDEN));

        WebTest.test(app, client -> assertEquals("forbidden page", client.get("/secret").body()));
    }

    @Test
    void errorHandlerCoversHttpExceptionAndSeesItsMessage() {
        App app = new App()
                .error(HttpStatus.BAD_REQUEST, req -> WebResponse.text("bad request: " + req.errorMessage()))
                .get("/decks/{deckId}",
                        req -> WebResponse.text("deck " + req.pathParamLong("deckId")));

        WebTest.test(app, client -> {
            var response = client.get("/decks/abc");
            assertEquals(400, response.statusCode());
            assertTrue(response.body().startsWith("bad request: Path variable {deckId}"),
                    "got: " + response.body());
        });
    }

    @Test
    void aHandlerThatAnsweredWithABodyIsLeftAlone() {
        App app = new App()
                .error(HttpStatus.NOT_FOUND, req -> WebResponse.text("replaced"))
                .get("/gone", req -> WebResponse.text("my own 404").status(HttpStatus.NOT_FOUND));

        WebTest.test(app, client -> assertEquals("my own 404", client.get("/gone").body()));
    }

    @Test
    void defaultBodiesSurviveWithoutErrorHandlers() {
        App app = new App().get("/", req -> WebResponse.text("ok"));

        WebTest.test(app, client -> {
            assertEquals("Not Found: /missing", client.get("/missing").body());

            var notAllowed = client.post("/");
            assertEquals(405, notAllowed.statusCode());
            assertEquals("GET, HEAD, OPTIONS",
                    notAllowed.headers().firstValue("Allow").orElseThrow());
            assertEquals("Method Not Allowed: POST /", notAllowed.body());
        });
    }

    /** The Allow header the framework worked out survives the error handler's body. */
    @Test
    void anErrorHandlerKeepsTheHeadersTheFrameworkAlreadySet() {
        App app = new App()
                .error(HttpStatus.METHOD_NOT_ALLOWED, req -> WebResponse.text("no such method here"))
                .get("/", req -> WebResponse.text("ok"));

        WebTest.test(app, client -> {
            var response = client.post("/");
            assertEquals(405, response.statusCode());
            assertEquals("no such method here", response.body());
            assertEquals("GET, HEAD, OPTIONS",
                    response.headers().firstValue("Allow").orElseThrow());
        });
    }

    @Test
    void errorHandlerCoversUncaughtExceptions() {
        App app = new App()
                .error(HttpStatus.INTERNAL_SERVER_ERROR, req -> WebResponse.text("something broke"))
                .get("/boom", req -> {
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
        assertThrows(IllegalArgumentException.class,
                () -> new App().before("/*/edit", req -> null));
    }
}
