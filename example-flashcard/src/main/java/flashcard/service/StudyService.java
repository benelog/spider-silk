package flashcard.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import flashcard.domain.Card;
import flashcard.domain.ReviewLog;
import flashcard.domain.ReviewState;
import flashcard.domain.SmartCondition;
import flashcard.domain.SmartDeck;
import flashcard.repository.CardRepository;
import flashcard.repository.ReviewLogRepository;
import flashcard.repository.ReviewStateRepository;

public class StudyService {

    private final CardRepository cardRepository;
    private final ReviewStateRepository reviewStateRepository;
    private final ReviewLogRepository reviewLogRepository;
    private final DeckService deckService;
    private final SmartDeckService smartDeckService;
    private final Transactions tx;

    public StudyService(CardRepository cardRepository,
                        ReviewStateRepository reviewStateRepository,
                        ReviewLogRepository reviewLogRepository,
                        DeckService deckService, SmartDeckService smartDeckService,
                        Transactions tx) {
        this.cardRepository = cardRepository;
        this.reviewStateRepository = reviewStateRepository;
        this.reviewLogRepository = reviewLogRepository;
        this.deckService = deckService;
        this.smartDeckService = smartDeckService;
        this.tx = tx;
    }

    /** The read transaction keeps the card list and the deck name on one snapshot. */
    public StudySession startDeckSession(Long deckId, StudyDirection direction) {
        return tx.read(() -> {
            List<Card> cards = cardRepository.findByDeckId(deckId);
            return new StudySession(deckService.getDeck(deckId).name(), direction, idsOf(cards));
        });
    }

    public StudySession startTodaySession(StudyDirection direction) {
        List<Card> cards = cardRepository.findDue(LocalDate.now(ZoneId.systemDefault()));
        return new StudySession("Today's review", direction, idsOf(cards));
    }

    public StudySession startSmartSession(SmartDeck smartDeck, StudyDirection direction) {
        List<Card> cards = smartDeckService.cardsFor(
                smartDeck.conditionType(), smartDeck.param());
        return new StudySession(smartDeck.name(), direction, idsOf(cards));
    }

    public StudySession startPresetSession(SmartCondition condition, StudyDirection direction) {
        List<Card> cards = smartDeckService.cardsFor(condition, null);
        return new StudySession(condition.getLabel(), direction, idsOf(cards));
    }

    public long todayCount() {
        return cardRepository.countDue(LocalDate.now(ZoneId.systemDefault()));
    }

    public Card currentCard(StudySession session) {
        return cardRepository.findById(session.currentCardId())
                .orElseThrow(() -> new IllegalStateException(
                        "Card in the session was deleted: " + session.currentCardId()));
    }

    /**
     * Records a single grading.
     * Only first-round gradings affect the review schedule;
     * retry rounds are logged only.
     */
    public void answer(StudySession session, boolean correct) {
        tx.writeVoid(() -> {
            Long cardId = session.currentCardId();
            LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());

            if (!session.isRetryRound()) {
                ReviewState state = reviewStateRepository.findByCardId(cardId)
                        .orElseGet(() -> ReviewState.initial(cardId));
                ReviewState reviewed = state.reviewed(correct, now);
                if (reviewed.id() == null) {
                    reviewStateRepository.insert(reviewed);
                } else {
                    reviewStateRepository.update(reviewed);
                }
            }
            reviewLogRepository.insert(
                    ReviewLog.of(cardId, correct, session.isRetryRound(), now));
        });
        session.answer(correct);
    }

    private List<Long> idsOf(List<Card> cards) {
        return cards.stream().map(Card::id).toList();
    }
}
