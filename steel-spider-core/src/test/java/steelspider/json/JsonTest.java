package steelspider.json;

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
