package flashcard.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import flashcard.domain.Card;
import flashcard.domain.Deck;
import flashcard.domain.ReviewState;

import static org.assertj.core.api.Assertions.assertThat;


class ReviewStateRepositoryTest extends RepositoryTestSupport {

    private final DeckRepository deckRepository = new DeckRepository(dataSource);
    private final CardRepository cardRepository = new CardRepository(dataSource);
    private final ReviewStateRepository reviewStateRepository = new ReviewStateRepository(dataSource);

    /** Reads the raw row: the insert has no query method returning every column at once. */
    private final JdbcTemplate raw = new JdbcTemplate(dataSource);

    private final LocalDateTime now = LocalDateTime.of(2026, 8, 17, 10, 0);
    private final LocalDate today = now.toLocalDate();

    private Card card(String text) {
        Deck deck = deckRepository.insert(Deck.create("English words " + text, now));
        return cardRepository.insert(Card.create(deck.id(), text, "meaning", now));
    }

    /**
     * The insert hands the whole record to SimpleJdbcInsert, so every component
     * has to reach its own column: the two counts hold distinct values to catch a swap.
     */
    @Test
    void insertWritesEveryComponentToItsOwnColumn() {
        Card card = card("apple");

        reviewStateRepository.insert(new ReviewState(null, card.id(), 1, today, 3, 1, now));

        assertThat(raw.queryForObject("select card_id from review_state", Long.class))
                .isEqualTo(card.id());
        assertThat(raw.queryForObject("select interval_days from review_state", Integer.class))
                .isEqualTo(1);
        assertThat(raw.queryForObject("select due_date from review_state", LocalDate.class))
                .isEqualTo(today);
        assertThat(raw.queryForObject("select correct_count from review_state", Integer.class))
                .isEqualTo(3);
        assertThat(raw.queryForObject("select wrong_count from review_state", Integer.class))
                .isEqualTo(1);
        assertThat(raw.queryForObject("select last_reviewed_at from review_state", LocalDateTime.class))
                .isEqualTo(now);
    }

    /**
     * The update takes its parameters off the record too, id included,
     * so it has to land every column on the one row that id names and leave the other alone.
     */
    @Test
    void updateRewritesTheRowItsIdNames() {
        Card updated = card("updated");
        Card untouched = card("untouched");
        reviewStateRepository.insert(new ReviewState(null, updated.id(), 1, today, 1, 0, now));
        reviewStateRepository.insert(new ReviewState(null, untouched.id(), 1, today, 1, 0, now));

        ReviewState state = reviewStateRepository.findByCardId(updated.id()).orElseThrow();
        ReviewState other = reviewStateRepository.findByCardId(untouched.id()).orElseThrow();
        LocalDateTime later = now.plusDays(1);
        reviewStateRepository.update(
                new ReviewState(state.id(), updated.id(), 6, today.plusDays(6), 3, 2, later));

        assertThat(reviewStateRepository.findByCardId(updated.id()).orElseThrow())
                .isEqualTo(new ReviewState(state.id(), updated.id(), 6, today.plusDays(6), 3, 2, later));
        assertThat(reviewStateRepository.findByCardId(untouched.id()).orElseThrow())
                .isEqualTo(other);
    }
}
