package net.benelog.silkjson.jsonp;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.json.JsonArray;
import jakarta.json.JsonConfig.KeyStrategy;
import jakarta.json.spi.JsonProvider;
import jakarta.json.stream.JsonLocation;
import jakarta.json.stream.JsonParser;
import jakarta.json.stream.JsonParser.Event;
import jakarta.json.stream.JsonParsingException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A document streamed reads as the same document held whole: the same events, values, failures, and locations. */
class StreamingJsonParserTest {

    static final JsonProvider JSON = new SilkJsonProvider();

    static final List<String> VALID = List.of(
            "{}", "[]", "1", " \"s\" ", "true", "null", "-0.5e10",
            "{\"a\":1,\"b\":[true,false,null],\"c\":{\"d\":\"e\\\"f\",\"g\":[]},\"h\":-12.5E+3}",
            "[[[[[[[[1]]]]]]]]",
            "{\n  \"이름\": \"값 \uD83D\uDE00\",\n  \"list\": [ 1 , 2 ,\t3 ],\n  \"o\" : { \"k\" : \"v\" }\n}",
            "{\"s\":\"" + "x".repeat(100) + "\",\"t\":[\"" + "가".repeat(40) + "\"]}",
            "[\"\\u00e9\\n\\t\\\\\", \"\\\"quoted\\\"\", \"a\\\\\"]",
            "[12345678901234567890, 1.0000000000000001, 1e400, 0, -0]",
            records(40));

    static final List<String> INVALID = List.of(
            "{\"a\":1,}", "[1,]", "[1 2]", "{\"a\" 1}", "{\"a\":}", "[", "{\"a\":1", "\"abc", "tru", "0.1E5E5",
            "[0.1E5E5]", "{\"a\":1}x", "[01]", "{\"a\":\"\\x\"}", "[\"a\nb\"]", "{1:2}", "[1,]]", "[] []", "",
            "[\"" + "y".repeat(50) + "\", truex]", "{\"long key " + "z".repeat(40) + "\":1,\"b\":nul}");

