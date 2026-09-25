package net.benelog.spidersilk;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.json.Json;
import net.benelog.spidersilk.json.JsonReader;
import net.benelog.spidersilk.json.JsonWriter;
import net.benelog.spidersilk.test.WebTest;

import static org.assertj.core.api.Assertions.assertThat;


/** {@code WebResponse.json(value, writer)} and {@code req.bodyJson(reader)} over HTTP. */
class JsonSeamTest {

    record Deck(long id, String name) {
    }

    static final JsonWriter<Deck> DECK =
            deck -> Json.object().put("id", deck.id()).put("name", deck.name());

    static final JsonReader<String> DECK_NAME = json -> json.asObject().getString("name");

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
                WebResponse.text(req.bodyJson(json -> json.asArray().get(0).asString())));

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
        App app = new App().post("/name", req -> WebResponse.text(req.bodyJson(json -> json.asObject().getString("name"))));

        WebTest.test(app, client -> {
            var array = client.postJson("/name", "[" + "\"secret\",".repeat(100) + "1]");
            assertThat(array.statusCode()).isEqualTo(400);
            assertThat(array.body()).doesNotContain("secret").hasSizeLessThan(100);

            var nested = client.postJson("/name", "{\"name\":{\"password\":\"hunter2\"}}");
            assertThat(nested.statusCode()).isEqualTo(400);
            assertThat(nested.body()).doesNotContain("hunter2").contains("Not a JSON string: an object");
        });
    }

    static final JsonReader<Long> DECK_ID = json -> json.asObject().getLong("id");

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
}
