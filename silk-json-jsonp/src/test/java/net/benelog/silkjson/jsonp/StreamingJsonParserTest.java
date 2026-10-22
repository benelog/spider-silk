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
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        int split = Math.min(headLength, utf8.length);
        return new StreamingJsonParser(Arrays.copyOf(utf8, split), trickle(Arrays.copyOfRange(utf8, split, utf8.length)), null,
                KeyStrategy.LAST, piece);
    }

    static JsonParser whole(String text) {
        return new SilkJsonParser(text.getBytes(StandardCharsets.UTF_8), null, KeyStrategy.LAST);
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
