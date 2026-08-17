package flashcard.domain;

/** The four conditions a smart deck uses to pick cards. */
public enum SmartCondition {

    OFTEN_WRONG("Often missed cards"),
    STALE("Not reviewed lately"),
    TAGGED("Cards with a specific tag"),
    RECENT("Recently added cards");

    private final String label;

    SmartCondition(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
