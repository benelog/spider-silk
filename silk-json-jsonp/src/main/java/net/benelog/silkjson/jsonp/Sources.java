package net.benelog.silkjson.jsonp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.SequenceInputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import jakarta.json.JsonException;
import jakarta.json.stream.JsonParsingException;

/**
 * A document as a stream of UTF-8, which is what
 * {@link net.benelog.silkjson.JsonInput} reads. A byte stream in UTF-8 passes
 * through as it is, and any other input is decoded and encoded again a buffer
 * at a time, so that a document of any size reaches the parser as it is read.
 */
final class Sources {

    private Sources() {
    }

    /** A reader's characters as UTF-8. */
    static InputStream of(Reader reader) {
        return new Encoded(reader);
    }

    /**
     * A byte stream as UTF-8, its encoding told from its first four bytes the
     * way RFC 4627 has it, which JSON-P asks of a byte stream: a byte order
     * mark names the encoding, and otherwise the zero bytes an ASCII
     * character takes in UTF-16 and UTF-32 do. Anything else is UTF-8, whose
     * byte order mark is passed over.
     */
    static InputStream detected(InputStream in) throws IOException {
        byte[] head = in.readNBytes(4);
        if (head.length > 0 && head.length < 4 && head[0] == 0) {
            throw new JsonException("A JSON document's first bytes tell its encoding, and a zero byte"
                    + " with fewer than three after it starts none of UTF-8, UTF-16, or UTF-32");
        }
        if (head.length == 4) {
            int b0 = head[0] & 0xFF;
            int b1 = head[1] & 0xFF;
            int b2 = head[2] & 0xFF;
            int b3 = head[3] & 0xFF;
            if (b0 == 0 && b1 == 0 && b2 == 0xFE && b3 == 0xFF) {
                return new Encoded(new Utf32(rest(head, 4, in), true));
            }
            if (b0 == 0xFF && b1 == 0xFE && b2 == 0 && b3 == 0) {
                return new Encoded(new Utf32(rest(head, 4, in), false));
            }
            if (b0 == 0xFE && b1 == 0xFF) {
                return new Encoded(new InputStreamReader(rest(head, 2, in), StandardCharsets.UTF_16BE));
            }
            if (b0 == 0xFF && b1 == 0xFE) {
                return new Encoded(new InputStreamReader(rest(head, 2, in), StandardCharsets.UTF_16LE));
            }
            if (b0 == 0 && b1 == 0 && b2 == 0) {
                return new Encoded(new Utf32(rest(head, 0, in), true));
            }
            if (b0 == 0 && b2 == 0) {
                return new Encoded(new InputStreamReader(rest(head, 0, in), StandardCharsets.UTF_16BE));
            }
            if (b1 == 0 && b2 == 0 && b3 == 0) {
                return new Encoded(new Utf32(rest(head, 0, in), false));
            }
            if (b1 == 0 && b3 == 0) {
                return new Encoded(new InputStreamReader(rest(head, 0, in), StandardCharsets.UTF_16LE));
            }
        }
        return rest(head, byteOrderMark(head) ? 3 : 0, in);
    }

    /** A byte stream in the encoding its reader names; UTF-8's byte order mark is passed over. */
    static InputStream decoded(InputStream in, Charset charset) throws IOException {
        if (charset.equals(StandardCharsets.UTF_8)) {
            byte[] head = in.readNBytes(3);
            return rest(head, byteOrderMark(head) ? 3 : 0, in);
        }
        return new Encoded(new InputStreamReader(in, charset));
    }

    /** Whether the bytes start with UTF-8's byte order mark, which RFC 8259 lets a parser ignore and the grammar would refuse. */
    private static boolean byteOrderMark(byte[] head) {
        return head.length >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF;
    }

    /** The bytes read ahead from that index on, followed by the rest of the stream. */
    private static InputStream rest(byte[] head, int from, InputStream in) {
        return new SequenceInputStream(new ByteArrayInputStream(head, from, head.length - from), in);
    }

    /**
     * Characters encoded as UTF-8 as they are read. A surrogate without its
     * pair has no UTF-8, and is a {@link JsonParsingException} at the
     * character it stands at, rather than the {@code ?} a lenient encoder
     * puts in its place.
     */
    private static final class Encoded extends InputStream {

