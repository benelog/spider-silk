package flashcard.domain;

/** A deck that stores a "collect cards like this" condition instead of cards. */
public record SmartDeck(Long id, String name, SmartCondition conditionType, String param) {

    public static SmartDeck create(String name, SmartCondition conditionType, String param) {
        return new SmartDeck(null, name, conditionType, param);
    }
}
