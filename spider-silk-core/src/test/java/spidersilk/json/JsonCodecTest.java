package spidersilk.json;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The typed JSON seam: hand-written lambdas that compose. */
class JsonCodecTest {

    record Deck(long id, String name) {
    }

    static final JsonWriter<Deck> DECK_OUT =
            deck -> Json.obj().put("id", deck.id()).put("name", deck.name());

    static final JsonReader<Deck> DECK_IN =
            json -> new Deck(json.asObject().getLong("id"), json.asObject().getString("name"));

    static final JsonCodec<Deck> DECK = JsonCodec.of(DECK_OUT, DECK_IN);

    @Test
    void eachHalfIsWrittenAsALambda() {
        assertThat(DECK_OUT.write(new Deck(1, "English")).toJson())
                .isEqualTo("{\"id\":1,\"name\":\"English\"}");
        assertThat(DECK_IN.read(Json.parse("{\"id\":1,\"name\":\"English\"}")))
                .isEqualTo(new Deck(1, "English"));
    }

    @Test
    void listComposesFromTheElementMapping() {
        JsonWriter<List<Deck>> decks = JsonWriter.list(DECK_OUT);

        assertThat(decks.write(List.of(new Deck(1, "a"), new Deck(2, "b"))).toJson())
                .isEqualTo("[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"}]");
        assertThat(decks.write(List.of()).toJson()).isEqualTo("[]");
    }

    @Test
    void aCodecRoundTripsThroughText() {
        JsonCodec<List<Deck>> codec = JsonCodec.list(DECK);
        List<Deck> decks = List.of(new Deck(1, "a"), new Deck(2, "b"));

        assertThat(codec.read(Json.parse(codec.write(decks).toJson()))).isEqualTo(decks);
    }

    @Test
    void aReaderRejectsInputItCannotBuildFrom() {
        assertThatThrownBy(() -> DECK_IN.read(Json.parse("{\"id\":1}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DECK_IN.read(Json.parse("{\"id\":\"one\",\"name\":\"a\"}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JsonReader.list(DECK_IN).read(Json.parse("{}")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
