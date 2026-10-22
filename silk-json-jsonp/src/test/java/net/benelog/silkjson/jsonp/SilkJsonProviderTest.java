package net.benelog.silkjson.jsonp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonConfig;
import jakarta.json.JsonException;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonPatch;
import jakarta.json.JsonReaderFactory;
import jakarta.json.JsonValue;
import jakarta.json.spi.JsonProvider;
import jakarta.json.stream.JsonGenerationException;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonParser;
import jakarta.json.stream.JsonParser.Event;
import jakarta.json.stream.JsonParsingException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the provider does that the JSON-P TCK leaves open or checks loosely: the tree, the parser, the generator, and their failures. */
class SilkJsonProviderTest {

    static final JsonProvider JSON = new SilkJsonProvider();

    static JsonObject object(String text) {
        return JSON.createReader(new StringReader(text)).readObject();
    }

    @Test
    void jakartaJsonFindsTheProviderThroughItsServicesFile() {
        assertThat(JsonProvider.provider()).isInstanceOf(SilkJsonProvider.class);
        assertThat(Json.createObjectBuilder().add("a", 1).build()).isInstanceOf(SilkJsonObject.class);
    }

    /** A document's members keep their order, and each number keeps what JSON-P says a number is: its decimal, scale and all. */
    @Test
    void aReaderBuildsTheTreeInDocumentOrder() {
        JsonObject deck = object(" {\"name\": \"한국어\", \"id\": 7, \"ratio\": 1.50, \"big\": 1e3,"
                + " \"huge\": 123456789012345678901234567890, \"tags\": [\"a\", null, true]} ");

        assertThat(deck.keySet()).containsExactly("name", "id", "ratio", "big", "huge", "tags");
        assertThat(deck.getString("name")).isEqualTo("한국어");
        assertThat(deck.getJsonNumber("id")).isInstanceOf(SilkJsonNumber.Whole.class);
        assertThat(deck.getJsonNumber("id").longValueExact()).isEqualTo(7);
        assertThat(deck.getJsonNumber("ratio").bigDecimalValue()).isEqualTo(new BigDecimal("1.50"));
        assertThat(deck.getJsonNumber("ratio").doubleValue()).isEqualTo(1.5);
        assertThat(deck.getJsonNumber("big").isIntegral()).isFalse();
        assertThat(deck.getJsonNumber("big").toString()).isEqualTo("1E+3");
        assertThat(deck.getJsonNumber("huge").bigIntegerValueExact()).isEqualTo(new BigInteger("123456789012345678901234567890"));
        assertThat(deck.getJsonArray("tags").isNull(1)).isTrue();
        assertThat(deck.getJsonArray("tags").get(2)).isSameAs(JsonValue.TRUE);
        assertThat(deck.toString()).isEqualTo("{\"name\":\"한국어\",\"id\":7,\"ratio\":1.50,\"big\":1E+3,"
                + "\"huge\":123456789012345678901234567890,\"tags\":[\"a\",null,true]}");
    }

    /** Two numbers are equal when their decimals are, as JSON-P has it, whichever way each was made. */
    @Test
    void numbersAreEqualAsTheirDecimalsAre() {
        JsonArray numbers = JSON.createReader(new StringReader("[1, 1.0, 10e-1, 0.1]")).readArray();

        assertThat(numbers.get(0)).isEqualTo(JSON.createValue(1)).isEqualTo(JSON.createValue(new BigDecimal("1")));
        assertThat(numbers.get(0)).hasSameHashCodeAs(JSON.createValue(BigInteger.ONE));
        assertThat(numbers.get(1)).isNotEqualTo(numbers.get(0)).isEqualTo(numbers.get(2));
        assertThat(numbers.get(3)).isEqualTo(JSON.createValue(0.1));
        // A float literal widens to the double overload, and a boxed Float is taken as the decimal it prints.
        assertThat(JSON.createValue(0.1f)).hasToString("0.10000000149011612");
        assertThat(JSON.createValue((Number) Float.valueOf(0.1f))).hasToString("0.1");
        assertThatThrownBy(() -> JSON.createValue(Double.NaN)).isInstanceOf(NumberFormatException.class);
        assertThatThrownBy(() -> JSON.createObjectBuilder().add("x", Double.POSITIVE_INFINITY))
                .isInstanceOf(NumberFormatException.class);
    }

