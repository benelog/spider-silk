package net.benelog.spidersilk;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;

import net.benelog.silkjson.JsonOutput;
import net.benelog.silkjson.JsonReader;
import net.benelog.silkjson.JsonWriter;
import net.benelog.spidersilk.test.WebTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/** {@code WebResponse.json(value, writer)} and {@code req.bodyJson(reader)} over HTTP. */
class JsonSeamTest {

    record Deck(long id, String name) {
    }

    static final JsonWriter<Deck> DECK =
            (deck, out) -> out.object().put("id", deck.id()).put("name", deck.name()).end();

    static final JsonReader<String> DECK_NAME = JsonReader.object(object -> object.getString("name"));

    @Test
    void aWriterRendersTheResponse() {
        App app = new App().get("/decks", req -> WebResponse.json(
                List.of(new Deck(1, "English"), new Deck(2, "Spanish")), JsonWriter.list(DECK)));

        WebTest.test(app, client -> {
            var response = client.get("/decks");

            assertThat(response.headers().firstValue("Content-Type").orElse(""))
                    .startsWith("application/json");
            assertThat(response.body())
                    .isEqualTo("[{\"id\":1,\"name\":\"English\"},{\"id\":2,\"name\":\"Spanish\"}]");
        });
    }

    @Test
    void aReaderBuildsTheRequestValue() {
        App app = new App().post("/decks", req -> WebResponse.text(req.bodyJson(DECK_NAME)));

        WebTest.test(app, client ->
                assertThat(client.postJson("/decks", "{\"name\":\"English\"}").body())
                        .isEqualTo("English"));
    }

    /** The reader throws IllegalArgumentException; the handler never sees a half-built value. */
    @Test
    void aBodyTheReaderRejectsIsA400() {
        App app = new App().post("/decks", req -> WebResponse.text(req.bodyJson(DECK_NAME)));

        WebTest.test(app, client -> {
            assertThat(client.postJson("/decks", "{\"title\":\"English\"}").statusCode())
                    .isEqualTo(400);
            assertThat(client.postJson("/decks", "{\"name\":42}").statusCode()).isEqualTo(400);
            assertThat(client.postJson("/decks", "not-json").statusCode()).isEqualTo(400);
        });
    }

    /** An element the array does not have is a missing value, as a missing key is. */
    @Test
    void aReaderGivenAShortArrayIsA400() {
        App app = new App().post("/first", req ->
                WebResponse.text(req.bodyJson(JsonReader.tree(json -> json.asArray().get(0).asString()))));

        WebTest.test(app, client -> {
            assertThat(client.postJson("/first", "[]").statusCode()).isEqualTo(400);
            assertThat(client.postJson("/first", "[\"a\"]").body()).isEqualTo("a");
        });
    }

    /** Text outside RFC 8259 that a lenient parser would read is a 400 as well. */
    @Test
    void aBodyOutsideTheJsonGrammarIsA400() {
        App app = new App()
                .post("/decks", req -> WebResponse.text(req.bodyJson(DECK_NAME)))
                .post("/raw", req -> WebResponse.text(req.bodyJson().toJson()));

        WebTest.test(app, client -> {
            for (String number : List.of("01", "+1", ".5", "1.", "-", "1e")) {
                assertThat(client.postJson("/raw", "{\"n\":" + number + "}").statusCode())
                        .as(number).isEqualTo(400);
            }
            assertThat(client.postJson("/decks", "{\"name\":\"a\nb\"}").statusCode()).isEqualTo(400);
            assertThat(client.postJson("/decks", "{\"name\":\"a\\n\tb\"}").statusCode()).isEqualTo(400);

            var accepted = client.postJson("/raw", "{\"n\":[-1,0.5,1e2],\"s\":\"a\\nb\\u0009\"}");
            assertThat(accepted.statusCode()).isEqualTo(200);
            assertThat(accepted.body()).isEqualTo("{\"n\":[-1,0.5,1e2],\"s\":\"a\\nb\\t\"}");
        });
    }

