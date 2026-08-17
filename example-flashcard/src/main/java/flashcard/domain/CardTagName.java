package flashcard.domain;

/** A (card id, tag name) pair. Used to load all tags of a deck at once. */
public record CardTagName(Long cardId, String tagName) {
}
