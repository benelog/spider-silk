package net.benelog.silkjson.jsonp;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

import jakarta.json.JsonException;
import jakarta.json.stream.JsonLocation;

import org.jspecify.annotations.Nullable;

/**
 * How a document reaches the parser: read whole when it is small, and
 * streamed past that. A document of at most {@link #STREAM_ABOVE} bytes of
 * UTF-8 is read into memory and parsed by one
 * {@link net.benelog.silkjson.JsonInput}. A larger one is parsed as it is
 * read, by a {@link StreamingJsonParser}, which hands the values of a large
 * object or array to a {@code JsonInput} at most {@link #PIECE} bytes at a
 * time.
 */
final class Documents {

    /** The bytes past which a document is streamed: 1 MiB, or the system property {@code net.benelog.silkjson.jsonp.streamAbove}. */
    static final int STREAM_ABOVE = Math.max(0, Math.min(Integer.getInteger("net.benelog.silkjson.jsonp.streamAbove", 1 << 20),
            Integer.MAX_VALUE - 16));

    /** The bytes of the largest object or array a streamed document reads whole, and of a run of its values: 64 KiB, or the system property {@code net.benelog.silkjson.jsonp.pieceSize}. */
    static final int PIECE = Math.max(1, Integer.getInteger("net.benelog.silkjson.jsonp.pieceSize", 64 * 1024));

    private Documents() {
    }

    /**
     * A document opened for parsing: the whole of it when it has at most
     * {@link #STREAM_ABOVE} bytes, and otherwise its first bytes and the
     * stream of the rest.
     */
    static final class Opened {

        private final byte[] head;
        private final @Nullable InputStream rest;

        Opened(byte[] head, @Nullable InputStream rest) {
            this.head = head;
            this.rest = rest;
        }

        /** The whole document, or its first bytes when there is a {@link #rest()}. */
        byte[] head() {
            return head;
        }

        /** The stream of the rest of the document, or null when {@link #head()} is the whole of it. */
        @Nullable InputStream rest() {
            return rest;
        }
    }

    /** Where a document's UTF-8 comes from: {@link Sources} over a reader or a byte stream. */
    @FunctionalInterface
    interface Source {
        InputStream utf8() throws IOException;
    }

    /** Reads the first {@link #STREAM_ABOVE} bytes and one more, which tells a small document from a large one. */
    static Opened open(Source source) {
        try {
            InputStream utf8 = source.utf8();
            byte[] head = utf8.readNBytes(STREAM_ABOVE + 1);
            return head.length <= STREAM_ABOVE ? new Opened(head, null) : new Opened(head, utf8);
        } catch (IOException e) {
            throw unreadable(e);
        }
    }

    /** An {@link IOException} of the document's source as the {@link JsonException} JSON-P has a parser and a reader throw. */
    static JsonException unreadable(IOException e) {
        return new JsonException("Could not read the JSON document: " + e.getMessage(), e);
    }

    /** A place in a document: its line and column from 1, and how many characters precede it. */
    record Location(long line, long column, long offset) implements JsonLocation {

        @Override
        public long getLineNumber() {
            return line;
        }

        @Override
        public long getColumnNumber() {
            return column;
        }

        @Override
        public long getStreamOffset() {
            return offset;
        }

        /** A place in a value read whole, as a place in the document, the value starting at this place. */
        Location plus(JsonLocation within) {
            long lines = within.getLineNumber() - 1;
            return new Location(line + lines, lines == 0 ? column + within.getColumnNumber() - 1 : within.getColumnNumber(),
                    offset + within.getStreamOffset());
        }

        @Override
        public String toString() {
            return "line " + line + ", column " + column + ", offset " + offset;
        }
    }

    /**
     * Counts the lines and the characters in a document's UTF-8, a range of
     * bytes at a time. Columns and the offset count UTF-16 characters, as a
     * {@link Reader} over the document would. Eight bytes with no line feed
     * among them, which is most of a document, are counted as one word.
     */
    static final class Counter {

        private static final VarHandle WORDS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
        private static final long ONES = 0x0101010101010101L;
        private static final long HIGH_BITS = 0x8080808080808080L;
        private static final long LINE_FEEDS = 0x0A0A0A0A0A0A0A0AL;

        private long line = 1;
        private long column = 1;
        private long offset;

        void count(byte[] utf8, int from, int to) {
            int i = from;
            while (i < to) {
                int stop = to;
                if (i <= to - 8) {
                    long word = (long) WORDS.get(utf8, i);
                    long feeds = word ^ LINE_FEEDS;
                    if (((feeds - ONES) & ~feeds & HIGH_BITS) == 0) {
                        // The bytes that start a character, less the continuation bytes 10xxxxxx,
                        // and a four-byte sequence, from a lead byte 1111xxxx, is a surrogate pair.
                        int chars = 8 - Long.bitCount(word & ~(word << 1) & HIGH_BITS)
                                + Long.bitCount(word & (word << 1) & (word << 2) & (word << 3) & HIGH_BITS);
                        column += chars;
                        offset += chars;
                        i += 8;
                        continue;
                    }
                    stop = i + 8;
                }
                for (; i < stop; i++) {
                    int b = utf8[i] & 0xFF;
                    if ((b & 0xC0) == 0x80) {
                        continue; // a continuation byte, of a character already counted
                    }
                    int chars = b >= 0xF0 ? 2 : 1; // a four-byte sequence is a surrogate pair
                    offset += chars;
                    if (b == '\n') {
                        line++;
                        column = 1;
                    } else {
                        column += chars;
                    }
                }
            }
        }

        Location location() {
            return new Location(line, column, offset);
        }
    }

    /**
     * Turns a position in a document held whole into a {@link Location},
     * counting on from the last position it was asked about, since a parser
     * asks after one event and then the next.
     */
    static final class Locator {

        private final byte[] utf8;
        private int at;
        private Counter counted = new Counter();

        Locator(byte[] utf8) {
            this.utf8 = utf8;
        }

        Location at(int position) {
            if (position < at) {
                at = 0;
                counted = new Counter();
            }
            int stop = Math.min(position, utf8.length);
            if (stop > at) {
                counted.count(utf8, at, stop);
                at = stop;
            }
            return counted.location();
        }
    }
}
