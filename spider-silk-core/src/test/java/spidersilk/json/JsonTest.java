package spidersilk.json;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonTest {

    @Test
    void buildsAndSerializesObjects() {
        String json = Json.obj()
                .put("id", 1L)
                .put("name", "English words")
                .put("active", true)
                .putNull("deletedAt")
                .put("tags", Json.arr().add("toeic").add("basic"))
                .toJson();

        assertThat(json).isEqualTo("{\"id\":1,\"name\":\"English words\",\"active\":true,"
                + "\"deletedAt\":null,\"tags\":[\"toeic\",\"basic\"]}");
    }

    @Test
    void escapesStrings() {
        assertThat(Json.obj().put("text", "a\"b\\c\n").toJson())
                .isEqualTo("{\"text\":\"a\\\"b\\\\c\\n\"}");
    }

    @Test
    void parseAndSerializeRoundTrip() {
        String source = "{\"id\":1,\"name\":\"deck\",\"ok\":true,\"none\":null,"
                + "\"nums\":[1,2.5,-3],\"nested\":{\"a\":\"b\"}}";
        Json.JsonValue value = Json.parse(source);
        assertThat(value.toJson()).isEqualTo(source);
    }

    @Test
    void extractsParsedValuesByType() {
        Json.JsonObject object = Json.parse(
                " { \"name\" : \"deck\\u0041\", \"count\" : 42, \"done\" : false } ").asObject();

        assertThat(object.getString("name")).isEqualTo("deckA");
        assertThat(object.getLong("count")).isEqualTo(42);
        assertThat(object.getBoolean("done")).isEqualTo(false);
        assertThat(object.optString("missing", "fallback")).isEqualTo("fallback");
    }

    /** The opt* family answers the default for a missing key and for a JSON null alike. */
    @Test
    void optAccessorsAnswerDefaultsForMissingOrNullValues() {
        Json.JsonObject object = Json.parse(
                "{\"name\":null,\"count\":3,\"ratio\":0.5,\"active\":true}").asObject();

        assertThat(object.optString("name", "unnamed")).isEqualTo("unnamed");
        assertThat(object.optLong("count", 0)).isEqualTo(3L);
        assertThat(object.optLong("missing", 7)).isEqualTo(7L);
        assertThat(object.optDouble("ratio", 0.0)).isEqualTo(0.5);
        assertThat(object.optDouble("missing", 1.5)).isEqualTo(1.5);
        assertThat(object.optBoolean("active", false)).isTrue();
        assertThat(object.optBoolean("missing", true)).isTrue();
        assertThat(object.getDouble("ratio")).isEqualTo(0.5);
    }

    @Test
    void aParsedArrayReadsWithForEach() {
        long sum = 0;
        for (Json.JsonValue value : Json.parse("[1,2,3]").asArray()) {
            sum += value.asLong();
        }
        assertThat(sum).isEqualTo(6);
    }

    @Test
    void throwsOnInvalidSyntax() {
        assertThatThrownBy(() -> Json.parse("{\"a\":}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Json.parse("[1,2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Json.parse("{} extra")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Json.parse("tru")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void throwsOnWrongTypeAccess() {
        Json.JsonValue value = Json.parse("{\"a\":1}");
        assertThatThrownBy(value::asArray).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> value.asObject().getString("a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Json.parse("null").isNull()).isTrue();
    }
}
