package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.test.WebTest;

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

        assertThat(visited).isEqualTo(List.of("filter:/admin", "filter:/admin/users"));
    }

    /** A guard that answered the request must stop the handler from also answering it. */
    @Test
    void aFilterThatAnswersEndsTheRequest() {
        App app = new App()
                .before("/admin/*", req -> WebResponse.text("Unauthorized").status(HttpStatus.UNAUTHORIZED))
                .get("/admin/users", req -> WebResponse.text("secret"));

        WebTest.test(app, client -> {
            var response = client.get("/admin/users");
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).isEqualTo("Unauthorized");
        });
    }

    @Test
    void aRedirectingFilterAlsoEndsTheRequest() {
        App app = new App()
                .before("/admin/*", req -> WebResponse.redirect("/login"))
                .get("/admin/users", req -> WebResponse.text("secret"));

        WebTest.test(app, client -> {
            var response = client.get("/admin/users");
            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(response.body()).isEmpty();
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
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).isEqualTo("login required");
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

        WebTest.test(app, client -> assertThat(client.get("/").body()).isEqualTo("ok"));

        assertThat(visited).isEqualTo(List.of("before", "after"));
    }

    /** An after-filter answers with what it returns, which is how it rewrites a response. */
    @Test
    void anAfterFilterCanReplaceTheResponse() {
        App app = new App()
                .after((req, res) -> res.header("X-Served-By", "spider-silk"))
                .get("/", req -> WebResponse.text("ok"));

        WebTest.test(app, client -> {
            var response = client.get("/");
            assertThat(response.body()).isEqualTo("ok");
            assertThat(response.headers().firstValue("X-Served-By").orElseThrow())
                    .isEqualTo("spider-silk");
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
            assertThat(client.get("/api/decks").body()).isEqualTo("list");
            assertThat(client.post("/api/decks").body()).isEqualTo("created");
            assertThat(client.get("/api/decks/7").body()).isEqualTo("deck 7");
        });
    }

    @Test
    void nestedGroupsAppendPrefixes() {
        App app = new App().path("/api", api -> api.path("/decks",
                decks -> decks.get("/{deckId}/cards", req -> WebResponse.text("cards"))));

        WebTest.test(app, client ->
                assertThat(client.get("/api/decks/3/cards").body()).isEqualTo("cards"));
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

        assertThat(visited).isEqualTo(List.of("/api", "/api/decks"));
    }

    @Test
    void errorHandlerRendersTheNotFoundBody() {
        App app = new App()
                .error(HttpStatus.NOT_FOUND, req -> WebResponse.html("<h1>no such page: " + req.path() + "</h1>"))
                .get("/", req -> WebResponse.text("ok"));

        WebTest.test(app, client -> {
            var response = client.get("/missing");
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.body()).isEqualTo("<h1>no such page: /missing</h1>");
        });
    }

    @Test
    void errorHandlerAlsoCoversAStatusSetByAHandler() {
        App app = new App()
                .error(HttpStatus.FORBIDDEN, req -> WebResponse.text("forbidden page"))
                .get("/secret", req -> WebResponse.empty(HttpStatus.FORBIDDEN));

        WebTest.test(app, client ->
                assertThat(client.get("/secret").body()).isEqualTo("forbidden page"));
    }

    @Test
    void errorHandlerCoversHttpExceptionAndSeesItsMessage() {
        App app = new App()
                .error(HttpStatus.BAD_REQUEST, req -> WebResponse.text("bad request: " + req.errorMessage()))
                .get("/decks/{deckId}",
                        req -> WebResponse.text("deck " + req.pathParamLong("deckId")));

        WebTest.test(app, client -> {
            var response = client.get("/decks/abc");
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body())
                    .startsWith("bad request: Path variable {deckId}");
        });
    }

    @Test
    void aHandlerThatAnsweredWithABodyIsLeftAlone() {
        App app = new App()
                .error(HttpStatus.NOT_FOUND, req -> WebResponse.text("replaced"))
                .get("/gone", req -> WebResponse.text("my own 404").status(HttpStatus.NOT_FOUND));

        WebTest.test(app, client -> assertThat(client.get("/gone").body()).isEqualTo("my own 404"));
    }

    @Test
    void defaultBodiesSurviveWithoutErrorHandlers() {
        App app = new App().get("/", req -> WebResponse.text("ok"));

        WebTest.test(app, client -> {
            assertThat(client.get("/missing").body()).isEqualTo("Not Found: /missing");

            var notAllowed = client.post("/");
            assertThat(notAllowed.statusCode()).isEqualTo(405);
            assertThat(notAllowed.headers().firstValue("Allow").orElseThrow())
                    .isEqualTo("GET, HEAD, OPTIONS");
            assertThat(notAllowed.body()).isEqualTo("Method Not Allowed: POST /");
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
            assertThat(response.statusCode()).isEqualTo(405);
            assertThat(response.body()).isEqualTo("no such method here");
            assertThat(response.headers().firstValue("Allow").orElseThrow())
                    .isEqualTo("GET, HEAD, OPTIONS");
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
            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body()).isEqualTo("something broke");
        });
    }

    @Test
    void wildcardIsOnlyAllowedAsTheLastSegment() {
        assertThatThrownBy(() -> new App().before("/*/edit", req -> null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A named tail hands the handler what a bare "*" only matched. */
    @Test
    void aNamedTailReachesTheHandlerAsAPathVariable() {
        App app = new App()
                .get("/files/{path*}", req -> WebResponse.text("[" + req.pathParam("path") + "]"));

        WebTest.test(app, client -> {
            assertThat(client.get("/files/docs/2026/report.pdf").body())
                    .isEqualTo("[docs/2026/report.pdf]");
            assertThat(client.get("/files/report.pdf").body()).isEqualTo("[report.pdf]");
            assertThat(client.get("/files").body()).isEqualTo("[]");
            assertThat(client.get("/elsewhere/report.pdf").statusCode()).isEqualTo(404);
        });
    }

    /** A tail is one variable in the route's path template, reported as written. */
    @Test
    void aNamedTailIsReportedByRoutesAsItWasWritten() {
        App app = new App().get("/files/{path*}", "Anything under /files",
                req -> WebResponse.text("file"));

        assertThat(app.routes()).containsExactly(
                new Route("GET", "/files/{path*}", "Anything under /files"));
    }
}
