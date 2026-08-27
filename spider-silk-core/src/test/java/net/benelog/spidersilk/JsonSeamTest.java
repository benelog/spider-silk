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
            deck -> Json.obj().put("id", deck.id()).put("name", deck.name());

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
}
