package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.test.TestRequest;
import net.benelog.spidersilk.test.WebTest;

class ApiContractsTest {

    @Test
    void aBodyRewriteCanRemoveOldMetadataWithoutLosingCookiesOrOtherHeaders() {
        App app = new App()
                .get("/", req -> WebResponse.text("old")
                        .header("Content-Length", "3").header("ETag", "\"old\"")
                        .header("X-Keep", "yes").cookie("kept", "yes"))
                .responseFilter((req, res) -> res.body(new WebResponse.Text("replacement body"))
                        .withoutHeader("content-length").withoutHeader("etag"));

        WebTest.test(app, client -> {
            var response = client.get("/");
            assertThat(response.body()).isEqualTo("replacement body");
            assertThat(response.headers().firstValue("Content-Length")).contains("16");
            assertThat(response.headers().firstValue("ETag")).isEmpty();
            assertThat(response.headers().firstValue("X-Keep")).contains("yes");
            assertThat(response.headers().firstValue("Set-Cookie")).hasValueSatisfying(
                    cookie -> assertThat(cookie).startsWith("kept=yes"));
        });
    }

    /** A description passed where the path goes fails at registration, instead of registering a route nothing reaches. */
    @Test
    void aPathWithWhitespaceIsRejectedAtRegistration() {
        App app = new App();

        assertThatThrownBy(() -> app.get("List the decks", "/decks", req -> WebResponse.text("decks")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("List the decks")
                .hasMessageContaining("get(path, description, handler)");
        assertThatThrownBy(() -> app.beforeRoute("/admin /*", req -> null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A before-request filter reading a path variable is told that routing has not happened yet. */
    @Test
    void aPathVariableReadBeforeRoutingNamesWhy() {
        App app = new App()
                .beforeRequest(req -> WebResponse.text(req.pathParam("deckId")))
                .get("/decks/{deckId}", req -> WebResponse.text(req.pathParam("deckId")));
        List<Exception> thrown = new ArrayList<>();
        app.exception(IllegalStateException.class, (req, e) -> {
            thrown.add(e);
            return WebResponse.text("failed").status(HttpStatus.INTERNAL_SERVER_ERROR);
        });

        WebTest.test(app, client -> assertThat(client.get("/decks/7").statusCode()).isEqualTo(500));

        assertThat(thrown).singleElement().satisfies(e -> assertThat(e.getMessage())
                .contains("{deckId}")
                .contains("beforeRequest filter runs before routing"));
    }

    /** A route that reads a variable its own pattern lacks is told which pattern it is. */
    @Test
    void anUndeclaredPathVariableNamesThePattern() {
        App app = new App().get("/decks/{deckId}", req -> WebResponse.text(req.pathParam("id")));
        List<Exception> thrown = new ArrayList<>();
        app.exception(IllegalStateException.class, (req, e) -> {
            thrown.add(e);
            return WebResponse.text("failed").status(HttpStatus.INTERNAL_SERVER_ERROR);
        });

        WebTest.test(app, client -> client.get("/decks/7"));

        assertThat(thrown).singleElement().satisfies(e -> assertThat(e.getMessage())
                .isEqualTo("Path pattern /decks/{deckId} has no such variable: {id}"));
    }

    /** rawJson sends the text as it is; a Java string meant as a JSON value goes through a tree. */
    @Test
    void rawJsonIsSentAsItIsAndJsonBuildsFromATree() {
        App app = new App()
                .get("/raw", req -> WebResponse.rawJson("{\"status\":\"up\"}"))
                .get("/tree", req -> WebResponse.json(net.benelog.spidersilk.json.Json.object().put("message", "hi")));

        WebTest.test(app, client -> {
            assertThat(client.get("/raw").body()).isEqualTo("{\"status\":\"up\"}");
            assertThat(client.get("/raw").headers().firstValue("Content-Type")).contains("application/json");
            assertThat(client.get("/tree").body()).isEqualTo("{\"message\":\"hi\"}");
        });
    }

    @Test
    void requiredSourceReadsRejectAbsenceAndOptionalReadsPreserveIt() {
        WebRequest queryOnly = TestRequest.post("/").queryParam("q", "query").build();
        WebRequest formOnly = TestRequest.post("/").formParam("q", "form").build();
        assertThat(queryOnly.queryParam("q")).isEqualTo("query");
        assertThat(formOnly.formParam("q")).isEqualTo("form");
        assertThat(queryOnly.formParamOrNull("q")).isNull();
        assertThat(formOnly.queryParamOrNull("q")).isNull();
        assertThatExceptionOfType(HttpException.class).isThrownBy(() -> queryOnly.formParam("q"))
                .satisfies(e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatExceptionOfType(HttpException.class).isThrownBy(() -> formOnly.queryParam("q"))
                .satisfies(e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(queryOnly.formParam("q", String::trim, "default")).isEqualTo("default");
        assertThat(formOnly.queryParam("q", String::trim, "default")).isEqualTo("default");
        assertThat(TestRequest.get("/").queryParam("q", "").build().queryParam("q")).isEmpty();
    }

    @Test
    void datesRejectedByJsonAndNdjsonReadersAreClientErrors() {
        App app = new App()
                .post("/json", req -> WebResponse.text(req.bodyJson(
                        json -> LocalDate.parse(json.asObject().getString("date"))).toString()))
                .post("/ndjson", req -> {
                    try (var dates = req.bodyNdjson(
                            json -> LocalDate.parse(json.asObject().getString("date")))) {
                        dates.forEach(date -> assertThat(date).isNotNull());
                    }
                    return WebResponse.empty();
                })
                .post("/bug", req -> WebResponse.text(req.bodyJson(json -> {
                    throw new IllegalStateException("reader bug");
                })));
        WebTest.test(app, client -> {
            assertThat(client.postJson("/json", "{\"date\":\"bad\"}").statusCode()).isEqualTo(400);
            var ndjson = client.postJson("/ndjson", "{\"date\":\"2026-01-01\"}\n{\"date\":\"bad\"}");
            assertThat(ndjson.statusCode()).isEqualTo(400);
            assertThat(ndjson.body()).contains("Line 2");
            assertThat(client.postJson("/bug", "{}").statusCode()).isEqualTo(500);
        });
    }

    @Test
    void requestGuardsCoverStaticFilesMissingRoutesAndAutomaticOptions() {
        AtomicInteger handled = new AtomicInteger();
        App app = new App()
                .beforeRequest(req -> WebResponse.empty(HttpStatus.FORBIDDEN))
                .statusPage(HttpStatus.FORBIDDEN, req -> WebResponse.text("denied"))
                .responseFilter((req, res) -> res.header("X-Filtered", "yes"))
                .securityHeaders()
                .get("/route", req -> {
                    handled.incrementAndGet();
                    return WebResponse.text("route");
                });
        WebTest.test(app, client -> {
            for (String path : List.of("/route", "/style.css", "/missing")) {
                var response = client.get(path);
                assertThat(response.statusCode()).isEqualTo(403);
                assertThat(response.body()).isEqualTo("denied");
                assertThat(response.headers().firstValue("X-Filtered")).contains("yes");
                assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
            }
            assertThat(client.options("/route").statusCode()).isEqualTo(403);
            assertThat(client.head("/style.css").statusCode()).isEqualTo(403);
            assertThat(client.post("/route").statusCode()).isEqualTo(403);
        });
        assertThat(handled).hasValue(0);
    }

    @Test
    void scopedRequestFiltersRunBeforeRouteFiltersAndExposeNoPathVariables() {
        List<String> visited = new ArrayList<>();
        App app = new App().path("/api", api -> api
                .beforeRequest(req -> {
                    visited.add("request");
                    assertThatThrownBy(() -> req.pathParam("id")).isInstanceOf(IllegalStateException.class);
                    return null;
                })
                .beforeRoute(req -> {
                    visited.add(req.pathParam("id"));
                    return null;
                })
                .get("/{id}", req -> WebResponse.text("ok")));
        WebTest.test(app, client -> {
            assertThat(client.get("/api/7").statusCode()).isEqualTo(200);
            client.get("/outside");
        });
        assertThat(visited).containsExactly("request", "7");
        assertThat(app.hooks()).containsExactly(
                new Hook.BeforeRequest("/api/*"), new Hook.BeforeRoute("/api/*"));
    }

    @Test
    void requestFilterExceptionsUseTheNormalErrorPipeline() {
        App app = new App().beforeRequest(req -> {
            throw new IllegalArgumentException("rejected");
        }).exception(IllegalArgumentException.class,
                (req, e) -> WebResponse.empty(HttpStatus.BAD_REQUEST))
                .statusPage(HttpStatus.BAD_REQUEST, req -> WebResponse.text("bad request"));
        WebTest.test(app, client -> {
            var response = client.get("/style.css");
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).isEqualTo("bad request");
        });
    }

    @Test
    void nullResponseTransformsAreErrorsAndTheFailingFilterIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        App app = new App().get("/", req -> WebResponse.text("ok"))
                .responseFilter((req, res) -> {
                    calls.incrementAndGet();
                    return null;
                }).statusPage(HttpStatus.INTERNAL_SERVER_ERROR, req -> WebResponse.text("broken filter"));
        WebTest.test(app, client -> {
            var response = client.get("/");
            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body()).isEqualTo("broken filter");
        });
        assertThat(calls).hasValue(1);

        App after = new App().get("/", req -> WebResponse.text("ok"))
                .afterRoute((req, res) -> null);
        WebTest.test(after, client -> assertThat(client.get("/").statusCode()).isEqualTo(500));
    }
}
