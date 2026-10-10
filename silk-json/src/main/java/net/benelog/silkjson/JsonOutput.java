package net.benelog.silkjson;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Where JSON is written: UTF-8 bytes, straight into a buffer, with no tree in
 * between. A {@link JsonWriter} is handed one and states what goes out, and a
 * generated codec does the same with its keys encoded once as a {@link JsonKey}.
 *
 * <pre>{@code
 * static final JsonWriter<Deck> DECK = (deck, out) -> out.object()
 *         .put("id", deck.id())
 *         .put("name", deck.name())
 *         .array("tags").values(deck.tags()).end()
 *         .end();
 * }</pre>
 *
 * <p>{@link #object()} and {@link #array()} open a container as the next
 * value, {@link #end()} closes the innermost one, and the commas are this
 * class's business. Inside an object a member is {@link #put}, or
 * {@link #name} followed by a value; inside an array each element is
 * {@link #value}. A document is one value, read back with {@link #toBytes()}
 * from an {@link #inMemory()} output, or sent on by {@link #flush()} from one
 * {@link #to(OutputStream)}.
 *
 * <p>A string is read through {@link String#getChars}, one copy for the whole
 * string, and its characters are checked in that array rather than through
 * {@link String#charAt}. The compiler keeps one profile of {@code charAt} for
 * the whole process, and once any string held outside Latin-1 has gone
 * through it, Korean text in a request or a log line, every loop calling it
 * takes the slower path; the loop over an array has no such path to take.
 * A quote or a backslash is escaped in that loop, and only the rare
 * characters, a control character or a character outside ASCII, leave it.
 * That loop is where a long document spends its time.
 */
public final class JsonOutput {

    /** Which ASCII characters a JSON string holds as themselves: all but the quote, the backslash, and the controls. */
    private static final boolean[] PLAIN = new boolean[0x80];

    static {
        for (char c = 0x20; c < 0x80; c++) {
            PLAIN[c] = c != '"' && c != '\\';
        }
    }

    private static final byte[] HEX = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    /** The literals as words, written with one store each. */
    private static final long TRUE = LittleEndian.words("true".getBytes(StandardCharsets.US_ASCII))[0];
    private static final long FALSE = LittleEndian.words("false".getBytes(StandardCharsets.US_ASCII))[0];
    private static final long NULL = LittleEndian.words("null".getBytes(StandardCharsets.US_ASCII))[0];

    /** Two digits per pair, so a number is written with half the divisions. */
    static final byte[] DIGIT_PAIRS = new byte[200];

    static {
        for (int i = 0; i < 100; i++) {
            DIGIT_PAIRS[i * 2] = (byte) ('0' + i / 10);
            DIGIT_PAIRS[i * 2 + 1] = (byte) ('0' + i % 10);
        }
    }

    /**
     * The buffer a platform thread wrote its last document into, kept for its
     * next one. A server thread answers request after request, and a buffer
     * already grown to the size of its documents is neither grown again nor
     * zeroed, and is still in the cache when the next document is written.
     * It is a plain byte array, so a thread of a container the application is
     * undeployed from holds nothing of the application's classes. A virtual
     * thread lives for one task, and keeps nothing. An output takes the buffer
     * while it writes and gives it back with {@link #toBytes()}, so two outputs
     * open at once on one thread never share it.
     */
    private static final ThreadLocal<byte[]> KEPT = new ThreadLocal<>();

    /** The largest buffer a thread keeps: a document past it is written into a buffer of its own. */
    private static final int KEPT_MAX = 64 * 1024;

    /** The buffer an output writing to a stream starts with. */
    private static final int STREAM_BUFFER = 8 * 1024;

    private static final int ARRAY = 0; // the open container is an array
    private static final int OBJECT = 1; // the open container is an object
    private static final int FIRST = 2; // nothing has been written into it yet
    private static final int NAMED = 4; // a member name was written, and its value is still to come
    private static final int TOP = 8; // no container is open, and a value goes out as a document of its own

    private byte[] buf;
    private int pos;

    private final @Nullable OutputStream stream;

    /** Whether {@link #buf} came out of {@link #KEPT} and goes back there. */
    private final boolean kept;

    /** The characters of the string being written, copied out of it in one call. */
    private char[] chars = new char[32];

    /**
     * The state of the container being written, a field the compiler keeps
     * in a register across a run of calls, and the states of the containers
     * around it, kept until it closes.
     */
    private int state = TOP;
    private byte[] parents = new byte[16];
    private int depth;

    /** How many times a stream output has sent its buffer, which tells a key cached from the buffer whether it is still whole there. */
    private int sends;

    /** How many objects have been written, which decides when keys are worth remembering. */
    private int objects;

    private @Nullable KeyCache keyCache;

    /** The document, once {@link #toBytes()} has taken it; nothing is written after that. */
    private byte @Nullable [] result;

    private JsonOutput(byte[] buf, @Nullable OutputStream stream, boolean kept) {
        this.buf = buf;
        this.stream = stream;
        this.kept = kept;
    }

    /**
     * An output that holds the document in memory, for {@link #toBytes()} or
     * {@link #toJson()} once it is written. A platform thread's output reuses
     * the buffer of its last document.
     */
    public static JsonOutput inMemory() {
        byte[] buffer = null;
        if (!Thread.currentThread().isVirtual()) {
            buffer = KEPT.get();
            if (buffer != null) {
                KEPT.set(null);
            }
        }
        return buffer != null ? new JsonOutput(buffer, null, true) : new JsonOutput(new byte[256], null, false);
    }

    /**
     * An output that writes to a stream as its buffer fills, for a document too
     * large to hold or a body written as it goes. {@link #flush()} sends what the
     * buffer still holds; nothing closes the stream, which stays its owner's.
     * A write that fails throws {@link UncheckedIOException}.
     */
    public static JsonOutput to(OutputStream stream) {
        Objects.requireNonNull(stream, "stream");
        return new JsonOutput(new byte[STREAM_BUFFER], stream, false);
    }

    // ---- structure ----

    /** Opens an object as the next value. */
    public JsonOutput object() {
        beforeValue();
        open(OBJECT | FIRST, '{');
        objects++;
        return this;
    }

    /** Opens an array as the next value. */
    public JsonOutput array() {
        beforeValue();
        open(ARRAY | FIRST, '[');
        return this;
    }

    /** Opens an object as the member of that name. */
    public JsonOutput object(String key) {
        return name(key).object();
    }

    /** Opens an array as the member of that name. */
    public JsonOutput array(String key) {
        return name(key).array();
    }

    public JsonOutput object(JsonKey key) {
        return name(key).object();
    }

    public JsonOutput array(JsonKey key) {
        return name(key).array();
    }

    /** Closes the innermost open object or array. */
    public JsonOutput end() {
        int closed = state;
        if (closed == TOP) {
            throw new IllegalStateException("No object or array is open");
        }
        if ((closed & NAMED) != 0) {
            throw new IllegalStateException("The last member was named and given no value");
        }
        state = parents[--depth];
        ensure(1);
        buf[pos++] = (closed & OBJECT) != 0 ? (byte) '}' : (byte) ']';
        return this;
    }

    /** Writes a member name; the value follows with {@link #value}, {@link #object()}, or {@link #array()}. */
    public JsonOutput name(String key) {
        beforeName();
        key(key);
        return this;
    }

    public JsonOutput name(JsonKey key) {
        int current = state;
        if (current == OBJECT) {
            state = OBJECT | NAMED;
            member(key.next0, key.next1, key.next, key.nextLength); // the comma comes with the key
        } else if (current == (OBJECT | FIRST)) {
            state = OBJECT | NAMED;
            member(key.first0, key.first1, key.first, key.firstLength);
        } else {
            throw misplacedName(current);
        }
        return this;
    }

    // ---- members ----

    /** A string member, or JSON null for a null string. */
    public JsonOutput put(String key, @Nullable String value) {
        return name(key).value(value);
    }

    public JsonOutput put(String key, long value) {
        return name(key).value(value);
    }

    /** Throws {@link JsonException} for NaN and for an infinity, which JSON has no syntax for. */
    public JsonOutput put(String key, double value) {
        return name(key).value(value);
    }

    /** Throws {@link JsonException} for NaN and for an infinity, which JSON has no syntax for. */
    public JsonOutput put(String key, float value) {
        return name(key).value(value);
    }

    public JsonOutput put(String key, boolean value) {
        return name(key).value(value);
    }

    /** A tree as a member, or JSON null for a null tree. */
    public JsonOutput put(String key, @Nullable JsonValue value) {
        return name(key).value(value);
    }

    /** A value written through its own writer, or JSON null for a null value. */
    public <T> JsonOutput put(String key, @Nullable T value, JsonWriter<T> writer) {
        return name(key).value(value, writer);
    }

    public JsonOutput putNull(String key) {
        return name(key).nullValue();
    }

    public JsonOutput put(JsonKey key, @Nullable String value) {
        return name(key).value(value);
    }

    public JsonOutput put(JsonKey key, long value) {
        return name(key).value(value);
    }

    public JsonOutput put(JsonKey key, double value) {
        return name(key).value(value);
    }

    public JsonOutput put(JsonKey key, float value) {
        return name(key).value(value);
    }

    public JsonOutput put(JsonKey key, boolean value) {
        return name(key).value(value);
    }

    public JsonOutput put(JsonKey key, @Nullable JsonValue value) {
        return name(key).value(value);
    }

    public <T> JsonOutput put(JsonKey key, @Nullable T value, JsonWriter<T> writer) {
        return name(key).value(value, writer);
    }

    public JsonOutput putNull(JsonKey key) {
        return name(key).nullValue();
    }

    // ---- values ----

    /** A string as the next value, or JSON null for a null string. */
    public JsonOutput value(@Nullable String value) {
        beforeValue();
        if (value == null) {
            word(NULL, 4);
        } else {
            string(value);
        }
        return this;
    }

    public JsonOutput value(long value) {
        beforeValue();
        number(value);
        return this;
    }

    /**
     * A double as {@link Double#toString(double)} writes it, the shortest
     * decimal that reads back as the same double, written as bytes with no
     * string in between. Throws {@link JsonException} for NaN and for an
     * infinity, which JSON has no syntax for.
     */
    public JsonOutput value(double value) {
        beforeValue();
        doubleValue(Json.finite(value));
        return this;
    }

    /**
     * A float as {@link Float#toString(float)} writes it: {@code 0.1f} goes
     * out as {@code 0.1}, where the double it widens to would be
     * {@code 0.10000000149011612}. Throws {@link JsonException} for NaN and for
     * an infinity.
     */
    public JsonOutput value(float value) {
        beforeValue();
        Json.finite(value);
        ensure(Schubfach.MAX_LENGTH);
        pos = Schubfach.write(value, buf, pos);
        return this;
    }

    public JsonOutput value(boolean value) {
        beforeValue();
        if (value) {
            word(TRUE, 4);
        } else {
            word(FALSE, 5);
        }
        return this;
    }

    /** A decimal as the number it is, with every digit: {@code 1E+3} and {@code 0.10} go out as written. */
    public JsonOutput value(BigDecimal value) {
        beforeValue();
        ascii(value.toString());
        return this;
    }

    /** An integer of any size as the number it is. */
    public JsonOutput value(BigInteger value) {
        beforeValue();
        ascii(value.toString());
        return this;
    }

    /** A tree as the next value, or JSON null for a null tree. */
    public JsonOutput value(@Nullable JsonValue value) {
        beforeValue();
        tree(value);
        return this;
    }

    /** A value written through its own writer, or JSON null for a null value. */
    public <T> JsonOutput value(@Nullable T value, JsonWriter<T> writer) {
        if (value == null) {
            return nullValue();
        }
        writer.write(value, this);
        return this;
    }

    public JsonOutput nullValue() {
        beforeValue();
        word(NULL, 4);
        return this;
    }

    /** Each string as an element of the open array: {@code out.array("tags").values(tags).end()}. */
    public JsonOutput values(Iterable<String> values) {
        for (String value : values) {
            value(value);
        }
        return this;
    }

    /**
     * A line break after a top-level value: the framing of NDJSON, where a
     * document holds a value per line. It is not JSON whitespace inside a value.
     */
    public JsonOutput newline() {
        if (state != TOP) {
            throw new IllegalStateException("A newline goes between top-level values, not inside one");
        }
        ensure(1);
        buf[pos++] = '\n';
        return this;
    }

    // ---- results ----

    /**
     * The document as UTF-8, once every container is closed. The output is
     * finished by this: it hands its buffer back for the thread's next
     * document, and a later call answers the same bytes.
     */
    public byte[] toBytes() {
        if (stream != null) {
            throw new IllegalStateException("This output writes to a stream: flush() sends what it holds");
        }
        if (state != TOP) {
            throw new IllegalStateException("An object or array is still open");
        }
        byte[] taken = result;
        if (taken != null) {
            return taken;
        }
        taken = Arrays.copyOf(buf, pos);
        result = taken;
        if (kept || buf.length <= KEPT_MAX) {
            if (!Thread.currentThread().isVirtual() && buf.length <= KEPT_MAX) {
                KEPT.set(buf);
            }
        }
        return taken;
    }

    /** The document as a string: {@link #toBytes()} decoded. */
    public String toJson() {
        return new String(toBytes(), StandardCharsets.UTF_8);
    }

    /** Sends what the buffer holds to the stream, and flushes the stream. In-memory outputs have nothing to flush. */
    public void flush() {
        if (stream == null) {
            return;
        }
        try {
            if (pos > 0) {
                stream.write(buf, 0, pos);
                pos = 0;
            }
            stream.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- the state of the open containers ----

    private void open(int opened, char bracket) {
        if (depth == parents.length) {
            parents = Arrays.copyOf(parents, depth * 2);
        }
        parents[depth++] = (byte) state;
        state = opened;
        ensure(1);
        buf[pos++] = (byte) bracket;
    }

    private void beforeName() {
        int current = state;
        if (current == OBJECT) {
            state = OBJECT | NAMED;
            ensure(1);
            buf[pos++] = ',';
        } else if (current == (OBJECT | FIRST)) {
            state = OBJECT | NAMED;
        } else {
            throw misplacedName(current);
        }
    }

    private static IllegalStateException misplacedName(int state) {
        return new IllegalStateException((state & OBJECT) == 0
                ? "A member name goes inside an object"
                : "The last member was named and given no value");
    }

    /** The comma before a value, when it is not the first in an array, and the state that the value moves the container to. */
    private void beforeValue() {
        int current = state;
        if (current == (OBJECT | NAMED)) {
            state = OBJECT;
        } else if (current == ARRAY) {
            ensure(1);
            buf[pos++] = ',';
        } else if (current == (ARRAY | FIRST)) {
            state = ARRAY;
        } else if (current == TOP) {
            if (result != null) {
                throw new IllegalStateException("The document was already taken with toBytes()");
            }
        } else {
            throw new IllegalStateException("A value inside an object follows a member name");
        }
    }

    // ---- a tree ----

    /** A value as a JsonValue holds it, or as an object or an array holds a string or a number a builder put in. */
    private void tree(@Nullable Object value) {
        if (value instanceof String s) {
            string(s);
        } else if (value instanceof Long l) {
            number(l);
        } else if (value instanceof Double d) {
            doubleValue(d);
        } else if (value instanceof JsonPrimitive primitive) {
            tree(primitive.value());
        } else if (value instanceof JsonObject object) {
            treeObject(object);
        } else if (value instanceof JsonArray array) {
            treeArray(array);
        } else if (value instanceof Boolean b) {
            if (b) {
                word(TRUE, 4);
            } else {
                word(FALSE, 5);
            }
        } else if (value == null) {
            word(NULL, 4);
        } else {
            ascii(value.toString()); // a parsed decimal as its text
        }
    }

    private void treeObject(JsonObject object) {
        objects++;
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
            tree(values[i]);
        }
        ensure(1);
        buf[pos++] = '}';
    }

    private void treeArray(JsonArray array) {
        ensure(2);
        buf[pos++] = '[';
        int size = array.size();
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                ensure(1);
                buf[pos++] = ',';
            }
            tree(array.at(i));
        }
        ensure(1);
        buf[pos++] = ']';
    }

    // ---- the pieces ----

    /** A key with its colon, copied as the bytes it was written as before when this document has written it already. */
    @SuppressWarnings("ReferenceEquality") // the same string as before, not an equal one
    private void key(String key) {
        KeyCache cache = keyCache;
        if (cache == null) {
            if (objects < 2) { // a second object is where keys start to repeat
                string(key);
                ensure(1);
                buf[pos++] = ':';
                return;
            }
            cache = new KeyCache();
            keyCache = cache;
        }
        int home = key.hashCode();
        int slot = -1;
        for (int probe = 0; probe < KeyCache.PROBES; probe++) {
            int at = (home + probe) & (KeyCache.SLOTS - 1);
            String cached = cache.keys[at];
            if (cached == key) {
                long[] words = cache.words[at];
                if (words != null) {
                    words(words, cache.lengths[at]);
                    return;
                }
            }
            if (cached == null) {
                slot = at;
                break;
            }
        }
        int sent = sends;
        int start = pos;
        string(key);
        ensure(1);
        buf[pos++] = ':';
        // A key with no free slot near its own is written the long way each time, and so is one
        // a stream output sent its buffer in the middle of, since the buffer no longer holds all of it.
        if (slot >= 0 && sends == sent) {
            cache.keys[slot] = key;
            cache.words[slot] = LittleEndian.words(buf, start, pos);
            cache.lengths[slot] = pos - start;
        }
    }

    /**
     * A string literal. A surrogate without its pair is written as an escape
     * of its code unit, which RFC 8259 allows: UTF-8 has no form for it, so
     * written as itself it reached the client as {@code ?}, and the value read
     * back differed from the one written. A valid pair is written as the one
     * character it encodes.
     *
     * <p>The first loop writes plain ASCII and escapes a quote or a backslash
     * as it goes, each escape moving the characters after it one byte further
     * on; only a control character or a character outside ASCII leaves it for
     * {@link #rest}. It checks a character with comparisons rather than a
     * table, which costs a load per character. The room kept for the string
     * holds no escape, so an escape that finds the buffer full makes room for
     * the worst of what is left: every character escaped.
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
        int at = pos + 1; // character i goes to at + i, and at moves on by one with each escape
        buf[pos] = '"';
        int i = 0;
        for (; i < length; i++) {
            char c = chars[i];
            if (c >= 0x80 || c < 0x20 || c == '"' || c == '\\') {
                if (c != '"' && c != '\\') {
                    break;
                }
                if (at + length + 2 > buf.length) {
                    pos = at + i;
                    ensure(2 * (length - i) + 1);
                    buf = this.buf;
                    at = pos - i;
                }
                buf[at + i] = '\\';
                at++;
            }
            buf[at + i] = (byte) c;
        }
        if (i == length) {
            buf[at + i] = '"'; // the room kept for the string held its closing quote too
            pos = at + i + 1;
            return;
        }
        pos = at + i;
        rest(chars, i, length);
        ensure(1);
        this.buf[pos++] = '"';
    }

    /**
     * The rest of a string from its first control character or character outside ASCII.
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

    /** A whole number as {@link Long#toString(long)} writes it, two digits at a time, with no string in between. */
    private void number(long value) {
        if (value == Long.MIN_VALUE) {
            ascii(Long.toString(value)); // the one long whose magnitude is not a long
            return;
        }
        ensure(20);
        byte[] buf = this.buf;
        int pos = this.pos;
        if (value < 0) {
            buf[pos++] = '-';
            value = -value;
        }
        int at = pos + digits(value);
        this.pos = at;
        while (value >= 100) {
            long quotient = value / 100;
            int pair = (int) (value - quotient * 100) * 2;
            value = quotient;
            buf[--at] = DIGIT_PAIRS[pair + 1];
            buf[--at] = DIGIT_PAIRS[pair];
        }
        if (value >= 10) {
            int pair = (int) value * 2;
            buf[--at] = DIGIT_PAIRS[pair + 1];
            buf[--at] = DIGIT_PAIRS[pair];
        } else {
            buf[--at] = (byte) ('0' + value);
        }
    }

    private void doubleValue(double value) {
        ensure(Schubfach.MAX_LENGTH);
        pos = Schubfach.write(value, buf, pos);
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

    /** A key as it is written, with two stores when it fits in two words, which is most keys. */
    private void member(long first, long second, long[] words, int length) {
        if (length > 16) {
            words(words, length);
            return;
        }
        ensure(16);
        byte[] buf = this.buf;
        int at = pos;
        LittleEndian.putLong(buf, at, first);
        LittleEndian.putLong(buf, at + 8, second);
        pos = at + length;
    }

    /** Bytes held eight to a word, written a word at a time; what the last word holds past the length is written over by what follows. */
    private void words(long[] words, int length) {
        ensure(words.length << 3);
        byte[] buf = this.buf;
        int at = pos;
        for (long word : words) {
            LittleEndian.putLong(buf, at, word);
            at += 8;
        }
        pos += length;
    }

    /** Up to eight bytes held in one word, written with one store. */
    private void word(long word, int length) {
        ensure(8);
        LittleEndian.putLong(buf, pos, word);
        pos += length;
    }

    /** Text known to be ASCII: a literal, or a number's own text. */
    private void ascii(String text) {
        int length = text.length();
        ensure(length);
        for (int i = 0; i < length; i++) {
            buf[pos++] = (byte) text.charAt(i);
        }
    }

    /** Room for that many more bytes: a stream output sends what it holds first, and either grows the buffer if it must. */
    private void ensure(int bytes) {
        if (bytes > buf.length - pos) {
            makeRoom(bytes);
        }
    }

    /** What {@link #ensure} does when the buffer is short, apart from it so that the check is inlined where it is made. */
    private void makeRoom(int bytes) {
        if (stream != null && pos > 0) {
            try {
                stream.write(buf, 0, pos);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            sends++;
            pos = 0;
            if (bytes <= buf.length) {
                return;
            }
        }
        buf = Arrays.copyOf(buf, Math.max(buf.length * 2, pos + bytes));
    }

    /**
     * The keys this document has written, by identity, with the bytes each
     * was written as, colon included, eight to a word. The objects of one list
     * share their keys, usually as the same string constants, so a key is
     * checked and encoded once per document rather than once per object. A key
     * whose slot is taken goes in one of the next few, so two keys of one
     * object that hash alike do not take each other's place object after object.
     */
    private static final class KeyCache {

        static final int SLOTS = 32; // a power of two, so a hash picks a slot with a mask
        static final int PROBES = 4;

        final String[] keys = new String[SLOTS];
        final long[][] words = new long[SLOTS][];
        final int[] lengths = new int[SLOTS];
    }
}
