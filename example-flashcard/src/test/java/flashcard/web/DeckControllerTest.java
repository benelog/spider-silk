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

import static org.assertj.core.api.Assertions.assertThat;


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

        assertThat(response.status()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.header("Location"))
                .as("unexpected redirect: " + response.header("Location"))
                .matches("/decks/\\d+");
    }

    @Test
    void renameDeckUpdatesTheNameAndRedirects() {
        Deck deck = deckService.createDeck("Old name");

        WebResponse response = controller.renameDeck(TestRequest.post("/decks/" + deck.id())
                .pathParam("deckId", String.valueOf(deck.id()))
                .formParam("name", "New name")
                .build());

        assertThat(deckService.getDeck(deck.id()).name()).isEqualTo("New name");
        assertThat(response.header("Location")).isEqualTo("/decks/" + deck.id());
    }

    @Test
    void importCsvAddsTheCardsInTheUploadedFile() {
        Deck deck = deckService.createDeck("Spanish");

        WebResponse response = controller.importCsv(
                TestRequest.post("/decks/" + deck.id() + "/import")
                        .pathParam("deckId", String.valueOf(deck.id()))
                        .file("file", "cards.csv", "hola,hello,greeting\nadios,goodbye\n")
                        .build());

        assertThat(response.header("Location")).isEqualTo("/decks/" + deck.id());
        assertThat(cardService.cardsWithTags(deck.id())).hasSize(2);
    }
}
