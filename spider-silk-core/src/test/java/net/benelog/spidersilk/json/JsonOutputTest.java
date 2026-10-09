package net.benelog.spidersilk.json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The streaming writer: containers, members, values, and the bytes they become. */
class JsonOutputTest {

    record Deck(long id, String name, List<String> tags) {
    }

    static final JsonKey ID = JsonKey.of("id");
    static final JsonKey NAME = JsonKey.of("name");

    static final JsonWriter<Deck> DECK = (deck, out) -> out.object()
            .put(ID, deck.id())
            .put(NAME, deck.name())
            .array("tags").values(deck.tags()).end()
            .end();

    @Test
    void writesAnObjectWithEveryKindOfMember() {
        String json = JsonOutput.inMemory().object()
                .put("s", "text")
                .put("n", 42L)
                .put("d", 1.5)
                .put("b", true)
                .putNull("z")
                .put("t", Json.array().add(1L))
                .put("w", new Deck(7, "French", List.of("a", "b")), DECK)
                .put("x", (String) null)
                .put("missing", null, DECK)
                .end()
                .toJson();

        assertThat(json).isEqualTo("{\"s\":\"text\",\"n\":42,\"d\":1.5,\"b\":true,\"z\":null,\"t\":[1],"
                + "\"w\":{\"id\":7,\"name\":\"French\",\"tags\":[\"a\",\"b\"]},\"x\":null,\"missing\":null}");
    }

    @Test
    void writesAnArrayOfEveryKindOfValue() {
        String json = JsonOutput.inMemory().array()
                .value("text").value(-3L).value(0.25).value(false).nullValue()
                .value(Json.object().put("k", "v"))
                .value(new Deck(1, "a", List.of()), DECK)
                .object().put("inner", 1L).end()
                .array().end()
                .end()
                .toJson();

        assertThat(json).isEqualTo("[\"text\",-3,0.25,false,null,{\"k\":\"v\"},"
                + "{\"id\":1,\"name\":\"a\",\"tags\":[]},{\"inner\":1},[]]");
    }

    @Test
    void aWriterAnswersTextAndBytes() {
        Deck deck = new Deck(1, "English", List.of("toeic"));

        assertThat(DECK.toJson(deck)).isEqualTo("{\"id\":1,\"name\":\"English\",\"tags\":[\"toeic\"]}");
        assertThat(DECK.toJsonBytes(deck)).isEqualTo(DECK.toJson(deck).getBytes(StandardCharsets.UTF_8));
        assertThat(JsonWriter.list(DECK).toJson(List.of(deck, deck)))
                .isEqualTo("[" + DECK.toJson(deck) + "," + DECK.toJson(deck) + "]");
    }

    /** The keys of a list of records are the same strings each time, and are written alike whether cached or not. */
    @Test
    void aKeyRepeatedAcrossObjectsIsWrittenTheSameEachTime() {
        JsonWriter<Deck> deck = (d, out) -> out.object()
                .put("plain", d.id()).put("이름", d.name()).put("say \"hi\"\n", true).end();
        List<Deck> decks = List.of(new Deck(0, "값", List.of()), new Deck(1, "값", List.of()), new Deck(2, "값", List.of()));

        String one = "{\"plain\":%d,\"이름\":\"값\",\"say \\\"hi\\\"\\n\":true}";
        assertThat(JsonWriter.list(deck).toJson(decks))
                .isEqualTo("[" + one.formatted(0) + "," + one.formatted(1) + "," + one.formatted(2) + "]");
    }

