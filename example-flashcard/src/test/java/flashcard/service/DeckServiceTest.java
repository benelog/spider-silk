package flashcard.service;

import java.util.List;

import org.junit.jupiter.api.Test;

import flashcard.domain.Deck;
import flashcard.repository.CardRepository;
import flashcard.repository.DeckRepository;
import flashcard.repository.RepositoryTestSupport;
import flashcard.repository.TagRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeckServiceTest extends RepositoryTestSupport {

    private final DeckRepository deckRepository = new DeckRepository(dataSource);
    private final CardRepository cardRepository = new CardRepository(dataSource);
    private final TagRepository tagRepository = new TagRepository(dataSource);
    private final CardService cardService =
            new CardService(cardRepository, tagRepository, tx);
    private final DeckService deckService =
            new DeckService(deckRepository, cardRepository, cardService, tx);

    @Test
    void csvImportStoresCardsTogetherWithTags() {
        Deck deck = deckService.createDeck("English");

        int imported = deckService.importCsv(deck.id(), """
                apple,a round fruit,fruit;basic
                run,move fast
                """);

        assertEquals(2, imported);
        var cards = cardService.cardsWithTags(deck.id());
        assertEquals(2, cards.size());
        assertEquals(List.of("basic", "fruit"), cards.get(0).tags());
    }

    @Test
    void aMalformedLineRollsBackTheWholeImport() {
        Deck deck = deckService.createDeck("English");

        assertThrows(CsvFormatException.class, () -> deckService.importCsv(deck.id(), """
                apple,a round fruit
                broken-line
                """));

        // TransactionTemplate rolled everything back on the runtime exception,
        // so even the first line was not stored
        assertEquals(0, cardRepository.findByDeckId(deck.id()).size());
    }

    @Test
    void exportedCsvCanBeImportedAgain() {
        Deck source = deckService.createDeck("Source");
        cardService.addCard(source.id(), "a, b", "say \"hi\"", "tag1, tag2");

        String csv = deckService.exportCsv(source.id());

        Deck target = deckService.createDeck("Copy");
        assertEquals(1, deckService.importCsv(target.id(), csv));
        var copied = cardService.cardsWithTags(target.id()).getFirst();
        assertEquals("a, b", copied.card().text());
        assertEquals(List.of("tag1", "tag2"), copied.tags());
    }
}
