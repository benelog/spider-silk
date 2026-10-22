package net.benelog.silkjson.jsonp;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import jakarta.json.JsonException;
import jakarta.json.stream.JsonLocation;
import jakarta.json.stream.JsonParsingException;

/**
 * How a document reaches {@link net.benelog.silkjson.JsonInput}: read whole,
 * as UTF-8. Bytes in UTF-8 go to it as they are read, and a document in any
 * other encoding is decoded and encoded again first.
 */
final class Documents {

    private Documents() {
    }

    /** A reader's whole document, as UTF-8; an {@link IOException} is a {@link JsonException}. */
    static byte[] read(Reader reader) {
        try {
            StringWriter text = new StringWriter();
            reader.transferTo(text);
            return utf8(text.toString());
        } catch (IOException e) {
            throw unreadable(e);
        }
    }

    /** A byte stream's whole document, its encoding told from its first bytes. */
    static byte[] read(InputStream in) {
        try {
            return detected(in.readAllBytes());
        } catch (IOException e) {
            throw unreadable(e);
        }
    }

    static byte[] read(InputStream in, Charset charset) {
        try {
            return decoded(in.readAllBytes(), charset);
        } catch (IOException e) {
            throw unreadable(e);
        }
    }

    private static JsonException unreadable(IOException e) {
        return new JsonException("Could not read the JSON document: " + e.getMessage(), e);
    }

    /**
     * The document as UTF-8, its encoding told from its first four bytes the
     * way RFC 4627 has it, which JSON-P asks of a byte stream: a byte order
     * mark names the encoding, and otherwise the zero bytes an ASCII
     * character takes in UTF-16 and UTF-32 do. Anything else is UTF-8.
     */
    static byte[] detected(byte[] bytes) {
        if (bytes.length > 0 && bytes.length < 4 && bytes[0] == 0) {
            throw new JsonException("A JSON document's first bytes tell its encoding, and a zero byte"
                    + " with fewer than three after it starts none of UTF-8, UTF-16, or UTF-32");
        }
        if (bytes.length >= 4) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            int b2 = bytes[2] & 0xFF;
            int b3 = bytes[3] & 0xFF;
            if (b0 == 0 && b1 == 0 && b2 == 0xFE && b3 == 0xFF) {
                return utf8(utf32(bytes, 4, true));
            }
            if (b0 == 0xFF && b1 == 0xFE && b2 == 0 && b3 == 0) {
                return utf8(utf32(bytes, 4, false));
            }
            if (b0 == 0xFE && b1 == 0xFF) {
                return utf8(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE));
            }
            if (b0 == 0xFF && b1 == 0xFE) {
                return utf8(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE));
            }
            if (b0 == 0 && b1 == 0 && b2 == 0) {
                return utf8(utf32(bytes, 0, true));
            }
            if (b0 == 0 && b2 == 0) {
                return utf8(new String(bytes, StandardCharsets.UTF_16BE));
            }
            if (b1 == 0 && b2 == 0 && b3 == 0) {
                return utf8(utf32(bytes, 0, false));
            }
            if (b1 == 0 && b3 == 0) {
                return utf8(new String(bytes, StandardCharsets.UTF_16LE));
            }
        }
        return withoutByteOrderMark(bytes);
    }

    /**
     * UTF-32 decoded by hand. The JDK's UTF-32 is an extended charset, which
     * a native image leaves out unless it is built with every charset, and a
     * code point UTF-32 cannot hold is refused here rather than read as
     * U+FFFD.
     */
    private static String utf32(byte[] bytes, int from, boolean bigEndian) {
        int end = bytes.length - (bytes.length - from) % 4;
        StringBuilder text = new StringBuilder((end - from) / 4);
        for (int i = from; i < end; i += 4) {
            int c = bigEndian
                    ? (bytes[i] & 0xFF) << 24 | (bytes[i + 1] & 0xFF) << 16 | (bytes[i + 2] & 0xFF) << 8 | (bytes[i + 3] & 0xFF)
                    : (bytes[i + 3] & 0xFF) << 24 | (bytes[i + 2] & 0xFF) << 16 | (bytes[i + 1] & 0xFF) << 8 | (bytes[i] & 0xFF);
            if (c < 0 || c > Character.MAX_CODE_POINT || (c >= Character.MIN_SURROGATE && c <= Character.MAX_SURROGATE)) {
                String decoded = text.toString();
                throw new JsonParsingException("Not a UTF-32 character: 0x%08X".formatted(c),
                        location(decoded, decoded.length()));
            }
            text.appendCodePoint(c);
        }
        String decoded = text.toString();
        if (end < bytes.length) {
            throw new JsonParsingException("A UTF-32 document takes four bytes a character, and this one ends "
                    + (bytes.length - end) + " bytes into one", location(decoded, decoded.length()));
        }
        return decoded;
    }

    static byte[] decoded(byte[] bytes, Charset charset) {
        if (charset.equals(StandardCharsets.UTF_8)) {
            return withoutByteOrderMark(bytes);
        }
        return utf8(new String(bytes, charset));
    }

    /** UTF-8 without the byte order mark RFC 8259 lets a parser ignore, which the grammar would refuse. */
    private static byte[] withoutByteOrderMark(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return Arrays.copyOfRange(bytes, 3, bytes.length);
        }
        return bytes;
    }

    /**
     * Text as UTF-8. A surrogate without its pair has no UTF-8, and is a
     * {@link JsonParsingException} rather than the {@code ?} the JDK's
     * encoder puts in its place.
     */
    static byte[] utf8(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        // The encoder writes a question mark for a lone surrogate, so a document without one needs no other look.
        for (byte b : bytes) {
            if (b == '?') {
                refuseLoneSurrogate(text);
                break;
            }
        }
        return bytes;
    }

    private static void refuseLoneSurrogate(String text) {
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            if (c >= 0xD800 && c <= 0xDFFF) {
                throw new JsonParsingException("Unpaired surrogate U+%04X at character %d, which has no UTF-8".formatted(c, i),
                        location(text, i));
            }
            i += Character.charCount(c);
        }
    }

    private static JsonLocation location(String text, int index) {
        long line = 1;
        long column = 1;
        for (int i = 0; i < index; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new Location(line, column, index);
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

        @Override
        public String toString() {
            return "line " + line + ", column " + column + ", offset " + offset;
        }
    }

    /**
     * Turns a position in a document's UTF-8 into a {@link Location},
     * counting on from the last position it was asked about, since a parser
     * asks after one event and then the next. Columns and the offset count
     * UTF-16 characters, as a {@link Reader} over the document would.
     */
    static final class Locator {

        private final byte[] utf8;
        private int at;
        private long line = 1;
        private long column = 1;
        private long offset;

        Locator(byte[] utf8) {
            this.utf8 = utf8;
        }

        Location at(int position) {
            if (position < at) {
                at = 0;
                line = 1;
                column = 1;
                offset = 0;
            }
            int stop = Math.min(position, utf8.length);
            for (; at < stop; at++) {
                int b = utf8[at] & 0xFF;
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
            return new Location(line, column, offset);
        }
    }
}
