package net.benelog.spidersilk.json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The pull parser: stepping through containers, matching keys, and reading values as bytes. */
class JsonInputTest {

    record Deck(long id, String name, boolean active, double ratio) {
    }

    static final JsonKey ID = JsonKey.of("id");
    static final JsonKey NAME = JsonKey.of("name");
    static final JsonKey ACTIVE = JsonKey.of("active");
    static final JsonKey RATIO = JsonKey.of("ratio");

    /** The shape a generated codec takes: one pass, keys matched as bytes, unknown keys skipped. */
    static final JsonReader<Deck> DECK = in -> {
        in.object();
        long id = 0;
        String name = "";
        boolean active = false;
        double ratio = 0;
        while (in.nextKey()) {
            if (in.keyIs(ID)) {
                id = in.readLong();
            } else if (in.keyIs(NAME)) {
                name = in.readString();
            } else if (in.keyIs(ACTIVE)) {
                active = in.readBoolean();
            } else if (in.keyIs(RATIO)) {
                ratio = in.readDouble();
            } else {
                in.skipValue();
            }
        }
        return new Deck(id, name, active, ratio);
    };

    @Test
    void readsAnObjectInOnePassAndSkipsWhatItDoesNotKnow() {
        Deck deck = DECK.fromJson(" { \"id\" : 7 , \"extra\" : {\"deep\":[1,{\"x\":null}],\"s\":\"a\\\"b\"} ,"
                + " \"name\" : \"French\", \"active\":true, \"ratio\": 2.5, \"more\": [ ] } ");

        assertThat(deck).isEqualTo(new Deck(7, "French", true, 2.5));
        assertThat(JsonReader.list(DECK).fromJson("[{\"id\":1},{\"id\":2,\"name\":\"b\"}]"))
                .containsExactly(new Deck(1, "", false, 0), new Deck(2, "b", false, 0));
        assertThat(JsonReader.list(DECK).fromJson("[]")).isEmpty();
    }

    @Test
    void matchesKeysWrittenWithEscapesOrOutsideAscii() {
        JsonKey korean = JsonKey.of("이름");
        JsonKey quoted = JsonKey.of("say \"hi\"");
        JsonReader<List<String>> reader = in -> {
            in.object();
            List<String> seen = new ArrayList<>();
            while (in.nextKey()) {
                seen.add(in.keyIs(korean) ? "korean" : in.keyIs(quoted) ? "quoted" : in.keyIs("plain") ? "plain" : in.key());
                in.skipValue();
            }
            return seen;
        };

        assertThat(reader.fromJson("{\"이름\":1,\"say \\\"hi\\\"\":2,\"plain\":3,\"\\u0069d\":4,\"other\":5}"))
                .containsExactly("korean", "quoted", "plain", "id", "other");
    }

