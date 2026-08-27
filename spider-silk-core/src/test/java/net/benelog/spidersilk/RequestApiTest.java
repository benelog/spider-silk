package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.test.WebTest;

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

            assertThat(response.body()).isEqualTo("query=url form=body");
        });
    }

    @Test
    void bodyArrivesAsItWasSent() {
        App app = new App().post("/echo", req -> WebResponse.text(
                req.body().replace("\r", "\\r").replace("\n", "\\n")));

        WebTest.test(app, client -> assertThat(client.post("/echo", "one\r\ntwo\n").body())
                .isEqualTo("one\\r\\ntwo\\n"));
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

            assertThat(response.body()).isEqualTo("[a, b] [c] [a, b, c]");
        });
    }

    @Test
    void aFormOnlyPostHasNoQueryParameters() {
        App app = new App().post("/submit", req -> WebResponse.text(
                "query=" + req.queryParam("name") + " form=" + req.formParam("name")));

        WebTest.test(app, client ->
                assertThat(client.postForm("/submit", Map.of("name", "Ada")).body())
                        .isEqualTo("query=null form=Ada"));
    }

    @Test
    void queryParametersAreUrlDecoded() {
        App app = new App().get("/search", req -> WebResponse.text(req.queryParam("q")));

        WebTest.test(app, client ->
                assertThat(client.get("/search?q=a+b%26c").body()).isEqualTo("a b&c"));
    }

    @Test
    void headIsAnsweredByTheGetRoute() {
        App app = new App().get("/decks", req -> WebResponse.text("one\ntwo"));

        WebTest.test(app, client -> {
            var response = client.head("/decks");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEmpty();
            assertThat(response.headers().firstValue("Content-Length").orElseThrow()).isEqualTo("7");
            assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/plain");
        });
    }

    @Test
    void aRouteRegisteredForHeadStillWins() {
        App app = new App()
                .get("/decks", req -> WebResponse.text("body"))
                .head("/decks", req -> WebResponse.empty().header("X-Count", "2"));

        WebTest.test(app, client ->
                assertThat(client.head("/decks").headers().firstValue("X-Count").orElseThrow())
                        .isEqualTo("2"));
    }

    @Test
    void optionsListsWhatThePathAnswersTo() {
        App app = new App()
                .get("/decks", req -> WebResponse.text("list"))
                .post("/decks", req -> WebResponse.text("created"));

        WebTest.test(app, client -> {
            var response = client.options("/decks");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEmpty();
            assertThat(allowed(response.headers().firstValue("Allow").orElseThrow()))
                    .isEqualTo(List.of("GET", "POST", "HEAD", "OPTIONS"));
        });
    }

    @Test
    void optionsOnAnUnknownPathIsStillA404() {
        App app = new App().get("/decks", req -> WebResponse.text("list"));

        WebTest.test(app, client ->
                assertThat(client.options("/missing").statusCode()).isEqualTo(404));
    }

    @Test
    void anOptionsRouteOfItsOwnTakesOver() {
        App app = new App()
                .get("/decks", req -> WebResponse.text("list"))
                .options("/decks",
                        req -> WebResponse.empty().header("Access-Control-Allow-Origin", "*"));

        WebTest.test(app, client ->
                assertThat(client.options("/decks").headers().firstValue("Access-Control-Allow-Origin").orElseThrow())
                        .isEqualTo("*"));
    }

    private static List<String> allowed(String header) {
        return List.of(header.split(", "));
    }
}
