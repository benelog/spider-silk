package flashcard.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import flashcard.domain.Card;
import flashcard.domain.Deck;
import flashcard.domain.ReviewState;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

        assertEquals(card.id(), raw.queryForObject("select card_id from review_state", Long.class));
        assertEquals(1, raw.queryForObject("select interval_days from review_state", Integer.class));
        assertEquals(today, raw.queryForObject("select due_date from review_state", LocalDate.class));
        assertEquals(3, raw.queryForObject("select correct_count from review_state", Integer.class));
        assertEquals(1, raw.queryForObject("select wrong_count from review_state", Integer.class));
        assertEquals(now,
                raw.queryForObject("select last_reviewed_at from review_state", LocalDateTime.class));
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

        assertEquals(new ReviewState(state.id(), updated.id(), 6, today.plusDays(6), 3, 2, later),
                reviewStateRepository.findByCardId(updated.id()).orElseThrow());
        assertEquals(other, reviewStateRepository.findByCardId(untouched.id()).orElseThrow());
    }
}
