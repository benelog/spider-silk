package spidersilk;

import java.util.List;

import org.junit.jupiter.api.Test;

import spidersilk.json.Json;
import spidersilk.json.JsonReader;
import spidersilk.json.JsonWriter;
import spidersilk.test.WebTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

            assertTrue(response.headers().firstValue("Content-Type").orElse("")
                    .startsWith("application/json"));
            assertEquals("[{\"id\":1,\"name\":\"English\"},{\"id\":2,\"name\":\"Spanish\"}]",
                    response.body());
        });
    }

    @Test
    void aReaderBuildsTheRequestValue() {
        App app = new App().post("/decks", req -> WebResponse.text(req.bodyJson(DECK_NAME)));

        WebTest.test(app, client -> assertEquals("English",
                client.postJson("/decks", "{\"name\":\"English\"}").body()));
    }

    /** The reader throws IllegalArgumentException; the handler never sees a half-built value. */
    @Test
    void aBodyTheReaderRejectsIsA400() {
        App app = new App().post("/decks", req -> WebResponse.text(req.bodyJson(DECK_NAME)));

        WebTest.test(app, client -> {
            assertEquals(400, client.postJson("/decks", "{\"title\":\"English\"}").statusCode());
            assertEquals(400, client.postJson("/decks", "{\"name\":42}").statusCode());
            assertEquals(400, client.postJson("/decks", "not-json").statusCode());
        });
    }
}
