package flashcard.service;

import java.util.List;

import org.junit.jupiter.api.Test;

import flashcard.domain.Deck;
import flashcard.repository.CardRepository;
import flashcard.repository.DeckRepository;
import flashcard.repository.RepositoryTestSupport;
import flashcard.repository.TagRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        assertThat(imported).isEqualTo(2);
        var cards = cardService.cardsWithTags(deck.id());
        assertThat(cards).hasSize(2);
        assertThat(cards.get(0).tags()).isEqualTo(List.of("basic", "fruit"));
    }

    @Test
    void aMalformedLineRollsBackTheWholeImport() {
        Deck deck = deckService.createDeck("English");

        assertThatThrownBy(() -> deckService.importCsv(deck.id(), """
                apple,a round fruit
                broken-line
                """))
                .isInstanceOf(CsvFormatException.class);

        // TransactionTemplate rolled everything back on the runtime exception,
        // so even the first line was not stored
        assertThat(cardRepository.findByDeckId(deck.id())).hasSize(0);
    }

    @Test
    void exportedCsvCanBeImportedAgain() {
        Deck source = deckService.createDeck("Source");
        cardService.addCard(source.id(), "a, b", "say \"hi\"", "tag1, tag2");

        String csv = deckService.exportCsv(source.id());

        Deck target = deckService.createDeck("Copy");
        assertThat(deckService.importCsv(target.id(), csv)).isEqualTo(1);
        var copied = cardService.cardsWithTags(target.id()).getFirst();
        assertThat(copied.card().text()).isEqualTo("a, b");
        assertThat(copied.tags()).isEqualTo(List.of("tag1", "tag2"));
    }
}
