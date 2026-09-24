package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.benelog.spidersilk.json.Json;
import net.benelog.spidersilk.json.JsonReader;
import net.benelog.spidersilk.test.TestClient;
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

    /**
     * The NDJSON reader holds a chunk of the body no one else can see, so a
     * reader after it would start partway through. It used to answer the rest
     * without a word, a line short.
     */
    @Test
    void aBodyTheNdjsonReaderTookGoesToNoOtherReader() {
        String body = "{\"name\":\"a\"}\n{\"name\":\"b\"}\n";
        App app = new App()
                .post("/stream", req -> {
                    String first = req.bodyNdjson(NAME).findFirst().orElse("");
                    req.bodyStream();
                    return WebResponse.text(first);
                })
                .post("/twice", req -> {
                    String first = req.bodyNdjson(NAME).findFirst().orElse("");
                    assertThat(first).isEqualTo("a");
                    return WebResponse.text(String.valueOf(req.bodyNdjson(NAME).count()));
                })
                .exception(IllegalStateException.class, (req, e) -> WebResponse.text(e.getMessage()));

        WebTest.test(app, client -> {
            assertThat(client.post("/stream", body).body())
                    .isEqualTo("The body already went out through bodyNdjson(), so bodyStream() cannot read it as well");
            assertThat(client.post("/twice", body).body())
                    .isEqualTo("The body already went out through bodyNdjson(), so bodyNdjson() cannot read it as well");
        });
    }

    /** The stream and the reader are the container's, so asking again answers the same one. */
    @Test
    void theSameStreamOrReaderCanBeAskedForAgain() {
        App app = new App()
                .post("/stream", req -> {
                    InputStream first = req.bodyStream();
                    return WebResponse.text(String.valueOf(req.bodyStream().equals(first)));
                })
                .post("/reader", req -> {
                    BufferedReader first = req.bodyReader();
                    return WebResponse.text(String.valueOf(req.bodyReader().equals(first)));
                })
                .post("/mixed", req -> {
                    req.bodyReader();
                    req.bodyStream();
                    return WebResponse.text("read twice");
                })
                .exception(IllegalStateException.class, (req, e) -> WebResponse.text(e.getMessage()));

        WebTest.test(app, client -> {
            assertThat(client.post("/stream", "x").body()).isEqualTo("true");
            assertThat(client.post("/reader", "x").body()).isEqualTo("true");
            assertThat(client.post("/mixed", "x").body())
                    .isEqualTo("The body already went out through bodyReader(), so bodyStream() cannot read it as well");
        });
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

    /** A charset this JVM cannot decode is the client's to fix: 415, whichever read meets it. */
    @Test
    void anUnknownCharsetIsA415ThroughEveryTextRead() {
        App app = new App()
                .post("/body", req -> WebResponse.text(req.body()))
                .post("/json", req -> WebResponse.json(req.bodyJson()))
                .post("/reader", req -> WebResponse.text(String.valueOf(req.bodyReader().readLine())))
                .post("/ndjson", req -> WebResponse.text(req.bodyNdjson(json -> json).count() + " lines"));

        WebTest.test(app, client -> {
            for (String path : List.of("/body", "/json", "/reader", "/ndjson")) {
                var response = client.send(builder -> builder
                        .uri(URI.create(client.url(path)))
                        .header("Content-Type", "application/json; charset=no-such-charset")
                        .POST(HttpRequest.BodyPublishers.ofString("{}")));

                assertThat(response.statusCode()).as(path).isEqualTo(415);
                assertThat(response.body()).as(path).contains("no-such-charset");
            }
        });
    }

    /** A charset the JVM knows is the one the text is decoded in. */
    @Test
    void aDeclaredCharsetIsTheOneTheTextIsDecodedIn() {
        App app = new App().post("/body", req -> WebResponse.text(req.body()));

        WebTest.test(app, client -> {
            var response = client.send(builder -> builder
                    .uri(URI.create(client.url("/body")))
                    .header("Content-Type", "text/plain; charset=ISO-8859-1")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(
                            "café".getBytes(StandardCharsets.ISO_8859_1))));

            assertThat(response.body()).isEqualTo("café");
        });
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

    /**
     * A client that sends less than its Content-Length and then stops is the
     * request's fault, so every text read answers 400, as a multipart body cut
     * short already did, and the logger does not hear of an application failure.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/text", "/json", "/ndjson"})
    void aBodyCutShortIsA400ThroughEveryTextRead(String path) {
        List<RequestCompletion> logged = new CopyOnWriteArrayList<>();
        App app = new App()
                .requestLogger((req, completion) -> logged.add(completion))
                .post("/text", req -> WebResponse.text(req.body()))
                .post("/json", req -> WebResponse.text(req.bodyJson(NAME)))
                .post("/ndjson", req -> WebResponse.text(String.join(",", req.bodyNdjson(NAME).toList())));

        WebTest.test(app, client -> {
            String response = cutShort(client, path, "{\"na");

            assertThat(response).startsWith("HTTP/1.1 400");
            assertThat(response).contains("Request body could not be read");
        });
        assertThat(logged).singleElement().satisfies(completion -> {
            assertThat(completion.statusCode()).isEqualTo(400);
            assertThat(completion.threw()).isFalse();
        });
    }

    /** A read after the failure refuses too, rather than answering what was left of the body. */
    @Test
    void aSecondReadOfABodyCutShortIsRefusedToo() {
        App app = new App().post("/", req -> {
            try {
                req.body();
            } catch (HttpException first) {
                return WebResponse.text(first.status() + " then " + req.body());
            }
            return WebResponse.text("read");
        });

        WebTest.test(app, client -> assertThat(cutShort(client, "/", "hello"))
                .startsWith("HTTP/1.1 400")
                .contains("Request body could not be read"));
    }

    /**
     * Announces 100 bytes, sends the few given, and half-closes the connection,
     * which is what a client that gave up partway looks like to the server.
     */
    private static String cutShort(TestClient client, String path, String sent) {
        URI base = URI.create(client.url("/"));
        try (Socket socket = new Socket(base.getHost(), base.getPort())) {
            String head = String.join("\r\n", "POST " + path + " HTTP/1.1", "Host: localhost",
                    "Content-Type: application/json", "Content-Length: 100", "Connection: close") + "\r\n\r\n";
            socket.getOutputStream().write((head + sent).getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            socket.shutdownOutput();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
