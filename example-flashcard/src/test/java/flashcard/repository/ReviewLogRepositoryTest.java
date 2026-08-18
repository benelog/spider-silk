package flashcard.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import flashcard.domain.Card;
import flashcard.domain.DailyStat;
import flashcard.domain.Deck;
import flashcard.domain.ReviewLog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewLogRepositoryTest extends RepositoryTestSupport {

    private final DeckRepository deckRepository = new DeckRepository(dataSource);
    private final CardRepository cardRepository = new CardRepository(dataSource);
    private final ReviewLogRepository reviewLogRepository = new ReviewLogRepository(dataSource);

    /** Reads the raw row: retry_round is written but no query method returns it. */
    private final JdbcTemplate raw = new JdbcTemplate(dataSource);

    private final LocalDateTime now = LocalDateTime.of(2026, 8, 17, 10, 0);

    private Card card() {
        Deck deck = deckRepository.insert(Deck.create("English words", now));
        return cardRepository.insert(Card.create(deck.id(), "apple", "a fruit", now));
    }

    /**
     * The insert hands the whole record to SimpleJdbcInsert, so every component
     * has to reach its own column: the two booleans hold opposite values here
     * to catch a swap, and reviewed_at/study_date have to match reviewedAt/studyDate.
     */
    @Test
    void insertWritesEveryComponentToItsOwnColumn() {
        Card card = card();

        reviewLogRepository.insert(ReviewLog.of(card.id(), false, true, now));

        assertEquals(card.id(), raw.queryForObject("select card_id from review_log", Long.class));
        assertFalse(raw.queryForObject("select correct from review_log", Boolean.class));
        assertTrue(raw.queryForObject("select retry_round from review_log", Boolean.class));
        assertEquals(now,
                raw.queryForObject("select reviewed_at from review_log", LocalDateTime.class));
        assertEquals(now.toLocalDate(),
                raw.queryForObject("select study_date from review_log", LocalDate.class));
    }

    @Test
    void dailyStatsSplitCorrectAndWrongAnswersPerDay() {
        Card card = card();
        LocalDateTime yesterday = now.minusDays(1);

        reviewLogRepository.insert(ReviewLog.of(card.id(), true, false, yesterday));
        reviewLogRepository.insert(ReviewLog.of(card.id(), true, false, now));
        reviewLogRepository.insert(ReviewLog.of(card.id(), false, true, now));

        assertEquals(
                List.of(new DailyStat(yesterday.toLocalDate(), 1, 0),
                        new DailyStat(now.toLocalDate(), 1, 1)),
                reviewLogRepository.findDailyStats(yesterday.toLocalDate()));
        assertEquals(List.of(now.toLocalDate(), yesterday.toLocalDate()),
                reviewLogRepository.findStudyDates());
        assertEquals(3, reviewLogRepository.countAll());
        assertEquals(2, reviewLogRepository.countCorrect());
    }
}
