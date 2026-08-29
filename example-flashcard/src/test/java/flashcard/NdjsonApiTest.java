package flashcard;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.json.Json;
import net.benelog.spidersilk.test.TestClient;
import net.benelog.spidersilk.test.WebTest;

import flashcard.repository.RepositoryTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The NDJSON export and import over real HTTP, which is where the parts the
 * handler tests cannot reach are visible: the status a rejected line actually
 * answers with, and the headers a streamed body goes out under.
 */
class NdjsonApiTest extends RepositoryTestSupport {

    @Test
    void aDeckExportsAsOneCardPerLine() {
        WebTest.test(FlashcardApp.createApp(dataSource), client -> {
            long deckId = createDeck(client, "French");
            client.post("/api/decks/%d/cards.ndjson".formatted(deckId), """
                    {"text":"pomme","meaning":"apple","tags":"fruit"}
                    {"text":"poire","meaning":"pear"}
                    """);

            HttpResponse<String> response =
                    client.get("/api/decks/%d/cards.ndjson".formatted(deckId));

            assertThat(response.headers().firstValue("Content-Type").orElse(""))
                    .startsWith("application/x-ndjson");
            String[] lines = response.body().split("\n");
            assertThat(lines).hasSize(2);
            assertThat(Json.parse(lines[0]).asObject().getString("text")).isEqualTo("pomme");
            assertThat(Json.parse(lines[1]).asObject().getString("meaning")).isEqualTo("pear");
        });
    }

    /**
     * The import consumes the lazy stream inside a write transaction, so this is
     * the assertion the handler test cannot make: the HttpException the reader
     * threw still leaves the transaction as itself, and the client is told which
     * line rather than being handed a 500.
     */
    @Test
    void aRejectedLineIsA400OverHttpAndImportsNothing() {
        WebTest.test(FlashcardApp.createApp(dataSource), client -> {
            long deckId = createDeck(client, "French");

            HttpResponse<String> response =
                    client.post("/api/decks/%d/cards.ndjson".formatted(deckId), """
                            {"text":"pomme"}
                            {"meaning":"pear"}
                            """);

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(client.get("/api/decks/%d/cards.ndjson".formatted(deckId)).body())
                    .isEmpty();
        });
    }

    /** A missing deck is answered before the stream starts, while a status can still change. */
    @Test
    void anExportOfAMissingDeckIsA404() {
        WebTest.test(FlashcardApp.createApp(dataSource), client ->
                assertThat(client.get("/api/decks/9999/cards.ndjson").statusCode())
                        .isEqualTo(404));
    }

    private static long createDeck(TestClient client, String name) {
        return Json.parse(client.postJson("/api/decks", "{\"name\":\"%s\"}".formatted(name)).body())
                .asObject().getLong("id");
    }
}
