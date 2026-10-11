package net.benelog.silkjson;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
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
 * from an {@link #inMemory()} output, or written from its buffer with
 * {@link #writeTo(OutputStream)} and then {@link #release()}d, or sent on by
 * {@link #flush()} from one {@link #to(OutputStream)}.
 *
 * <p>A string is read through {@link String#getChars}, one copy for the whole
 * string, and its characters are checked in that array rather than through
 * {@link String#charAt}. The compiler keeps one profile of {@code charAt} for
 * the whole process, and once any string held outside Latin-1 has gone
 * through it, Korean text in a request or a log line, every loop calling it
 * takes the slower path; the loop over an array has no such path to take.
 * A quote or a backslash is escaped in that loop, and only the rare
 * characters, a control character or a character outside ASCII, leave it.
 * That loop is where a long document spends its time. A string of 64
 * characters or more is narrowed by the JDK's ASCII encoder instead, whose
 * loop the compiler turns into vector instructions, searched for a quote and
 * a backslash with {@link String#indexOf(int)}, and checked for a control
 * character a word at a time.
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
     * Each number below 1000 as its digits in the low three bytes, the first
     * digit lowest, and how many there are in the high byte: an id or a count
     * that small, the commonest number a document carries, goes out with one
     * store, whose fourth byte the bytes after the number write over.
     */
    private static final int[] SMALL_NUMBERS = new int[1000];

    static {
        for (int i = 0; i < 1000; i++) {
            byte[] digits = Integer.toString(i).getBytes(StandardCharsets.US_ASCII);
            int packed = digits.length << 24;
            for (int d = 0; d < digits.length; d++) {
                packed |= digits[d] << (d << 3);
            }
            SMALL_NUMBERS[i] = packed;
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
     * while it writes and gives it back with {@link #toBytes()} or
     * {@link #release()}, so two outputs open at once on one thread never
     * share it.
     */
    private static final ThreadLocal<byte[]> KEPT = new ThreadLocal<>();

    /** The largest buffer a thread keeps: a document past it is written into a buffer of its own. */
    private static final int KEPT_MAX = 64 * 1024;

    /** What a released output holds in place of the buffer it handed back. */
    private static final byte[] NO_BYTES = new byte[0];

    /** The buffer an output writing to a stream starts with. */
    private static final int STREAM_BUFFER = 8 * 1024;

    /**
     * The length from which a string is narrowed by the JDK's encoder rather
     * than a character at a time. The two cost the same at about 40
     * characters, and the encoder is ahead from there on.
     */
    private static final int LONG_STRING = 64;

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

    /** The ASCII encoder a long string is narrowed with, and its views of {@link #chars} and {@link #buf}, made with the first long string and kept while the arrays are the same. */
    private @Nullable CharsetEncoder asciiEncoder;
    private @Nullable CharBuffer charView;
    private @Nullable ByteBuffer byteView;

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

    /** The document, once {@link #toBytes()} has taken it. */
    private byte @Nullable [] result;

    /** Whether the buffer went back to the thread, through {@link #toBytes()} or {@link #release()}; nothing is written after that. */
    private boolean released;

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
        memberKey(key, OBJECT | NAMED);
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

    /**
     * A string member, or JSON null for a null string. This put and those of
     * a long, a double, a float, and a boolean under a {@link JsonKey} check
     * the state once and write the key and then the value, with no named state
     * in between: a generated codec writes most members through them.
     */
    public JsonOutput put(JsonKey key, @Nullable String value) {
        memberKey(key, OBJECT);
        if (value == null) {
            word(NULL, 4);
        } else {
            string(value);
        }
        return this;
    }

    public JsonOutput put(JsonKey key, long value) {
        memberKey(key, OBJECT);
        number(value);
        return this;
    }

    /** Throws {@link JsonException} for NaN and for an infinity, before the key is written. */
    public JsonOutput put(JsonKey key, double value) {
        Json.finite(value);
        memberKey(key, OBJECT);
        doubleValue(value);
        return this;
    }

    /** Throws {@link JsonException} for NaN and for an infinity, before the key is written. */
    public JsonOutput put(JsonKey key, float value) {
        Json.finite(value);
        memberKey(key, OBJECT);
        ensure(Schubfach.MAX_LENGTH);
        pos = Schubfach.write(value, buf, pos);
        return this;
    }

    public JsonOutput put(JsonKey key, boolean value) {
        memberKey(key, OBJECT);
        if (value) {
            word(TRUE, 4);
        } else {
            word(FALSE, 5);
        }
        return this;
    }

    /**
     * A string member whose value is a name encoded once, such as an enum
     * constant's from {@link JsonKey#names}: its bytes are copied as a key's
     * are, with no string to check.
     */
    public JsonOutput put(JsonKey key, JsonKey value) {
        memberKey(key, OBJECT);
        quotedName(value);
        return this;
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

    /**
     * A name encoded once, such as an enum constant's from {@link JsonKey#names},
     * as the next value: a string copied as a key is, with no string to check.
     */
    public JsonOutput value(JsonKey name) {
        beforeValue();
        quotedName(name);
        return this;
    }

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
        if (released) {
            throw finished();
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
        requireDocument();
        byte[] taken = result;
        if (taken != null) {
            return taken;
        }
        requireHeld();
        taken = Arrays.copyOf(buf, pos);
        result = taken;
        release();
        return taken;
    }

    /**
     * How many bytes the document is, once every container is closed: what
     * {@link #writeTo(OutputStream)} sends, and what a {@code Content-Length}
     * says before a byte of it is sent.
     *
     * @throws IllegalStateException after {@link #release()}, since the buffer
     *         may hold another document by then
     */
    public int length() {
        requireDocument();
        byte[] taken = result;
        if (taken != null) {
            return taken.length;
        }
        requireHeld();
        return pos;
    }

    /**
     * Writes the document to a stream straight from the buffer, with no copy
     * of it made: the way a response body goes out without the array
     * {@link #toBytes()} would allocate. The stream is not flushed or closed.
     * A {@link #release()} follows once the bytes are written.
     *
     * @throws IllegalStateException after {@link #release()}, since the buffer
     *         may hold another document by then
     */
    public void writeTo(OutputStream out) throws IOException {
        requireDocument();
        byte[] taken = result;
        if (taken != null) {
            out.write(taken);
            return;
        }
        requireHeld();
        out.write(buf, 0, pos);
    }

    /**
     * Hands the buffer back for the thread's next document, which is what
     * {@link #toBytes()} does once it has copied the document out. An output
     * read with {@link #writeTo(OutputStream)} calls this when the bytes are
     * written, and an output abandoned halfway, by a writer that threw, may
     * call it whether or not its containers are closed. A second call does
     * nothing.
     *
     * <p>Nothing is written after it, and {@link #length()} and
     * {@link #writeTo(OutputStream)} throw: the next output on this thread
     * writes into the same array.
     */
    public void release() {
        if (released) {
            return;
        }
        released = true;
        byte[] held = buf;
        // A writer that kept writing into a container it had left open would
        // write into the next document; it writes into an array of its own.
        buf = NO_BYTES;
        if (stream == null && (kept || held.length <= KEPT_MAX)) {
            if (!Thread.currentThread().isVirtual() && held.length <= KEPT_MAX) {
                KEPT.set(held);
            }
        }
    }

    /** What the methods reading a whole document ask first: an output held in memory, with every container closed. */
    private void requireDocument() {
        if (stream != null) {
            throw new IllegalStateException("This output writes to a stream: flush() sends what it holds");
        }
        if (state != TOP) {
            throw new IllegalStateException("An object or array is still open");
        }
    }

    /** What reading the buffer asks: that it has not gone back to the thread. */
    private void requireHeld() {
        if (released) {
            throw new IllegalStateException("The buffer was released: the document is no longer there to read");
        }
    }

    private static IllegalStateException finished() {
        return new IllegalStateException("The document was already taken with toBytes() or released");
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
            if (released) {
                throw finished();
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
        if (length >= LONG_STRING) {
            longString(s, length);
            return;
        }
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
     * A string of at least {@link #LONG_STRING} characters. The JDK's ASCII
     * encoder narrows its characters into the buffer, in a loop the compiler
     * turns into vector instructions, and stops at the first one outside
     * ASCII. The string's own {@link String#indexOf(int)}, another vector
     * loop, finds its first quote and its first backslash, and the bytes
     * narrowed before them are checked a word at a time for a control
     * character only. The plain run before the first of these is written as
     * it stands, and an escape is written where it is found, with the bytes
     * narrowed after it moved up rather than narrowed again; a character
     * outside ASCII leaves the rest of the string to {@link #rest}. Three
     * passes over the string cost less than one character at a time once a
     * string is this long.
     */
    @SuppressWarnings("ReferenceEquality") // whether ensure() put another array in place
    private void longString(String s, int length) {
        char[] chars = this.chars;
        if (chars.length < length) {
            chars = new char[Math.max(length, chars.length * 2)];
            this.chars = chars;
        }
        s.getChars(0, length, chars, 0);
        ensure(length + 2);
        buf[pos++] = '"';
        int quote = s.indexOf('"');
        int backslash = s.indexOf('\\');
        int i = 0;
        int narrowed = 0; // how many characters from i stand narrowed at pos
        while (i < length) {
            if (narrowed == 0) {
                narrowed = narrow(chars, i, length);
            }
            int escapeAt = Math.min(quote < 0 ? length : quote, backslash < 0 ? length : backslash);
            int plain = controlFree(buf, pos, Math.min(narrowed, escapeAt - i));
            pos += plain;
            i += plain;
            narrowed -= plain;
            if (i == length) {
                break;
            }
            char c = chars[i];
            if (c >= 0x80 || length - i < LONG_STRING) {
                rest(chars, i, length);
                break;
            }
            // c stands narrowed at pos; the narrowed characters after it move
            // up by what its escape adds, rather than being narrowed again.
            byte[] held = this.buf;
            int at = pos;
            ensure(length - i + 6);
            if (this.buf != held || pos != at) {
                narrowed = 0; // the buffer was sent or grown: narrow what is left again
                continue;
            }
            byte[] buf = held;
            int tail = narrowed - 1;
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
            if (escape == 0) {
                System.arraycopy(buf, at + 1, buf, at + 6, tail);
                buf[at] = '\\';
                buf[at + 1] = 'u';
                buf[at + 2] = '0';
                buf[at + 3] = '0';
                buf[at + 4] = HEX[c >> 4];
                buf[at + 5] = HEX[c & 0xF];
                pos = at + 6;
            } else {
                System.arraycopy(buf, at + 1, buf, at + 2, tail);
                buf[at] = '\\';
                buf[at + 1] = escape;
                pos = at + 2;
            }
            i++;
            narrowed = tail;
            if (c == '"') {
                quote = s.indexOf('"', i);
            } else if (c == '\\') {
                backslash = s.indexOf('\\', i);
            }
        }
        ensure(1);
        buf[pos++] = '"';
    }

    /** The name's bytes, quoted and without the colon a key carries. */
    private void quotedName(JsonKey name) {
        int length = name.firstLength - 1;
        if (length > 16) {
            words(name.first, length);
        } else {
            ensure(16);
            byte[] buf = this.buf;
            int at = pos;
            LittleEndian.putLong(buf, at, name.first0);
            LittleEndian.putLong(buf, at + 8, name.first1);
            pos = at + length;
        }
    }

    /** How many of so many ASCII bytes at that index come before the first control character. */
    private static int controlFree(byte[] buf, int from, int count) {
        int p = from;
        int end = from + count;
        while (p <= end - 8) {
            long controls = (LittleEndian.getLong(buf, p) - 0x2020202020202020L) & 0x8080808080808080L;
            if (controls != 0) {
                return p - from + (Long.numberOfTrailingZeros(controls) >>> 3);
            }
            p += 8;
        }
        while (p < end && buf[p] >= 0x20) {
            p++;
        }
        return p - from;
    }

    /**
     * Narrows the characters from that index into the buffer at {@link #pos},
     * as far as the first one outside ASCII, and answers how many it narrowed.
     * The position does not move: the caller decides how many of the bytes
     * to keep. The buffer has room for every character left.
     */
    @SuppressWarnings({"ReferenceEquality", "ByteBufferBackingArray"}) // the same arrays, which the views wrap whole
    private int narrow(char[] chars, int from, int to) {
        CharsetEncoder encoder = asciiEncoder;
        if (encoder == null) {
            encoder = StandardCharsets.US_ASCII.newEncoder();
            asciiEncoder = encoder;
        }
        CharBuffer in = charView;
        if (in == null || in.array() != chars) {
            in = CharBuffer.wrap(chars);
            charView = in;
        }
        ByteBuffer out = byteView;
        if (out == null || out.array() != buf) {
            out = ByteBuffer.wrap(buf);
            byteView = out;
        }
        in.limit(to).position(from);
        out.limit(out.capacity()).position(pos);
        encoder.encode(in, out, false);
        return in.position() - from;
    }

    /**
     * The rest of a string from its first control character or character outside ASCII.
     * Three bytes are reserved for every character left, which covers all but
     * a backslash-u escape, which reserves its own, and one more for the
     * fourth byte of the store a three-byte character goes out with.
     */
    private void rest(char[] chars, int from, int length) {
        ensure((length - from) * 3 + 1);
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
                // 1110xxxx 10xxxxxx 10xxxxxx as the low three bytes of one store, the first lowest
                LittleEndian.putInt(buf, pos, (c >> 12) | ((c << 2) & 0x3F00) | ((c << 16) & 0x3F0000) | 0x8080E0);
                pos += 3;
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
        ensure(7 + charactersLeft * 3);
        buf[pos++] = '\\';
        buf[pos++] = 'u';
        buf[pos++] = HEX[c >> 12];
        buf[pos++] = HEX[(c >> 8) & 0xF];
        buf[pos++] = HEX[(c >> 4) & 0xF];
        buf[pos++] = HEX[c & 0xF];
    }

    /** A whole number as {@link Long#toString(long)} writes it, two digits at a time, with no string in between. */
    private void number(long value) {
        if (value >= 0 && value < 1000) {
            ensure(4);
            int packed = SMALL_NUMBERS[(int) value];
            LittleEndian.putInt(buf, pos, packed);
            pos += packed >>> 24;
            return;
        }
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

    /**
     * A member's key as it is written, comma and colon included, and the state
     * after it: {@code NAMED} when a value is still to come, or the plain
     * object state when the caller writes the value itself. The key goes out
     * with two stores when it fits in two words, which is most keys. The form
     * is picked with no branch around a call, so there is one call to inline;
     * the state moves after the room is made, so a failure leaves no comma
     * owed.
     */
    private void memberKey(JsonKey key, int after) {
        int current = state;
        boolean next = current == OBJECT;
        if (!next && current != (OBJECT | FIRST)) {
            throw misplacedName(current);
        }
        int length = next ? key.nextLength : key.firstLength;
        if (length > 16) {
            words(next ? key.next : key.first, length);
        } else {
            ensure(16);
            byte[] buf = this.buf;
            int at = pos;
            LittleEndian.putLong(buf, at, next ? key.next0 : key.first0);
            LittleEndian.putLong(buf, at + 8, next ? key.next1 : key.first1);
            pos = at + length;
        }
        state = after;
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
