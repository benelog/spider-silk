package net.benelog.spidersilk.json;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The typed JSON seam: hand-written lambdas that compose. */
class JsonCodecTest {

    record Deck(long id, String name) {
    }

    static final JsonWriter<Deck> DECK_OUT =
            (deck, out) -> out.object().put("id", deck.id()).put("name", deck.name()).end();

    static final JsonReader<Deck> DECK_IN =
            JsonReader.object(object -> new Deck(object.getLong("id"), object.getString("name")));

    static final JsonCodec<Deck> DECK = JsonCodec.of(DECK_OUT, DECK_IN);

    @Test
    void eachHalfIsWrittenAsALambda() {
        assertThat(DECK_OUT.toJson(new Deck(1, "English")))
                .isEqualTo("{\"id\":1,\"name\":\"English\"}");
        assertThat(DECK_IN.fromJson("{\"id\":1,\"name\":\"English\"}"))
                .isEqualTo(new Deck(1, "English"));
    }

    @Test
    void listComposesFromTheElementMapping() {
        JsonWriter<List<Deck>> decks = JsonWriter.list(DECK_OUT);

        assertThat(decks.toJson(List.of(new Deck(1, "a"), new Deck(2, "b"))))
                .isEqualTo("[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"}]");
        assertThat(decks.toJson(List.of())).isEqualTo("[]");
    }

    @Test
    void aCodecRoundTripsThroughText() {
        JsonCodec<List<Deck>> codec = JsonCodec.list(DECK);
        List<Deck> decks = List.of(new Deck(1, "a"), new Deck(2, "b"));

        assertThat(codec.fromJson(codec.toJson(decks))).isEqualTo(decks);
    }

    @Test
    void aReaderRejectsInputItCannotBuildFrom() {
        assertThatThrownBy(() -> DECK_IN.fromJson("{\"id\":1}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DECK_IN.fromJson("{\"id\":\"one\",\"name\":\"a\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonReader.list(DECK_IN).fromJson("{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a JSON array: an object");
    }

    /** A value of another shape is the JsonException that bodyJson answers with 400. */
    @Test
    void anObjectReaderRefusesAValueThatIsNotAnObject() {
        assertThatThrownBy(() -> DECK_IN.fromJson("[1, \"a\"]"))
                .isInstanceOf(JsonException.class)
                .hasMessage("Not a JSON object: an array");
        assertThatThrownBy(() -> DECK_IN.fromJson("\"English\""))
                .isInstanceOf(JsonException.class);
    }

    @Test
    void anObjectReaderMayAnswerNull() {
        JsonReader<@Nullable String> authorName = JsonReader.object(object -> {
            JsonObject author = object.getObjectOrNull("author");
            return author == null ? null : author.getString("name");
        });

        assertThat(authorName.fromJson("{}")).isNull();
        assertThat(authorName.fromJson("{\"author\":{\"name\":\"Al\"}}")).isEqualTo("Al");
    }

    /**
     * An element reader may answer null for a JSON null. The list used to be
     * built with List.copyOf, which threw a NullPointerException, and a body
     * holding a null answered 500.
     */
    @Test
    void aListHoldsTheNullsItsElementReaderAnswered() {
        JsonReader<@Nullable String> name = in -> in.readNull() ? null : in.readString();

        List<@Nullable String> names = JsonReader.list(name).fromJson("[\"a\", null]");

        assertThat(names).containsExactly("a", null);
        assertThatThrownBy(() -> names.add("b")).isInstanceOf(UnsupportedOperationException.class);
    }
}
