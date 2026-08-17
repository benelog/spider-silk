package flashcard.domain;

import java.time.LocalDateTime;

public record Deck(Long id, String name, LocalDateTime createdAt) {

    public static Deck create(String name, LocalDateTime now) {
        return new Deck(null, name, now);
    }

    public Deck rename(String newName) {
        return new Deck(id, newName, createdAt);
    }
}