    /**
     * Keys whose hashes land on one slot of the key cache, five of them so
     * that the last finds the slots near its own taken, are each written
     * whole, object after object.
     */
    @Test
    void keysThatHashAlikeAreEachWrittenWhole() {
        List<String> keys = new ArrayList<>(List.of("name", "quantity")); // both land on slot 11 of 32
        for (int i = 0; keys.size() < 5; i++) {
            String key = "k" + i;
            if ((key.hashCode() & 31) == 11) {
                keys.add(key);
            }
        }
        JsonWriter<Integer> writer = (n, out) -> {
            out.object();
            for (String key : keys) {
                out.put(key, (long) n);
            }
            out.end();
        };
        List<Integer> numbers = List.of(1, 2, 3, 4, 5);

        StringBuilder expected = new StringBuilder("[");
        for (int n : numbers) {
            expected.append(n > 1 ? "," : "").append('{');
            for (int k = 0; k < keys.size(); k++) {
                expected.append(k > 0 ? "," : "").append('"').append(keys.get(k)).append("\":").append(n);
            }
            expected.append('}');
        }
        assertThat(JsonWriter.list(writer).toJson(numbers)).isEqualTo(expected.append(']').toString());
    }

    /** A key past what two words hold is written from the rest of its words, first in an object and after another. */
    @Test
    void aLongJsonKeyIsWrittenWhole() {
        JsonKey longer = JsonKey.of("a_rather_long_property_name");
        JsonKey longest = JsonKey.of("x".repeat(100));

        assertThat(JsonOutput.inMemory().object().put(longer, 1L).put(longest, 2L).put(longer, 3L).end().toJson())
                .isEqualTo("{\"a_rather_long_property_name\":1,\"" + "x".repeat(100) + "\":2,\"a_rather_long_property_name\":3}");
    }

    /** Literals are written a word at a time, which holds as the buffer grows and as a stream output sends it. */
    @Test
    void literalsAreWrittenWholeAsTheBufferGrowsAndIsSent() {
        StringBuilder expected = new StringBuilder("[");
        JsonOutput memory = JsonOutput.inMemory().array();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        JsonOutput stream = JsonOutput.to(bytes).array();
        for (int i = 0; i < 5000; i++) {
            switch (i % 3) {
                case 0 -> {
                    memory.value(true);
                    stream.value(true);
                    expected.append(i > 0 ? "," : "").append("true");
                }
                case 1 -> {
                    memory.value(false);
                    stream.value(false);
                    expected.append(",false");
                }
                default -> {
                    memory.nullValue();
                    stream.nullValue();
                    expected.append(",null");
                }
            }
        }
        expected.append(']');
        stream.end().flush();

        assertThat(memory.end().toJson()).isEqualTo(expected.toString());
        assertThat(bytes.toString(StandardCharsets.UTF_8)).isEqualTo(expected.toString());
    }

    /** A stream output may send its buffer in the middle of a key; the key is still written whole each time after. */
    @Test
    void stringKeysAreWrittenWholeAcrossTheSendsOfAStreamOutput() {
        String key = "a key of some length, " + "k".repeat(150);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        JsonOutput out = JsonOutput.to(bytes).array();
        StringBuilder expected = new StringBuilder("[");
        for (int i = 0; i < 300; i++) {
            out.object().put(key, (long) i).put("n", "v").end();
            expected.append(i > 0 ? "," : "").append("{\"").append(key).append("\":").append(i).append(",\"n\":\"v\"}");
        }
        out.end().flush();

        assertThat(bytes.toString(StandardCharsets.UTF_8)).isEqualTo(expected.append(']').toString());
    }

    @Test
    void aTreeWriterIsTheOldShapeOfAWriter() {
        JsonWriter<Deck> tree = JsonWriter.tree(d -> Json.object().put("id", d.id()));

        assertThat(tree.toJson(new Deck(5, "x", List.of()))).isEqualTo("{\"id\":5}");
        assertThat(JsonOutput.inMemory().array().value(new Deck(5, "x", List.of()), tree).end().toJson())
                .isEqualTo("[{\"id\":5}]");
    }

    @Test
    void numbersAreWrittenAsLongAndDoubleWriteThem() {
        JsonOutput out = JsonOutput.inMemory().array();
        long[] numbers = {0, 7, -7, 9, 10, 99, 100, -100, 12345, Integer.MAX_VALUE, Integer.MIN_VALUE,
                999_999_999_999_999_999L, 1_000_000_000_000_000_000L, Long.MAX_VALUE, Long.MIN_VALUE};
        StringBuilder expected = new StringBuilder("[");
        for (long n : numbers) {
            out.value(n);
            expected.append(expected.length() > 1 ? "," : "").append(n);
        }
        out.value(1.10).value(1e21).value(-0.0);
        expected.append(",1.1,1.0E21,-0.0]");

        assertThat(out.end().toJson()).isEqualTo(expected.toString());
        assertThatThrownBy(() -> JsonOutput.inMemory().value(Double.NaN))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Not a JSON number");
    }

