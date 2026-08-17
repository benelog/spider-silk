package flashcard.service;

/**
 * Thrown on a malformed CSV line.
 * A runtime exception, so the surrounding transaction rolls back too.
 */
public class CsvFormatException extends RuntimeException {

    public CsvFormatException(int lineNumber, String line) {
        super("Line %d has an invalid format: %s".formatted(lineNumber, line));
    }
}
