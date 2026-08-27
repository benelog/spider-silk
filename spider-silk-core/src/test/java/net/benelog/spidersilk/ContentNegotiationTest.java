package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.test.TestClient;
import net.benelog.spidersilk.test.WebTest;

/** The Accept header read for the handler, so no application parses one itself. */
class ContentNegotiationTest {

    /** The app the negotiation tests ask: HTML for a browser, JSON for a client. */
    private static App negotiating() {
        return new App().get("/decks", req -> switch (req.accepts("text/html", "application/json")) {
            case "application/json" -> WebResponse.json("[]");
            default -> WebResponse.html("<h1>Decks</h1>");
        });
    }

    @Test
    void theCallerGetsTheTypeItAskedFor() {
        WebTest.test(negotiating(), client -> {
            assertThat(get(client, "/decks", "application/json").body()).isEqualTo("[]");
            assertThat(get(client, "/decks", "text/html").body()).isEqualTo("<h1>Decks</h1>");
        });
    }

    /** A browser's Accept lists HTML first and everything else behind a wildcard. */
    @Test
    void aBrowserGetsThePage() {
        WebTest.test(negotiating(), client -> assertThat(get(client, "/decks",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8").body())
                .isEqualTo("<h1>Decks</h1>"));
    }

    @Test
    void qualityValuesDecideBetweenTwoTypesTheCallerWillTake() {
        WebTest.test(negotiating(), client -> assertThat(get(client, "/decks",
                "text/html;q=0.8, application/json;q=0.9").body()).isEqualTo("[]"));
    }

    /** {@code application/*} covers JSON, and a bare type covers it more closely. */
    @Test
    void theClosestMatchWinsOverAWildcard() {
        WebTest.test(negotiating(), client -> assertThat(get(client, "/decks",
                "*/*;q=0.8, application/json").body()).isEqualTo("[]"));
    }

    /** A refusal is not overridden by a wildcard that would otherwise have covered it. */
    @Test
    void aTypeRefusedWithZeroQualityIsNotAnswered() {
        WebTest.test(negotiating(), client -> assertThat(get(client, "/decks",
                "text/html;q=0, */*").body()).isEqualTo("[]"));
    }

    /** No Accept is a caller that never said, so the handler's own order decides. */
    @Test
    void aCallerThatAsksForNothingGetsTheFirstTypeOffered() {
        WebTest.test(negotiating(), client ->
                assertThat(client.get("/decks").body()).isEqualTo("<h1>Decks</h1>"));
    }

    @Test
    void aCallerThatWillTakeNoneOfThemGetsA406() {
        WebTest.test(negotiating(), client -> {
            HttpResponse<String> response = get(client, "/decks", "application/pdf");

            assertThat(response.statusCode()).isEqualTo(406);
            assertThat(response.body()).contains("text/html, application/json");
        });
    }

    /**
     * The answer depends on a request header, so a shared cache is told —
     * whichever type it turned out to be, and even when the answer was a 406.
     */
    @Test
    void aNegotiatedAnswerVariesByAccept() {
        WebTest.test(negotiating(), client -> {
            assertThat(header(get(client, "/decks", "application/json"), "Vary"))
                    .contains("Accept");
            assertThat(header(get(client, "/decks", "application/pdf"), "Vary"))
                    .contains("Accept");
        });
    }

    /** A route that never asks is a route whose answer does not depend on Accept. */
    @Test
    void aRouteThatDoesNotNegotiateSaysNothingAboutAccept() {
        App app = new App().get("/health", req -> WebResponse.text("ok"));

        WebTest.test(app, client -> assertThat(get(client, "/health", "text/html")
                .headers().firstValue("Vary")).isEmpty());
    }

    /** The parsed view: what was asked for, best first, the refusals dropped. */
    @Test
    void theTypesAskedForAreReadInPreferenceOrder() {
        App app = new App().get("/types", req -> WebResponse.text(
                String.join(" ", req.acceptedTypes())));

        WebTest.test(app, client -> {
            assertThat(get(client, "/types", "text/plain;q=0.5, text/html, image/png;q=0")
                    .body()).isEqualTo("text/html text/plain");
            assertThat(client.get("/types").body()).isEmpty();
        });
    }

    /** Types the caller wants equally are ordered by how closely they name one. */
    @Test
    void aWildcardComesAfterTheTypesItCoversWhenTheyAreWantedEqually() {
        App app = new App().get("/types", req -> WebResponse.text(
                String.join(" ", req.acceptedTypes())));

        WebTest.test(app, client -> assertThat(get(client, "/types", "*/*, text/html, text/*")
                .body()).isEqualTo("text/html text/* */*"));
    }

    private static HttpResponse<String> get(TestClient client, String path, String accept) {
        return client.send(request -> request.uri(URI.create(client.url(path)))
                .header("Accept", accept)
                .GET());
    }

    private static String header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }
}