    /** The key a reader expects next is matched where it stands, quotes and colon included, and anything else is left to nextKey(). */
    @Test
    void nextKeyIsMatchesTheExpectedKeyInPlaceAndMovesNowhereOtherwise() {
        JsonKey id = JsonKey.of("id");
        JsonKey name = JsonKey.of("name");
        JsonKey quoted = JsonKey.of("say \"hi\"");
        JsonKey korean = JsonKey.of("이름");
        JsonKey longer = JsonKey.of("a_rather_long_property_name");
        JsonInput in = JsonInput.of("{\"id\":1,\"name\":\"a\",\"say \\\"hi\\\"\":2,\"이름\":3,"
                + "\"a_rather_long_property_name\":4,\"idx\":5, \"id\" : 6,\"name\":7}");

        in.object();
        assertThat(in.nextKeyIs(name)).isFalse();
        assertThat(in.nextKeyIs(id)).isTrue();
        assertThat(in.keyIs(id)).isTrue();
        assertThat(in.key()).isEqualTo("id");
        assertThat(in.readLong()).isEqualTo(1);
        assertThat(in.nextKeyIs(name)).isTrue();
        assertThat(in.readString()).isEqualTo("a");
        assertThat(in.nextKeyIs(quoted)).isTrue();
        assertThat(in.keyIs(quoted)).isTrue();
        assertThat(in.key()).isEqualTo("say \"hi\"");
        assertThat(in.readLong()).isEqualTo(2);
        assertThat(in.nextKeyIs(korean)).isTrue();
        assertThat(in.key()).isEqualTo("이름");
        assertThat(in.readLong()).isEqualTo(3);
        assertThat(in.nextKeyIs(longer)).isTrue();
        assertThat(in.keyIs("a_rather_long_property_name")).isTrue();
        assertThat(in.readLong()).isEqualTo(4);
        assertThat(in.nextKeyIs(id)).as("a longer key that starts the same").isFalse();
        assertThat(in.nextKey()).isTrue();
        assertThat(in.key()).isEqualTo("idx");
        in.skipValue();
        assertThat(in.nextKeyIs(id)).as("the key with whitespace around it").isFalse();
        assertThat(in.nextKey()).isTrue();
        assertThat(in.keyIs(id)).isTrue();
        assertThat(in.readLong()).isEqualTo(6);
        assertThat(in.nextKeyIs(name)).as("the last bytes of the document, compared one at a time").isTrue();
        assertThat(in.readLong()).isEqualTo(7);
        assertThat(in.nextKeyIs(id)).as("the end of the object").isFalse();
        assertThat(in.nextKey()).isFalse();
        in.end();

        JsonInput small = JsonInput.of("{\"id\":1}");
        small.object();
        assertThat(small.nextKeyIs(id)).isTrue();
        assertThat(small.readLong()).isEqualTo(1);
        assertThat(small.nextKey()).isFalse();
        assertThatThrownBy(() -> {
            JsonInput array = JsonInput.of("[1]");
            array.array();
            array.nextKeyIs(id);
        }).isInstanceOf(IllegalStateException.class);
    }

    /** A key repeated across the objects of one document is one string. */
    @Test
    void aRepeatedKeyIsTheSameString() {
        JsonReader<List<String>> keys = in -> {
            in.array();
            List<String> seen = new ArrayList<>();
            while (in.nextElement()) {
                in.object();
                while (in.nextKey()) {
                    seen.add(in.key());
                    in.skipValue();
                }
            }
            return seen;
        };

        List<String> seen = keys.fromJson("[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"}]");

