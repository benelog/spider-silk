package flashcard.service;

/**
 * Thrown when a lookup by id finds nothing.
 * A type of its own, so FlashcardContext maps exactly this to 404 and leaves
 * an IllegalArgumentException from a bug to answer 500 as the bug it is.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
