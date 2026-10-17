package net.benelog.spidersilk.jackson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import tools.jackson.core.Base64Variants;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.exc.StreamWriteException;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import net.benelog.silkjson.JsonCodec;
import net.benelog.silkjson.JsonException;
import net.benelog.silkjson.JsonReader;
import net.benelog.silkjson.JsonWriter;
import net.benelog.spidersilk.App;
import net.benelog.spidersilk.WebResponse;
import net.benelog.spidersilk.test.WebTest;

/** What a {@link JacksonCodecs} writer and reader promise where core takes a {@code JsonWriter} and a {@code JsonReader}. */
class JacksonCodecsTest {

    record Deck(long id, String name) {
    }

    record NewDeck(String name) {
        NewDeck {
            if (name.isBlank()) {
                throw new IllegalArgumentException("A deck needs a name");
            }
        }
    }

    record Named(String name) {
        Named {
            Objects.requireNonNull(name, "name");
        }
    }

    enum Level { EASY, HARD }

    // The arrays are the point: byte[] goes out through writeBinary, and int[] through Jackson's array serializer.
    @SuppressWarnings("ArrayRecordComponent")
    record Everything(String text, int count, long big, double ratio, float weight, BigDecimal price,
            BigInteger huge, boolean flag, String missing, List<String> tags, Map<String, Integer> scores,
            Optional<String> nickname, byte[] blob, LocalDate day, Level level, Deck deck, int[] numbers) {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({@JsonSubTypes.Type(value = Circle.class, name = "circle")})
    interface Shape {
    }

    record Circle(double radius) implements Shape {
    }

    interface Unmapped {
    }

    record Fragment(@JsonRawValue String json) {
    }

    static final JacksonCodecs JACKSON = new JacksonCodecs();

    static final JsonWriter<Deck> DECK = JACKSON.writer(Deck.class);
    static final JsonReader<NewDeck> NEW_DECK = JACKSON.reader(NewDeck.class);

    @Test
    void aBodyIsBoundAndTheResponseWrittenThroughTheMapper() {
        App app = new App().post("/decks", req -> {
            NewDeck body = req.bodyJson(NEW_DECK);
            return WebResponse.json(new Deck(1, body.name()), DECK);
        });

        WebTest.test(app, client -> {
            HttpResponse<String> response = client.postJson("/decks", "{\"name\":\"Kanji\"}");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("{\"id\":1,\"name\":\"Kanji\"}");
            assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
            assertThat(response.headers().firstValue("Content-Length").orElseThrow())
                    .isEqualTo(String.valueOf(response.body().length()));
        });
    }

    /** Every token goes through JsonOutput, and the document is the one Jackson writes on its own. */
    @Test
    void theDocumentIsTheOneJacksonWritesItself() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("first", 1);
        scores.put("second", 2);
        Everything value = new Everything("quote \" backslash \\ tab \t bell \u0007 한글 😀", 42,
                Long.MIN_VALUE, 0.1, 0.1f, new BigDecimal("1E+3"), new BigInteger("123456789012345678901234567890"),
                true, null, List.of("a", "b"), scores, Optional.of("silk"), new byte[] {0, 1, 2, (byte) 0xff},
                LocalDate.of(2026, 10, 13), Level.HARD, new Deck(7, "Verbs"), new int[] {1, 2, 3});
        ObjectMapper mapper = JsonMapper.builder().build();

        String written = JACKSON.writer(Everything.class).toJson(value);

        assertThat(written).isEqualTo(mapper.writeValueAsString(value));
    }

    @Test
    void numbersAsStringsAreWrittenAsJacksonWritesThem() {
        ObjectMapper mapper = JsonMapper.builder().enable(JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS).build();
        Everything value = new Everything("text", 42, Long.MIN_VALUE, 0.1, 0.1f, new BigDecimal("1E+3"),
                new BigInteger("123456789012345678901234567890"), true, null, List.of(), Map.of("one", 1),
                Optional.empty(), new byte[0], LocalDate.of(2026, 10, 13), Level.EASY, new Deck(7, "Verbs"),
                new int[] {1, 2, 3});

        String written = new JacksonCodecs(mapper).writer(Everything.class).toJson(value);

        assertThat(written).isEqualTo(mapper.writeValueAsString(value));
        assertThat(written).contains("\"count\":\"42\"", "\"price\":\"1E+3\"");
    }

    @Test
    void nanAndInfinityAreWrittenAsTheStringsJacksonWrites() {
        record Ratios(double nan, double up, double down, float floatNan) {
        }
        Ratios value = new Ratios(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Float.NaN);

        String written = JACKSON.writer(Ratios.class).toJson(value);

        assertThat(written).isEqualTo(JsonMapper.builder().build().writeValueAsString(value));
        assertThat(written).isEqualTo("{\"nan\":\"NaN\",\"up\":\"Infinity\",\"down\":\"-Infinity\",\"floatNan\":\"NaN\"}");
    }

