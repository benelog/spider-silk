package net.benelog.spidersilk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.json.Json;
import net.benelog.spidersilk.json.JsonReader;
import net.benelog.spidersilk.json.JsonWriter;
import net.benelog.spidersilk.test.WebTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code WebResponse.jsonArray}, {@code WebResponse.ndjson}, and
 * {@code req.bodyNdjson(reader)} over HTTP: an answer and a body too big to
 * hold, one value at a time.
 */
class JsonStreamingTest {

    record Card(long id, String text) {
    }

    static final JsonWriter<Card> CARD =
            card -> Json.obj().put("id", card.id()).put("text", card.text());

    static final JsonReader<Card> READ_CARD = json ->
            new Card(json.asObject().getLong("id"), json.asObject().getString("text"));

    private static final List<Card> CARDS = List.of(new Card(1, "one"), new Card(2, "two"));

    @Test
    void aStreamedArrayIsTheSameDocumentAsAHeldOne() {
        App app = new App()
                .get("/streamed", req -> WebResponse.jsonArray(sink -> {
                    for (Card card : CARDS) {
                        sink.write(card, CARD);
                    }
                }))
                .get("/held", req -> WebResponse.json(CARDS, JsonWriter.list(CARD)));

        WebTest.test(app, client -> {
            var streamed = client.get("/streamed");

            assertThat(streamed.headers().firstValue("Content-Type").orElse(""))
                    .startsWith("application/json");
            assertThat(streamed.body()).isEqualTo(client.get("/held").body());
        });
    }

    /** An array nothing was written to is still an array. */
    @Test
    void anEmptyStreamedArrayIsEmptyBrackets() {
        App app = new App().get("/cards", req -> WebResponse.jsonArray(sink -> {
        }));

        WebTest.test(app, client -> assertThat(client.get("/cards").body()).isEqualTo("[]"));
    }

    @Test
    void ndjsonIsOneValuePerLine() {
        App app = new App().get("/cards.ndjson", req -> WebResponse.ndjson(sink -> {
            for (Card card : CARDS) {
                sink.write(card, CARD);
            }
        }));

        WebTest.test(app, client -> {
            var response = client.get("/cards.ndjson");

            assertThat(response.headers().firstValue("Content-Type").orElse(""))
                    .startsWith("application/x-ndjson");
            assertThat(response.body()).isEqualTo("""
                    {"id":1,"text":"one"}
                    {"id":2,"text":"two"}
                    """);
        });
    }

    /** The writer runs to work out the length, the same as any other streamed body. */
    @Test
    void aHeadOfAStreamedArrayReportsItsLength() {
        App app = new App().get("/cards", req -> WebResponse.jsonArray(sink -> {
            for (Card card : CARDS) {
                sink.write(card, CARD);
            }
        }));

        WebTest.test(app, client -> {
            var response = client.head("/cards");

            assertThat(response.body()).isEmpty();
            assertThat(response.headers().firstValue("Content-Length").orElse(""))
                    .isEqualTo(String.valueOf(client.get("/cards").body().length()));
        });
    }

    @Test
    void bodyNdjsonReadsAValuePerLineAndSkipsBlankOnes() {
        App app = new App().post("/cards", req ->
                WebResponse.json(Json.obj().put("imported", req.bodyNdjson(READ_CARD).count())));

        WebTest.test(app, client -> assertThat(client.post("/cards", """
                {"id":1,"text":"one"}

                {"id":2,"text":"two"}
                """).body()).isEqualTo("{\"imported\":2}"));
    }

    /** The line number is the point: a large body says where it went wrong. */
    @Test
    void aRejectedLineIsA400NamingTheLine() {
        App app = new App().post("/cards", req ->
                WebResponse.json(Json.obj().put("imported", req.bodyNdjson(READ_CARD).count())));

        WebTest.test(app, client -> {
            var response = client.post("/cards", """
                    {"id":1,"text":"one"}
                    {"id":2}
                    """);

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("Line 2");
        });
    }

    /** The raw body, for a parser from another library. */
    @Test
    void bodyStreamHandsOverTheBytesUnread() {
        App app = new App().post("/raw", req -> {
            try (InputStream body = req.bodyStream()) {
                return WebResponse.text(new String(body.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        WebTest.test(app, client ->
                assertThat(client.post("/raw", "{\"a\":1}").body()).isEqualTo("{\"a\":1}"));
    }
}
