package net.benelog.spidersilk.json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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

    /** A missing element is a JsonException, as a missing key is, and not the List's own exception. */
    @Test
    void anIndexOutsideTheArrayIsAJsonException() {
        JsonArray array = Json.array().add("only");

        assertThat(array.get(0).asString()).isEqualTo("only");
        assertThatThrownBy(() -> array.get(1))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Index 1");
        assertThatThrownBy(() -> array.get(-1)).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.array().get(0)).isInstanceOf(JsonException.class);
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

    /** Every form RFC 8259 section 6 allows: signed, fractional, exponent, whole. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "0                    | 0.0",
            "-0                   | 0.0",
            "7                    | 7.0",
            "-7                   | -7.0",
            "120                  | 120.0",
            "0.5                  | 0.5",
            "-0.5                 | -0.5",
            "10.25                | 10.25",
            "1e2                  | 100.0",
            "1E2                  | 100.0",
            "1e+2                 | 100.0",
            "1e-2                 | 0.01",
            "-1.5E-2              | -0.015",
            "0e0                  | 0.0",
            "0.0e+00              | 0.0",
            "123456789012345678   | 123456789012345678.0",
            "9223372036854775807  | 9223372036854775807.0",
            "-9223372036854775808 | -9223372036854775808.0",
    })
    void acceptsEveryNumberTheGrammarAllows(String text, double expected) {
        assertThat(Json.parse(text).asDouble()).isEqualTo(expected);
        assertThat(Json.parse("[" + text + "]").asArray().get(0).asDouble()).isEqualTo(expected);
        assertThat(Json.parse("{\"n\":" + text + "}").asObject().getDouble("n")).isEqualTo(expected);
    }

    /** An integer reads exactly whether it is scanned in place or handed to Long.parseLong. */
    @Test
    void readsAnIntegerExactlyOnEitherSideOfTheScannedFastPath() {
        assertThat(Json.parse("999999999999999999").asLong()).isEqualTo(999_999_999_999_999_999L);
        assertThat(Json.parse("-999999999999999999").asLong()).isEqualTo(-999_999_999_999_999_999L);
        assertThat(Json.parse("1000000000000000000").asLong()).isEqualTo(1_000_000_000_000_000_000L);
        assertThat(Json.parse("9223372036854775807").asLong()).isEqualTo(Long.MAX_VALUE);
        assertThat(Json.parse("-9223372036854775808").asLong()).isEqualTo(Long.MIN_VALUE);
        assertThat(Json.parse("-0").asLong()).isZero();
        assertThat(Json.parse("[0,-12,345]").toJson()).isEqualTo("[0,-12,345]");

    }

    /**
     * An integer past the range of a long is still a number, read as the
     * nearest double. Only asLong refuses it, as it refuses 1e19.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "9223372036854775808   | 9.223372036854775808E18",
            "-9223372036854775809  | -9.223372036854775809E18",
            "12345678901234567890  | 1.2345678901234567E19",
            "123456789012345678901234567890 | 1.2345678901234568E29",
    })
    void anIntegerPastTheRangeOfALongIsANumberThatAsLongRefuses(String text, double expected) {
        JsonValue value = Json.parse(text);

        assertThat(value.isNumber()).isTrue();
        assertThat(value.asDouble()).isEqualTo(expected);
        assertThatThrownBy(value::asLong)
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Not a JSON integer: " + text);
        assertThat(Json.parse("{\"n\":" + text + "}").asObject().getDouble("n")).isEqualTo(expected);
    }

    /**
     * Forms outside RFC 8259's number grammar fail as a syntax error, several
     * of which Long.parseLong or Double.parseDouble would read, whether the
     * number stands alone, sits in an array, or is an object member.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "01", "-01", "00", "00.5", "0123", "-00",
            "+1", "+0", "+1.5",
            ".5", "-.5", ".e1",
            "1.", "-1.", "1.e5", "1.E5",
            "1e", "1E", "1e+", "1e-", "1.5e", "-1e+", "1e++1",
            "-", "--1", "-+1", "- 1",
            "1e5.5", "1.5.5", "0x10", "1_000", "1d", "1f", "1L",
            "NaN", "Infinity", "-Infinity",
            "\uff11", "\u0661", "-\uff11",
    })
    void rejectsANumberOutsideTheGrammar(String text) {
        assertThatThrownBy(() -> Json.parse(text)).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.parse("[" + text + "]")).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.parse("{\"n\":" + text + "}")).isInstanceOf(JsonException.class);
    }

    @Test
    void aMalformedNumberNamesWhatIsWrong() {
        assertThatThrownBy(() -> Json.parse("{\"id\":01}"))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Leading zero");
        assertThatThrownBy(() -> Json.parse("1."))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Expected a digit after the decimal point");
        assertThatThrownBy(() -> Json.parse("1e+"))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Expected a digit in the exponent");
    }

    /**
     * Every character below U+0020 is written as an escape inside a string,
     * and is rejected written as itself: in a string with no escape, which
     * the fast path scans, after an escape, where the slow path copies, and
     * in a key.
     */
    @Test
    void rejectsAnUnescapedControlCharacterInEitherStringPath() {
        for (char c = 0; c < 0x20; c++) {
            String raw = String.valueOf(c);
            assertThatThrownBy(() -> Json.parse("\"a" + raw + "b\""))
                    .isInstanceOf(JsonException.class)
                    .hasMessageContaining("Unescaped control character");
            assertThatThrownBy(() -> Json.parse("\"a\\n" + raw + "b\""))
                    .isInstanceOf(JsonException.class)
                    .hasMessageContaining("Unescaped control character");
            assertThatThrownBy(() -> Json.parse("{\"k" + raw + "\":1}"))
                    .isInstanceOf(JsonException.class)
                    .hasMessageContaining("Unescaped control character");
        }
        assertThatThrownBy(() -> Json.parse("\"a\nb\""))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("U+000A");

        assertThat(Json.parse("\"a\u007fb\"").asString()).isEqualTo("a\u007fb");
        assertThat(Json.parse("\"a b\"").asString()).isEqualTo("a b");
    }

    /** The same characters are accepted written as escapes, short and backslash-u alike. */
    @Test
    void acceptsEscapedControlCharacters() {
        assertThat(Json.parse("\"a\\nb\\tc\\rd\\be\\ff\"").asString())
                .isEqualTo("a\nb\tc\rd\be\ff");
        assertThat(Json.parse("\"\\u0000\\u001f\\u001F\"").asString())
                .isEqualTo("\u0000\u001f\u001f");

        StringBuilder controls = new StringBuilder();
        for (char c = 0; c < 0x20; c++) {
            controls.append(c);
        }
        String written = Json.array().add(controls.toString()).toJson();
        assertThat(Json.parse(written).asArray().get(0).asString()).isEqualTo(controls.toString());
    }

    /** JSON whitespace is space, tab, line feed, and carriage return, and nothing else. */
    @Test
    void whitespaceIsOnlyTheFourCharactersTheGrammarNames() {
        JsonObject object = Json.parse(" \t\r\n{ \t\r\n\"a\" \t\r\n: \t\r\n[ 1 , 2 ] \t\r\n} \t\r\n")
                .asObject();
        assertThat(object.get("a").asArray().size()).isEqualTo(2);

        for (String other : new String[] {"\f", "\u000b", "\u001c", "\u001f", "\u00a0", "\u2028", "\u3000"}) {
            assertThatThrownBy(() -> Json.parse(other + "1")).isInstanceOf(JsonException.class);
            assertThatThrownBy(() -> Json.parse("1" + other)).isInstanceOf(JsonException.class);
            assertThatThrownBy(() -> Json.parse("[1," + other + "2]")).isInstanceOf(JsonException.class);
        }
    }

    /** A backslash-u escape takes ASCII hex digits only, in either case. */
    @Test
    void aUnicodeEscapeTakesAsciiHexDigitsOnly() {
        assertThat(Json.parse("\"\\uABCD\\uabcd\"").asString()).isEqualTo("\uabcd\uabcd");
        assertThatThrownBy(() -> Json.parse("\"\\u\uff10041\""))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Expected 4 hex digits");
        assertThatThrownBy(() -> Json.parse("\"\\u\u0660041\""))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Expected 4 hex digits");
        assertThatThrownBy(() -> Json.parse("\"\\u004G\""))
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
     * A decimal or exponent token is converted to a long from its text, not
     * from the nearest double, which has already lost the digits that decide
     * whether it is whole and which whole number it is.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "9007199254740993.0     | 9007199254740993",
            "9007199254740993e0     | 9007199254740993",
            "9.007199254740993e15   | 9007199254740993",
            "9007199254740992.0     | 9007199254740992",
            "9007199254740995.00    | 9007199254740995",
            "-9007199254740993.0    | -9007199254740993",
            "9223372036854775807.0  | 9223372036854775807",
            "9.223372036854775807e18 | 9223372036854775807",
            "-9223372036854775808.0 | -9223372036854775808",
            "-9.223372036854775808E18 | -9223372036854775808",
            "2.0                    | 2",
            "1e3                    | 1000",
            "1E+3                   | 1000",
            "12.5e1                 | 125",
            "100e-2                 | 1",
            "-0.0                   | 0",
            "0e-5                   | 0",
            "0.0e99999999999        | 0",
            "0e-99999999999         | 0",
    })
    void asLongConvertsADecimalTokenExactly(String text, long expected) {
        assertThat(Json.parse(text).asLong()).isEqualTo(expected);
        assertThat(Json.parse("{\"n\":" + text + "}").asObject().getLong("n")).isEqualTo(expected);
    }

    /** A fraction that rounds to a whole double, and a value past either end of long. */
    @ParameterizedTest
    @ValueSource(strings = {
            "1.0000000000000001",
            "0.99999999999999999",
            "9007199254740992.5",
            "9007199254740993.1",
            "-9007199254740992.5",
            "4503599627370496.5",
            "125e-1",
            "1e-400",
            "1e-99999999999",
            "9223372036854775808.0",
            "9.223372036854775808e18",
            "-9223372036854775809.0",
            "-9.223372036854775809e18",
            "9.3e18",
            "1e19",
            "-1e19",
    })
    void asLongRejectsADecimalTokenThatIsNotALong(String text) {
        JsonValue value = Json.parse(text);
        assertThatThrownBy(value::asLong)
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Not a JSON integer: " + text);
        assertThatThrownBy(() -> Json.parse("{\"n\":" + text + "}").asObject().getLong("n", 0))
                .isInstanceOf(JsonException.class);
    }

    /**
     * A mantissa of a million digits fits in a 1MB body. It is refused from
     * its digit count, where converting it to a BigDecimal took seconds.
     */
    @Test
    @Timeout(2)
    void asLongRefusesALongMantissaWithoutConvertingIt() {
        String fraction = "1." + "3".repeat(1_000_000);
        assertThatThrownBy(() -> Json.parse(fraction).asLong())
                .isInstanceOf(JsonException.class)
                .hasMessageStartingWith("Not a JSON integer: 1.333");
        String whole = "4" + "7".repeat(999_999) + ".0";
        assertThatThrownBy(() -> Json.parse(whole).asLong())
                .isInstanceOf(JsonException.class);
    }

    /** Zeros around the significant digits cost nothing, however many there are. */
    @Test
    @Timeout(2)
    void asLongReadsAWholeNumberAmongManyZeros() {
        assertThat(Json.parse("1." + "0".repeat(1_000_000)).asLong()).isEqualTo(1L);
        assertThat(Json.parse("0." + "0".repeat(999_990) + "5e999991").asLong())
                .isEqualTo(5L);
        assertThat(Json.parse("-12" + "0".repeat(17) + ".000").asLong())
                .isEqualTo(-1_200_000_000_000_000_000L);
    }

    /**
     * The parser accepts an escaped lone surrogate, and writing it back as
     * itself put a character UTF-8 cannot encode on the wire, where it became
     * a question mark. It is escaped again instead, and a valid pair is not.
     */
    @Test
    void aLoneSurrogateIsWrittenEscapedAndAPairAsItIs() {
        JsonValue parsed = Json.parse("{\"s\":\"a\\ud800b\"}");

        assertThat(parsed.toJson()).isEqualTo("{\"s\":\"a\\ud800b\"}");
        assertThat(Json.parse(parsed.toJson()).asObject().getString("s")).isEqualTo("a\ud800b");
        assertThat(Json.array().add("\udc00").toJson()).isEqualTo("[\"\\udc00\"]");
        assertThat(Json.array().add("smile \uD83D\uDE00").toJson()).isEqualTo("[\"smile \uD83D\uDE00\"]");
        assertThat(Json.array().add("\uDE00\uD83D").toJson()).isEqualTo("[\"\\ude00\\ud83d\"]");
    }

    /** asDouble stays the nearest double, and a decimal serializes as it did before. */
    @Test
    void aDecimalTokenStillReadsAndWritesAsADouble() {
        assertThat(Json.parse("9007199254740993.0").asDouble()).isEqualTo(9007199254740992.0);
        assertThat(Json.parse("1.0000000000000001").asDouble()).isEqualTo(1.0);
        assertThat(Json.parse("1e3").toJson()).isEqualTo("1000.0");
        assertThat(Json.parse("[2.5,-0.5]").toJson()).isEqualTo("[2.5,-0.5]");
        assertThat(Json.parse("2.5").isNumber()).isTrue();
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
