package net.benelog.silkjson;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    enum Shade { A, PALE_BLUE_GREEN_X, RATHER_LONG_NAME_OF_A_SHADE, $ }

    /** A name from a fixed set is written from its key and read back by its index, compared in place or, when escaped or near the end, as a string. */
    @Test
    @SuppressWarnings("EnumOrdinal") // the index into the names is the point
    void namesFromAFixedSetAreWrittenAndReadWithNoString() {
        Shade[] shades = Shade.values();
        JsonKey[] names = JsonKey.names(shades);
        JsonKey shade = JsonKey.of("shade");
        for (Shade written : shades) {
            for (int pad = 0; pad < 20; pad++) {
                JsonOutput out = JsonOutput.inMemory();
                out.object().put(shade, names[written.ordinal()]).put(JsonKey.of("pad"), "x".repeat(pad)).end();
                String json = out.toJson();
                assertThat(json).isEqualTo("{\"shade\":\"" + written.name() + "\",\"pad\":\"" + "x".repeat(pad) + "\"}");
                for (String document : List.of(json, "[" + json.substring(9, json.indexOf(',')) + "]")) {
                    JsonInput in = JsonInput.of(document);
                    if (document.startsWith("{")) {
                        in.object();
                        assertThat(in.nextKeyIs(shade)).isTrue();
                    } else {
                        in.array();
                        assertThat(in.nextElement()).isTrue();
                    }
                    assertThat(shades[in.readOneOf(names)]).as(document).isEqualTo(written);
                }
            }
        }
        JsonReader<Shade> asShade = in -> shades[in.readOneOf(names)];
        assertThat(asShade.fromJson("\"\\u0041\"")).isEqualTo(Shade.A);
        assertThat(asShade.fromJson(" \"PALE_BLUE_GREEN_X\"  ")).isEqualTo(Shade.PALE_BLUE_GREEN_X);
        assertThatThrownBy(() -> asShade.fromJson("\"PALE_BLUE_GREEN\"            ")).isInstanceOf(JsonException.class)
                .hasMessageContaining("Not a name this value takes: PALE_BLUE_GREEN");
        assertThatThrownBy(() -> asShade.fromJson("\"AB\"")).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> asShade.fromJson("1")).isInstanceOf(JsonException.class)
                .hasMessageContaining("Not a JSON string");
    }

    /** Runs of whitespace of every length up to two words, of spaces and of the other three, read as the document without them. */
    @Test
    void whitespaceOfEveryLengthIsPassedOver() {
        String[] kinds = {" ", "\n", "\r\n", "\t", "\n    "};
        for (int length = 0; length <= 17; length++) {
            for (String kind : kinds) {
                String gap = (" ".repeat(length) + kind).repeat(length % 3 + 1);
                String json = "{" + gap + "\"id\"" + gap + ":" + gap + "1" + gap + "," + gap + "\"tags\":" + gap + "["
                        + gap + "\"a\"" + gap + "," + gap + "true" + gap + "]" + gap + "}" + gap;
                JsonValue read = JsonInput.of(json).readValue();
                assertThat(read.toJson()).as("%s", json).isEqualTo("{\"id\":1,\"tags\":[\"a\",true]}");
            }
        }
    }

    /** Whitespace before a key, or after its comma, as a pretty printer or Python's separators write it, is passed over; a comma is still required between members and refused before the end. */
    @Test
    void nextKeyIsPassesOverWhitespaceBeforeTheKey() {
        JsonKey id = JsonKey.of("id");
        JsonKey name = JsonKey.of("name");
        JsonKey description = JsonKey.of("a_rather_long_property_name");
        JsonInput in = JsonInput.of("{\n  \"id\": 1, \"name\": \"a\" ,\n\t\"a_rather_long_property_name\":\r\n2 ,  \"id\":3  }");

        in.object();
        assertThat(in.nextKeyIs(name)).isFalse();
        assertThat(in.nextKeyIs(id)).isTrue();
        assertThat(in.key()).isEqualTo("id");
        assertThat(in.readLong()).isEqualTo(1);
        assertThat(in.nextKeyIs(id)).as("another key after the whitespace").isFalse();
        assertThat(in.nextKeyIs(name)).isTrue();
        assertThat(in.readString()).isEqualTo("a");
        assertThat(in.nextKeyIs(description)).as("whitespace before the comma too").isTrue();
        assertThat(in.keyIs(description)).isTrue();
        assertThat(in.readLong()).isEqualTo(2);
        assertThat(in.nextKeyIs(id)).as("near the end, where only the compact form is compared").isFalse();
        assertThat(in.nextKey()).isTrue();
        assertThat(in.keyIs(id)).isTrue();
        assertThat(in.readLong()).isEqualTo(3);
        assertThat(in.nextKeyIs(id)).as("the end of the object").isFalse();
        assertThat(in.nextKey()).isFalse();
        in.end();

        JsonInput missingComma = JsonInput.of("{\"id\":1 \"name\":2, \"padding to sixteen bytes\":0}");
        missingComma.object();
        assertThat(missingComma.nextKeyIs(id)).isTrue();
        assertThat(missingComma.readLong()).isEqualTo(1);
        assertThat(missingComma.nextKeyIs(name)).isFalse();
        assertThatThrownBy(missingComma::nextKey).isInstanceOf(JsonException.class);

        JsonInput trailingComma = JsonInput.of("{\"id\":1, \"name\":2,                   }");
        trailingComma.object();
        assertThat(trailingComma.nextKeyIs(id)).isTrue();
        trailingComma.readLong();
        assertThat(trailingComma.nextKeyIs(name)).isTrue();
        trailingComma.readLong();
        assertThat(trailingComma.nextKeyIs(id)).isFalse();
        assertThatThrownBy(trailingComma::nextKey).isInstanceOf(JsonException.class);

        JsonInput spaceInKey = JsonInput.of("{\" id\":1,                        \"id\":2}");
        spaceInKey.object();
        assertThat(spaceInKey.nextKeyIs(id)).as("a key that starts with a space is another key").isFalse();
        assertThat(spaceInKey.nextKey()).isTrue();
        assertThat(spaceInKey.key()).isEqualTo(" id");
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
        assertThat(string.fromJson("\"" + "x".repeat(200) + "한글\"")).isEqualTo("x".repeat(200) + "한글");
        assertThat(string.fromJson("\"" + "한".repeat(200) + "\"")).isEqualTo("한".repeat(200));
    }

    /** Strings of every mix, written by JsonOutput and read back three ways: as a string, skipped, and as a tree. */
    @Test
    void stringsOfEveryShapeReadBackAsTheyWereWritten() {
        Random random = new Random(76);
        String[] pieces = {"\"", "\\", "/", "\n", "\u0001", "\u001f", " ", "\u007f", "\u00e9", "\u00ff", "\u0100", "한", "😀"};
        for (int i = 0; i < 3000; i++) {
            StringBuilder text = new StringBuilder();
            int length = random.nextInt(i % 10 == 0 ? 300 : 40);
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
                {'"', (byte) 0xE4, (byte) 0xB8, '"'}, // three bytes cut short by the quote
                {'"', (byte) 0xE4, 'A', 'A', '"'}, // three bytes with no continuation
                {'"', 'a', 'b', (byte) 0xE4, (byte) 0xB8}, // three bytes cut short by the end of the input
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
        assertThatThrownBy(() -> JsonInput.of(new byte[] {'"', (byte) 0xEA, (byte) 0xB0, (byte) 0x80}).readString())
                .as("no closing quote")
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Unexpected end of input");
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
        assertThat(asDouble.fromJson("-0")).isEqualTo(-0.0);
        assertThatThrownBy(() -> asDouble.fromJson("1e400")).isInstanceOf(JsonException.class)
                .hasMessageContaining("Number out of range");

        JsonReader<Float> asFloat = JsonInput::readFloat;
        assertThat(asFloat.fromJson("-0")).isEqualTo(-0.0f);
        assertThat(asFloat.fromJson("1.00000017881393432617187499")).isEqualTo(1.0000001f);
        assertThat(asFloat.fromJson("1e-50")).isEqualTo(0.0f);
        assertThatThrownBy(() -> asFloat.fromJson("1e39")).isInstanceOf(JsonException.class)
                .hasMessageContaining("Near character 4: Number out of range: 1e39");
        assertThatThrownBy(() -> asFloat.fromJson("true")).isInstanceOf(JsonException.class)
                .hasMessageContaining("Not a JSON number: true");
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

    /**
     * Integers of 1 to 19 digits, either sign, with a leading zero or ending in a
     * fraction or an exponent, at every distance from the end of the input:
     * each read as {@link Long#parseLong} or {@link BigDecimal} reads
     * the same text, or refused where the grammar refuses it.
     */
    @Test
    void readsRandomIntegersAsTheJdkDoes() {
        Random random = new Random(19);
        JsonReader<List<Long>> longs = JsonReader.list(JsonInput::readLong);
        for (int n = 0; n < 20_000; n++) {
            StringBuilder digits = new StringBuilder();
            int length = 1 + random.nextInt(19);
            digits.append(random.nextInt(8) == 0 ? 0 : 1 + random.nextInt(9));
            for (int d = 1; d < length; d++) {
                digits.append(random.nextInt(10));
            }
            String number = (random.nextBoolean() ? "-" : "") + digits;
            String tail = switch (random.nextInt(6)) {
                case 0 -> ".0";
                case 1 -> ".5";
                case 2 -> "e0";
                case 3 -> "E1";
                default -> "";
            };
            String text = "[" + number + tail + "," + "1".repeat(random.nextInt(15)) + "1]";
            boolean leadingZero = digits.length() > 1 && digits.charAt(0) == '0';
            BigDecimal exact = leadingZero ? null : new BigDecimal(number + tail);
            boolean integral = exact != null && exact.stripTrailingZeros().scale() <= 0
                    && exact.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0
                    && exact.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) >= 0;
            if (integral) {
                assertThat(longs.fromJson(text).get(0)).as(text).isEqualTo(exact.longValueExact());
            } else {
                assertThatThrownBy(() -> longs.fromJson(text)).as(text).isInstanceOf(JsonException.class);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"01", "+1", ".5", "1.", "1e", "-", "1e+", "0x10", "NaN", "- 1"})
    void aNumberOutsideTheGrammarIsRefusedWhetherReadOrSkipped(String text) {
        JsonReader<Long> asLong = JsonInput::readLong;
        JsonReader<Double> asDouble = JsonInput::readDouble;

        assertThatThrownBy(() -> asLong.fromJson(text)).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> asDouble.fromJson(text)).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> ((JsonReader<Float>) JsonInput::readFloat).fromJson(text))
                .isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> JsonInput.of("[" + text + "]").skipValue()).isInstanceOf(JsonException.class);
    }

    /** A double and a float read from the digits are the ones the JDK reads from the text, to the bit, and so is the sign of a zero. */
    @Test
    void readsDoublesAndFloatsAsTheJdkDoes() {
        List<String> numbers = new ArrayList<>(List.of(
                "0", "-0", "0.0", "-0.0", "0e5", "-0E-5", "0.000", "1", "-1", "7e0", "1.5", "-1.5E-2", "19.99",
                "37.566535", "0.25", "0.000123", "-0.000123", "1.2300", "1.23000000000000000000000000",
                "100", "1e2", "1E+2", "1e-2", "123456789012345678", "999999999999999999",
                "9007199254740992", "9007199254740993", "9007199254740991.5", "18014398509481985",
                "9223372036854775807", "9223372036854775808", "9999999999999999999", "10000000000000000000",
                "12345678901234567890", "1234567890123456789.0", "0.1234567890123456789", "0.12345678901234567890",
                "1e22", "1e23", "9e22", "1.7976931348623157e308", "1.7976931348623158e308", "1.7976931348623159e308",
                "2.2250738585072014e-308", "2.2250738585072011e-308", "2.2250738585072012e-308",
                "4.9e-324", "5e-324", "2.4703282292062327e-324", "2.4703282292062328e-324", "1e-323",
                "3.4028235e38", "3.4028236e38", "3.40282356779733661637539395458142568448e38",
                "1.17549435e-38", "1.4e-45", "7.006492321624085e-46", "7.006492321624086e-46",
                "0.1", "0.2", "0.3", "0.30000000000000004", "2.5", "3.5", "1.00000017881393432617187499",
                "1.00000017881393432617187501", "1.000000178813934326171875", "9007199254740993.0000000000001",
                "4503599627370497.5", "4503599627370496.5", "1e-342", "1e-343", "1e308", "1e-325",
                "1" + "0".repeat(30) + "e-30", "0." + "0".repeat(30) + "1e30", "1e0000000000001"));
        Random random = new Random(20261010);
        for (int i = 0; i < 20_000; i++) {
            double d = Double.longBitsToDouble(random.nextLong());
            if (Double.isFinite(d)) {
                numbers.add(Double.toString(d));
                numbers.add(new BigDecimal(d).round(new MathContext(1 + random.nextInt(25))).toString());
                numbers.add(String.format(Locale.ROOT, "%." + random.nextInt(20) + "e", d));
            }
            double plain = random.nextInt(10_000_000) / Math.pow(10, random.nextInt(9));
            numbers.add(Double.toString(plain));
            numbers.add(new BigDecimal(plain).setScale(random.nextInt(12), RoundingMode.HALF_EVEN).toPlainString());
            numbers.add(Float.toString(Float.intBitsToFloat(random.nextInt())));
            StringBuilder digits = new StringBuilder().append(1 + random.nextInt(9));
            for (int n = random.nextInt(24); n > 0; n--) {
                digits.append(random.nextInt(10));
            }
            numbers.add(digits.insert(1 + random.nextInt(digits.length()), '.').append('e')
                    .append(random.nextInt(700) - 350).toString().replace(".e", "e"));
        }

        for (String text : numbers) {
            if (text.contains("Infinity") || text.contains("NaN")) {
                continue;
            }
            double expectedDouble = Double.parseDouble(text);
            if (Double.isFinite(expectedDouble)) {
                assertThat(Double.doubleToRawLongBits(JsonInput.of(text).readDouble())).as(text)
                        .isEqualTo(Double.doubleToRawLongBits(expectedDouble));
                assertThat(Json.parse(text).asDouble()).as(text).isEqualTo(expectedDouble);
            } else {
                assertThatThrownBy(() -> JsonInput.of(text).readDouble()).as(text)
                        .hasMessageContaining("Number out of range: " + shortened(text));
            }
            float expectedFloat = Float.parseFloat(text);
            if (Float.isFinite(expectedFloat)) {
                assertThat(Float.floatToRawIntBits(JsonInput.of(text).readFloat())).as(text)
                        .isEqualTo(Float.floatToRawIntBits(expectedFloat));
            } else {
                assertThatThrownBy(() -> JsonInput.of(text).readFloat()).as(text)
                        .hasMessageContaining("Number out of range: " + shortened(text));
            }
        }
    }

    /** A token as a rejection quotes it: its first 32 characters and an ellipsis, when it is longer. */
    private static String shortened(String text) {
        return text.length() <= 32 ? text : text.substring(0, 32) + "...";
    }

    /** A number read where it stands: a word of input after it, or none, and the input moved past it either way. */
    @Test
    void readsADecimalWhereverItStands() {
        for (String number : List.of("0", "-0", "1.5", "37.566535", "-0.000123", "1e5", "1.0E-7", "123456789.125",
                "0.30000000000000004", "3.4028235e38", "12345678901234567890.5")) {
            double expected = Double.parseDouble(number);
            for (int pad = 0; pad < 10; pad++) {
                String text = "[" + number + " ".repeat(pad) + "," + number + "]";
                JsonInput in = JsonInput.of(text);
                in.array();
                assertThat(in.nextElement()).isTrue();
                assertThat(in.readDouble()).as(text).isEqualTo(expected);
                assertThat(in.nextElement()).isTrue();
                assertThat(in.readFloat()).as(text).isEqualTo(Float.parseFloat(number));
                assertThat(in.nextElement()).isFalse();
                in.end();
            }
        }
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

    /** The bytes of a value are the ones it is written in, from its first byte to its last, and reading goes on after it. */
    @Test
    void readValueBytesTakesAValueAsItIsWritten() {
        List<String> values = List.of("{\"a\" : [1, {\"b\":\"c\"}] }", "[ ]", "\"say \\\"hi\\\" \\u00e9 한글\"", "-12.5e+3",
                "0", "true", "false", "null");
        JsonInput in = JsonInput.of(" [ " + String.join(" ,\n ", values) + " ] ");
        in.array();
        for (String value : values) {
            assertThat(in.nextElement()).isTrue();
            assertThat(new String(in.readValueBytes(), StandardCharsets.UTF_8)).isEqualTo(value);
        }
        assertThat(in.nextElement()).isFalse();
        in.end();

        JsonInput last = JsonInput.of("{\"id\":1,\"tags\": [\"a\"] \n}");
        last.object();
        assertThat(last.nextKey()).isTrue();
        last.skipValue();
        assertThat(last.nextKey()).isTrue();
        assertThat(last.readValueBytes()).isEqualTo("[\"a\"]".getBytes(StandardCharsets.UTF_8));
        assertThat(last.nextKey()).isFalse();
        last.end();
    }

    /** A value whose bytes are taken is checked as one skipped is, and a failure names its place in the whole document. */
    @Test
    void readValueBytesRefusesWhatSkipValueRefuses() {
        for (String bad : List.of("\"\\x\"", "\"a\nb\"", "tru", "[1,]", "{\"a\" 1}", "01", "{\"a\":")) {
            assertThatThrownBy(() -> {
                JsonInput in = JsonInput.of("[" + bad + "]");
                in.array();
                in.nextElement();
                in.readValueBytes();
            }).as(bad).isInstanceOf(JsonException.class);
        }
        assertThatThrownBy(() -> {
            JsonInput in = JsonInput.of("[1, {\"a\":tru}]");
            in.array();
            in.nextElement();
            in.skipValue();
            in.nextElement();
            in.readValueBytes();
        }).hasMessageStartingWith("Near character 9:");
        assertThatThrownBy(() -> JsonInput.of("   ").readValueBytes()).hasMessageContaining("Missing value");
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

    /** A rejected number is quoted by its head, since the message may go back to the client in a 400. */
    @Test
    void aRejectedLongNumberIsQuotedShortened() {
        JsonReader<Long> asLong = JsonInput::readLong;
        String integer = "9".repeat(100_000);
        String huge = "1" + "0".repeat(400);

        assertThatThrownBy(() -> asLong.fromJson(integer))
                .hasMessageContaining("Not a JSON integer: " + "9".repeat(32) + "...")
                .satisfies(e -> assertThat(e.getMessage()).hasSizeLessThan(100));
        assertThatThrownBy(() -> JsonInput.of(huge).readDouble())
                .hasMessageContaining("Number out of range: " + "1" + "0".repeat(31) + "...");
        assertThatThrownBy(() -> JsonInput.of(huge).readFloat())
                .satisfies(e -> assertThat(e.getMessage()).hasSizeLessThan(100));
        assertThatThrownBy(() -> Json.parse(huge))
                .satisfies(e -> assertThat(e.getMessage()).hasSizeLessThan(100));
    }

    /** A surrogate without its pair has no UTF-8: text holding one is refused, as a malformed sequence in bytes is. */
    @Test
    void aLoneSurrogateInTextIsRefused() {
        assertThatThrownBy(() -> JsonInput.of("[\"a\uD800\"]"))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Unpaired surrogate U+D800 at character 3");
        assertThatThrownBy(() -> Json.parse("{\"k\":\"x\uDE00?\"}"))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("U+DE00");
        assertThatThrownBy(() -> Json.parse("\"" + "a".repeat(20) + "\uD83D\""))
                .isInstanceOf(JsonException.class);

        assertThat(Json.parse("\"why?\"").asString()).isEqualTo("why?");
        assertThat(Json.parse("\"\uD83D\uDE00?\"").asString()).isEqualTo("\uD83D\uDE00?");
        // an escape is JSON's way to write one
        assertThat(Json.parse("\"\\ud800\"").asString()).isEqualTo("\uD800");
    }

    /** A key whose name holds a lone surrogate matches the key written with its escape, never one holding a question mark. */
    @Test
    void aKeyWithALoneSurrogateMatchesOnlyItsEscape() {
        JsonKey key = JsonKey.of("a\uD800");

        JsonInput question = JsonInput.of("{\"a?\":1}");
        question.object();
        question.nextKey();
        assertThat(question.keyIs(key)).isFalse();

        JsonInput escaped = JsonInput.of("{\"a\\ud800\":1}");
        escaped.object();
        escaped.nextKey();
        assertThat(escaped.keyIs(key)).isTrue();

        byte[] written = JsonOutput.inMemory().object().put(key, 1L).end().toBytes();
        JsonInput generated = JsonInput.of(written);
        generated.object();
        assertThat(generated.nextKeyIs(key)).isTrue();
        assertThat(generated.readLong()).isEqualTo(1L);
    }
}