    /** The output writes every string as UTF-8, so a mapper that asks for ASCII-only output is refused rather than ignored. */
    @Test
    void aMapperThatEscapesNonAsciiIsRefused() {
        ObjectMapper mapper = JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build();

        assertThatThrownBy(() -> new JacksonCodecs(mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ESCAPE_NON_ASCII");
    }

    /** With the feature off Jackson writes a bare NaN, which is not JSON; the output refuses it. */
    @Test
    void nanIsRefusedWhenItIsNotToBeAString() {
        JacksonCodecs bare = new JacksonCodecs(JsonMapper.builder().disable(JsonWriteFeature.WRITE_NAN_AS_STRINGS).build());
        record Ratio(double value) {
        }

        assertThatThrownBy(() -> bare.writer(Ratio.class).toJson(new Ratio(Double.NaN)))
                .isInstanceOf(StreamWriteException.class)
                .hasMessageContaining("WRITE_NAN_AS_STRINGS");
    }

    @Test
    void aPlainDecimalIsWrittenPlainWithinJacksonsScaleLimit() {
        ObjectMapper mapper = JsonMapper.builder().enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN).build();
        JsonWriter<BigDecimal> plain = new JacksonCodecs(mapper).writer(BigDecimal.class);

        for (String text : List.of("0.0000001", "-1.5E-12", "1E+3", "0.10", "1E+9999", "1E-9999")) {
            BigDecimal value = new BigDecimal(text);
            assertThat(plain.toJson(value)).isEqualTo(mapper.writeValueAsString(value));
        }
        assertThat(plain.toJson(new BigDecimal("0.0000001"))).isEqualTo("0.0000001");
        for (String text : List.of("1E+10000", "1E-10000", "1e999999999")) {
            BigDecimal value = new BigDecimal(text);
            assertThatThrownBy(() -> mapper.writeValueAsString(value)).isInstanceOf(StreamWriteException.class);
            assertThatThrownBy(() -> plain.toJson(value))
                    .isInstanceOf(StreamWriteException.class)
                    .hasMessageContaining("illegal scale");
        }
    }

    /** A MIME or PEM variant breaks its lines with a line feed inside the string, which decodes back to the bytes. */
    @Test
    void base64WithLineBreaksIsWrittenAsJacksonWritesIt() {
        byte[] bytes = new byte[200];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        for (var variant : List.of(Base64Variants.MIME, Base64Variants.PEM)) {
            ObjectMapper mapper = JsonMapper.builder().defaultBase64Variant(variant).build();
            JsonCodec<byte[]> codec = new JacksonCodecs(mapper).codec(byte[].class);

            String written = codec.toJson(bytes);

            assertThat(written).isEqualTo(mapper.writeValueAsString(bytes));
            assertThat(written).contains("\\n").doesNotContain("\\\\n");
            assertThat(codec.fromJson(written)).isEqualTo(bytes);
        }
    }

    @Test
    void theMapperHandedInIsTheOneThatBinds() {
        JacksonCodecs snakeCase = new JacksonCodecs(JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build());
        record Price(String itemName, BigDecimal amount) {
        }

        String written = snakeCase.writer(Price.class).toJson(new Price("tea", new BigDecimal("1E+3")));
        Price read = snakeCase.reader(Price.class).fromJson("{\"item_name\":\"tea\",\"amount\":2.5}");

        assertThat(written).isEqualTo("{\"item_name\":\"tea\",\"amount\":1000}");
        assertThat(read).isEqualTo(new Price("tea", new BigDecimal("2.5")));
    }

    @Test
    void theWriterForAnyTypeWritesTheValueAsItIsAtRunTime() {
        JsonNode tree = JsonMapper.builder().build().readTree("{\"a\":[1,true,null,\"x\"]}");

        assertThat(JACKSON.writer().toJson(new Deck(3, "Nouns"))).isEqualTo("{\"id\":3,\"name\":\"Nouns\"}");
        assertThat(JACKSON.writer().toJson(tree)).isEqualTo("{\"a\":[1,true,null,\"x\"]}");
    }

    @Test
    void aDeclaredTypeCarriesItsTypeInformation() {
        JsonCodec<List<Shape>> shapes = JACKSON.codec(new TypeReference<List<Shape>>() { });

        String written = shapes.toJson(List.of(new Circle(1.5)));

        assertThat(written).isEqualTo("[{\"kind\":\"circle\",\"radius\":1.5}]");
        assertThat(shapes.fromJson(written)).containsExactly(new Circle(1.5));
    }

    /** Nested in silk-json's own list writer and reader, a Jackson codec writes and reads one element at a time. */
    @Test
    void aCodecNestsInsideSilkJsonsOwn() {
        JsonCodec<Deck> deck = JACKSON.codec(Deck.class);

        String written = JsonWriter.list(deck).toJson(List.of(new Deck(1, "a"), new Deck(2, "b")));
        List<Deck> read = JsonReader.list(deck).fromJson(" [ {\"id\":1,\"name\":\"a\"} , {\"name\":\"b\",\"id\":2} ] ");

        assertThat(written).isEqualTo("[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"}]");
        assertThat(read).containsExactly(new Deck(1, "a"), new Deck(2, "b"));
    }

