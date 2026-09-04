package net.benelog.spidersilk.json;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
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

    /** optObject and optArray answer null for a missing key and for a JSON null alike. */
    @Test
    void optObjectAndOptArrayAnswerNullForMissingOrNullValues() {
        Json.JsonObject object = Json.parse(
                "{\"page\":{\"size\":20},\"tags\":[\"a\"],\"owner\":null,\"cards\":null}").asObject();

        assertThat(object.optObject("page").getLong("size")).isEqualTo(20L);
        assertThat(object.optObject("owner")).isNull();
        assertThat(object.optObject("missing")).isNull();
        assertThat(object.optArray("tags").size()).isEqualTo(1);
        assertThat(object.optArray("cards")).isNull();
        assertThat(object.optArray("missing")).isNull();

        assertThatThrownBy(() -> object.optObject("tags")).isInstanceOf(Json.JsonException.class);
        assertThatThrownBy(() -> object.optArray("page")).isInstanceOf(Json.JsonException.class);
    }

    /** A key not known in advance is reachable, so a document reads into a Map. */
    @Test
    void aParsedObjectReadsWithForEachInDocumentOrder() {
        Json.JsonObject counts = Json.parse("{\"toeic\":3,\"basic\":1,\"verbs\":7}").asObject();

        Map<String, Long> byTag = new LinkedHashMap<>();
        for (Map.Entry<String, Json.JsonValue> member : counts) {
            byTag.put(member.getKey(), member.getValue().asLong());
        }

        assertThat(counts.size()).isEqualTo(3);
        assertThat(counts.keys()).containsExactly("toeic", "basic", "verbs");
        assertThat(byTag).containsExactly(
                Map.entry("toeic", 3L), Map.entry("basic", 1L), Map.entry("verbs", 7L));
    }

    /** The keys read back are a copy: writing through one does not reach the object. */
    @Test
    void keysAndMembersAreReadOnly() {
        Json.JsonObject object = Json.obj().put("a", 1L);

        assertThat(Json.obj().size()).isZero();
        assertThat(Json.obj().keys()).isEmpty();
        assertThatThrownBy(() -> object.keys().add("b"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> object.iterator().next().setValue(Json.arr()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(object.toJson()).isEqualTo("{\"a\":1}");
    }

    /** A value that may be a string or a number is told apart without a try/catch. */
    @Test
    void aPrimitiveReportsItsOwnType() {
        Json.JsonObject object = Json.parse(
                "{\"name\":\"deck\",\"count\":42,\"ratio\":0.5,\"done\":false,"
                        + "\"none\":null,\"tags\":[],\"page\":{}}").asObject();

        assertThat(object.get("name").isString()).isTrue();
        assertThat(object.get("count").isNumber()).isTrue();
        assertThat(object.get("ratio").isNumber()).isTrue();
        assertThat(object.get("done").isBoolean()).isTrue();

        assertThat(object.get("count").isString()).isFalse();
        assertThat(object.get("name").isNumber()).isFalse();
        assertThat(object.get("count").isBoolean()).isFalse();
        assertThat(object.get("none").isString()).isFalse();
        assertThat(object.get("none").isNumber()).isFalse();
        assertThat(object.get("none").isBoolean()).isFalse();
        assertThat(object.get("none").isNull()).isTrue();
        assertThat(object.get("tags").isString()).isFalse();
        assertThat(object.get("page").isNumber()).isFalse();
        assertThat(object.get("page").isNull()).isFalse();
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
    void rejectsNestingDeeperThanItCanRecurseThrough() {
        assertThatNoException().isThrownBy(() -> Json.parse("[".repeat(256) + "]".repeat(256)));

        assertThatThrownBy(() -> Json.parse("[".repeat(50_000) + "]".repeat(50_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nested deeper than");
        assertThatThrownBy(() -> Json.parse("{\"a\":".repeat(50_000) + "1" + "}".repeat(50_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nested deeper than");
    }

    /**
     * Every failure is a Json.JsonException, so an application that maps
     * IllegalArgumentException to a status of its own can still tell a body
     * that failed to parse apart from a bad argument of its own.
     */
    @Test
    void throwsOnWrongTypeAccess() {
        Json.JsonValue value = Json.parse("{\"a\":1}");
        assertThatThrownBy(value::asArray).isInstanceOf(Json.JsonException.class);
        assertThatThrownBy(() -> value.asObject().getString("a"))
                .isInstanceOf(Json.JsonException.class);
        assertThatThrownBy(() -> value.asObject().get("missing"))
                .isInstanceOf(Json.JsonException.class);
        assertThatThrownBy(() -> Json.parse("{\"a\":}")).isInstanceOf(Json.JsonException.class);
        assertThat(Json.parse("null").isNull()).isTrue();
    }

    /** A number with a fractional part is not a long, and is rejected rather than truncated. */
    @Test
    void asLongRejectsAFractionRatherThanTruncatingIt() {
        assertThatThrownBy(() -> Json.parse("1.5").asLong())
                .isInstanceOf(Json.JsonException.class)
                .hasMessageContaining("Not a JSON integer");
        assertThatThrownBy(() -> Json.parse("{\"n\":2.5}").asObject().getLong("n"))
                .isInstanceOf(Json.JsonException.class);
        assertThatThrownBy(() -> Json.parse("{\"n\":2.5}").asObject().optLong("n", 0))
                .isInstanceOf(Json.JsonException.class);

        assertThat(Json.parse("2.0").asLong()).isEqualTo(2L);
        assertThat(Json.parse("1e3").asLong()).isEqualTo(1000L);
        assertThat(Json.parse("-7").asLong()).isEqualTo(-7L);
    }
}
