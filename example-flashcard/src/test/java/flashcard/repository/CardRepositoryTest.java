package flashcard.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import flashcard.domain.Card;
import flashcard.domain.Deck;
import flashcard.domain.ReviewState;
import flashcard.domain.Tag;

import static org.assertj.core.api.Assertions.assertThat;


class CardRepositoryTest extends RepositoryTestSupport {

    private final DeckRepository deckRepository = new DeckRepository(dataSource);
    private final CardRepository cardRepository = new CardRepository(dataSource);
    private final TagRepository tagRepository = new TagRepository(dataSource);
    private final ReviewStateRepository reviewStateRepository = new ReviewStateRepository(dataSource);

    private final LocalDateTime now = LocalDateTime.of(2026, 8, 17, 10, 0);

    private Deck deck() {
        return deckRepository.insert(Deck.create("English words", now));
    }

    @Test
    void insertReturnsTheGeneratedId() {
        Deck deck = deck();
        Card card = cardRepository.insert(Card.create(deck.id(), "apple", "a fruit", now));

        assertThat(card.id()).isNotNull();
        assertThat(cardRepository.findByDeckId(deck.id())).isEqualTo(List.of(card));
    }

    @Test
    void updateAndDelete() {
        Deck deck = deck();
        Card card = cardRepository.insert(Card.create(deck.id(), "apple", "a fruit", now));

        cardRepository.update(card.edit("apple!", "a fruit!"));
        assertThat(cardRepository.findById(card.id()).orElseThrow().text()).isEqualTo("apple!");

        cardRepository.deleteById(card.id());
        assertThat(cardRepository.findById(card.id())).isEmpty();
    }

    @Test
    void findsCardsWhoseDueDateHasPassed() {
        Deck deck = deck();
        Card due = cardRepository.insert(Card.create(deck.id(), "due", "meaning", now));
        Card notDue = cardRepository.insert(Card.create(deck.id(), "notDue", "meaning", now));

        LocalDate today = now.toLocalDate();
        reviewStateRepository.insert(new ReviewState(null, due.id(), 1, today, 1, 0, now));
        reviewStateRepository.insert(
                new ReviewState(null, notDue.id(), 6, today.plusDays(5), 1, 0, now));

        assertThat(cardRepository.findDue(today)).isEqualTo(List.of(due));
        assertThat(cardRepository.countDue(today)).isEqualTo(1);
    }

    @Test
    void findsOftenMissedCards() {
        Deck deck = deck();
        Card oftenWrong = cardRepository.insert(Card.create(deck.id(), "hard", "meaning", now));
        Card easy = cardRepository.insert(Card.create(deck.id(), "easy", "meaning", now));

        reviewStateRepository.insert(
                new ReviewState(null, oftenWrong.id(), 1, null, 3, 2, now));
        reviewStateRepository.insert(new ReviewState(null, easy.id(), 6, null, 5, 0, now));

        assertThat(cardRepository.findOftenWrong(3, 40)).isEqualTo(List.of(oftenWrong));
        assertThat(cardRepository.countOftenWrong(3, 40)).isEqualTo(1);
    }

    @Test
    void findsCardsByTag() {
        Deck deck = deck();
        Card card = cardRepository.insert(Card.create(deck.id(), "apple", "a fruit", now));
        Tag tag = tagRepository.insert(Tag.create("fruit"));
        tagRepository.attach(card.id(), tag.id());
        tagRepository.attach(card.id(), tag.id());   // merge makes duplicate calls safe

        assertThat(cardRepository.findByTagName("fruit")).isEqualTo(List.of(card));
        assertThat(tagRepository.findTagNamesByCardId(card.id())).isEqualTo(List.of("fruit"));
    }

    @Test
    void staleCardsWithoutReviewsFallBackToCreationTime() {
        Deck deck = deck();
        Card stale = cardRepository.insert(Card.create(deck.id(), "old", "meaning",
                now.minusDays(10)));
        cardRepository.insert(Card.create(deck.id(), "new", "meaning", now));

        assertThat(cardRepository.findStale(now.minusDays(7))).isEqualTo(List.of(stale));
        assertThat(cardRepository.countStale(now.minusDays(7))).isEqualTo(1);
    }
}
