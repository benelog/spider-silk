package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.json.Json;
import net.benelog.spidersilk.json.JsonReader;
import net.benelog.spidersilk.test.TestRequest;
import net.benelog.spidersilk.test.WebTest;

/** The body as text is read once and kept; the body handed over unread goes out once. */
class BodyReadsTest {

    private static final JsonReader<String> NAME = json -> json.asObject().getString("name");

    @Test
    void theTextAndTheJsonAreTheSameBodyReadOnce() {
        WebRequest request = TestRequest.post("/decks").jsonBody("{\"name\":\"Spanish\"}").build();

        assertThat(request.body()).isEqualTo("{\"name\":\"Spanish\"}");
        assertThat(request.bodyJson(NAME)).isEqualTo("Spanish");
        assertThat(request.bodyJson().asObject().getString("name")).isEqualTo("Spanish");
        assertThat(request.body()).isEqualTo("{\"name\":\"Spanish\"}");
    }

    @Test
    void anEmptyBodyIsKeptAsEmptyText() {
        WebRequest request = TestRequest.post("/decks").build();

        assertThat(request.body()).isEmpty();
        assertThat(request.body()).isEmpty();
    }

    /** A copy of the request over the same servlet request sees the text another copy read. */
    @Test
    void theTextIsSharedByEveryWrapperOfOneRequest() {
        WebRequest first = TestRequest.post("/decks").body("signed").build();
        WebRequest second = new WebRequest(first.raw(), java.util.Map.of());

        assertThat(first.body()).isEqualTo("signed");
        assertThat(second.body()).isEqualTo("signed");
    }

    @Test
    void theTextThenAStreamIsRefused() {
        WebRequest request = TestRequest.post("/decks").jsonBody("{}").build();

        request.bodyJson();

        assertThatIllegalStateException().isThrownBy(request::bodyStream).withMessageContaining("body()");
        assertThatIllegalStateException().isThrownBy(request::bodyReader).withMessageContaining("body()");
        assertThatIllegalStateException()
                .isThrownBy(() -> request.bodyNdjson(NAME))
                .withMessageContaining("body()");
    }

    @Test
    void aReaderThenTheTextIsRefused() {
        WebRequest request = TestRequest.post("/decks").body("text").build();

        request.bodyReader();

        assertThatIllegalStateException().isThrownBy(request::body).withMessageContaining("bodyReader()");
        assertThatIllegalStateException().isThrownBy(request::bodyJson);
    }

    /** NDJSON stays lazy: taking the stream reads no line, and the text is refused all the same. */
    @Test
    void ndjsonIsHandedOverUnreadAndStaysLazy() {
        WebRequest request = TestRequest.post("/cards").body("{\"name\":\"a\"}\nnot json\n").build();

        var names = request.bodyNdjson(NAME);

        assertThatIllegalStateException().isThrownBy(request::body);
        assertThat(names.limit(1).toList()).containsExactly("a");
    }

    /** The case the kept text exists for: a filter reads the body, and the handler still parses it. */
    @Test
    void aBeforeFilterThatReadsTheBodyLeavesItForTheHandler() {
        List<String> signed = new ArrayList<>();
        App app = new App()
                .beforeRoute(req -> {
                    signed.add(req.body());
                    return null;
                })
                .post("/decks", req -> WebResponse.text(req.bodyJson(NAME)))
                .requestLogger((req, completion) -> signed.add(req.body()));

        WebTest.test(app, client -> {
            var response = client.send(builder -> builder
                    .uri(URI.create(client.url("/decks")))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Spanish\"}")));

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("Spanish");
        });
        assertThat(signed).containsExactly("{\"name\":\"Spanish\"}", "{\"name\":\"Spanish\"}");
    }

    /** Behind a container too: bytes after text is the framework's refusal, answered as a 500. */
    @Test
    void aStreamAfterTheTextIsAServerErrorBehindAContainer() {
        App app = new App().post("/decks", req -> {
            req.body();
            try (InputStream in = req.bodyStream()) {
                return WebResponse.text(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        });

        WebTest.test(app, client -> {
            var response = client.send(builder -> builder
                    .uri(URI.create(client.url("/decks")))
                    .POST(HttpRequest.BodyPublishers.ofString(Json.object().put("name", "x").toJson())));

            assertThat(response.statusCode()).isEqualTo(500);
        });
    }
}
