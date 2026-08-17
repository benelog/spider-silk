package flashcard.web;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import spidersilk.App;
import spidersilk.WebContext;

import flashcard.domain.Deck;
import flashcard.repository.CardRepository;
import flashcard.repository.DeckRepository;
import flashcard.repository.RepositoryTestSupport;
import flashcard.repository.TagRepository;
import flashcard.service.CardService;
import flashcard.service.DeckService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Calls the package-private handler methods directly with mock
 * requests/responses, without a running server.
 */
class DeckControllerTest extends RepositoryTestSupport {

    private final DeckRepository deckRepository = new DeckRepository(dataSource);
    private final CardRepository cardRepository = new CardRepository(dataSource);
    private final TagRepository tagRepository = new TagRepository(dataSource);
    private final CardService cardService =
            new CardService(cardRepository, tagRepository, tx);
    private final DeckService deckService =
            new DeckService(deckRepository, cardRepository, cardService, tx);
    private final DeckController controller = new DeckController(deckService, cardService);

    @Test
    void createDeckRedirectsToTheNewDeck() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setParameter("name", "English");
        MockHttpServletResponse res = new MockHttpServletResponse();

        controller.createDeck(new WebContext(new App(), req, res, Map.of()));

        assertTrue(res.getRedirectedUrl().matches("/decks/\\d+"),
                "unexpected redirect: " + res.getRedirectedUrl());
    }

    @Test
    void renameDeckUpdatesTheNameAndRedirects() {
        Deck deck = deckService.createDeck("Old name");

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setParameter("name", "New name");
        MockHttpServletResponse res = new MockHttpServletResponse();

        // Path variables arrive as an explicit map instead of URL matching
        controller.renameDeck(new WebContext(new App(), req, res,
                Map.of("deckId", String.valueOf(deck.id()))));

        assertEquals("New name", deckService.getDeck(deck.id()).name());
        assertEquals("/decks/" + deck.id(), res.getRedirectedUrl());
    }
}
