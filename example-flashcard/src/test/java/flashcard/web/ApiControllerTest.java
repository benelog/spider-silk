package flashcard.web;

import org.junit.jupiter.api.Test;

import spidersilk.HttpException;
import spidersilk.HttpStatus;
import spidersilk.WebResponse;
import spidersilk.json.Json;
import spidersilk.test.TestRequest;

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

    /** A JSON response carries its document as text, which is what to assert on. */
    private static String body(WebResponse response) {
        return ((WebResponse.Text) response.body()).content();
    }
}
