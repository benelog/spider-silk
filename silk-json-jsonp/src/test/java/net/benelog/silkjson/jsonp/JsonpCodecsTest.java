package net.benelog.silkjson.jsonp;

import java.io.StringReader;
import java.util.List;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.json.spi.JsonProvider;

import org.junit.jupiter.api.Test;

import net.benelog.silkjson.JsonException;
import net.benelog.silkjson.JsonOutput;
import net.benelog.silkjson.JsonReader;
import net.benelog.silkjson.JsonWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The JSON-P tree where silk-json takes a writer or a reader. */
class JsonpCodecsTest {

    static final JsonProvider JSON = new SilkJsonProvider();

    record NewDeck(String name, int limit) {
    }

    static final JsonReader<NewDeck> NEW_DECK = JsonpCodecs.object(object -> new NewDeck(
            object.getString("name"),
            object.getInt("limit", 20)));

    /** The writer walks the interfaces, so the API's own constants and a tree built anywhere go out the same way. */
    @Test
    void theWriterWritesAnyTreeIntoTheOutput() {
        JsonObject deck = JSON.createObjectBuilder()
                .add("name", "French")
                .add("empty", JsonValue.EMPTY_JSON_OBJECT)
                .add("cards", JsonValue.EMPTY_JSON_ARRAY)
                .add("ratio", 0.5)
                .build();

        assertThat(JsonpCodecs.WRITER.toJson(deck))
                .isEqualTo("{\"name\":\"French\",\"empty\":{},\"cards\":[],\"ratio\":0.5}");

        JsonWriter<List<JsonValue>> list = JsonWriter.list(JsonpCodecs.WRITER);
        assertThat(list.toJson(List.of(JsonValue.NULL, JSON.createValue("a")))).isEqualTo("[null,\"a\"]");

        JsonOutput out = JsonOutput.inMemory();
        out.object().put("tree", (net.benelog.silkjson.JsonValue) null).name("jsonp");
        JsonpCodecs.WRITER.write(deck.getJsonArray("cards"), out);
        assertThat(out.end().toJson()).isEqualTo("{\"tree\":null,\"jsonp\":[]}");
    }

    @Test
    void theReadersBuildTheTreeOffTheInput() {
        JsonObject read = JsonpCodecs.OBJECT.fromJson("{\"a\":[1,2.5,\"x\"],\"b\":null}");

        assertThat(read).isEqualTo(JSON.createReader(new StringReader("{\"b\":null,\"a\":[1,2.5,\"x\"]}")).readObject());
        assertThat(JsonpCodecs.ARRAY.fromJson("[]")).isEmpty();
        assertThat(JsonpCodecs.VALUE.fromJson(" null ")).isSameAs(JsonValue.NULL);
        assertThat(JsonReader.list(JsonpCodecs.OBJECT).fromJson("[{\"a\":1},{}]")).hasSize(2);

        assertThatThrownBy(() -> JsonpCodecs.OBJECT.fromJson("[1]")).isInstanceOf(JsonException.class)
                .hasMessage("Near character 0: Not a JSON object: an array");
        assertThatThrownBy(() -> JsonpCodecs.ARRAY.fromJson("[1,]")).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> JsonpCodecs.VALUE.fromJson("{} {}")).isInstanceOf(JsonException.class)
                .hasMessageContaining("Trailing characters");
    }

    /** What the tree's accessors throw inside the mapping is the document's fault, and a JsonException that says which key. */
    @Test
    void anObjectReaderTurnsTheTreesExceptionsIntoJsonException() {
        assertThat(NEW_DECK.fromJson("{\"name\":\"French\"}")).isEqualTo(new NewDeck("French", 20));

        assertThatThrownBy(() -> NEW_DECK.fromJson("{\"limit\":3}")).isInstanceOf(JsonException.class)
                .hasMessage("Missing key in JSON object: name");
        assertThatThrownBy(() -> NEW_DECK.fromJson("{\"name\":7}")).isInstanceOf(JsonException.class)
                .hasMessage("Not a JSON string under key \"name\": a number");

        JsonReader<Long> id = JsonpCodecs.object(object -> object.getJsonNumber("id").longValueExact());
        assertThatThrownBy(() -> id.fromJson("{\"id\":1.5}")).isInstanceOf(JsonException.class)
                .hasMessage("Not a whole number in range: Rounding necessary");

        JsonReader<JsonArray> first = JsonpCodecs.object(object -> object.getJsonArray("list").getJsonArray(0));
        assertThatThrownBy(() -> first.fromJson("{\"list\":[]}")).isInstanceOf(JsonException.class)
                .hasMessage("Index 0 is out of bounds for a JSON array of 0 elements");

        // The program's own NullPointerException says nothing of the program back to the client.
        JsonReader<String> nested = JsonpCodecs.object(object -> object.getJsonObject("missing").getString("x"));
        assertThatThrownBy(() -> nested.fromJson("{}")).isInstanceOf(JsonException.class)
                .hasMessage("The JSON object is not the shape this reader takes");
    }
}