    static String records(int count) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            text.append(i == 0 ? "" : ",").append("{\"id\":").append(i).append(",\"name\":\"item-").append(i)
                    .append("\",\"tags\":[\"a\",\"b\"],\"ratio\":").append(i / 4.0).append(",\"ok\":").append(i % 2 == 0).append('}');
        }
        return text.append(']').toString();
    }

    /** A stream that hands its bytes over one to three at a time, so that every refill of the parser's buffer is reached. */
    static InputStream trickle(byte[] bytes) {
        return new ByteArrayInputStream(bytes) {
            private int calls;

            @Override
            public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, 1 + calls++ % 3));
            }
        };
    }

    static JsonParser streamed(String text, int headLength, int piece) {
        return streamed(text, headLength, piece, true, KeyStrategy.LAST);
    }

    /** A streaming parser of the text, its first bytes read ahead, and the rest handed over a few bytes at a time or as fast as it is asked for. */
    static JsonParser streamed(String text, int headLength, int piece, boolean trickling, KeyStrategy strategy) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        int split = Math.min(headLength, utf8.length);
        byte[] rest = Arrays.copyOfRange(utf8, split, utf8.length);
        return new StreamingJsonParser(Arrays.copyOf(utf8, split), trickling ? trickle(rest) : new ByteArrayInputStream(rest), null,
                strategy, piece);
    }

    static JsonParser whole(String text) {
        return whole(text, KeyStrategy.LAST);
    }

    static JsonParser whole(String text, KeyStrategy strategy) {
        return new SilkJsonParser(text.getBytes(StandardCharsets.UTF_8), null, strategy);
    }

    /** Every event, with what it holds and where it leaves the parser, or the failure that ends it. */
    static List<String> trace(JsonParser parser) {
        List<String> trace = new ArrayList<>();
        try {
            while (parser.hasNext()) {
                Event event = parser.next();
                String held = switch (event) {
                    case KEY_NAME, VALUE_STRING -> parser.getString();
                    case VALUE_NUMBER -> parser.getString() + " " + parser.isIntegralNumber() + " " + parser.getLong();
                    default -> "";
                };
                trace.add(event + " " + held + " @" + place(parser.getLocation()));
            }
        } catch (JsonParsingException e) {
            trace.add("failure " + e.getMessage() + " @" + place(e.getLocation()));
        }
        return trace;
    }

    /**
     * The events, with every other object or array read as a tree from its start, the document's own first when
     * the parity is 0, or the failure that ends them.
     */
    static List<String> trees(JsonParser parser, int parity) {
        List<String> trace = new ArrayList<>();
        int starts = 0;
        try {
            while (parser.hasNext()) {
                Event event = parser.next();
                if ((event == Event.START_OBJECT || event == Event.START_ARRAY) && starts++ % 2 == parity) {
                    trace.add("value " + parser.getValue() + " @" + place(parser.getLocation()));
                } else {
                    trace.add(event + " @" + place(parser.getLocation()));
                }
            }
        } catch (JsonParsingException e) {
            trace.add("failure " + e.getMessage() + " @" + place(e.getLocation()));
        }
        return trace;
    }

    /**
     * The events, with the rest of every other object or array passed over from its start, the document's own first
     * when the parity is 0, or the failure that ends them.
     */
    static List<String> skips(JsonParser parser, int parity) {
        List<String> trace = new ArrayList<>();
        int starts = 0;
        try {
            while (parser.hasNext()) {
                Event event = parser.next();
                if ((event == Event.START_OBJECT || event == Event.START_ARRAY) && starts++ % 2 == parity) {
                    if (event == Event.START_OBJECT) {
                        parser.skipObject();
                    } else {
                        parser.skipArray();
                    }
                    trace.add("skipped to " + parser.currentEvent() + " @" + place(parser.getLocation()));
                } else {
                    trace.add(event + " @" + place(parser.getLocation()));
                }
            }
        } catch (JsonParsingException e) {
            trace.add("failure " + e.getMessage() + " @" + place(e.getLocation()));
        }
        return trace;
    }

    static String place(JsonLocation location) {
        return location.getLineNumber() + ":" + location.getColumnNumber() + ":" + location.getStreamOffset();
    }

    @Test
    void aStreamedDocumentHasTheEventsOfTheDocumentHeldWhole() {
        for (String text : VALID) {
            List<String> expected = trace(whole(text));
            assertThat(expected).as(text).noneMatch(line -> line.startsWith("failure"));
            for (int piece : new int[] {1, 2, 7, 16, 64, 1 << 20}) {
                for (int head : new int[] {0, 1, 5, 1 << 20}) {
                    assertThat(trace(streamed(text, head, piece))).as("%s, piece %d, head %d", text, piece, head)
                            .isEqualTo(expected);
                }
            }
        }
    }

    @Test
    void aStreamedDocumentFailsWhereTheDocumentHeldWholeFails() {
        for (String text : INVALID) {
            List<String> expected = trace(whole(text));
            assertThat(expected.getLast()).as(text).startsWith("failure");
            for (int piece : new int[] {1, 2, 7, 16, 1 << 20}) {
                assertThat(trace(streamed(text, 0, piece))).as("%s, piece %d", text, piece).isEqualTo(expected);
            }
        }
    }

    /**
     * A stream that hands over as much as it is asked for has its values read in runs, which end wherever a piece
     * does, inside a value too; the steps a run's bytes end inside are read again off the stream, as events, as
     * trees, and passed over alike.
     */
    @Test
    void runsThatEndAnywhereReadAsTheDocumentHeldWhole() {
        List<String> texts = new ArrayList<>(VALID);
        texts.addAll(INVALID);
        for (String text : texts) {
            List<String> events = trace(whole(text));
            for (int piece = 1; piece <= 300; piece += piece < 40 ? 1 : 37) {
                for (int head : new int[] {0, 5, 1 << 20}) {
                    for (boolean trickling : new boolean[] {false, true}) {
                        String as = "%s, piece %d, head %d, trickling %s".formatted(text, piece, head, trickling);
                        assertThat(trace(streamed(text, head, piece, trickling, KeyStrategy.LAST))).as(as).isEqualTo(events);
                        for (int parity : new int[] {0, 1}) {
                            assertThat(trees(streamed(text, head, piece, trickling, KeyStrategy.LAST), parity)).as(as)
                                    .isEqualTo(trees(whole(text), parity));
                            assertThat(skips(streamed(text, head, piece, trickling, KeyStrategy.LAST), parity)).as(as)
                                    .isEqualTo(skips(whole(text), parity));
                        }
                    }
                }
            }
        }
    }

    /** A repeated key is refused where the document held whole refuses it, in an object read off the stream or in a run. */
    @Test
    void aRepeatedKeyIsRefusedWhereItIsWhole() {
        List<String> texts = List.of(
                "{\"a\":1,\"b\":2,\"a\":3}",
                "{\"a\":{\"b\":1},\n\"c\":2, \"a\" : 3}",
                "[{\"x\":1},{\"y\":{\"z\":1,\"z\":2}}]",
                records(30).replace("{\"id\":20,", "{\"id\":20,\"id\":21,"));
        for (String text : texts) {
            String expected = tree(whole(text, KeyStrategy.NONE));
            assertThat(expected).as(text).startsWith("failure").contains("Duplicate key");
            for (int piece : new int[] {1, 2, 7, 16, 64, 1 << 20}) {
                for (int head : new int[] {0, 1 << 20}) {
                    assertThat(tree(streamed(text, head, piece, false, KeyStrategy.NONE)))
                            .as("%s, piece %d, head %d", text, piece, head).isEqualTo(expected);
                }
            }
        }
    }

    /** The document's value read as a tree from its first event, and whether events follow, or the failure that ends it. */
    static String tree(JsonParser parser) {
        try {
            parser.next();
            return parser.getValue() + " " + parser.hasNext();
        } catch (JsonParsingException e) {
            return "failure " + e.getMessage() + " @" + place(e.getLocation());
        }
    }

    /** Nesting is bounded at 256 levels however the levels divide between the containers read here and the values read whole. */
    @Test
    void nestingIsBoundedAsItIsWhole() {
        String deep = "[".repeat(300) + "]".repeat(300);
        String allowed = "[".repeat(256) + "]".repeat(256);
        for (int piece : new int[] {1, 16, 200, 1 << 20}) {
            assertThatThrownBy(() -> trace(streamed(deep, 0, piece)).forEach(line -> {
                if (line.startsWith("failure")) {
                    throw new IllegalStateException(line);
                }
            })).as("piece %d", piece).hasMessageContaining("Nested deeper than 256 levels");
            assertThat(trace(streamed(allowed, 0, piece))).as("piece %d", piece).isEqualTo(trace(whole(allowed)));
        }
    }

    @Test
    void theTreeAndTheSkipsReadAsTheyDoWhole() {
        String text = records(30);
        for (int piece : new int[] {1, 16, 1 << 20}) {
            JsonParser parser = streamed(text, 3, piece);
            parser.next();
            assertThat(parser.getArray()).isEqualTo(whole(text).getValueStream().findFirst().orElseThrow());
            assertThat(parser.hasNext()).isFalse();

            JsonParser skipping = streamed("[{\"a\":[1,{\"b\":2}],\"c\":3},[4,[5]],6]", 0, piece);
            skipping.next();
            skipping.next();
            skipping.next();
            skipping.skipObject();
            assertThat(skipping.currentEvent()).isEqualTo(Event.END_OBJECT);
            assertThat(skipping.next()).isEqualTo(Event.START_ARRAY);
            skipping.skipArray();
            assertThat(skipping.currentEvent()).isEqualTo(Event.END_ARRAY);
            assertThat(skipping.next()).isEqualTo(Event.VALUE_NUMBER);
            assertThat(skipping.getInt()).isEqualTo(6);
            assertThat(skipping.next()).isEqualTo(Event.END_ARRAY);
            assertThat(skipping.hasNext()).isFalse();

            JsonParser stream = streamed("{\"a\":1,\"b\":{\"c\":[2]},\"d\":\"e\"}", 0, piece);
            stream.next();
            assertThat(stream.getObjectStream().map(member -> member.getKey() + "=" + member.getValue()))
                    .containsExactly("a=1", "b={\"c\":[2]}", "d=\"e\"");
        }
    }

    /** A document past the size read whole goes through the streaming parser from the provider's own factories, the reader's included. */
    @Test
    void aLargeDocumentIsStreamedByTheProvider() {
        String text = records(30_000);
        assertThat(text.length()).isGreaterThan(Documents.STREAM_ABOVE);

        JsonParser parser = JSON.createParser(new StringReader(text));
        assertThat(parser).isInstanceOf(StreamingJsonParser.class);
        parser.next();
        assertThat(parser.getArrayStream().count()).isEqualTo(30_000);
        assertThat(parser.hasNext()).isFalse();

        JsonArray read = JSON.createReader(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_16LE))).readArray();
        assertThat(read).isEqualTo(JsonpCodecs.ARRAY.fromJson(text));
        assertThatThrownBy(() -> JSON.createReader(new StringReader(text)).readObject())
                .isInstanceOf(JsonParsingException.class).hasMessageContaining("Not a JSON object: an array");
        assertThatThrownBy(() -> JSON.createReader(new StringReader(text + " x")).readArray())
                .isInstanceOf(JsonParsingException.class).hasMessageContaining("Trailing characters");
    }

    /**
     * A document of 128 MB, made as it is read, parses in the heap of 64 MB the build gives these tests:
     * the parser holds a record at a time, and never the document.
     */
    @Test
    void aDocumentLargerThanTheHeapIsParsed() {
        byte[] record = "{\"id\":12345,\"name\":\"a record of the stream\",\"tags\":[\"x\",\"y\"],\"ok\":true},"
                .getBytes(StandardCharsets.UTF_8);
        long records = (128L << 20) / record.length;
        AtomicLong sent = new AtomicLong();
        InputStream generated = new InputStream() {
            private long at = -1;

            @Override
            public int read() {
                byte[] b = new byte[1];
                return read(b, 0, 1) < 0 ? -1 : b[0] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                long total = records * record.length + 1;
                if (at >= total) {
                    return -1;
                }
                int n = 0;
                while (n < len && at < total) {
                    if (at < 0) {
                        b[off + n] = '[';
                    } else if (at == total - 1) {
                        b[off + n] = ']';
                    } else {
                        int i = (int) (at % record.length);
                        b[off + n] = at == total - 2 ? (byte) ' ' : record[i];
                    }
                    at++;
                    n++;
                }
                sent.addAndGet(n);
                return n;
            }
        };

        JsonParser parser = JSON.createParser(generated);
        parser.next();
        long count = parser.getArrayStream().filter(value -> value.asJsonObject().getBoolean("ok")).count();

        assertThat(count).isEqualTo(records);
        assertThat(parser.hasNext()).isFalse();
        assertThat(Runtime.getRuntime().maxMemory()).isLessThan(sent.get());
    }

    @Test
    void aLoneSurrogateInAStreamedReaderIsRefusedWhereItStands() {
        String text = "[\"" + "a".repeat(2 << 20) + "\",\n\"\uD800\"]";
        assertThatThrownBy(() -> JSON.createParser(new StringReader(text)).getValueStream().count())
                .isInstanceOfSatisfying(JsonParsingException.class, e -> {
                    assertThat(e.getMessage()).contains("Unpaired surrogate U+D800");
                    assertThat(e.getLocation().getLineNumber()).isEqualTo(2);
                    assertThat(e.getLocation().getColumnNumber()).isEqualTo(2);
                });
    }
}