    @Test
    void aMisplacedCallIsAnIllegalState() {
        assertThatThrownBy(() -> JsonOutput.inMemory().put("a", 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inside an object");
        assertThatThrownBy(() -> JsonOutput.inMemory().object().value(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("follows a member name");
        assertThatThrownBy(() -> JsonOutput.inMemory().object().name("a").end())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("given no value");
        assertThatThrownBy(() -> JsonOutput.inMemory().object().name("a").name("b"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JsonOutput.inMemory().end())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No object or array is open");
        assertThatThrownBy(() -> JsonOutput.inMemory().object().toBytes())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still open");
        assertThatThrownBy(() -> JsonOutput.inMemory().object().newline())
                .isInstanceOf(IllegalStateException.class);
    }

    /** The document is taken once; the output then hands its buffer on and takes nothing more. */
    @Test
    void theDocumentIsTakenOnceAndTheOutputIsFinished() {
        JsonOutput out = JsonOutput.inMemory().value("done");

        byte[] first = out.toBytes();
        assertThat(out.toBytes()).isSameAs(first);
        assertThat(out.toJson()).isEqualTo("\"done\"");
        assertThatThrownBy(() -> out.value("more")).isInstanceOf(IllegalStateException.class);
    }

    /** Two outputs open at once on one thread never share the thread's kept buffer. */
    @Test
    void twoOutputsOpenAtOnceKeepTheirOwnBuffers() {
        JsonOutput first = JsonOutput.inMemory().array().value("first");
        JsonOutput second = JsonOutput.inMemory().array().value("second");

        assertThat(first.end().toJson()).isEqualTo("[\"first\"]");
        assertThat(second.end().toJson()).isEqualTo("[\"second\"]");
        assertThat(JsonOutput.inMemory().value("third").toJson()).isEqualTo("\"third\"");
    }

    @Test
    void aStreamOutputSendsItsBufferAsItFillsAndOnFlush() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        JsonOutput out = JsonOutput.to(bytes);
        String large = "x".repeat(20_000);

        out.array().value("small");
        assertThat(bytes.size()).isZero();
        out.value(large).value(large).end();
        assertThat(bytes.size()).isGreaterThan(20_000);
        out.flush();

        assertThat(bytes.toString(StandardCharsets.UTF_8))
                .isEqualTo("[\"small\",\"" + large + "\",\"" + large + "\"]");
        assertThatThrownBy(out::toBytes).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ndjsonIsTopLevelValuesWithNewlinesBetween() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        JsonOutput out = JsonOutput.to(bytes);
        out.object().put("n", 1L).end().newline();
        out.value("two").newline();
        out.flush();

        assertThat(bytes.toString(StandardCharsets.UTF_8)).isEqualTo("{\"n\":1}\n\"two\"\n");
    }

    @Test
    void aFailingStreamSurfacesAsAnUncheckedIOException() {
        OutputStream failing = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("gone");
            }
        };
        JsonOutput out = JsonOutput.to(failing).value("x");

        assertThatThrownBy(out::flush).isInstanceOf(UncheckedIOException.class).hasMessageContaining("gone");
    }

    @Test
    void aJsonKeyIsWrittenAsItsNameWouldBe() {
        JsonKey plain = JsonKey.of("plain");
        JsonKey escaped = JsonKey.of("say \"hi\"\n");
        JsonKey korean = JsonKey.of("이름");

        assertThat(JsonOutput.inMemory().object().put(plain, 1L).put(escaped, 2L).put(korean, 3L).end().toJson())
                .isEqualTo("{\"plain\":1,\"say \\\"hi\\\"\\n\":2,\"이름\":3}");
        assertThat(plain.name()).isEqualTo("plain");
        assertThat(korean.toString()).isEqualTo("이름");
    }
}
