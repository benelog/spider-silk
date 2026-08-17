package flashcard.domain;

/** Deck summary for the home screen: card count and cards due today. */
public record DeckSummary(Long id, String name, long cardCount, long dueCount) {
}
