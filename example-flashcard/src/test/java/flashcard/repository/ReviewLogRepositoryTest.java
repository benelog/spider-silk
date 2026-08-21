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

import static org.assertj.core.api.Assertions.assertThat;


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

        assertThat(raw.queryForObject("select card_id from review_log", Long.class))
                .isEqualTo(card.id());
        assertThat(raw.queryForObject("select correct from review_log", Boolean.class)).isFalse();
        assertThat(raw.queryForObject("select retry_round from review_log", Boolean.class))
                .isTrue();
        assertThat(raw.queryForObject("select reviewed_at from review_log", LocalDateTime.class))
                .isEqualTo(now);
        assertThat(raw.queryForObject("select study_date from review_log", LocalDate.class))
                .isEqualTo(now.toLocalDate());
    }

    @Test
    void dailyStatsSplitCorrectAndWrongAnswersPerDay() {
        Card card = card();
        LocalDateTime yesterday = now.minusDays(1);

        reviewLogRepository.insert(ReviewLog.of(card.id(), true, false, yesterday));
        reviewLogRepository.insert(ReviewLog.of(card.id(), true, false, now));
        reviewLogRepository.insert(ReviewLog.of(card.id(), false, true, now));

        assertThat(reviewLogRepository.findDailyStats(yesterday.toLocalDate())).isEqualTo(List.of(
                new DailyStat(yesterday.toLocalDate(), 1, 0),
                new DailyStat(now.toLocalDate(), 1, 1)));
        assertThat(reviewLogRepository.findStudyDates())
                .isEqualTo(List.of(now.toLocalDate(), yesterday.toLocalDate()));
        assertThat(reviewLogRepository.countAll()).isEqualTo(3);
        assertThat(reviewLogRepository.countCorrect()).isEqualTo(2);
    }
}