    /** A 400 for the wrong type names what it found, and never echoes the body back. */
    @Test
    void aTypeMismatchDoesNotEchoTheBody() {
        App app = new App().post("/name", req -> WebResponse.text(req.bodyJson(JsonReader.object(object -> object.getString("name")))));

        WebTest.test(app, client -> {
            var array = client.postJson("/name", "[" + "\"secret\",".repeat(100) + "1]");
            assertThat(array.statusCode()).isEqualTo(400);
            assertThat(array.body()).doesNotContain("secret").hasSizeLessThan(100);

            var nested = client.postJson("/name", "{\"name\":{\"password\":\"hunter2\"}}");
            assertThat(nested.statusCode()).isEqualTo(400);
            assertThat(nested.body()).doesNotContain("hunter2").contains("Not a JSON string: an object");
        });
    }

    static final JsonReader<Long> DECK_ID = JsonReader.object(object -> object.getLong("id"));

    /**
     * An identifier is read from its digits, not from the nearest double: a
     * decimal that is exactly a long reads as that long, and one with a
     * fraction the double rounded away is a 400 rather than a different id.
     */
    @Test
    void aDecimalIdIsReadExactlyOrRejectedAsA400() {
        App app = new App().post("/decks", req -> WebResponse.text(String.valueOf(req.bodyJson(DECK_ID))));

        WebTest.test(app, client -> {
            var exact = client.postJson("/decks", "{\"id\":9007199254740993.0}");
            assertThat(exact.statusCode()).isEqualTo(200);
            assertThat(exact.body()).isEqualTo("9007199254740993");

            assertThat(client.postJson("/decks", "{\"id\":1.0000000000000001}").statusCode())
                    .isEqualTo(400);
            assertThat(client.postJson("/decks", "{\"id\":9223372036854775808.0}").statusCode())
                    .isEqualTo(400);
        });
    }

    /** Enough decks that gzip is worth it, and a document of a size the servlet would otherwise copy. */
    private static final List<Deck> MANY = manyDecks();

    private static final String MANY_JSON = JsonWriter.list(DECK).toJson(MANY);