    @Test
    void aRawValueIsCheckedAsJson() {
        JsonWriter<Fragment> fragment = JACKSON.writer(Fragment.class);

        assertThat(fragment.toJson(new Fragment("{\"a\": [1, 2]}"))).isEqualTo("{\"json\":{\"a\":[1,2]}}");
        assertThatThrownBy(() -> fragment.toJson(new Fragment("{not json")))
                .hasRootCauseInstanceOf(JsonException.class);
    }

    @Test
    void inputJacksonRejectsIsA400() {
        App app = new App().post("/decks", req -> WebResponse.text(req.bodyJson(NEW_DECK).name()));

        WebTest.test(app, client -> {
            assertThat(client.postJson("/decks", "{\"name\":").statusCode()).isEqualTo(400);
            assertThat(client.postJson("/decks", "{\"name\":[1]}").statusCode()).isEqualTo(400);
            assertThat(client.postJson("/decks", "{\"name\":\"a\"} {}").statusCode()).isEqualTo(400);
            assertThat(client.postJson("/decks", "null").statusCode()).isEqualTo(400);

            HttpResponse<String> blank = client.postJson("/decks", "{\"name\":\" \"}");
            assertThat(blank.statusCode()).isEqualTo(400);
            assertThat(blank.body()).contains("A deck needs a name");
        });
    }

    /** A creator's NullPointerException, such as a record's requireNonNull, is the body leaving a property out. */
    @Test
    void aPropertyTheCreatorNeedsLeftOutIsA400() {
        JsonReader<Named> named = JACKSON.reader(Named.class);
        App app = new App()
                .post("/named", req -> WebResponse.text(req.bodyJson(named).name()))
                .post("/decks", req -> WebResponse.text(req.bodyJson(NEW_DECK).name()));

        WebTest.test(app, client -> {
            assertThat(client.postJson("/named", "{\"name\":\"a\"}").body()).isEqualTo("a");
            assertThat(client.postJson("/named", "{}").statusCode()).isEqualTo(400);
            assertThat(client.postJson("/named", "{\"name\":null}").statusCode()).isEqualTo(400);
            assertThat(client.postJson("/decks", "{}").statusCode()).isEqualTo(400);
        });
    }

    /** Nested in silk-json's list reader, a Jackson reader still answers what it rejects with 400. */
    @Test
    void inputJacksonRejectsInsideSilkJsonsReaderIsA400() {
        JsonReader<List<NewDeck>> decks = JsonReader.list(NEW_DECK);
        App app = new App().post("/decks", req -> WebResponse.text(String.valueOf(req.bodyJson(decks).size())));

        WebTest.test(app, client -> {
            assertThat(client.postJson("/decks", "[{\"name\":\"a\"},{\"name\":\"b\"}]").body()).isEqualTo("2");
            assertThat(client.postJson("/decks", "[{\"name\":\"a\"},{\"name\":[1]}]").statusCode()).isEqualTo(400);
            assertThat(client.postJson("/decks", "[{\"name\":\"a\"},{\"name\":}]").statusCode()).isEqualTo(400);
            assertThat(client.postJson("/decks", "[{\"name\":\"a\"},null]").statusCode()).isEqualTo(400);

            HttpResponse<String> blank = client.postJson("/decks", "[{\"name\":\" \"}]");
            assertThat(blank.statusCode()).isEqualTo(400);
            assertThat(blank.body()).contains("A deck needs a name");
        });
    }

    @Test
    void aMappingJacksonCannotBuildIsA500() {
        JsonReader<Unmapped> unmapped = JACKSON.reader(Unmapped.class);
        App app = new App().post("/unmapped", req -> WebResponse.text(req.bodyJson(unmapped).toString()));

        WebTest.test(app, client ->
                assertThat(client.postJson("/unmapped", "{}").statusCode()).isEqualTo(500));
    }

    @Test
    void ndjsonIsReadAndAnArrayStreamedALineAndAnElementAtATime() {
        App app = new App().post("/decks/import", req -> {
            List<NewDeck> decks = req.bodyNdjson(NEW_DECK).toList();
            return WebResponse.jsonArray(sink -> {
                for (int i = 0; i < decks.size(); i++) {
                    sink.write(new Deck(i + 1, decks.get(i).name()), DECK);
                }
            });
        });

        WebTest.test(app, client -> {
            HttpResponse<String> response = client.post("/decks/import", "{\"name\":\"a\"}\n{\"name\":\"b\"}\n");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\"}]");
        });
    }
}
