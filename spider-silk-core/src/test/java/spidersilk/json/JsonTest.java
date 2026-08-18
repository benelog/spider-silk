package spidersilk.json;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertEquals("{\"id\":1,\"name\":\"English words\",\"active\":true,"
                + "\"deletedAt\":null,\"tags\":[\"toeic\",\"basic\"]}", json);
    }

    @Test
    void escapesStrings() {
        assertEquals("{\"text\":\"a\\\"b\\\\c\\n\"}",
                Json.obj().put("text", "a\"b\\c\n").toJson());
    }

    @Test
    void parseAndSerializeRoundTrip() {
        String source = "{\"id\":1,\"name\":\"deck\",\"ok\":true,\"none\":null,"
                + "\"nums\":[1,2.5,-3],\"nested\":{\"a\":\"b\"}}";
        Json.JsonValue value = Json.parse(source);
        assertEquals(source, value.toJson());
    }

    @Test
    void extractsParsedValuesByType() {
        Json.JsonObject object = Json.parse(
                " { \"name\" : \"deck\\u0041\", \"count\" : 42, \"done\" : false } ").asObject();

        assertEquals("deckA", object.getString("name"));
        assertEquals(42, object.getLong("count"));
        assertEquals(false, object.getBoolean("done"));
        assertEquals("fallback", object.optString("missing", "fallback"));
    }

    /** The opt* family answers the default for a missing key and for a JSON null alike. */
    @Test
    void optAccessorsAnswerDefaultsForMissingOrNullValues() {
        Json.JsonObject object = Json.parse(
                "{\"name\":null,\"count\":3,\"ratio\":0.5,\"active\":true}").asObject();

        assertEquals("unnamed", object.optString("name", "unnamed"));
        assertEquals(3L, object.optLong("count", 0));
        assertEquals(7L, object.optLong("missing", 7));
        assertEquals(0.5, object.optDouble("ratio", 0.0));
        assertEquals(1.5, object.optDouble("missing", 1.5));
        assertTrue(object.optBoolean("active", false));
        assertTrue(object.optBoolean("missing", true));
        assertEquals(0.5, object.getDouble("ratio"));
    }

    @Test
    void aParsedArrayReadsWithForEach() {
        long sum = 0;
        for (Json.JsonValue value : Json.parse("[1,2,3]").asArray()) {
            sum += value.asLong();
        }
        assertEquals(6, sum);
    }

    @Test
    void throwsOnInvalidSyntax() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,2"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{} extra"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("tru"));
    }

    @Test
    void throwsOnWrongTypeAccess() {
        Json.JsonValue value = Json.parse("{\"a\":1}");
        assertThrows(IllegalArgumentException.class, value::asArray);
        assertThrows(IllegalArgumentException.class,
                () -> value.asObject().getString("a"));
        assertTrue(Json.parse("null").isNull());
    }
}