    /** The tree's accessors throw what JSON-P names, with a message that says which key or index, and the tree refuses every change. */
    @Test
    void theTreeIsImmutableAndItsAccessorsSayWhatTheyFound() {
        JsonObject deck = object("{\"name\":\"French\",\"cards\":[1,\"two\"]}");

        assertThatThrownBy(() -> deck.getString("missing")).isInstanceOf(NullPointerException.class)
                .hasMessage("Missing key in JSON object: missing");
        assertThatThrownBy(() -> deck.getInt("name")).isInstanceOf(ClassCastException.class)
                .hasMessage("Not a JSON number under key \"name\": a string");
        assertThatThrownBy(() -> deck.getJsonArray("cards").getInt(1)).isInstanceOf(ClassCastException.class)
                .hasMessage("Not a JSON number at index 1: a string");
        assertThatThrownBy(() -> deck.getJsonArray("cards").get(2)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThat(deck.getJsonObject("missing")).isNull();
        assertThat(deck.getString("missing", "none")).isEqualTo("none");
        assertThat(deck.getInt("name", -1)).isEqualTo(-1);

        assertThatThrownBy(() -> deck.put("x", JsonValue.NULL)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> deck.entrySet().iterator().next().setValue(JsonValue.NULL))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> deck.getJsonArray("cards").add(JsonValue.NULL))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** An object of many members finds a key through the index it is built with, and stays equal to the same members in another order. */
    @Test
    void aLargeObjectIsIndexedAndComparedAsAMap() {
        Map<String, Object> members = new LinkedHashMap<>();
        for (int i = 0; i < 40; i++) {
            members.put("k" + i, i);
        }
        JsonObject built = JSON.createObjectBuilder(members).remove("k3").build();
        Map<String, Object> reversed = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(members.keySet());
        for (int i = keys.size() - 1; i >= 0; i--) {
            reversed.put(keys.get(i), members.get(keys.get(i)));
        }
        reversed.remove("k3");

        assertThat(built).hasSize(39).doesNotContainKey("k3");
        assertThat(built.getInt("k39")).isEqualTo(39);
        assertThat(built).isEqualTo(JSON.createObjectBuilder(reversed).build()).isEqualTo(new LinkedHashMap<>(built));
        assertThat(built.hashCode()).isEqualTo(new LinkedHashMap<>(built).hashCode());
    }

    /** A map or a collection is taken by type, with an empty Optional left out, and an unknown type refused. */
    @Test
    void buildersTakeJavaValues() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("s", "x");
        map.put("n", null);
        map.put("i", 1);
        map.put("short", (short) 2);
        map.put("list", List.of(1, "two", Optional.of(3), Optional.empty()));
        map.put("ints", new int[] {4, 5});
        map.put("absent", Optional.empty());
        map.put("nested", Map.of("b", true));

        assertThat(JSON.createObjectBuilder(map).build().toString())
                .isEqualTo("{\"s\":\"x\",\"n\":null,\"i\":1,\"short\":2,\"list\":[1,\"two\",3],\"ints\":[4,5],\"nested\":{\"b\":true}}");
        assertThatThrownBy(() -> JSON.createArrayBuilder(List.of(new Object())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A repeated key keeps its first place, and the value the key strategy says. */
    @Test
    void aRepeatedKeyIsKeptAsTheKeyStrategySays() {
        String text = "{\"a\":1,\"b\":2,\"a\":3}";
        JsonReaderFactory first = JSON.createReaderFactory(Map.of(JsonConfig.KEY_STRATEGY, JsonConfig.KeyStrategy.FIRST));
        JsonReaderFactory none = JSON.createReaderFactory(Map.of(JsonConfig.KEY_STRATEGY, "none"));

        assertThat(object(text).toString()).isEqualTo("{\"a\":3,\"b\":2}");
        assertThat(first.createReader(new StringReader(text)).readObject().toString()).isEqualTo("{\"a\":1,\"b\":2}");
        assertThat(first.getConfigInUse().get(JsonConfig.KEY_STRATEGY)).isEqualTo(JsonConfig.KeyStrategy.FIRST);
        assertThatThrownBy(() -> none.createReader(new StringReader(text)).readObject())
                .isInstanceOf(JsonParsingException.class).hasMessageContaining("Duplicate key in a JSON object: a");
        assertThatThrownBy(() -> JSON.createBuilderFactory(Map.of(JsonConfig.KEY_STRATEGY, JsonConfig.KeyStrategy.NONE))
                .createObjectBuilder().add("a", 1).add("a", 2))
                .isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> JSON.createReaderFactory(Map.of(JsonConfig.KEY_STRATEGY, "sometimes")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aParserReportsEachEventAndHoldsWhatItRead() {
        JsonParser parser = JSON.createParser(new StringReader("{\"id\": 12345678901234567890, \"tags\": [\"a\", 2.5],"
                + " \"ok\": false, \"none\": null}"));
        List<Event> events = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        while (parser.hasNext()) {
            Event event = parser.next();
            events.add(event);
            if (event == Event.KEY_NAME || event == Event.VALUE_STRING || event == Event.VALUE_NUMBER) {
                texts.add(parser.getString());
            }
            if (event == Event.VALUE_NUMBER && parser.getString().equals("2.5")) {
                assertThat(parser.isIntegralNumber()).isFalse();
                assertThat(parser.getInt()).isEqualTo(2);
                assertThat(parser.getBigDecimal()).isEqualTo(new BigDecimal("2.5"));
            }
        }

        assertThat(events).containsExactly(Event.START_OBJECT, Event.KEY_NAME, Event.VALUE_NUMBER, Event.KEY_NAME,
                Event.START_ARRAY, Event.VALUE_STRING, Event.VALUE_NUMBER, Event.END_ARRAY, Event.KEY_NAME,
                Event.VALUE_FALSE, Event.KEY_NAME, Event.VALUE_NULL, Event.END_OBJECT);
        assertThat(texts).containsExactly("id", "12345678901234567890", "tags", "a", "2.5", "ok", "none");
        assertThat(parser.currentEvent()).isEqualTo(Event.END_OBJECT);
        assertThatThrownBy(parser::next).isInstanceOf(java.util.NoSuchElementException.class);
    }

    /** getObject() and skipArray() take the rest of a container in one call, and the events go on after it. */
    @Test
    void aParserTakesOrSkipsTheRestOfAContainer() {
        JsonParser parser = JSON.createParser(new StringReader("[{\"a\":[1,2]}, [3, [4]], 5]"));
        assertThat(parser.next()).isEqualTo(Event.START_ARRAY);
        assertThat(parser.next()).isEqualTo(Event.START_OBJECT);
        assertThat(parser.getObject().toString()).isEqualTo("{\"a\":[1,2]}");
        assertThat(parser.currentEvent()).isEqualTo(Event.END_OBJECT);
        assertThat(parser.next()).isEqualTo(Event.START_ARRAY);
        parser.skipArray();
        assertThat(parser.currentEvent()).isEqualTo(Event.END_ARRAY);
        assertThat(parser.next()).isEqualTo(Event.VALUE_NUMBER);
        assertThat(parser.getLong()).isEqualTo(5);
        assertThat(parser.next()).isEqualTo(Event.END_ARRAY);
        assertThat(parser.hasNext()).isFalse();

        JsonParser stream = JSON.createParser(new StringReader("{\"a\":1,\"b\":{\"c\":2}}"));
        stream.next();
        assertThat(stream.getObjectStream().map(Map.Entry::getKey)).containsExactly("a", "b");
        assertThat(stream.hasNext()).isFalse();
    }

    /** A failure is a JsonParsingException at the line and column it happened, and text after the value is one too. */
    @Test
    void aParserFailureSaysWhere() {
        JsonParser parser = JSON.createParser(new StringReader("{\n  \"이름\": tru\n}"));
        parser.next();
        parser.next();
        assertThatThrownBy(parser::next).isInstanceOfSatisfying(JsonParsingException.class, e -> {
            assertThat(e.getMessage()).isEqualTo("Near character 10: Unknown literal");
            assertThat(e.getLocation().getLineNumber()).isEqualTo(2);
            assertThat(e.getLocation().getColumnNumber()).isEqualTo(9);
            assertThat(e.getLocation().getStreamOffset()).isEqualTo(10);
        });

        JsonParser trailing = JSON.createParser(new StringReader("[1] [2]"));
        trailing.next();
        trailing.next();
        trailing.next();
        assertThatThrownBy(trailing::hasNext).isInstanceOf(JsonParsingException.class)
                .hasMessageContaining("Trailing characters");
        assertThatThrownBy(() -> JSON.createReader(new StringReader("{}x")).readObject())
                .isInstanceOf(JsonParsingException.class);
        assertThatThrownBy(() -> JSON.createReader(new StringReader("\"a\"")).read())
                .isInstanceOf(JsonParsingException.class).hasMessageContaining("Not a JSON object or array: a string");
    }

    /** A byte stream's encoding is told from its first bytes, and a byte order mark is passed over. */
    @Test
    void aByteStreamIsReadInTheEncodingItsFirstBytesTell() {
        String text = "{\"이름\":\"값 \uD83D\uDE00\"}";
        for (Charset charset : List.of(StandardCharsets.UTF_8, StandardCharsets.UTF_16BE, StandardCharsets.UTF_16LE,
                Charset.forName("UTF-32BE"), Charset.forName("UTF-32LE"))) {
            JsonObject read = JSON.createReader(new ByteArrayInputStream(text.getBytes(charset))).readObject();
            assertThat(read.getString("이름")).as(charset.name()).isEqualTo("값 \uD83D\uDE00");
        }
        byte[] withMark = ("\uFEFF" + text).getBytes(StandardCharsets.UTF_8);
        assertThat(JSON.createReader(new ByteArrayInputStream(withMark)).readObject().getString("이름")).isEqualTo("값 \uD83D\uDE00");
        assertThat(JSON.createReader(new ByteArrayInputStream("7".getBytes(StandardCharsets.UTF_8))).readValue())
                .isEqualTo(JSON.createValue(7));
        assertThatThrownBy(() -> JSON.createReader(new StringReader("[\"\uD800\"]")).readArray())
                .isInstanceOf(JsonParsingException.class).hasMessageContaining("Unpaired surrogate U+D800");
    }

    /** UTF-32 is decoded here rather than by the JDK, whose UTF-32 a native image leaves out, and a malformed one is refused. */
    @Test
    void utf32IsDecodedByHandAndHeldToItsForm() {
        byte[] surrogate = {0, 0, 0, '[', 0, 0, (byte) 0xD8, 0, 0, 0, 0, ']'};
        byte[] short4 = {0, 0, 0, '[', 0, 0, 0, ']', 0, 0};

        assertThatThrownBy(() -> JSON.createReader(new ByteArrayInputStream(surrogate)).readArray())
                .isInstanceOf(JsonParsingException.class).hasMessage("Not a UTF-32 character: 0x0000D800");
        assertThatThrownBy(() -> JSON.createParser(new ByteArrayInputStream(short4)))
                .isInstanceOf(JsonParsingException.class).hasMessageContaining("ends 2 bytes into one");
        assertThat(JSON.createReader(new ByteArrayInputStream(new byte[] {(byte) 0xFF, (byte) 0xFE, 0, 0, '7', 0, 0, 0}))
                .readValue()).isEqualTo(JSON.createValue(7));
    }

    /** The generator writes through JsonOutput: strings escaped as silk-json escapes them, and doubles as Double.toString writes them. */
    @Test
    void aGeneratorWritesThroughJsonOutput() {
        StringWriter text = new StringWriter();
        try (JsonGenerator generator = JSON.createGenerator(text)) {
            generator.writeStartObject()
                    .write("s", "say \"hi\"\n")
                    .write("n", 1)
                    .write("d", 1e10)
                    .write("big", new BigDecimal("1.50"))
                    .writeKey("tree").write(object("{\"a\":[true,null]}"))
                    .writeStartArray("list").write(-2L).writeNull().writeEnd()
                    .writeEnd();
        }
        assertThat(text).hasToString("{\"s\":\"say \\\"hi\\\"\\n\",\"n\":1,\"d\":1.0E10,\"big\":1.50,"
                + "\"tree\":{\"a\":[true,null]},\"list\":[-2,null]}");
    }

    /** A call out of place is a JsonGenerationException before anything is written, and an unfinished document leaves its stream open. */
    @Test
    void aGeneratorRefusesACallOutOfPlace() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        JsonGenerator generator = JSON.createGenerator(bytes);
        assertThatThrownBy(() -> generator.write("a", 1)).isInstanceOf(JsonGenerationException.class)
                .hasMessageContaining("no object is open");
        generator.writeStartObject();
        assertThatThrownBy(() -> generator.write(1)).isInstanceOf(JsonGenerationException.class);
        generator.writeKey("a");
        assertThatThrownBy(generator::writeEnd).isInstanceOf(JsonGenerationException.class);
        assertThatThrownBy(() -> generator.write(Double.NaN)).isInstanceOf(NumberFormatException.class);
        assertThatThrownBy(generator::close).isInstanceOf(JsonGenerationException.class);
        generator.write(1).writeEnd();
        assertThatThrownBy(generator::writeStartArray).isInstanceOf(JsonGenerationException.class)
                .hasMessageContaining("already written");
        generator.close();
        assertThat(bytes.toString(StandardCharsets.UTF_8)).isEqualTo("{\"a\":1}");
    }

    /** Pretty printing is the reference implementation's layout, laid over the compact bytes as they pass. */
    @Test
    void prettyPrintingLaysTheDocumentOut() {
        StringWriter text = new StringWriter();
        Map<String, ?> pretty = Map.of(JsonGenerator.PRETTY_PRINTING, true);
        try (JsonGenerator generator = JSON.createGeneratorFactory(pretty).createGenerator(text)) {
            generator.write(object("{\"a\":[1,{\"b\":\"x:,{\"}],\"e\":{},\"f\":[]}"));
        }

        assertThat(text).hasToString("""
                {
                    "a": [
                        1,
                        {
                            "b": "x:,{"
                        }
                    ],
                    "e": {
                    },
                    "f": [
                    ]
                }""");
        assertThat(JSON.createWriterFactory(Map.of(JsonGenerator.PRETTY_PRINTING, "false")).getConfigInUse()).isEmpty();
    }

    /** UTF-8 that a long document sends in pieces reaches a Writer whole, a character split between two pieces included. */
    @Test
    void aWriterTakesTheDocumentAsCharacters() {
        String value = "한글 \uD83D\uDE00 ".repeat(3000);
        StringWriter text = new StringWriter();
        try (JsonGenerator generator = JSON.createGenerator(text)) {
            generator.writeStartArray().write(value).writeEnd();
        }
        assertThat(text).hasToString("[\"" + value + "\"]");

        ByteArrayOutputStream utf16 = new ByteArrayOutputStream();
        JSON.createWriterFactory(Map.of()).createWriter(utf16, StandardCharsets.UTF_16BE).write(JSON.createValue(value));
        assertThat(utf16.toString(StandardCharsets.UTF_16BE)).isEqualTo("\"" + value + "\"");
    }

    /** RFC 6902's add, move, and test, and a diff whose patch turns one tree into the other. */
    @Test
    void aPatchAndADiffWorkOnTheTree() {
        JsonObject source = object("{\"title\":\"Goodbye!\",\"author\":{\"givenName\":\"John\",\"familyName\":\"Doe\"},"
                + "\"tags\":[\"example\",\"sample\"],\"content\":\"This will be unchanged\"}");
        JsonPatch patch = JSON.createPatchBuilder()
                .replace("/title", "Hello!")
                .add("/phoneNumber", "+01-123-456-7890")
                .remove("/author/familyName")
                .move("/tags/0", "/tags/1")
                .test("/content", "This will be unchanged")
                .build();
        JsonObject patched = patch.apply(source);

        assertThat(patched.toString()).isEqualTo("{\"title\":\"Hello!\",\"author\":{\"givenName\":\"John\"},"
                + "\"tags\":[\"sample\",\"example\"],\"content\":\"This will be unchanged\",\"phoneNumber\":\"+01-123-456-7890\"}");
        assertThat(source.getString("title")).isEqualTo("Goodbye!");
        assertThat(JSON.createDiff(source, patched).apply(source)).isEqualTo(patched);

        JsonArray from = JSON.createReader(new StringReader("[1,2,3,4,5,[6,7]]")).readArray();
        JsonArray to = JSON.createReader(new StringReader("[0,1,3,5,[6,8],9]")).readArray();
        assertThat(JSON.createDiff(from, to).apply(from)).isEqualTo(to);

        assertThatThrownBy(() -> JSON.createPatchBuilder().test("/title", "Hello!").build().apply(source))
                .isInstanceOf(JsonException.class).hasMessageContaining("test failed");
        assertThatThrownBy(() -> JSON.createPatchBuilder().move("/author/x", "/author").build().apply(source))
                .isInstanceOf(JsonException.class).hasMessageContaining("cannot put it inside itself");
    }

    /** A pointer's tokens are unescaped, an index has no leading zero, and "-" is the place after the last element. */
    @Test
    void aPointerResolvesAsRfc6901Has() {
        JsonObject document = object("{\"a/b\":1,\"m~n\":2,\"list\":[10,20]}");

        assertThat(JSON.createPointer("/a~1b").getValue(document)).isEqualTo(JSON.createValue(1));
        assertThat(JSON.createPointer("/m~0n").getValue(document)).isEqualTo(JSON.createValue(2));
        assertThat(JSON.createPointer("").getValue(document)).isSameAs(document);
        assertThat(document.getValue("/list/1")).isEqualTo(JSON.createValue(20));
        assertThat(JSON.createPointer("/list/01").containsValue(document)).isFalse();
        assertThat(JSON.createPointer("/list/-").add(document, JsonValue.TRUE).getJsonArray("list").toString())
                .isEqualTo("[10,20,true]");
        assertThatThrownBy(() -> JSON.createPointer("/list/-").getValue(document)).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> JSON.createPointer("a")).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> JSON.createPointer("").remove(document)).isInstanceOf(JsonException.class);
    }

    /** RFC 7396: a null removes a member, an object merges into one, and anything else takes the place of what it patches. */
    @Test
    void aMergePatchAndItsDiff() {
        JsonObject target = object("{\"a\":\"b\",\"c\":{\"d\":\"e\",\"f\":\"g\"}}");
        JsonValue patch = object("{\"a\":\"z\",\"c\":{\"f\":null},\"h\":{\"i\":null,\"j\":1}}");

        JsonValue merged = JSON.createMergePatch(patch).apply(target);
        assertThat(merged.toString()).isEqualTo("{\"a\":\"z\",\"c\":{\"d\":\"e\"},\"h\":{\"j\":1}}");
        assertThat(JSON.createMergeDiff(target, merged).apply(target)).isEqualTo(merged);
        assertThat(JSON.createMergePatch(JsonValue.TRUE).apply(target)).isSameAs(JsonValue.TRUE);
    }
}
