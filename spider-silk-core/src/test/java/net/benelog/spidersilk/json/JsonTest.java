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
        String json = Json.object()
                .put("id", 1L)
                .put("name", "English words")
                .put("active", true)
                .putNull("deletedAt")
                .put("tags", Json.array().add("toeic").add("basic"))
                .toJson();

        assertThat(json).isEqualTo("{\"id\":1,\"name\":\"English words\",\"active\":true,"
                + "\"deletedAt\":null,\"tags\":[\"toeic\",\"basic\"]}");
    }

    @Test
    void escapesStrings() {
        assertThat(Json.object().put("text", "a\"b\\c\n").toJson())
                .isEqualTo("{\"text\":\"a\\\"b\\\\c\\n\"}");
    }

    @Test
    void parseAndSerializeRoundTrip() {
        String source = "{\"id\":1,\"name\":\"deck\",\"ok\":true,\"none\":null,"
                + "\"nums\":[1,2.5,-3],\"nested\":{\"a\":\"b\"}}";
        JsonValue value = Json.parse(source);
        assertThat(value.toJson()).isEqualTo(source);
    }

    @Test
    void extractsParsedValuesByType() {
        JsonObject object = Json.parse(
                " { \"name\" : \"deck\\u0041\", \"count\" : 42, \"done\" : false } ").asObject();

        assertThat(object.getString("name")).isEqualTo("deckA");
        assertThat(object.getLong("count")).isEqualTo(42);
        assertThat(object.getBoolean("done")).isEqualTo(false);
        assertThat(object.getString("missing", "fallback")).isEqualTo("fallback");
    }

    /** The opt* family answers the default for a missing key and for a JSON null alike. */
    @Test
    void defaultedGettersAnswerDefaultsForMissingOrNullValues() {
        JsonObject object = Json.parse(
                "{\"name\":null,\"count\":3,\"ratio\":0.5,\"active\":true}").asObject();

        assertThat(object.getString("name", "unnamed")).isEqualTo("unnamed");
        assertThat(object.getLong("count", 0)).isEqualTo(3L);
        assertThat(object.getLong("missing", 7)).isEqualTo(7L);
        assertThat(object.getDouble("ratio", 0.0)).isEqualTo(0.5);
        assertThat(object.getDouble("missing", 1.5)).isEqualTo(1.5);
        assertThat(object.getBoolean("active", false)).isTrue();
        assertThat(object.getBoolean("missing", true)).isTrue();
        assertThat(object.getDouble("ratio")).isEqualTo(0.5);
    }

    /** getObjectOrNull and getArrayOrNull answer null for a missing key and for a JSON null alike. */
    @Test
    void orNullGettersAnswerNullForMissingOrNullValues() {
        JsonObject object = Json.parse(
                "{\"page\":{\"size\":20},\"tags\":[\"a\"],\"owner\":null,\"cards\":null}").asObject();

        assertThat(object.getObjectOrNull("page").getLong("size")).isEqualTo(20L);
        assertThat(object.getObjectOrNull("owner")).isNull();
        assertThat(object.getObjectOrNull("missing")).isNull();
        assertThat(object.getArrayOrNull("tags").size()).isEqualTo(1);
        assertThat(object.getArrayOrNull("cards")).isNull();
        assertThat(object.getArrayOrNull("missing")).isNull();

        assertThatThrownBy(() -> object.getObjectOrNull("tags")).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> object.getArrayOrNull("page")).isInstanceOf(JsonException.class);
    }

    /** A key not known in advance is reachable, so a document reads into a Map. */
    @Test
    void aParsedObjectReadsWithForEachInDocumentOrder() {
        JsonObject counts = Json.parse("{\"toeic\":3,\"basic\":1,\"verbs\":7}").asObject();

        Map<String, Long> byTag = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> member : counts) {
            byTag.put(member.getKey(), member.getValue().asLong());
        }

        assertThat(counts.size()).isEqualTo(3);
        assertThat(counts.keys()).containsExactly("toeic", "basic", "verbs");
        assertThat(byTag).containsExactly(
                Map.entry("toeic", 3L), Map.entry("basic", 1L), Map.entry("verbs", 7L));
    }

    /** What is read back is read-only: writing through it does not reach the object. */
    @Test
    void keysAndMembersAreReadOnly() {
        JsonObject object = Json.object().put("a", 1L);

        assertThat(Json.object().size()).isZero();
        assertThat(Json.object().keys()).isEmpty();
        assertThatThrownBy(() -> object.keys().add("b"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> object.iterator().next().setValue(Json.array()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(object.toJson()).isEqualTo("{\"a\":1}");

        JsonArray array = Json.array().add(1L);
        assertThatThrownBy(() -> array.values().add(Json.array()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> {
            var iterator = array.iterator();
            iterator.next();
            iterator.remove();
        }).isInstanceOf(UnsupportedOperationException.class);
        assertThat(array.toJson()).isEqualTo("[1]");
    }

    /** A value that may be a string or a number is told apart without a try/catch. */
    @Test
    void aPrimitiveReportsItsOwnType() {
        JsonObject object = Json.parse(
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
        for (JsonValue value : Json.parse("[1,2,3]").asArray()) {
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
        assertThatThrownBy(() -> Json.parse("\"abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Json.parse("\"a\\")).isInstanceOf(IllegalArgumentException.class);
    }

    /** A string with no escape and one with an escape read the same, either way round. */
    @Test
    void parsesStringsWithAndWithoutEscapes() {
        assertThat(Json.parse("\"plain text\"").asString()).isEqualTo("plain text");
        assertThat(Json.parse("\"\"").asString()).isEmpty();
        assertThat(Json.parse("\"a\\\"b\\\\c\\n\\t\\r\\b\\f\\/d\"").asString())
                .isEqualTo("a\"b\\c\n\t\r\b\f/d");
        assertThat(Json.parse("\"tail after \\u0041 escape\"").asString())
                .isEqualTo("tail after A escape");

        String source = "{\"plain\":\"no escape here\",\"escaped\":\"a\\nb\"}";
        assertThat(Json.parse(source).toJson()).isEqualTo(source);
    }

    /**
     * A &#92;u escape is exactly four hex digits. A sign is not one of them, and
     * text that is not one fails the way every other syntax error here does.
     */
    @Test
    void aUnicodeEscapeTakesFourHexDigits() {
        assertThat(Json.parse("\"\\u0041\"").asString()).isEqualTo("A");
        assertThat(Json.parse("\"\\u00e9\"").asString()).isEqualTo("\u00e9");

        assertThatThrownBy(() -> Json.parse("\"\\u-001\""))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Expected 4 hex digits");
        assertThatThrownBy(() -> Json.parse("\"\\u+041\""))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Expected 4 hex digits");
        assertThatThrownBy(() -> Json.parse("\"\\uZZZZ\""))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Expected 4 hex digits");
        assertThatThrownBy(() -> Json.parse("\"\\u12\""))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Expected 4 hex digits");
        assertThatThrownBy(() -> Json.parse("\"\\u12"))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Expected 4 hex digits");
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
     * Every failure is a JsonException, so an application that maps
     * IllegalArgumentException to a status of its own can still tell a body
     * that failed to parse apart from a bad argument of its own.
     */
    @Test
    void throwsOnWrongTypeAccess() {
        JsonValue value = Json.parse("{\"a\":1}");
        assertThatThrownBy(value::asArray).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> value.asObject().getString("a"))
                .isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> value.asObject().get("missing"))
                .isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.parse("{\"a\":}")).isInstanceOf(JsonException.class);
        assertThat(Json.parse("null").isNull()).isTrue();
    }

    /** A number with a fractional part is not a long, and is rejected rather than truncated. */
    @Test
    void asLongRejectsAFractionRatherThanTruncatingIt() {
        assertThatThrownBy(() -> Json.parse("1.5").asLong())
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Not a JSON integer");
        assertThatThrownBy(() -> Json.parse("{\"n\":2.5}").asObject().getLong("n"))
                .isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.parse("{\"n\":2.5}").asObject().getLong("n", 0))
                .isInstanceOf(JsonException.class);

        assertThat(Json.parse("2.0").asLong()).isEqualTo(2L);
        assertThat(Json.parse("1e3").asLong()).isEqualTo(1000L);
        assertThat(Json.parse("-7").asLong()).isEqualTo(-7L);
    }

    /**
     * JSON has no NaN and no infinity, so neither is allowed into a tree: a
     * document that held one would serialize to text this parser rejects.
     */
    @Test
    void rejectsNonFiniteDoubles() {
        assertThatThrownBy(() -> Json.object().put("x", Double.NaN))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Not a JSON number");
        assertThatThrownBy(() -> Json.object().put("x", Double.POSITIVE_INFINITY))
                .isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.object().put("x", Double.NEGATIVE_INFINITY))
                .isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.array().add(0.0 / 0.0))
                .isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.array().add(1.0 / 0.0))
                .isInstanceOf(JsonException.class);

        assertThat(Json.object().put("x", 0.5).toJson()).isEqualTo("{\"x\":0.5}");
        assertThat(Json.array().add(-1.5).toJson()).isEqualTo("[-1.5]");
    }

    /** A literal too large for a double is a parse error, not a silent infinity. */
    @Test
    void rejectsANumberTooLargeForADouble() {
        assertThatThrownBy(() -> Json.parse("1e400"))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Number out of range");
        assertThatThrownBy(() -> Json.parse("{\"n\":-1e400}"))
                .isInstanceOf(JsonException.class);

        assertThat(Json.parse("1e-400").asDouble()).isEqualTo(0.0);
        assertThat(Json.parse("1e308").asDouble()).isEqualTo(1e308);
    }
}
