package flashcard.web;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import spidersilk.HttpException;
import spidersilk.WebRequest;
import spidersilk.WebResponse;
import spidersilk.json.Json;

import flashcard.repository.CardRepository;
import flashcard.repository.DeckRepository;
import flashcard.repository.RepositoryTestSupport;
import flashcard.repository.TagRepository;
import flashcard.service.CardService;
import flashcard.service.DeckService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Calls the package-private handler methods directly and asserts on the
 * response they return, without a running server.
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

        WebResponse response = controller.listDecks(request(new MockHttpServletRequest()));

        Json.JsonArray decks = Json.parse(body(response)).asArray();
        assertEquals(1, decks.size());
        assertEquals("English", decks.get(0).asObject().getString("name"));
        assertEquals(0, decks.get(0).asObject().getLong("cardCount"));
    }

    @Test
    void createDeckRespondsWith201AndLocation() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setContent("{\"name\": \"Spanish\"}".getBytes(StandardCharsets.UTF_8));

        WebResponse response = controller.createDeck(request(req));

        assertEquals(201, response.status());
        long id = Json.parse(body(response)).asObject().getLong("id");
        assertEquals("/api/decks/" + id, response.header("Location"));
        assertEquals("Spanish", deckService.getDeck(id).name());
    }

    @Test
    void createDeckRejectsMalformedBodyWith400() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setContent("not-json".getBytes(StandardCharsets.UTF_8));

        HttpException e = assertThrows(HttpException.class,
                () -> controller.createDeck(request(req)));
        assertEquals(400, e.status());
    }

    /** The reader throws on the missing key; bodyJson(reader) turns that into a 400. */
    @Test
    void createDeckRejectsABodyWithoutANameWith400() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setContent("{\"title\": \"Spanish\"}".getBytes(StandardCharsets.UTF_8));

        HttpException e = assertThrows(HttpException.class,
                () -> controller.createDeck(request(req)));
        assertEquals(400, e.status());
    }

    private static WebRequest request(MockHttpServletRequest req) {
        return new WebRequest(req, Map.of());
    }

    /** A JSON response carries its document as text, which is what to assert on. */
    private static String body(WebResponse response) {
        return ((WebResponse.Text) response.body()).content();
    }
}
