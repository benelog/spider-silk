package flashcard.web;

import org.junit.jupiter.api.Test;

import spidersilk.HttpStatus;
import spidersilk.WebResponse;
import spidersilk.test.TestRequest;

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
 * Calls the handler methods directly and asserts on the response they return,
 * without a running server.
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
        WebResponse response = controller.createDeck(TestRequest.post("/decks")
                .formParam("name", "English")
                .build());

        assertEquals(HttpStatus.FOUND, response.status());
        assertTrue(response.header("Location").matches("/decks/\\d+"),
                "unexpected redirect: " + response.header("Location"));
    }

    @Test
    void renameDeckUpdatesTheNameAndRedirects() {
        Deck deck = deckService.createDeck("Old name");

        WebResponse response = controller.renameDeck(TestRequest.post("/decks/" + deck.id())
                .pathParam("deckId", String.valueOf(deck.id()))
                .formParam("name", "New name")
                .build());

        assertEquals("New name", deckService.getDeck(deck.id()).name());
        assertEquals("/decks/" + deck.id(), response.header("Location"));
    }

    @Test
    void importCsvAddsTheCardsInTheUploadedFile() {
        Deck deck = deckService.createDeck("Spanish");

        WebResponse response = controller.importCsv(
                TestRequest.post("/decks/" + deck.id() + "/import")
                        .pathParam("deckId", String.valueOf(deck.id()))
                        .file("file", "cards.csv", "hola,hello,greeting\nadios,goodbye\n")
                        .build());

        assertEquals("/decks/" + deck.id(), response.header("Location"));
        assertEquals(2, cardService.cardsWithTags(deck.id()).size());
    }
}