        assertThat(seen).containsExactly("id", "name", "id", "name");
        assertThat(seen.get(0)).isSameAs(seen.get(2));
        assertThat(seen.get(1)).isSameAs(seen.get(3));
    }

    @Test
    void readsStringsOnTheFastPathAndTheSlowOne() {
        JsonReader<String> string = JsonInput::readString;

        assertThat(string.fromJson("\"plain text\"")).isEqualTo("plain text");
        assertThat(string.fromJson("\"\"")).isEmpty();
        assertThat(string.fromJson("\"a\\\"b\\\\c\\n\\t\\r\\b\\f\\/d\"")).isEqualTo("a\"b\\c\n\t\r\b\f/d");
        assertThat(string.fromJson("\"tail after \\u0041 escape\"")).isEqualTo("tail after A escape");
        assertThat(string.fromJson("\"한글 é 😀 ascii\"")).isEqualTo("한글 é 😀 ascii");
        assertThat(string.fromJson("\"\\ud83d\\ude00\"")).isEqualTo("😀");
        assertThat(string.fromJson("\"" + "x".repeat(1000) + "\\n\"")).hasSize(1001);
        assertThat(string.fromJsonBytes("\"bytes\"".getBytes(StandardCharsets.UTF_8))).isEqualTo("bytes");
        assertThat(string.fromJson("\"Latin-1 by escape: \\u00e9 \\u00ff\"")).isEqualTo("Latin-1 by escape: \u00e9 \u00ff");
        assertThat(string.fromJson("\"past Latin-1 by escape: \\u0100\"")).isEqualTo("past Latin-1 by escape: \u0100");
        assertThat(string.fromJson("\"an escape, then Korean: \\n한글\"")).isEqualTo("an escape, then Korean: \n한글");
        assertThat(string.fromJson("\"Korean, then an escape: 한글\\n\"")).isEqualTo("Korean, then an escape: 한글\n");
    }

    /** Strings of every mix, written by JsonOutput and read back three ways: as a string, skipped, and as a tree. */
    @Test
    void stringsOfEveryShapeReadBackAsTheyWereWritten() {
        Random random = new Random(76);
        String[] pieces = {"\"", "\\", "/", "\n", "\u0001", "\u001f", " ", "\u007f", "\u00e9", "\u00ff", "\u0100", "한", "😀"};
        for (int i = 0; i < 3000; i++) {
            StringBuilder text = new StringBuilder();
            int length = random.nextInt(40);
            for (int j = 0; j < length; j++) {
                if (random.nextInt(4) == 0) {
                    text.append(pieces[random.nextInt(pieces.length)]);
                } else {
                    text.append((char) ('a' + random.nextInt(26)));
                }
            }
            String value = text.toString();
            byte[] json = JsonOutput.inMemory().array().value(value).value(value).end().toBytes();

            JsonInput in = JsonInput.of(json);
            in.array();
            assertThat(in.nextElement()).isTrue();
            assertThat(in.readString()).as(value).isEqualTo(value);
            assertThat(in.nextElement()).isTrue();
            in.skipValue();
            assertThat(in.nextElement()).isFalse();
            in.end();
            assertThat(Json.parse(json).asArray().get(1).asString()).isEqualTo(value);
        }
    }

    /** A malformed UTF-8 sequence is refused, in a value read and in one skipped, rather than read as U+FFFD. */
    @Test
    void refusesMalformedUtf8() {
        byte[][] malformed = {
                {'"', (byte) 0xC0, (byte) 0x80, '"'}, // overlong
                {'"', (byte) 0xE0, (byte) 0x80, (byte) 0x80, '"'}, // overlong
                {'"', (byte) 0xED, (byte) 0xA0, (byte) 0x80, '"'}, // a surrogate
                {'"', (byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80, '"'}, // past U+10FFFF
                {'"', (byte) 0xC3, '"'}, // truncated
                {'"', (byte) 0x80, '"'}, // a continuation byte alone
                {'"', (byte) 0xFF, '"'},
        };
        for (byte[] bytes : malformed) {
            byte[] key = new byte[bytes.length + 4];
            key[0] = '{';
            System.arraycopy(bytes, 0, key, 1, bytes.length);
            key[bytes.length + 1] = ':';
            key[bytes.length + 2] = '1';
            key[bytes.length + 3] = '}';
            assertThatThrownBy(() -> JsonInput.of(key).skipValue())
                    .as("a key")
                    .isInstanceOf(JsonException.class)
                    .hasMessageContaining("Malformed UTF-8");
            assertThatThrownBy(() -> JsonInput.of(bytes).readString())
                    .as(new String(bytes, StandardCharsets.ISO_8859_1))
                    .isInstanceOf(JsonException.class)
                    .hasMessageContaining("Malformed UTF-8");
            assertThatThrownBy(() -> JsonInput.of(bytes).skipValue())
                    .isInstanceOf(JsonException.class)
                    .hasMessageContaining("Malformed UTF-8");
            assertThatThrownBy(() -> Json.parse(bytes)).isInstanceOf(JsonException.class);
        }
        assertThat(JsonInput.of("\"\u00e9\"".getBytes(StandardCharsets.UTF_8)).readString()).isEqualTo("\u00e9");
    }

    @Test
    void readsNumbersExactlyOrRefusesThem() {
        JsonReader<Long> asLong = JsonInput::readLong;
        JsonReader<Integer> asInt = JsonInput::readInt;
        JsonReader<Double> asDouble = JsonInput::readDouble;

        assertThat(asLong.fromJson("42")).isEqualTo(42L);
        assertThat(asLong.fromJson("-0")).isZero();
        assertThat(asLong.fromJson("999999999999999999")).isEqualTo(999_999_999_999_999_999L);
        assertThat(asLong.fromJson("9223372036854775807")).isEqualTo(Long.MAX_VALUE);
        assertThat(asLong.fromJson("-9223372036854775808")).isEqualTo(Long.MIN_VALUE);
        assertThat(asLong.fromJson("2.0")).isEqualTo(2L);
        assertThat(asLong.fromJson("1e3")).isEqualTo(1000L);
        assertThat(asLong.fromJson("9007199254740993.0")).isEqualTo(9007199254740993L);
        assertThatThrownBy(() -> asLong.fromJson("1.5")).isInstanceOf(JsonException.class)
                .hasMessageContaining("Not a JSON integer: 1.5");
        assertThatThrownBy(() -> asLong.fromJson("9223372036854775808")).isInstanceOf(JsonException.class)
                .hasMessageContaining("Not a JSON integer");
        assertThatThrownBy(() -> asLong.fromJson("\"5\"")).isInstanceOf(JsonException.class)
                .hasMessageContaining("Not a JSON number: a string");
        assertThatThrownBy(() -> asLong.fromJson("0." + "1".repeat(100)))
                .hasMessageContaining("Not a JSON integer: 0." + "1".repeat(30) + "...");

        assertThat(asInt.fromJson("-2147483648")).isEqualTo(Integer.MIN_VALUE);
        assertThatThrownBy(() -> asInt.fromJson("2147483648")).isInstanceOf(JsonException.class)
                .hasMessageContaining("Not a 32-bit integer");

        assertThat(asDouble.fromJson("7")).isEqualTo(7.0);
        assertThat(asDouble.fromJson("-1.5E-2")).isEqualTo(-0.015);
        assertThat(asDouble.fromJson("12345678901234567890")).isEqualTo(1.2345678901234567E19);
        assertThat(asDouble.fromJson("1e-400")).isEqualTo(0.0);
        assertThatThrownBy(() -> asDouble.fromJson("1e400")).isInstanceOf(JsonException.class)
                .hasMessageContaining("Number out of range");
        assertThat(((JsonReader<String>) JsonInput::readNumber).fromJson("1.50e+2")).isEqualTo("1.50e+2");
    }

    /** An integer of any length, wherever it stands: a word to read it in at once, or too near the end for one. */
    @Test
    void readsIntegersOfEveryLengthWhereverTheyStand() {
        long[] numbers = {0, 7, -7, 42, -42, 1234567, -1234567, 12345678, 99999999, 123456789, -987654321,
                999_999_999_999_999_999L, Long.MAX_VALUE, Long.MIN_VALUE};
        for (long n : numbers) {
            for (int pad = 0; pad < 10; pad++) {
                String text = "[" + n + " ".repeat(pad) + "," + n + "]";
                JsonInput in = JsonInput.of(text);
                in.array();
                assertThat(in.nextElement()).isTrue();
                assertThat(in.readLong()).as(text).isEqualTo(n);
                assertThat(in.nextElement()).isTrue();
                assertThat(in.readLong()).as(text).isEqualTo(n);
                assertThat(in.nextElement()).isFalse();
            }
        }
        JsonReader<List<Long>> longs = JsonReader.list(JsonInput::readLong);
        assertThat(longs.fromJson("[2.0, 1e2]")).containsExactly(2L, 100L);
        assertThat(longs.fromJson("[1234567.0,7e0,0,-0]")).containsExactly(1234567L, 7L, 0L, 0L);
        for (String bad : List.of("[0123456,1]", "[-0123456,1]", "[1234567.5,1]", "[01]", "[-01]")) {
            assertThatThrownBy(() -> longs.fromJson(bad)).as(bad).isInstanceOf(JsonException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"01", "+1", ".5", "1.", "1e", "-", "1e+", "0x10", "NaN", "- 1"})
    void aNumberOutsideTheGrammarIsRefusedWhetherReadOrSkipped(String text) {
        JsonReader<Long> asLong = JsonInput::readLong;
        JsonReader<Double> asDouble = JsonInput::readDouble;

        assertThatThrownBy(() -> asLong.fromJson(text)).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> asDouble.fromJson(text)).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> JsonInput.of("[" + text + "]").skipValue()).isInstanceOf(JsonException.class);
    }

    @Test
    void readsBooleansAndNulls() {
        assertThat(JsonInput.of(" true ").readBoolean()).isTrue();
        assertThat(JsonInput.of("false").readBoolean()).isFalse();
        assertThatThrownBy(() -> JsonInput.of("1").readBoolean()).isInstanceOf(JsonException.class)
                .hasMessageContaining("Not a JSON boolean: a number");
        assertThatThrownBy(() -> JsonInput.of("tru").readBoolean()).isInstanceOf(JsonException.class)
                .hasMessageContaining("Unknown literal");

        JsonInput in = JsonInput.of("[null,\"x\"]");
        in.array();
        assertThat(in.nextElement()).isTrue();
        assertThat(in.readNull()).isTrue();
        assertThat(in.nextElement()).isTrue();
        assertThat(in.readNull()).isFalse();
        assertThat(in.readString()).isEqualTo("x");
        assertThat(in.nextElement()).isFalse();
        in.end();
    }

    @Test
    void readValueTakesAWholeTreeAndSkipValuePassesOneOver() {
        JsonInput in = JsonInput.of("{\"a\":[1,{\"b\":\"c\"}],\"d\":2,\"e\":{\"f\":[true,null]}}");
        in.object();
        assertThat(in.nextKey()).isTrue();
        assertThat(in.readValue().toJson()).isEqualTo("[1,{\"b\":\"c\"}]");
        assertThat(in.nextKey()).isTrue();
        in.skipValue();
        assertThat(in.nextKey()).isTrue();
        assertThat(in.key()).isEqualTo("e");
        assertThat(in.readValue().asObject().getArray("f").size()).isEqualTo(2);
        assertThat(in.nextKey()).isFalse();
        in.end();
    }

    /** What is skipped is still checked: a bad escape or a control character in a skipped string is a syntax error. */
    @Test
    void aSkippedValueIsStillHeldToTheGrammar() {
        for (String bad : List.of("\"\\x\"", "\"\\u12G4\"", "\"a\nb\"", "tru", "[1,]", "{\"a\":1,}", "{\"a\" 1}", "[1 2]")) {
            assertThatThrownBy(() -> {
                JsonInput in = JsonInput.of("[" + bad + "]");
                in.array();
                in.nextElement();
                in.skipValue();
                in.nextElement();
            }).as(bad).isInstanceOf(JsonException.class);
        }
    }

    @Test
    void aStructureOutOfPlaceIsASyntaxErrorAndAMisuseIsAnIllegalState() {
        assertThatThrownBy(() -> JsonInput.of("[1]").object()).isInstanceOf(JsonException.class)
                .hasMessage("Near character 0: Not a JSON object: an array");
        assertThatThrownBy(() -> JsonInput.of("{}").array()).isInstanceOf(JsonException.class)
                .hasMessageContaining("Not a JSON array: an object");
        assertThatThrownBy(() -> JsonInput.of("}").readString()).isInstanceOf(JsonException.class)
                .hasMessageContaining("Unexpected character");
        assertThatThrownBy(() -> JsonInput.of("").readString()).isInstanceOf(JsonException.class)
                .hasMessageContaining("Unexpected end of input");
        assertThatThrownBy(() -> {
            JsonInput in = JsonInput.of("{\"a\":1} extra");
            in.skipValue();
            in.end();
        }).hasMessageContaining("Trailing characters");

        assertThatThrownBy(() -> JsonInput.of("{}").nextKey()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JsonInput.of("[]").nextElement()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> {
            JsonInput in = JsonInput.of("[]");
            in.array();
            in.nextKey();
        }).isInstanceOf(IllegalStateException.class);
    }

    /** The position in a message counts characters, so a key after Korean text is named where the client sees it. */
    @Test
    void aFailureNamesItsPositionInCharacters() {
        assertThatThrownBy(() -> Json.parse("{\"이름\":\"값\",\"n\":01}"))
                .isInstanceOf(JsonException.class)
                .hasMessage("Near character 15: Leading zero in a number");
        assertThatThrownBy(() -> JsonInput.of("[\"a\"]").readLong())
                .hasMessage("Near character 0: Not a JSON number: an array");
    }

    @Test
    void nestingIsBoundedWhetherReadOrSkipped() {
        assertThatThrownBy(() -> JsonInput.of("[".repeat(300) + "]".repeat(300)).skipValue())
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Nested deeper than 256");
        assertThatThrownBy(() -> JsonInput.of("[".repeat(300) + "]".repeat(300)).readValue())
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Nested deeper than 256");
        JsonInput in = JsonInput.of("[".repeat(256) + "]".repeat(256));
        in.skipValue();
        in.end();
    }

    @Test
    void aReaderOfItsOwnThrowsThroughTheInput() {
        JsonReader<String> reader = in -> {
            String name = in.readString();
            throw in.error("Not a deck name: " + name);
        };

        assertThatThrownBy(() -> reader.fromJson("  \"x\"")).isInstanceOf(JsonException.class)
                .hasMessage("Near character 5: Not a deck name: x");
    }
}
