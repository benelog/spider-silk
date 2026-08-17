package flashcard.service;

/** Study direction: which side to look at and which side to recall. */
public enum StudyDirection {

    TEXT_TO_MEANING("See text, recall meaning"),
    MEANING_TO_TEXT("See meaning, recall text");

    private final String label;

    StudyDirection(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