    private static List<Deck> manyDecks() {
        List<Deck> decks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            decks.add(new Deck(i, "deck " + i));
        }
        return decks;
    }

    /**
     * The document goes out from the buffer it was written into, after a
     * Content-Length, and the buffer goes back once it is sent: the output the
     * writer was handed can no longer be read.
     */
    @Test
    void aJsonBodyIsSentWithItsLengthAndItsBufferIsReleased() {
        Released released = new Released();
        App app = released.watch(new App());

        WebTest.test(app, client -> {
            var response = client.get("/decks");

            assertThat(response.body()).isEqualTo(MANY_JSON);
            assertThat(response.headers().firstValue("Content-Length"))
                    .hasValue(Integer.toString(MANY_JSON.getBytes(StandardCharsets.UTF_8).length));
            released.await();
        });
    }

    /** A HEAD carries the length the GET sends, and hands the buffer back all the same. */
    @Test
    void aHeadOfAJsonBodyCarriesItsLengthAndReleasesTheBuffer() {
        Released released = new Released();
        App app = released.watch(new App());

        WebTest.test(app, client -> {
            var response = client.head("/decks");

            assertThat(response.body()).isEmpty();
            assertThat(response.headers().firstValue("Content-Length"))
                    .hasValue(Integer.toString(MANY_JSON.getBytes(StandardCharsets.UTF_8).length));
            released.await();
        });
    }

    /** Gzip deflates the document from the buffer, and the buffer goes back after that too. */
    @Test
    void aCompressedJsonBodyIsDeflatedFromTheBufferAndReleasesIt() {
        Released released = new Released();
        App app = released.watch(new App().gzip());

        WebTest.test(app, client -> {
            HttpResponse<byte[]> response = client.send(request -> request
                    .uri(URI.create(client.url("/decks")))
                    .header("Accept-Encoding", "gzip")
                    .GET(), HttpResponse.BodyHandlers.ofByteArray());

            assertThat(response.headers().firstValue("Content-Encoding")).hasValue("gzip");
            assertThat(response.headers().firstValue("Content-Length"))
                    .hasValue(Integer.toString(response.body().length));
            assertThat(inflate(response.body())).isEqualTo(MANY_JSON);
            released.await();
        });
    }

    /** A response kept as a constant is written again for every request it answers. */
    @Test
    void aJsonResponseHeldAsAConstantIsWrittenForEachRequest() {
        WebResponse decks = WebResponse.json(MANY, JsonWriter.list(DECK));
        App app = new App()
                .get("/decks", req -> decks)
                .get("/one", req -> WebResponse.json(new Deck(1, "English"), DECK));

        WebTest.test(app, client -> {
            for (int i = 0; i < 3; i++) {
                assertThat(client.get("/decks").body()).isEqualTo(MANY_JSON);
                assertThat(client.get("/one").body()).isEqualTo("{\"id\":1,\"name\":\"English\"}");
            }
        });
    }

    /** The writer runs while exception handling still applies, so what it throws reaches an exception handler. */
    @Test
    void aWriterThatThrowsReachesTheExceptionHandlers() {
        JsonWriter<Deck> failing = (deck, out) -> {
            throw new IllegalArgumentException("no name for " + deck.id());
        };
        App app = new App()
                .get("/decks/1", req -> WebResponse.json(new Deck(1, "English"), failing))
                .exception(IllegalArgumentException.class,
                        (req, e) -> WebResponse.text(e.getMessage()).status(HttpStatus.CONFLICT));

        WebTest.test(app, client -> {
            var response = client.get("/decks/1");

            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(response.body()).isEqualTo("no name for 1");
        });
    }

    /** A writer that leaves an object open is a 500 before anything is sent, not half a document. */
    @Test
    void aWriterThatLeavesAContainerOpenIsA500() {
        JsonWriter<Deck> unclosed = (deck, out) -> out.object().put("id", deck.id());
        App app = new App().get("/decks/1", req -> WebResponse.json(new Deck(1, "English"), unclosed));

        WebTest.test(app, client -> {
            var response = client.get("/decks/1");

            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body()).isEqualTo("Internal Server Error");
        });
    }

    /** An exception handler whose JSON fails as well leaves a plain 500. */
    @Test
    void anErrorAnswerWhoseWriterFailsTooIsAPlain500() {
        JsonWriter<Deck> failing = (deck, out) -> {
            throw new IllegalArgumentException("no name for " + deck.id());
        };
        App app = new App()
                .get("/decks/1", req -> WebResponse.json(new Deck(1, "English"), failing))
                .exception(IllegalArgumentException.class,
                        (req, e) -> WebResponse.json(new Deck(2, "error"), failing).status(HttpStatus.CONFLICT));

        WebTest.test(app, client -> {
            var response = client.get("/decks/1");

            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body()).isEqualTo("Internal Server Error");
        });
    }

    /**
     * Keeps the output a route's writer was handed, and tells the test when
     * the request logger has heard of the request, which is after the servlet
     * released the buffer.
     */
    private static final class Released {

        private final AtomicReference<JsonOutput> handed = new AtomicReference<>();
        private final CountDownLatch logged = new CountDownLatch(1);

        App watch(App app) {
            JsonWriter<List<Deck>> keeping = (decks, out) -> {
                handed.set(out);
                JsonWriter.list(DECK).write(decks, out);
            };
            return app.get("/decks", req -> WebResponse.json(MANY, keeping))
                    .requestLogger((request, completion) -> logged.countDown());
        }

        void await() {
            try {
                assertThat(logged.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            JsonOutput out = handed.get();
            assertThat(out).isNotNull();
            assertThatThrownBy(out::length).isInstanceOf(IllegalStateException.class);
        }
    }

    private static String inflate(byte[] compressed) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
