package net.benelog.spidersilk.json;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.jspecify.annotations.Nullable;

/**
 * Writes a tree as UTF-8 JSON, straight into a byte array: the one place the
 * text of a value is produced. A response body is bytes, so a document written
 * as bytes goes to the client as it is, rather than as a string encoded again
 * on its way out.
 *
 * <p>A string is read through {@link String#getChars}, one copy for the whole
 * string, and its characters are checked in that array rather than through
 * {@link String#charAt}. The compiler keeps one profile of {@code charAt} for
 * the whole process, and once any string held outside Latin-1 has gone
 * through it, Korean text in a request or a log line, every loop calling it
 * takes the slower path; the loop over an array has no such path to take.
 * Only the rare characters, an escape or a character outside ASCII, leave the
 * first loop. That is where a long document spends its time.
 */
final class JsonOutput {

    /** Which ASCII characters a JSON string holds as themselves: all but the quote, the backslash, and the controls. */
    private static final boolean[] PLAIN = new boolean[0x80];

    static {
        for (char c = 0x20; c < 0x80; c++) {
            PLAIN[c] = c != '"' && c != '\\';
        }
    }

    private static final byte[] HEX = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TRUE = "true".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FALSE = "false".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NULL = "null".getBytes(StandardCharsets.US_ASCII);

    /**
     * The buffer a platform thread wrote its last document into, kept for its
     * next one. A server thread answers request after request, and a buffer
     * already grown to the size of its documents is neither grown again nor
     * zeroed, and is still in the cache when the next document is written.
     * It is a plain byte array, so a thread of a container the application is
     * undeployed from holds nothing of the application's classes. A virtual
     * thread lives for one task, and keeps nothing.
     */
    private static final ThreadLocal<byte[]> BUFFERS = new ThreadLocal<>();

    /** The largest buffer a thread keeps: a document past it is written into a buffer of its own. */
    private static final int KEPT = 64 * 1024;

    private byte[] buf;
    private int pos;

    /** The characters of the string being written, copied out of it in one call. */
    private char[] chars = new char[32];

    /** How many objects have been written, which decides when keys are worth remembering. */
    private int objects;

    private @Nullable KeyCache keyCache;

    private JsonOutput(byte[] buf) {
        this.buf = buf;
    }

    @SuppressWarnings("ReferenceEquality") // whether the buffer was replaced as it grew, not what it holds
    static byte[] utf8(JsonValue value) {
        boolean keeps = !Thread.currentThread().isVirtual();
        byte[] kept = keeps ? BUFFERS.get() : null;
        JsonOutput out = new JsonOutput(kept != null ? kept : new byte[256]);
        out.write(value);
        if (keeps && out.buf != kept && out.buf.length <= KEPT) {
            BUFFERS.set(out.buf);
        }
        return Arrays.copyOf(out.buf, out.pos);
    }

    /** A value as a JsonValue holds it, or as an object or an array holds a string or a number a builder put in. */
    private void write(@Nullable Object value) {
        if (value instanceof String s) {
            string(s);
        } else if (value instanceof Long l) {
            number(l);
        } else if (value instanceof JsonPrimitive primitive) {
            write(primitive.value());
        } else if (value instanceof JsonObject object) {
            object(object);
        } else if (value instanceof JsonArray array) {
            array(array);
        } else if (value instanceof Boolean b) {
            bytes(b ? TRUE : FALSE);
        } else if (value == null) {
            bytes(NULL);
        } else {
            ascii(value.toString()); // a double, or a parsed decimal as its text
        }
    }

