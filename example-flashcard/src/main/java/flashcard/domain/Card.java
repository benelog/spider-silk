package flashcard.domain;

import java.time.LocalDateTime;

public record Card(Long id, Long deckId, String text, String meaning,
                   LocalDateTime createdAt) {

    public static Card create(Long deckId, String text, String meaning, LocalDateTime now) {
        return new Card(null, deckId, text, meaning, now);
    }

    public Card edit(String newText, String newMeaning) {
        return new Card(id, deckId, newText, newMeaning, createdAt);
    }
}
