package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.test.TestClient;
import net.benelog.spidersilk.test.TestRequest;
import net.benelog.spidersilk.test.WebTest;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

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

    /** A request that declares its charset is read in it; one that does not is read as UTF-8. */
    @Test
    void bodyIsDecodedInTheCharsetTheRequestDeclared() {
        App app = new App().post("/echo", req -> WebResponse.text(req.body()));

        WebTest.test(app, client -> {
            var latin1 = client.send(request -> request.uri(URI.create(client.url("/echo")))
                    .header("Content-Type", "text/plain; charset=ISO-8859-1")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(
                            "caf\u00e9".getBytes(StandardCharsets.ISO_8859_1))));
            assertThat(latin1.body()).isEqualTo("caf\u00e9");

            var undeclared = client.send(request -> request.uri(URI.create(client.url("/echo")))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(
                            "caf\u00e9".getBytes(StandardCharsets.UTF_8))));
            assertThat(undeclared.body()).isEqualTo("caf\u00e9");
        });
    }

    /** A variable the pattern never declared is the handler's mistake, not the caller's. */
    @Test
    void anUndeclaredPathVariableIsAProgrammingError() {
        WebRequest request = TestRequest.get("/decks/3").pathParam("deckId", "3").build();

        assertThatIllegalStateException()
                .isThrownBy(() -> request.pathParam("deckid"))
                .withMessageContaining("{deckid}");
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

    /** A percent-escape that will not decode is bad input, so it answers 400 and not 500. */
    @Test
    void aMalformedQueryStringIsRejectedAsBadInput() {
        App app = new App().get("/search", req -> WebResponse.text(req.queryParam("q")));

        WebTest.test(app, client -> {
            String response = raw(client, "",
                    "GET /search?q=%zz HTTP/1.1", "Host: localhost", "Connection: close");

            assertThat(response).startsWith("HTTP/1.1 400");
            assertThat(response).contains("Query string is not valid URL encoding");
        });
    }

    /** A form read consults the query string to tell the two apart, so it answers 400 too. */
    @Test
    void aFormReadBehindAMalformedQueryStringIsAlsoA400() {
        App app = new App().post("/submit", req -> WebResponse.text(req.formParam("name")));

        WebTest.test(app, client -> {
            String body = "name=Ada";
            String response = raw(client, body,
                    "POST /submit?q=%zz HTTP/1.1",
                    "Host: localhost",
                    "Content-Type: application/x-www-form-urlencoded",
                    "Content-Length: " + body.length(),
                    "Connection: close");

            assertThat(response).startsWith("HTTP/1.1 400");
            assertThat(response).contains("Query string is not valid URL encoding");
        });
    }

    /**
     * Sends a request the java.net.http client cannot build: {@link URI} refuses
     * a percent-escape that is not two hex digits, so a malformed query string
     * has to go on the wire by hand. The whole response is returned as text.
     */
    private static String raw(TestClient client, String body, String... requestLines) {
        String request = String.join("\r\n", requestLines) + "\r\n\r\n" + body;
        URI base = URI.create(client.url("/"));
        try (Socket socket = new Socket(base.getHost(), base.getPort())) {
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
