package flashcard.domain;

public record Tag(Long id, String name) {

    public static Tag create(String name) {
        return new Tag(null, name);
    }
}
