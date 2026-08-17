package flashcard.web;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import steelspider.App;
import steelspider.WebContext;
import steelspider.HttpException;
import steelspider.json.Json;

import flashcard.repository.CardRepository;
import flashcard.repository.DeckRepository;
import flashcard.repository.RepositoryTestSupport;
import flashcard.repository.TagRepository;
import flashcard.service.CardService;
import flashcard.service.DeckService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Calls the package-private handler methods directly with mock
 * requests/responses, without a running server.
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

        MockHttpServletResponse res = new MockHttpServletResponse();
        controller.listDecks(new WebContext(new App(), new MockHttpServletRequest(), res, Map.of()));

        Json.JsonArray decks = Json.parse(body(res)).asArray();
        assertEquals(1, decks.size());
        assertEquals("English", decks.get(0).asObject().getString("name"));
        assertEquals(0, decks.get(0).asObject().getLong("cardCount"));
    }

    @Test
    void createDeckRespondsWith201AndLocation() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setContent("{\"name\": \"Spanish\"}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse res = new MockHttpServletResponse();

        controller.createDeck(new WebContext(new App(), req, res, Map.of()));

        assertEquals(201, res.getStatus());
        long id = Json.parse(body(res)).asObject().getLong("id");
        assertEquals("/api/decks/" + id, res.getHeader("Location"));
        assertEquals("Spanish", deckService.getDeck(id).name());
    }

    @Test
    void createDeckRejectsMalformedBodyWith400() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setContent("not-json".getBytes(StandardCharsets.UTF_8));

        HttpException e = assertThrows(HttpException.class, () -> controller.createDeck(
                new WebContext(new App(), req, new MockHttpServletResponse(), Map.of())));
        assertEquals(400, e.status());
    }

    /** json() sets no charset, so decode the raw bytes as UTF-8 explicitly. */
    private static String body(MockHttpServletResponse res) {
        return new String(res.getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
