package flashcard.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import flashcard.domain.Card;
import flashcard.domain.Deck;
import flashcard.domain.DeckSummary;
import flashcard.repository.CardRepository;
import flashcard.repository.DeckRepository;
import flashcard.service.CsvCodec.CsvCard;

public class DeckService {

    private final DeckRepository deckRepository;
    private final CardRepository cardRepository;
    private final CardService cardService;
    private final Transactions tx;

    public DeckService(DeckRepository deckRepository, CardRepository cardRepository,
                       CardService cardService, Transactions tx) {
        this.deckRepository = deckRepository;
        this.cardRepository = cardRepository;
        this.cardService = cardService;
        this.tx = tx;
    }

    public Deck createDeck(String name) {
        return tx.write(() -> deckRepository.insert(Deck.create(name, LocalDateTime.now())));
    }

    public void renameDeck(Long deckId, String newName) {
        tx.writeVoid(() -> deckRepository.update(getDeck(deckId).rename(newName)));
    }

    public void deleteDeck(Long deckId) {
        tx.writeVoid(() -> deckRepository.deleteById(deckId));
    }

    public Deck getDeck(Long deckId) {
        return deckRepository.findById(deckId)
                .orElseThrow(() -> new IllegalArgumentException("Deck not found: " + deckId));
    }

    public List<DeckSummary> deckSummaries() {
        return deckRepository.findAllSummaries(LocalDate.now());
    }

    /**
     * Imports the whole CSV in a single transaction.
     * If any line is malformed, everything is rolled back.
     */
    public int importCsv(Long deckId, String csvContent) {
        return tx.write(() -> {
            List<CsvCard> parsed = CsvCodec.parse(csvContent);

            LocalDateTime now = LocalDateTime.now();
            for (CsvCard csvCard : parsed) {
                Card card = cardRepository.insert(
                        Card.create(deckId, csvCard.text(), csvCard.meaning(), now));
                cardService.attachTags(card.id(), String.join(",", csvCard.tags()));
            }
            return parsed.size();
        });
    }

    public String exportCsv(Long deckId) {
        // cardsWithTags() already runs its reads in one snapshot
        return CsvCodec.format(cardService.cardsWithTags(deckId));
    }
}