    private void object(JsonObject object) {
        if (++objects == 2) {
            keyCache = new KeyCache(); // a second object is where keys start to repeat
        }
        String[] keys = object.keys;
        Object[] values = object.values;
        int size = object.size;
        ensure(2);
        buf[pos++] = '{';
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                ensure(1);
                buf[pos++] = ',';
            }
            key(keys[i]);
            ensure(1);
            buf[pos++] = ':';
            write(values[i]);
        }
        ensure(1);
        buf[pos++] = '}';
    }

    private void array(JsonArray array) {
        ensure(2);
        buf[pos++] = '[';
        int size = array.size();
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                ensure(1);
                buf[pos++] = ',';
            }
            write(array.at(i));
        }
        ensure(1);
        buf[pos++] = ']';
    }

    /** A key, copied as the bytes it was written as before when this document has written it already. */
    @SuppressWarnings("ReferenceEquality") // the same string as before, not an equal one
    private void key(String key) {
        KeyCache cache = keyCache;
        if (cache == null) {
            string(key);
            return;
        }
        int slot = key.hashCode() & (KeyCache.SLOTS - 1);
        byte[] bytes = cache.bytes[slot];
        if (cache.keys[slot] == key && bytes != null) {
            bytes(bytes);
            return;
        }
        int start = pos;
        string(key);
        cache.keys[slot] = key;
        cache.bytes[slot] = Arrays.copyOfRange(buf, start, pos);
    }

    /**
     * A string literal. A surrogate without its pair is written as an escape
     * of its code unit, which RFC 8259 allows: UTF-8 has no form for it, so
     * written as itself it reached the client as {@code ?}, and the value read
     * back differed from the one written. A valid pair is written as the one
     * character it encodes.
     */
    private void string(String s) {
        int length = s.length();
        char[] chars = this.chars;
        if (chars.length < length) {
            chars = new char[Math.max(length, chars.length * 2)];
            this.chars = chars;
        }
        s.getChars(0, length, chars, 0);
        ensure(length + 2);
        byte[] buf = this.buf;
        int pos = this.pos;
        buf[pos++] = '"';
        int i = 0;
        for (; i < length; i++) {
            char c = chars[i];
            if (c >= 0x80 || !PLAIN[c]) {
                break;
            }
            buf[pos++] = (byte) c;
        }
        this.pos = pos;
        if (i < length) {
            rest(chars, i, length);
        }
        ensure(1);
        this.buf[this.pos++] = '"';
    }

    /**
     * The rest of a string from its first character that is not plain ASCII.
     * Three bytes are reserved for every character left, which covers all but
     * a backslash-u escape; that one reserves its own.
     */
    private void rest(char[] chars, int from, int length) {
        ensure((length - from) * 3);
        byte[] buf = this.buf;
        int pos = this.pos;
        for (int i = from; i < length; i++) {
            char c = chars[i];
            if (c < 0x80) {
                if (PLAIN[c]) {
                    buf[pos++] = (byte) c;
                    continue;
                }
                byte escape = switch (c) {
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '\n' -> 'n';
                    case '\r' -> 'r';
                    case '\t' -> 't';
                    case '\b' -> 'b';
                    case '\f' -> 'f';
                    default -> 0;
                };
                if (escape != 0) {
                    buf[pos++] = '\\';
                    buf[pos++] = escape;
                    continue;
                }
                this.pos = pos;
                unicodeEscape(c, length - i - 1);
                buf = this.buf;
                pos = this.pos;
            } else if (c < 0x800) {
                buf[pos++] = (byte) (0xC0 | (c >> 6));
                buf[pos++] = (byte) (0x80 | (c & 0x3F));
            } else if (!Character.isSurrogate(c)) {
                buf[pos++] = (byte) (0xE0 | (c >> 12));
                buf[pos++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                buf[pos++] = (byte) (0x80 | (c & 0x3F));
            } else if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(chars[i + 1])) {
                int codePoint = Character.toCodePoint(c, chars[++i]); // four bytes for two characters
                buf[pos++] = (byte) (0xF0 | (codePoint >> 18));
                buf[pos++] = (byte) (0x80 | ((codePoint >> 12) & 0x3F));
                buf[pos++] = (byte) (0x80 | ((codePoint >> 6) & 0x3F));
                buf[pos++] = (byte) (0x80 | (codePoint & 0x3F));
            } else {
                this.pos = pos;
                unicodeEscape(c, length - i - 1);
                buf = this.buf;
                pos = this.pos;
            }
        }
        this.pos = pos;
    }

    /** A control character or a lone surrogate as {@code \}{@code u} and four hex digits, with room kept for the characters after it. */
    private void unicodeEscape(char c, int charactersLeft) {
        ensure(6 + charactersLeft * 3);
        buf[pos++] = '\\';
        buf[pos++] = 'u';
        buf[pos++] = HEX[c >> 12];
        buf[pos++] = HEX[(c >> 8) & 0xF];
        buf[pos++] = HEX[(c >> 4) & 0xF];
        buf[pos++] = HEX[c & 0xF];
    }

    /** A whole number as {@link Long#toString(long)} writes it, digit by digit, with no string in between. */
    private void number(long value) {
        if (value == Long.MIN_VALUE) {
            ascii(Long.toString(value)); // the one long whose magnitude is not a long
            return;
        }
        ensure(20);
        if (value < 0) {
            buf[pos++] = '-';
            value = -value;
        }
        int end = pos + digits(value);
        for (int at = end - 1; at >= pos; at--) {
            buf[at] = (byte) ('0' + value % 10);
            value /= 10;
        }
        pos = end;
    }

    private static int digits(long value) {
        long bound = 10;
        for (int digits = 1; digits < 19; digits++) {
            if (value < bound) {
                return digits;
            }
            bound *= 10;
        }
        return 19;
    }

    private void bytes(byte[] bytes) {
        ensure(bytes.length);
        System.arraycopy(bytes, 0, buf, pos, bytes.length);
        pos += bytes.length;
    }

    /** Text known to be ASCII: a literal, or a number's own text. */
    private void ascii(String text) {
        int length = text.length();
        ensure(length);
        for (int i = 0; i < length; i++) {
            buf[pos++] = (byte) text.charAt(i);
        }
    }

    private void ensure(int bytes) {
        if (bytes > buf.length - pos) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, pos + bytes));
        }
    }

    /**
     * The keys this document has written, by identity, with the bytes each
     * was written as. The objects of one list share their keys, usually as the
     * same string constants, so a key is checked and encoded once per document
     * rather than once per object. A key that lands on a taken slot takes it
     * over, and is only written the long way.
     */
    private static final class KeyCache {

        static final int SLOTS = 32; // a power of two, so a hash picks a slot with a mask

        final String[] keys = new String[SLOTS];
        final byte[][] bytes = new byte[SLOTS][];
    }
}
