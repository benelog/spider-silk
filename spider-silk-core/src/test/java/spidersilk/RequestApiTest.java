package spidersilk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import spidersilk.test.WebTest;

/** Query parameters told apart from form fields, plus automatic HEAD and OPTIONS. */
class RequestApiTest {

    @Test
    void formParamAndQueryParamAreToldApart() {
        App app = new App().post("/submit", req -> WebResponse.text(
                "query=" + req.queryParam("source") + " form=" + req.formParam("source")));

        WebTest.test(app, client -> {
            var response = client.send(request -> request.uri(URI.create(client.url(
                            "/submit?source=url")))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("source=body")));

            assertEquals("query=url form=body", response.body());
        });
    }

    @Test
    void repeatedValuesAreSplitByWhereTheyCameFrom() {
        App app = new App().post("/submit", req -> WebResponse.text(
                req.queryParams("tag") + " " + req.formParams("tag") + " " + req.params("tag")));

        WebTest.test(app, client -> {
            var response = client.send(request -> request.uri(URI.create(client.url(
                            "/submit?tag=a&tag=b")))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("tag=c")));

            assertEquals("[a, b] [c] [a, b, c]", response.body());
        });
    }

    @Test
    void aFormOnlyPostHasNoQueryParameters() {
        App app = new App().post("/submit", req -> WebResponse.text(
                "query=" + req.queryParam("name") + " form=" + req.formParam("name")));

        WebTest.test(app, client -> assertEquals("query=null form=Ada",
                client.postForm("/submit", Map.of("name", "Ada")).body()));
    }

    @Test
    void queryParametersAreUrlDecoded() {
        App app = new App().get("/search", req -> WebResponse.text(req.queryParam("q")));

        WebTest.test(app, client ->
                assertEquals("a b&c", client.get("/search?q=a+b%26c").body()));
    }

    @Test
    void headIsAnsweredByTheGetRoute() {
        App app = new App().get("/decks", req -> WebResponse.text("one\ntwo"));

        WebTest.test(app, client -> {
            var response = client.head("/decks");
            assertEquals(200, response.statusCode());
            assertEquals("", response.body());
            assertEquals("7", response.headers().firstValue("Content-Length").orElseThrow());
            assertTrue(response.headers().firstValue("Content-Type").orElseThrow()
                    .startsWith("text/plain"));
        });
    }

    @Test
    void aRouteRegisteredForHeadStillWins() {
        App app = new App()
                .get("/decks", req -> WebResponse.text("body"))
                .head("/decks", req -> WebResponse.empty().header("X-Count", "2"));

        WebTest.test(app, client -> assertEquals("2",
                client.head("/decks").headers().firstValue("X-Count").orElseThrow()));
    }

    @Test
    void optionsListsWhatThePathAnswersTo() {
        App app = new App()
                .get("/decks", req -> WebResponse.text("list"))
                .post("/decks", req -> WebResponse.text("created"));

        WebTest.test(app, client -> {
            var response = client.options("/decks");
            assertEquals(200, response.statusCode());
            assertEquals("", response.body());
            assertEquals(List.of("GET", "POST", "HEAD", "OPTIONS"),
                    allowed(response.headers().firstValue("Allow").orElseThrow()));
        });
    }

    @Test
    void optionsOnAnUnknownPathIsStillA404() {
        App app = new App().get("/decks", req -> WebResponse.text("list"));

        WebTest.test(app, client -> assertEquals(404, client.options("/missing").statusCode()));
    }

    @Test
    void anOptionsRouteOfItsOwnTakesOver() {
        App app = new App()
                .get("/decks", req -> WebResponse.text("list"))
                .options("/decks",
                        req -> WebResponse.empty().header("Access-Control-Allow-Origin", "*"));

        WebTest.test(app, client -> assertEquals("*", client.options("/decks").headers()
                .firstValue("Access-Control-Allow-Origin").orElseThrow()));
    }

    private static List<String> allowed(String header) {
        return List.of(header.split(", "));
    }
}
