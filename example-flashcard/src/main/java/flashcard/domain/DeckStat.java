package flashcard.domain;

/** Per-deck performance. */
public record DeckStat(Long deckId, String deckName, long totalCount, long correctCount) {

    public int accuracyPercent() {
        if (totalCount == 0) {
            return 0;
        }
        return (int) Math.round(correctCount * 100.0 / totalCount);
    }
}
