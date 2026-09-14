package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.test.WebTest;

/** {@code app.responseFilter(...)}: every answer, including the ones an after-filter never sees. */
class ResponseFilterTest {

    private static final ResponseFilter REQUEST_ID = (req, res) -> res.header("X-Request-Id", "r-1");

    /** The example the manual used to give for an after-filter, now covering what that one missed. */
    @Test
    void everyKindOfAnswerCarriesTheHeader() {
        App app = new App()
                .responseFilter(REQUEST_ID)
                .beforeRoute("/admin/*", req -> WebResponse.redirect("/login"))
                .get("/admin/users", req -> WebResponse.text("never"))
                .get("/ok", req -> WebResponse.text("ok"))
                .post("/only-post", req -> WebResponse.empty())
                .get("/boom", req -> {
                    throw new IllegalStateException("boom");
                })
                .get("/missing", req -> {
                    throw new HttpException(HttpStatus.NOT_FOUND, "no such deck");
                })
                .exception(IllegalArgumentException.class,
                        (req, e) -> WebResponse.text("bad").status(HttpStatus.BAD_REQUEST))
                .get("/bad", req -> {
                    throw new IllegalArgumentException("bad");
                });

        WebTest.test(app, client -> {
            assertCarriesId(client.get("/ok"), 200);
            assertCarriesId(client.get("/admin/users"), 302);
            assertCarriesId(client.get("/nowhere"), 404);
            assertCarriesId(client.get("/only-post"), 405);
            assertCarriesId(client.options("/only-post"), 200);
            assertCarriesId(client.get("/boom"), 500);
            assertCarriesId(client.get("/missing"), 404);
            assertCarriesId(client.get("/bad"), 400);
            assertCarriesId(client.get("/style.css"), 200);
            assertCarriesId(client.head("/ok"), 200);
        });
    }

    /** The filter sees the finished answer: the error body is already there. */
    @Test
    void theFilterSeesTheErrorBodyAndTheRenderedTemplate() {
        List<String> seen = new ArrayList<>();
        App app = new App()
                .error(HttpStatus.NOT_FOUND, req -> WebResponse.text("styled 404"))
                .get("/page", req -> WebResponse.template("greeting", Map.of("name", "Silk")))
                .responseFilter((req, res) -> {
                    seen.add(switch (res.body()) {
                        case WebResponse.Text text -> text.content().strip();
                        default -> res.body().getClass().getSimpleName();
                    });
                    return res;
                });

        WebTest.test(app, client -> {
            client.get("/nowhere");
            client.get("/page");
        });
        assertThat(seen).containsExactly("styled 404", "<p>Hello, Silk!</p>");
    }

    /** Several run in registration order, each on what the one before it answered. */
    @Test
    void filtersRunInRegistrationOrderOverEachOthersAnswers() {
        App app = new App()
                .get("/", req -> WebResponse.text("ok"))
                .responseFilter((req, res) -> res.header("X-Trail", "first"))
                .responseFilter((req, res) -> res.header("X-Trail", res.header("X-Trail") + ",second"));

        WebTest.test(app, client ->
                assertThat(client.get("/").headers().firstValue("X-Trail")).hasValue("first,second"));
    }

    /** Path variables of the route that matched reach the filter, as they reach the handler. */
    @Test
    void theRequestCarriesThePathVariables() {
        App app = new App()
                .get("/decks/{deckId}", req -> WebResponse.text("deck"))
                .responseFilter((req, res) -> res.header("X-Deck", req.pathParam("deckId")));

        WebTest.test(app, client ->
                assertThat(client.get("/decks/7").headers().firstValue("X-Deck")).hasValue("7"));
    }

    /** A replacement body is rendered when it is a template, and still compressed, and still decorated. */
    @Test
    void aReplacementIsRenderedAndThenDecorated() {
        App app = new App()
                .gzip(Gzip.defaults().minBytes(0))
                .securityHeaders()
                .get("/", req -> WebResponse.text("plain"))
                .responseFilter((req, res) -> WebResponse.template("greeting", Map.of("name", "Filter".repeat(100)))
                        .header("X-Frame-Options", "SAMEORIGIN"));

        WebTest.test(app, client -> {
            HttpResponse<String> plain = client.get("/");
            assertThat(plain.body()).isEqualTo("<p>Hello, " + "Filter".repeat(100) + "!</p>\n");
            assertThat(plain.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
            assertThat(plain.headers().firstValue("X-Frame-Options")).hasValue("SAMEORIGIN");

            HttpResponse<String> zipped = client.send(builder -> builder
                    .uri(URI.create(client.url("/")))
                    .header("Accept-Encoding", "gzip"));
            assertThat(zipped.headers().firstValue("Content-Encoding")).hasValue("gzip");
        });
    }

    /**
     * A filter that throws is answered as a handler that throws is: through the
     * exception handlers and the error bodies. The filters do not run over that
     * answer, so a filter that always throws does not throw forever.
     */
    @Test
    void aFilterThatThrowsIsAnsweredOnceAndNotFilteredAgain() {
        AtomicInteger calls = new AtomicInteger();
        App app = new App()
                .get("/", req -> WebResponse.text("ok"))
                .error(HttpStatus.INTERNAL_SERVER_ERROR, req -> WebResponse.text("styled 500"))
                .responseFilter((req, res) -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("filter broke");
                })
                .securityHeaders();

        WebTest.test(app, client -> {
            HttpResponse<String> response = client.get("/");
            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body()).isEqualTo("styled 500");
            assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        });
        assertThat(calls).hasValue(1);
    }

    @Test
    void aFilterThatThrowsReachesTheExceptionHandlers() {
        App app = new App()
                .get("/", req -> WebResponse.text("ok"))
                .exception(IllegalArgumentException.class,
                        (req, e) -> WebResponse.text(e.getMessage()).status(HttpStatus.BAD_REQUEST))
                .responseFilter((req, res) -> {
                    throw new IllegalArgumentException("rejected by the filter");
                });

        WebTest.test(app, client -> {
            HttpResponse<String> response = client.get("/");
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).isEqualTo("rejected by the filter");
        });
    }

    @Test
    void anAfterFilterStillSeesOnlyARouteThatCompleted() {
        App app = new App()
                .afterRoute((req, res) -> res.header("X-After", "yes"))
                .responseFilter((req, res) -> res.header("X-Response", "yes"))
                .get("/ok", req -> WebResponse.text("ok"));

        WebTest.test(app, client -> {
            HttpResponse<String> ok = client.get("/ok");
            assertThat(ok.headers().firstValue("X-After")).hasValue("yes");
            assertThat(ok.headers().firstValue("X-Response")).hasValue("yes");

            HttpResponse<String> missing = client.get("/nowhere");
            assertThat(missing.headers().firstValue("X-After")).isEmpty();
            assertThat(missing.headers().firstValue("X-Response")).hasValue("yes");
        });
    }

    private static void assertCarriesId(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.uri().getPath()).isEqualTo(status);
        assertThat(response.headers().firstValue("X-Request-Id"))
                .as(response.request().method() + " " + response.uri().getPath())
                .hasValue("r-1");
    }
}
