package flashcard.web;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.HttpException;
import net.benelog.spidersilk.HttpStatus;
import net.benelog.spidersilk.WebResponse;
import net.benelog.spidersilk.json.Json;
import net.benelog.spidersilk.test.TestRequest;

import flashcard.repository.CardRepository;
import flashcard.repository.DeckRepository;
import flashcard.repository.RepositoryTestSupport;
import flashcard.repository.TagRepository;
import flashcard.service.CardService;
import flashcard.service.DeckService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Calls the handler methods directly and asserts on the response they return,
 * without a running server.
 */
class ApiControllerTest extends RepositoryTestSupport {

    private final DeckRepository deckRepository = new DeckRepository(dataSource);
    private final CardRepository cardRepository = new CardRepository(dataSource);
    private final TagRepository tagRepository = new TagRepository(dataSource);
    private final CardService cardService =
            new CardService(cardRepository, tagRepository, tx);
    private final DeckService deckService =
            new DeckService(deckRepository, cardRepository, cardService, tx);
    private final ApiController controller = new ApiController(deckService, cardService);

    @Test
    void listDecksRespondsWithJsonArray() {
        deckService.createDeck("English");

        WebResponse response = controller.listDecks(TestRequest.get("/api/decks").build());

        Json.JsonArray decks = Json.parse(body(response)).asArray();
        assertThat(decks).hasSize(1);
        assertThat(decks.get(0).asObject().getString("name")).isEqualTo("English");
        assertThat(decks.get(0).asObject().getLong("cardCount")).isEqualTo(0);
    }

    @Test
    void createDeckRespondsWith201AndLocation() {
        WebResponse response = controller.createDeck(TestRequest.post("/api/decks")
                .jsonBody("{\"name\": \"Spanish\"}")
                .build());

        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
        long id = Json.parse(body(response)).asObject().getLong("id");
        assertThat(response.header("Location")).isEqualTo("/api/decks/" + id);
        assertThat(deckService.getDeck(id).name()).isEqualTo("Spanish");
    }

    @Test
    void createDeckRejectsMalformedBodyWith400() {
        assertThatExceptionOfType(HttpException.class)
                .isThrownBy(() -> controller.createDeck(TestRequest.post("/api/decks")
                        .jsonBody("not-json")
                        .build()))
                .satisfies(e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    /** The reader throws on the missing key; bodyJson(reader) turns that into a 400. */
    @Test
    void createDeckRejectsABodyWithoutANameWith400() {
        assertThatExceptionOfType(HttpException.class)
                .isThrownBy(() -> controller.createDeck(TestRequest.post("/api/decks")
                        .jsonBody("{\"title\": \"Spanish\"}")
                        .build()))
                .satisfies(e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void exportCardsWritesOneCardPerLine() throws Exception {
        long deckId = deckService.createDeck("French").id();
        cardService.addCard(deckId, "pomme", "apple", "fruit");
        cardService.addCard(deckId, "poire", "pear", "");

        WebResponse response = controller.exportCards(TestRequest.get("/api/decks/1/cards.ndjson")
                .pathParam("deckId", String.valueOf(deckId))
                .build());

        assertThat(response.header("Content-Type")).isEqualTo("application/x-ndjson");
        String[] lines = streamedBody(response).split("\n");
        assertThat(lines).hasSize(2);
        assertThat(Json.parse(lines[0]).asObject().getString("text")).isEqualTo("pomme");
        assertThat(Json.parse(lines[1]).asObject().getString("text")).isEqualTo("poire");
    }

    @Test
    void importCardsReadsOneCardPerLineAndSkipsBlankOnes() {
        long deckId = deckService.createDeck("French").id();

        WebResponse response = controller.importCards(
                TestRequest.post("/api/decks/1/cards.ndjson")
                        .pathParam("deckId", String.valueOf(deckId))
                        .body("""
                                {"text":"pomme","meaning":"apple","tags":"fruit"}

                                {"text":"poire"}
                                """)
                        .build());

        assertThat(Json.parse(body(response)).asObject().getLong("imported")).isEqualTo(2);
        assertThat(cardService.cardsWithTags(deckId)).hasSize(2);
    }

    /** The whole import is one transaction, so a bad line leaves the deck as it was. */
    @Test
    void aRejectedLineIsA400AndRollsTheImportBack() {
        long deckId = deckService.createDeck("French").id();

        assertThatExceptionOfType(HttpException.class)
                .isThrownBy(() -> controller.importCards(
                        TestRequest.post("/api/decks/1/cards.ndjson")
                                .pathParam("deckId", String.valueOf(deckId))
                                .body("""
                                        {"text":"pomme"}
                                        {"meaning":"pear"}
                                        """)
                                .build()))
                .satisfies(e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("Line 2");
                });

        assertThat(cardService.cardsWithTags(deckId)).isEmpty();
    }

    /** A JSON response carries its document as text, which is what to assert on. */
    private static String body(WebResponse response) {
        return ((WebResponse.Text) response.body()).content();
    }

    /** A streamed body is produced by running its writer, which is what the servlet does. */
    private static String streamedBody(WebResponse response) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ((WebResponse.Stream) response.body()).writer().write(out);
        return out.toString(StandardCharsets.UTF_8);
    }
}
