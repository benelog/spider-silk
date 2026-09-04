package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.test.TestRequest;
import net.benelog.spidersilk.test.WebTest;

/** The reads about a request that used to need {@code raw()}. */
class RequestDetailsTest {

    @Test
    void aPlainRequestReportsItsSchemeAndClientAddress() {
        App app = new App().get("/where", req -> WebResponse.text(
                req.scheme() + " " + req.isSecure() + " " + req.remoteAddress()));

        WebTest.test(app, client ->
                assertThat(client.get("/where").body()).isEqualTo("http false 127.0.0.1"));
    }

    /** The absolute URL of the request, built out of the three reads that make one. */
    @Test
    void schemeHostAndPathRebuildTheUrl() {
        App app = new App().get("/decks/{deckId}", req -> WebResponse.text(
                req.scheme() + "://" + req.host() + req.path()));

        WebTest.test(app, client ->
                assertThat(client.get("/decks/3").body()).isEqualTo(client.url("/decks/3")));
    }

    @Test
    void theQueryStringAndTheContentTypeAreReadAsTheyArrived() {
        App app = new App().post("/echo", req ->
                WebResponse.text(req.queryString() + " | " + req.contentType()));

        WebTest.test(app, client -> {
            var response = client.send(request -> request
                    .uri(URI.create(client.url("/echo?q=a+b%26c&page=2")))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")));

            assertThat(response.body()).isEqualTo("q=a+b%26c&page=2 | application/json");
        });
    }

    /** No query string at all is null, not an empty string: the URL carried none. */
    @Test
    void aUrlWithoutAQueryStringReportsNull() {
        WebRequest request = TestRequest.get("/decks").build();

        assertThat(request.queryString()).isNull();
        assertThat(request.contentType()).isNull();
    }

    @Test
    void everyValueOfARepeatedHeaderIsRead() {
        WebRequest request = TestRequest.get("/decks")
                .header("X-Tag", "one")
                .header("X-Tag", "two")
                .build();

        assertThat(request.header("X-Tag")).isEqualTo("one");
        assertThat(request.headers("X-Tag")).containsExactly("one", "two");
        assertThat(request.headers("X-Absent")).isEmpty();
    }

    @Test
    void everyHeaderIsReadAtOnce() {
        WebRequest request = TestRequest.get("/decks")
                .header("Accept", "text/html")
                .header("X-Tag", "one")
                .header("X-Tag", "two")
                .build();

        assertThat(request.headers()).isEqualTo(Map.of(
                "Accept", List.of("text/html"),
                "X-Tag", List.of("one", "two")));
    }

    /** A handler that branches on the caller states the caller in the builder. */
    @Test
    void aTestRequestSuppliesTheClientAddressAndTheHost() {
        WebRequest request = TestRequest.get("/decks")
                .remoteAddress("203.0.113.7")
                .header("Host", "shop.example.com:8080")
                .build();

        assertThat(request.remoteAddress()).isEqualTo("203.0.113.7");
        assertThat(request.host()).isEqualTo("shop.example.com:8080");
        assertThat(request.scheme()).isEqualTo("http");
        assertThat(request.isSecure()).isFalse();
    }

    /** The default port for the scheme is left off, so the host reads as it was written. */
    @Test
    void theDefaultPortIsNotSpelledOut() {
        assertThat(TestRequest.get("/decks").header("Host", "shop.example.com").build().host())
                .isEqualTo("shop.example.com");
        assertThat(TestRequest.get("/decks").secure().header("Host", "shop.example.com").build().host())
                .isEqualTo("shop.example.com");
    }

    @Test
    void aSecureRequestSaysSoInBothPlaces() {
        WebRequest request = TestRequest.get("/decks").secure().build();

        assertThat(request.isSecure()).isTrue();
        assertThat(request.scheme()).isEqualTo("https");
    }
}
