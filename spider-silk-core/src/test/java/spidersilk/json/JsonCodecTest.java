package spidersilk.json;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertEquals("{\"id\":1,\"name\":\"English\"}",
                DECK_OUT.write(new Deck(1, "English")).toJson());
        assertEquals(new Deck(1, "English"), DECK_IN.read(Json.parse("{\"id\":1,\"name\":\"English\"}")));
    }

    @Test
    void listComposesFromTheElementMapping() {
        JsonWriter<List<Deck>> decks = JsonWriter.list(DECK_OUT);

        assertEquals("[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"}]",
                decks.write(List.of(new Deck(1, "a"), new Deck(2, "b"))).toJson());
        assertEquals("[]", decks.write(List.of()).toJson());
    }

    @Test
    void aCodecRoundTripsThroughText() {
        JsonCodec<List<Deck>> codec = JsonCodec.list(DECK);
        List<Deck> decks = List.of(new Deck(1, "a"), new Deck(2, "b"));

        assertEquals(decks, codec.read(Json.parse(codec.write(decks).toJson())));
    }

    @Test
    void aReaderRejectsInputItCannotBuildFrom() {
        assertThrows(IllegalArgumentException.class,
                () -> DECK_IN.read(Json.parse("{\"id\":1}")));
        assertThrows(IllegalArgumentException.class,
                () -> DECK_IN.read(Json.parse("{\"id\":\"one\",\"name\":\"a\"}")));
        assertThrows(IllegalArgumentException.class,
                () -> JsonReader.list(DECK_IN).read(Json.parse("{}")));
    }
}