        private final Reader reader;
        private final CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        private final CharBuffer chars = CharBuffer.allocate(4096).flip();
        private final ByteBuffer bytes = ByteBuffer.allocate(3 * 4096).flip();
        private boolean endOfInput;
        private boolean flushed;

        /** Where the characters encoded so far end: lines and columns from 1, and characters from 0. */
        private long line = 1;
        private long column = 1;
        private long offset;

        Encoded(Reader reader) {
            this.reader = reader;
        }

        @Override
        public int read() throws IOException {
            while (!bytes.hasRemaining()) {
                if (!encode()) {
                    return -1;
                }
            }
            return bytes.get() & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            while (!bytes.hasRemaining()) {
                if (!encode()) {
                    return -1;
                }
            }
            int n = Math.min(len, bytes.remaining());
            bytes.get(b, off, n);
            return n;
        }

        /** Encodes the next buffer of characters, and answers false once there is nothing more. */
        private boolean encode() throws IOException {
            if (flushed) {
                return false;
            }
            if (!endOfInput) {
                chars.compact();
                endOfInput = reader.read(chars) < 0;
                chars.flip();
            }
            bytes.clear();
            int from = chars.position();
            CoderResult result = encoder.encode(chars, bytes, endOfInput);
            count(from, chars.position());
            if (result.isError()) {
                int c = chars.get(chars.position());
                throw new JsonParsingException("Unpaired surrogate U+%04X at character %d, which has no UTF-8"
                        .formatted(c, offset), new Documents.Location(line, column, offset));
            }
            if (endOfInput && !chars.hasRemaining()) {
                encoder.flush(bytes);
                flushed = true;
            }
            bytes.flip();
            return bytes.hasRemaining() || !flushed;
        }

        private void count(int from, int to) {
            for (int i = from; i < to; i++) {
                if (chars.get(i) == '\n') {
                    line++;
                    column = 1;
                } else {
                    column++;
                }
            }
            offset += to - from;
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

    /**
     * UTF-32 decoded by hand. The JDK's UTF-32 is an extended charset, which
     * a native image leaves out unless it is built with every charset. A code
     * point UTF-32 cannot hold, and a document that ends inside a character,
     * are refused rather than read as U+FFFD.
     */
    private static final class Utf32 extends Reader {

        private final InputStream in;
        private final boolean bigEndian;
        private final byte[] word = new byte[4];

        /** The low surrogate of a pair whose high one went out at the end of the last read. */
        private int pending = -1;
        private long offset;

        Utf32(InputStream in, boolean bigEndian) {
            this.in = in;
            this.bigEndian = bigEndian;
        }

        @Override
        public int read(char[] buffer, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            int n = 0;
            if (pending >= 0) {
                buffer[off + n++] = (char) pending;
                pending = -1;
            }
            while (n < len) {
                int read = in.readNBytes(word, 0, 4);
                if (read == 0) {
                    break;
                }
                if (read < 4) {
                    throw new JsonParsingException("A UTF-32 document takes four bytes a character, and this one ends "
                            + read + " bytes into one", new Documents.Location(-1, -1, offset));
                }
                int c = bigEndian
                        ? (word[0] & 0xFF) << 24 | (word[1] & 0xFF) << 16 | (word[2] & 0xFF) << 8 | (word[3] & 0xFF)
                        : (word[3] & 0xFF) << 24 | (word[2] & 0xFF) << 16 | (word[1] & 0xFF) << 8 | (word[0] & 0xFF);
                if (c < 0 || c > Character.MAX_CODE_POINT || (c >= Character.MIN_SURROGATE && c <= Character.MAX_SURROGATE)) {
                    throw new JsonParsingException("Not a UTF-32 character: 0x%08X".formatted(c),
                            new Documents.Location(-1, -1, offset));
                }
                if (Character.isBmpCodePoint(c)) {
                    buffer[off + n++] = (char) c;
                    offset++;
                } else {
                    buffer[off + n++] = Character.highSurrogate(c);
                    offset += 2;
                    if (n < len) {
                        buffer[off + n++] = Character.lowSurrogate(c);
                    } else {
                        pending = Character.lowSurrogate(c);
                    }
                }
                if (n > 0 && in.available() <= 0) {
                    break;
                }
            }
            return n == 0 ? -1 : n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
