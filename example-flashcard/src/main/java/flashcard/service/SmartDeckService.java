package flashcard.service;

import java.time.LocalDateTime;
import java.util.List;

import flashcard.domain.Card;
import flashcard.domain.SmartCondition;
import flashcard.domain.SmartDeck;
import flashcard.repository.CardRepository;
import flashcard.repository.SmartDeckRepository;

public class SmartDeckService {

    // Thresholds shared by the "recommended review" tiles and smart decks
    public static final int OFTEN_WRONG_MIN_ATTEMPTS = 3;
    public static final int OFTEN_WRONG_MIN_PERCENT = 40;
    public static final int STALE_DAYS = 7;
    public static final int RECENT_DAYS_DEFAULT = 7;

    private final SmartDeckRepository smartDeckRepository;
    private final CardRepository cardRepository;
    private final Transactions tx;

    public SmartDeckService(SmartDeckRepository smartDeckRepository,
                            CardRepository cardRepository, Transactions tx) {
        this.smartDeckRepository = smartDeckRepository;
        this.cardRepository = cardRepository;
        this.tx = tx;
    }

    /** A smart deck stores no cards; it picks them by condition when studying starts. */
    public List<Card> cardsFor(SmartCondition condition, String param) {
        return switch (condition) {
            case OFTEN_WRONG -> cardRepository.findOftenWrong(
                    OFTEN_WRONG_MIN_ATTEMPTS, OFTEN_WRONG_MIN_PERCENT);
            case STALE -> cardRepository.findStale(
                    LocalDateTime.now().minusDays(STALE_DAYS));
            case TAGGED -> cardRepository.findByTagName(param);
            case RECENT -> cardRepository.findRecent(
                    LocalDateTime.now().minusDays(parseDays(param)));
        };
    }

    private int parseDays(String param) {
        try {
            return Integer.parseInt(param);
        } catch (NumberFormatException e) {
            return RECENT_DAYS_DEFAULT;
        }
    }

    public long oftenWrongCount() {
        return cardRepository.countOftenWrong(
                OFTEN_WRONG_MIN_ATTEMPTS, OFTEN_WRONG_MIN_PERCENT);
    }

    public long staleCount() {
        return cardRepository.countStale(LocalDateTime.now().minusDays(STALE_DAYS));
    }

    public List<SmartDeck> smartDecks() {
        return smartDeckRepository.findAllOrdered();
    }

    public SmartDeck getSmartDeck(Long id) {
        return smartDeckRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Smart deck not found: " + id));
    }

    public SmartDeck create(String name, SmartCondition condition, String param) {
        return tx.write(() -> smartDeckRepository.insert(
                SmartDeck.create(name, condition, param)));
    }

    public void delete(Long id) {
        tx.writeVoid(() -> smartDeckRepository.deleteById(id));
    }
}
