package flashcard.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import flashcard.domain.Card;
import flashcard.domain.Deck;
import flashcard.domain.ReviewState;
import flashcard.domain.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertNotNull(card.id());
        assertEquals(List.of(card), cardRepository.findByDeckId(deck.id()));
    }

    @Test
    void updateAndDelete() {
        Deck deck = deck();
        Card card = cardRepository.insert(Card.create(deck.id(), "apple", "a fruit", now));

        cardRepository.update(card.edit("apple!", "a fruit!"));
        assertEquals("apple!", cardRepository.findById(card.id()).orElseThrow().text());

        cardRepository.deleteById(card.id());
        assertTrue(cardRepository.findById(card.id()).isEmpty());
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

        assertEquals(List.of(due), cardRepository.findDue(today));
        assertEquals(1, cardRepository.countDue(today));
    }

    @Test
    void findsOftenMissedCards() {
        Deck deck = deck();
        Card oftenWrong = cardRepository.insert(Card.create(deck.id(), "hard", "meaning", now));
        Card easy = cardRepository.insert(Card.create(deck.id(), "easy", "meaning", now));

        reviewStateRepository.insert(
                new ReviewState(null, oftenWrong.id(), 1, null, 3, 2, now));
        reviewStateRepository.insert(new ReviewState(null, easy.id(), 6, null, 5, 0, now));

        assertEquals(List.of(oftenWrong), cardRepository.findOftenWrong(3, 40));
        assertEquals(1, cardRepository.countOftenWrong(3, 40));
    }

    @Test
    void findsCardsByTag() {
        Deck deck = deck();
        Card card = cardRepository.insert(Card.create(deck.id(), "apple", "a fruit", now));
        Tag tag = tagRepository.insert(Tag.create("fruit"));
        tagRepository.attach(card.id(), tag.id());
        tagRepository.attach(card.id(), tag.id());   // merge makes duplicate calls safe

        assertEquals(List.of(card), cardRepository.findByTagName("fruit"));
        assertEquals(List.of("fruit"), tagRepository.findTagNamesByCardId(card.id()));
    }

    @Test
    void staleCardsWithoutReviewsFallBackToCreationTime() {
        Deck deck = deck();
        Card stale = cardRepository.insert(Card.create(deck.id(), "old", "meaning",
                now.minusDays(10)));
        cardRepository.insert(Card.create(deck.id(), "new", "meaning", now));

        assertEquals(List.of(stale), cardRepository.findStale(now.minusDays(7)));
        assertEquals(1, cardRepository.countStale(now.minusDays(7)));
    }
}
