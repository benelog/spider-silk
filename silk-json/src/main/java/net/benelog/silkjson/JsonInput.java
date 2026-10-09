package net.benelog.silkjson;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.jspecify.annotations.Nullable;

/**
 * Where JSON is read from: the document's UTF-8 bytes, one value at a time,
 * with no string made of the document first. A {@link JsonReader} is handed
 * one and takes what it needs; a generated codec matches each key against a
 * {@link JsonKey} without making a string of it either.
 *
 * <pre>{@code
 * static final JsonReader<Deck> DECK = in -> {
 *     in.object();
 *     long id = 0;
 *     String name = null;
 *     while (in.nextKey()) {
 *         if (in.keyIs(ID)) {
 *             id = in.readLong();
 *         } else if (in.keyIs(NAME)) {
 *             name = in.readString();
 *         } else {
 *             in.skipValue();
 *         }
 *     }
 *     return new Deck(id, name);
 * };
 * }</pre>
 *
 * <p>{@link #object()} and {@link #array()} enter a container, and
 * {@link #nextKey()} and {@link #nextElement()} step through it and leave it
 * when they answer false. A value is read with one of the {@code read}
 * methods, taken whole as a tree with {@link #readValue()}, or passed over
 * with {@link #skipValue()}. {@link #end()} checks that nothing follows.
 *
 * <p>The syntax is RFC 8259's, with no extension, as {@link Json#parse}
 * states it, and every failure is a {@link JsonException} that says where.
 * A string's bytes are checked as UTF-8 on the way to characters, so a
 * malformed sequence is rejected rather than read as a replacement character.
 *
 * <p>A string is scanned eight bytes at a time for the first byte that is
 * not a plain ASCII character, and most strings have none: the bytes between
 * the quotes are then the string. A key is compared with a {@link JsonKey} a
 * word at a time, and {@link #nextKeyIs} compares the key a reader expects
 * next where it stands, quotes and colon included, so a document in the order
 * a codec declares has none of its keys scanned.
 */
public final class JsonInput {

    /**
     * How deeply objects and arrays may nest. A tree is parsed with one
     * recursion per level, so a body nested past any depth the JDK stack can
     * hold would fail as a StackOverflowError instead of a rejected request.
     */
    private static final int MAX_DEPTH = 256;

    private static final int NONE = 0; // no container is open
    private static final int OBJECT = 1;
    private static final int ARRAY = 2;

    private static final byte[] TRUE = "true".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FALSE = "false".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NULL = "null".getBytes(StandardCharsets.US_ASCII);

    /** The powers of ten a double holds exactly, from 10^0 to 10^22. */
    private static final double[] POWERS_OF_TEN = {
        1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11,
        1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22,
    };

    /** The powers of ten a float holds exactly, from 10^0 to 10^10. */
    private static final float[] FLOAT_POWERS_OF_TEN = {
        1e0f, 1e1f, 1e2f, 1e3f, 1e4f, 1e5f, 1e6f, 1e7f, 1e8f, 1e9f, 1e10f,
    };

    private static final byte[] NO_BYTES = new byte[0];
    private static final char[] NO_CHARS = new char[0];

    private final byte[] in;
    private int pos;

    /**
     * The container entered last: its kind, and whether nothing in it has been
     * read yet. Fields, so the compiler keeps them in registers from one call
     * to the next.
     */
    private int kind = NONE;
    private boolean first;

    /** The kind of each container around the one entered last, indexed by depth, put back as each closes. */
    private byte[] kinds = new byte[16];
    private int depth;

    /** The key {@link #nextKey()} stopped at: the bytes between its quotes, as written. */
    private int keyStart;
    private int keyEnd;
    private boolean keyEscaped;
    private boolean keyAscii;

    /** The bytes of a string with an escape in it, the escapes resolved, while every character is Latin-1. */
    private byte[] unescaped = NO_BYTES;

    /** The characters of a string with an escape and a character outside Latin-1, decoded by hand, and where the string ended. */
    private char[] chars = NO_CHARS;
    private int stringLength;
    private int stringEnd;

    /** What {@link #scanNumber()} found out about the token. */
    private boolean numberIntegral;
    private int numberDigits;

    /** What {@link #decimal} found out about the token: its digits as an unsigned long, and the power of ten that scales them. */
    private long decimalSignificand;
    private int decimalExponent;

    private @Nullable KeyTable keyTable;

    private JsonInput(byte[] in) {
        this.in = in;
    }

    /** An input over a document's UTF-8 bytes. */
    public static JsonInput of(byte[] utf8) {
        return new JsonInput(utf8);
    }

    /** An input over a document's text, encoded as UTF-8 first. */
    public static JsonInput of(String text) {
        return new JsonInput(text.getBytes(StandardCharsets.UTF_8));
    }

    // ---- containers ----

    /** Enters the object that comes next, or throws for a value of another kind. */
    public void object() {
        byte[] in = this.in;
        int p = skipWhitespace(pos);
        if (p >= in.length) {
            throw errorAt(p, "Unexpected end of input");
        }
        if (in[p] != '{') {
            throw mismatchAt(p, "object");
        }
        pos = p + 1;
        enter(OBJECT);
    }

    /** Enters the array that comes next, or throws for a value of another kind. */
    public void array() {
        byte[] in = this.in;
        int p = skipWhitespace(pos);
        if (p >= in.length) {
            throw errorAt(p, "Unexpected end of input");
        }
        if (in[p] != '[') {
            throw mismatchAt(p, "array");
        }
        pos = p + 1;
        enter(ARRAY);
    }

    private void enter(int entered) {
        int d = depth + 1;
        if (d > MAX_DEPTH) {
            throw error("Nested deeper than " + MAX_DEPTH + " levels");
        }
        if (d == kinds.length) {
            kinds = Arrays.copyOf(kinds, d * 2);
        }
        kinds[d] = (byte) kind;
        depth = d;
        kind = entered;
        first = true;
    }

    /** Leaves the container entered last, whose parent has just had one more value read. */
    private void exit() {
        kind = kinds[depth--];
        first = false;
    }

    /**
     * Moves to the next member of the object entered last, stopping after its
     * colon with the key held for {@link #keyIs} and {@link #key()}, or leaves
     * the object and answers false at its closing brace.
     */
    public boolean nextKey() {
        if (kind != OBJECT) {
            throw new IllegalStateException("nextKey() steps through an object: call object() first");
        }
        byte[] in = this.in;
        int p = skipWhitespace(pos);
        if (p >= in.length) {
            throw errorAt(p, "Unexpected end of input");
        }
        byte b = in[p];
        if (b == '}') {
            pos = p + 1;
            exit();
            return false;
        }
        if (first) {
            first = false;
        } else {
            if (b != ',') {
                throw errorAt(p, "Expected ',' or '}'");
            }
            p = skipWhitespace(p + 1);
            if (p >= in.length) {
                throw errorAt(p, "Expected '\"'");
            }
            b = in[p];
        }
        if (b != '"') {
            throw errorAt(p, "Expected '\"'");
        }
        p = skipWhitespace(scanKey(p + 1));
        if (p >= in.length || in[p] != ':') {
            throw errorAt(p, "Expected ':'");
        }
        pos = p + 1;
        return true;
    }

    /**
     * Moves to the next member when its key is that one as {@link JsonOutput}
     * writes it, with no whitespace around it and the same escapes, and answers
     * true; answers false and moves nowhere otherwise, which leaves the member
     * to {@link #nextKey()}: another key, the key written another way, or the
     * end of the object. A document written from the same declaration has its
     * keys this way, so a generated codec tries the key it declares next with
     * this, and has the key and its colon in one comparison, with no scan.
     */
    public boolean nextKeyIs(JsonKey key) {
        if (kind != OBJECT) {
            throw new IllegalStateException("nextKeyIs() steps through an object: call object() first");
        }
        byte[] in = this.in;
        int p = pos;
        boolean firstMember = first;
        long[] words = firstMember ? key.first : key.next;
        int length = firstMember ? key.firstLength : key.nextLength;
        if (p <= in.length - 16) {
            long low = LittleEndian.getLong(in, p);
            long high = LittleEndian.getLong(in, p + 8);
            long differ = firstMember
                    ? ((low ^ key.first0) & key.firstMask0) | ((high ^ key.first1) & key.firstMask1)
                    : ((low ^ key.next0) & key.nextMask0) | ((high ^ key.next1) & key.nextMask1);
            if (differ != 0 || (length > 16 && !restAt(p, words, length))) {
                return false;
            }
        } else if (!bytesAt(p, words, length)) {
            return false;
        }
        keyStart = p + length - key.firstLength + 1; // after the comma, if any, and the quote
        keyEnd = p + length - 2; // before the closing quote and the colon
        keyEscaped = key.escaped;
        keyAscii = key.ascii;
        pos = p + length;
        first = false;
        return true;
    }

    /** Whether the words of a form past its first two are the bytes 16 places after that index and on. */
    private boolean restAt(int p, long[] words, int length) {
        byte[] in = this.in;
        int last = words.length - 1;
        if (p + (words.length << 3) > in.length) {
            return bytesAt(p, words, length);
        }
        for (int i = 2; i < last; i++) {
            if (LittleEndian.getLong(in, p + (i << 3)) != words[i]) {
                return false;
            }
        }
        int bytes = length - (last << 3);
        long mask = bytes == 8 ? -1L : (1L << (bytes << 3)) - 1;
        return ((LittleEndian.getLong(in, p + (last << 3)) ^ words[last]) & mask) == 0;
    }

    /** Whether the bytes at that index are those of a form, compared one at a time where the input has too few left for a word. */
    private boolean bytesAt(int p, long[] words, int length) {
        byte[] in = this.in;
        if (p + length > in.length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (in[p + i] != (byte) (words[i >>> 3] >>> ((i & 7) << 3))) {
                return false;
            }
        }
        return true;
    }

    /** The key's bytes up to its closing quote, and the index after that quote. */
    private int scanKey(int start) {
        byte[] in = this.in;
        int end = start;
        while (end < in.length) {
            byte b = in[end];
            if (b == '"' || b == '\\' || b < 0x20) {
                break;
            }
            end++;
        }
        if (end < in.length && in[end] == '"') {
            keyStart = start;
            keyEnd = end;
            keyEscaped = false;
            keyAscii = true;
            return end + 1;
        }
        return scanKeySlow(start, end);
    }

    /** A key with an escape, a character outside ASCII, or a fault, checked the way a skipped string is. */
    private int scanKeySlow(int start, int at) {
        byte[] in = this.in;
        boolean escaped = false;
        boolean ascii = true;
        int p = at;
        while (true) {
            if (p >= in.length) {
                throw errorAt(p, "Unexpected end of input");
            }
            byte b = in[p];
            if (b == '"') {
                break;
            }
            if (b == '\\') {
                escaped = true;
                escape(p);
                p += in[p + 1] == 'u' ? 6 : 2;
            } else if (b < 0) {
                ascii = false;
                p += sequenceLength(codePoint(p));
            } else {
                throw unescapedControlCharacter(p, b);
            }
            p = plainRun(p);
        }
        keyStart = start;
        keyEnd = p;
        keyEscaped = escaped;
        keyAscii = ascii;
        return p + 1;
    }

    /**
     * Moves to the next element of the array entered last, or leaves the array
     * and answers false at its closing bracket.
     */
    public boolean nextElement() {
        if (kind != ARRAY) {
            throw new IllegalStateException("nextElement() steps through an array: call array() first");
        }
        byte[] in = this.in;
        int p = skipWhitespace(pos);
        if (p >= in.length) {
            throw errorAt(p, "Unexpected end of input");
        }
        byte b = in[p];
        if (b == ']') {
            pos = p + 1;
            exit();
            return false;
        }
        if (first) {
            first = false;
            pos = p;
        } else {
            if (b != ',') {
                throw errorAt(p, "Expected ',' or ']'");
            }
            pos = p + 1;
        }
        return true;
    }

    // ---- the key ----

    /** Whether the key {@link #nextKey()} stopped at is that one, compared as bytes, a word at a time. */
    public boolean keyIs(JsonKey key) {
        int start = keyStart;
        if (keyEnd - start != key.utf8.length || keyEscaped) {
            return keyEscaped && key.name().equals(key());
        }
        byte[] in = this.in;
        long[] words = key.utf8Words;
        int last = words.length - 1;
        if (start + (words.length << 3) > in.length) {
            return Arrays.equals(in, start, keyEnd, key.utf8, 0, key.utf8.length); // too near the end for a word
        }
        for (int i = 0; i < last; i++) {
            if (LittleEndian.getLong(in, start + (i << 3)) != words[i]) {
                return false;
            }
        }
        return ((LittleEndian.getLong(in, start + (last << 3)) ^ words[last]) & key.lastMask) == 0;
    }

    /** Whether the key {@link #nextKey()} stopped at is that name. */
    public boolean keyIs(String name) {
        if (keyEscaped || !keyAscii) {
            return name.equals(key());
        }
        int length = keyEnd - keyStart;
        if (name.length() != length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (in[keyStart + i] != name.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** The key {@link #nextKey()} stopped at, as a string. A key that repeats across objects is the same string each time. */
    public String key() {
        if (keyEscaped) {
            return decode(keyStart);
        }
        int start = keyStart;
        int length = keyEnd - start;
        KeyTable table = keyTable;
        if (table == null) {
            table = new KeyTable();
            keyTable = table;
        }
        int slot = KeyTable.slot(in, start, length);
        byte[] bytes = table.bytes[slot];
        if (bytes != null && bytes.length == length && Arrays.equals(in, start, keyEnd, bytes, 0, length)) {
            return table.names[slot];
        }
        // A key outside ASCII was checked as UTF-8 when it was scanned, so the JDK's decoder finds nothing to replace.
        String name = new String(in, start, length, keyAscii ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
        table.bytes[slot] = Arrays.copyOfRange(in, start, keyEnd);
        table.names[slot] = name;
        return name;
    }

    // ---- values ----

    /** The string that comes next, or a {@link JsonException} for a value of another kind. */
    public String readString() {
        byte[] in = this.in;
        int p = skipWhitespace(pos);
        if (p >= in.length) {
            throw errorAt(p, "Unexpected end of input");
        }
        if (in[p] != '"') {
            throw mismatchAt(p, "string");
        }
        int start = p + 1;
        int end = plainRun(start);
        if (end < in.length && in[end] == '"') {
            pos = end + 1;
            return latin1(in, start, end - start); // ASCII, so Latin-1 is UTF-8
        }
        return readStringSlow(start, end);
    }

    /**
     * A string past its first byte that is not plain ASCII. One with an
     * escape and only Latin-1 characters is resolved into bytes, and one with
     * characters outside ASCII and no escape is checked as UTF-8 and decoded by
     * the JDK; a string with both, or with a fault, is decoded a character at
     * a time, which also says where the fault is.
     */
    private String readStringSlow(int start, int at) {
        byte[] in = this.in;
        if (at < in.length) {
            byte b = in[at];
            String value = b == '\\' ? unescape(start) : b < 0 ? utf8(start, at) : null;
            if (value != null) {
                return value;
            }
        }
        String value = decode(start);
        pos = stringEnd;
        return value;
    }

    /**
     * The whole number that comes next. A decimal or exponent token is read
     * from its digits, as {@link JsonValue#asLong()} reads one: {@code 2.0}
     * is 2, and {@code 1.5} is not a long.
     */
    public long readLong() {
        byte[] in = this.in;
        int p = skipWhitespace(pos);
        if (p >= in.length) {
            throw errorAt(p, "Unexpected end of input");
        }
        int start = p;
        byte b = in[p];
        boolean negative = b == '-';
        if (negative) {
            p++;
        } else if (b < '0' || b > '9') {
            throw mismatchAt(p, "number");
        }
        // The commonest case with no branch per digit: up to seven digits with
        // no leading zero, no fraction and no exponent, counted and converted
        // a word at a time.
        if (p <= in.length - 8) {
            long word = LittleEndian.getLong(in, p);
            int digits = LittleEndian.digits(word);
            if (digits > 0 && digits < 8) {
                byte after = in[p + digits]; // not a digit, since fewer than eight were counted
                if (after != '.' && after != 'e' && after != 'E' && (digits == 1 || (byte) word != '0')) {
                    pos = p + digits;
                    long value = LittleEndian.digitsValue(word, digits);
                    return negative ? -value : value;
                }
            }
        }
        // The common case in one pass: up to 18 digits with no leading zero,
        // no fraction and no exponent, accumulated as they are scanned.
        // Anything else takes the path that checks the whole grammar first.
        int digitsFrom = p;
        int stop = Math.min(in.length, p + 18);
        long magnitude = 0;
        while (p < stop) {
            int digit = in[p] - '0';
            if (digit < 0 || digit > 9) {
                break;
            }
            magnitude = magnitude * 10 + digit;
            p++;
        }
        int digits = p - digitsFrom;
        if (digits > 0 && (digits == 1 || in[digitsFrom] != '0') && (p == in.length || !continuesNumber(in[p]))) {
            pos = p;
            return negative ? -magnitude : magnitude;
        }
        pos = start;
        int end = scanNumber();
        pos = end;
        if (numberIntegral) {
            if (numberDigits <= 18) {
                return accumulate(start, end);
            }
            String text = ascii(start, end);
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                throw error("Not a JSON integer: " + text);
            }
        }
        String text = ascii(start, end);
        try {
            return JsonDecimal.exactLong(text);
        } catch (ArithmeticException e) {
            throw error("Not a JSON integer: " + shortened(text));
        }
    }

    /** {@link #readLong()}, refused outside the range of an {@code int}. */
    public int readInt() {
        long value = readLong();
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw error("Not a 32-bit integer: " + value);
        }
        return (int) value;
    }

    /**
     * The number that comes next as the nearest {@code double}, the one
     * {@link Double#parseDouble} answers for its text, {@code -0} included.
     * A number past the largest double is refused, and one closer to zero
     * than the smallest is zero.
     */
    public double readDouble() {
        return doubleAt(expectNumber());
    }

    /**
     * The number that comes next as the nearest {@code float}, the one
     * {@link Float#parseFloat} answers for its text. It is rounded once, from
     * the decimal, which {@code (float) readDouble()} is not: a number just
     * off halfway between two floats can round to a double that is halfway,
     * and then to the wrong one of the two. A number past the largest float is
     * refused, and one closer to zero than the smallest is zero.
     */
    public float readFloat() {
        int start = expectNumber();
        if (decimal(start)) {
            float value = nearestFloat(decimalSignificand, decimalExponent);
            if (!Float.isNaN(value)) {
                return in[start] == '-' ? -value : value;
            }
        }
        return toFloat(numberText(start));
    }

    /** The number that comes next as its own text, for a {@link java.math.BigDecimal} or any other exact reading. */
    public String readNumber() {
        int start = expectNumber();
        int stop = scanNumber();
        pos = stop;
        return ascii(start, stop);
    }

    public boolean readBoolean() {
        byte[] in = this.in;
        int p = skipWhitespace(pos);
        pos = p;
        if (p >= in.length) {
            throw error("Unexpected end of input");
        }
        byte b = in[p];
        if (b == 't') {
            literal(TRUE);
            return true;
        }
        if (b == 'f') {
            literal(FALSE);
            return false;
        }
        throw mismatch("boolean");
    }

    /** Reads the JSON null that comes next and answers true, or answers false and reads nothing when the next value is something else. */
    public boolean readNull() {
        int p = skipWhitespace(pos);
        pos = p;
        if (p < in.length && in[p] == 'n') {
            literal(NULL);
            return true;
        }
        return false;
    }

    /** The whole value that comes next, as a tree. */
    public JsonValue readValue() {
        return JsonPrimitive.wrap(parse());
    }

    /** Passes over the whole value that comes next, checking its syntax and nothing else. */
    public void skipValue() {
        byte[] in = this.in;
        int p = skipWhitespace(pos);
        pos = p;
        if (p >= in.length) {
            throw error("Missing value");
        }
        byte b = in[p];
        switch (b) {
            case '{' -> {
                object();
                while (nextKey()) {
                    skipValue();
                }
            }
            case '[' -> {
                array();
                while (nextElement()) {
                    skipValue();
                }
            }
            case '"' -> pos = skipString(p + 1);
            case 't' -> literal(TRUE);
            case 'f' -> literal(FALSE);
            case 'n' -> literal(NULL);
            default -> {
                if (b != '-' && !isDigit(b)) {
                    throw error("Unexpected character");
                }
                pos = scanNumber();
            }
        }
    }

    /** Checks that only whitespace is left, so a document is one value and not one value and some more. */
    public void end() {
        int p = skipWhitespace(pos);
        pos = p;
        if (p < in.length) {
            throw error("Trailing characters after the value");
        }
    }

    /** A {@link JsonException} naming where the input stands, for a reader to throw for a rule of its own. */
    public JsonException error(String message) {
        return new JsonException("Near character " + characterIndex() + ": " + message);
    }

    // ---- a tree ----

    /** A value as a tree holds it: a String, a Long, a JsonDecimal, a JsonPrimitive constant, a JsonObject, or a JsonArray. */
    private Object parse() {
        byte[] in = this.in;
        int p = skipWhitespace(pos);
        pos = p;
        if (p >= in.length) {
            throw error("Missing value");
        }
        byte b = in[p];
        return switch (b) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> readString();
            case 't' -> {
                literal(TRUE);
                yield JsonPrimitive.TRUE;
            }
            case 'f' -> {
                literal(FALSE);
                yield JsonPrimitive.FALSE;
            }
            case 'n' -> {
                literal(NULL);
                yield JsonPrimitive.NULL;
            }
            default -> {
                if (b != '-' && !isDigit(b)) {
                    throw error("Unexpected character");
                }
                yield parseNumber();
            }
        };
    }

    private JsonObject parseObject() {
        object();
        JsonObject object = new JsonObject();
        while (nextKey()) {
            String key = key();
            object.member(key, parse());
        }
        return object;
    }

    private JsonArray parseArray() {
        array();
        JsonArray array = new JsonArray();
        while (nextElement()) {
            array.element(parse());
        }
        return array;
    }

    /**
     * A number as the tree holds it: a Long for an integer of up to 18 digits,
     * accumulated as it is scanned, or any other integer a long holds, and a
     * {@link JsonDecimal} beside the nearest double for the rest, including an
     * integer past the range of a long.
     */
    private Object parseNumber() {
        int start = pos;
        int stop = scanNumber();
        pos = stop;
        if (numberIntegral && numberDigits <= 18) {
            return accumulate(start, stop);
        }
        String text = ascii(start, stop);
        if (numberIntegral) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                // Past the range of a long, and still a number: it is kept the
                // way a fraction is, its text beside the nearest double.
            }
        }
        return new JsonDecimal(doubleAt(start), text);
    }

    // ---- strings ----

    /**
     * Where the run of plain string bytes that starts at that index stops: at
     * a quote, a backslash, a control character, a byte outside ASCII, or the
     * end of the input. Eight bytes are checked at a time while eight are left.
     */
    private int plainRun(int from) {
        byte[] in = this.in;
        int p = from;
        int limit = in.length - 8;
        while (p <= limit) {
            long special = LittleEndian.special(LittleEndian.getLong(in, p));
            if (special != 0) {
                return p + LittleEndian.plainBytes(special);
            }
            p += 8;
        }
        while (p < in.length) {
            byte b = in[p];
            if (b == '"' || b == '\\' || b < 0x20) { // a byte outside ASCII is negative
                return p;
            }
            p++;
        }
        return p;
    }

    /**
     * A string with an escape, from its first byte, with its escapes resolved
     * into bytes: the runs between them are copied a word at a time, and the
     * bytes are the string's Latin-1. Answers null at the first character
     * outside Latin-1, which leaves the string to {@link #decode}.
     */
    private @Nullable String unescape(int start) {
        byte[] in = this.in;
        int p = start;
        int n = 0;
        while (true) {
            int end = copyPlain(p, n);
            n += end - p;
            p = end;
            if (p >= in.length) {
                throw errorAt(p, "Unexpected end of input");
            }
            byte b = in[p];
            if (b == '"') {
                pos = p + 1;
                return latin1(unescaped, 0, n);
            }
            if (b != '\\') {
                if (b < 0) {
                    return null;
                }
                throw unescapedControlCharacter(p, b);
            }
            int c = escape(p);
            if (c > 0xFF) {
                return null;
            }
            p += in[p + 1] == 'u' ? 6 : 2;
            byte[] out = unescaped;
            if (n == out.length) {
                out = grow(n + 1);
            }
            out[n++] = (byte) c;
        }
    }

    /** Copies the run of plain bytes at that index into {@link #unescaped} at n, a word at a time, and answers where the run stops. */
    private int copyPlain(int from, int to) {
        byte[] in = this.in;
        byte[] out = unescaped;
        int p = from;
        int n = to;
        int limit = in.length - 8;
        while (p <= limit) {
            if (n > out.length - 8) {
                out = grow(n + 8);
            }
            long word = LittleEndian.getLong(in, p);
            LittleEndian.putLong(out, n, word); // the bytes past the run are written over by what follows
            long special = LittleEndian.special(word);
            if (special != 0) {
                return p + LittleEndian.plainBytes(special);
            }
            p += 8;
            n += 8;
        }
        while (p < in.length) {
            byte b = in[p];
            if (b == '"' || b == '\\' || b < 0x20) {
                return p;
            }
            if (n == out.length) {
                out = grow(n + 1);
            }
            out[n++] = b;
            p++;
        }
        return p;
    }

    private byte[] grow(int needed) {
        byte[] grown = Arrays.copyOf(unescaped, Math.max(needed, Math.max(64, unescaped.length * 2)));
        unescaped = grown;
        return grown;
    }

    /**
     * A string with a character outside ASCII, from its first byte, checked as
     * UTF-8 as far as its closing quote and then decoded by the JDK, which
     * finds nothing to replace. Answers null at an escape, which leaves the
     * string to {@link #decode}.
     */
    private @Nullable String utf8(int start, int at) {
        byte[] in = this.in;
        int p = at;
        while (true) {
            if (p >= in.length) {
                throw errorAt(p, "Unexpected end of input");
            }
            byte b = in[p];
            if (b == '"') {
                pos = p + 1;
                return new String(in, start, p - start, StandardCharsets.UTF_8);
            }
            if (b < 0) {
                do {
                    p += sequenceLength(codePoint(p));
                } while (p < in.length && in[p] < 0);
            } else if (b == '\\') {
                return null;
            } else {
                throw unescapedControlCharacter(p, b);
            }
            p = plainRun(p);
        }
    }

    /** The characters of a string from its first byte, decoded a character at a time; {@link #stringEnd} is left after the closing quote. */
    private String decode(int from) {
        stringEnd = decodeChars(from);
        return new String(chars, 0, stringLength);
    }

    /**
     * Decodes a string from its first byte to its closing quote into
     * {@link #chars}, checking every escape and every multi-byte sequence.
     * Answers the index after the closing quote.
     */
    private int decodeChars(int from) {
        byte[] in = this.in;
        char[] chars = this.chars;
        int n = 0;
        int p = from;
        while (true) {
            if (p >= in.length) {
                throw errorAt(p, "Unexpected end of input");
            }
            if (n + 2 > chars.length) {
                chars = Arrays.copyOf(chars, Math.max(64, chars.length * 2));
                this.chars = chars;
            }
            int b = in[p];
            if (b == '"') {
                stringLength = n;
                return p + 1;
            }
            if (b == '\\') {
                chars[n++] = (char) escape(p);
                p += in[p + 1] == 'u' ? 6 : 2;
            } else if (b >= 0) {
                if (b < 0x20) {
                    throw unescapedControlCharacter(p, b);
                }
                chars[n++] = (char) b;
                p++;
            } else {
                int codePoint = codePoint(p);
                p += sequenceLength(codePoint);
                if (codePoint < 0x10000) {
                    chars[n++] = (char) codePoint;
                } else {
                    chars[n++] = Character.highSurrogate(codePoint);
                    chars[n++] = Character.lowSurrogate(codePoint);
                }
            }
        }
    }

    /** Passes over a string from its first byte, checking every escape and every multi-byte sequence, and answers the index after the closing quote. */
    private int skipString(int from) {
        byte[] in = this.in;
        int p = from;
        while (true) {
            p = plainRun(p);
            if (p >= in.length) {
                throw errorAt(p, "Unexpected end of input");
            }
            byte b = in[p];
            if (b == '"') {
                return p + 1;
            }
            if (b == '\\') {
                escape(p);
                p += in[p + 1] == 'u' ? 6 : 2;
            } else if (b < 0) {
                p += sequenceLength(codePoint(p));
            } else {
                throw unescapedControlCharacter(p, b);
            }
        }
    }

    /** The character the escape at that index names: two bytes, or six for a backslash-u escape. */
    private int escape(int at) {
        if (at + 1 >= in.length) {
            throw errorAt(at + 1, "Unexpected end of input");
        }
        byte escaped = in[at + 1];
        return switch (escaped) {
            case '"' -> '"';
            case '\\' -> '\\';
            case '/' -> '/';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'u' -> hexEscape(at + 2);
            default -> throw errorAt(at, "Unknown escape: \\" + (char) escaped);
        };
    }

    /** The code point of the UTF-8 sequence whose lead byte, outside ASCII, is at that index, or a failure for a malformed one. */
    private int codePoint(int p) {
        int b = in[p];
        if ((b & 0xE0) == 0xC0) {
            int codePoint = ((b & 0x1F) << 6) | continuation(p, 1);
            if (codePoint < 0x80) {
                throw malformed(p);
            }
            return codePoint;
        }
        if ((b & 0xF0) == 0xE0) {
            int codePoint = ((b & 0x0F) << 12) | (continuation(p, 1) << 6) | continuation(p, 2);
            if (codePoint < 0x800 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
                throw malformed(p);
            }
            return codePoint;
        }
        if ((b & 0xF8) == 0xF0) {
            int codePoint = ((b & 0x07) << 18) | (continuation(p, 1) << 12) | (continuation(p, 2) << 6)
                    | continuation(p, 3);
            if (codePoint < 0x10000 || codePoint > 0x10FFFF) {
                throw malformed(p);
            }
            return codePoint;
        }
        throw malformed(p);
    }

    /** How many bytes a code point outside ASCII takes, which a sequence checked by {@link #codePoint} matches, overlong forms being refused. */
    private static int sequenceLength(int codePoint) {
        return codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
    }

    /** The six low bits of the continuation byte that many places after a lead byte, or a failure. */
    private int continuation(int lead, int offset) {
        int at = lead + offset;
        if (at >= in.length) {
            throw malformed(lead);
        }
        int b = in[at];
        if ((b & 0xC0) != 0x80) {
            throw malformed(lead);
        }
        return b & 0x3F;
    }

    private JsonException malformed(int at) {
        return errorAt(at, "Malformed UTF-8 in a string");
    }

    /**
     * The character a backslash-u escape names, read as exactly four hex
     * digits. Integer.parseInt would do the arithmetic, but it also accepts
     * a leading sign, which is not a hex digit and which JSON's grammar
     * does not allow here. Character.digit would accept fullwidth and other
     * non-ASCII digits, which are not hex digits in JSON either.
     */
    private char hexEscape(int at) {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            if (at + i >= in.length) {
                throw errorAt(at + i, "Expected 4 hex digits after \\u");
            }
            int digit = hexDigit(in[at + i]);
            if (digit < 0) {
                throw errorAt(at + i, "Expected 4 hex digits after \\u");
            }
            value = value * 16 + digit;
        }
        return (char) value;
    }

    private static int hexDigit(byte c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    /**
     * RFC 8259 section 7: a character below U+0020 inside a string is
     * written as an escape, never as itself, so a raw line break or tab
     * is a syntax error rather than part of the value.
     */
    private JsonException unescapedControlCharacter(int at, int c) {
        return errorAt(at, "Unescaped control character U+%04X in a string".formatted(c));
    }

    // ---- numbers ----

    /** Where the number that comes next starts, or a failure for a value of another kind. */
    private int expectNumber() {
        int p = skipWhitespace(pos);
        pos = p;
        if (p >= in.length) {
            throw error("Unexpected end of input");
        }
        byte b = in[p];
        if (b != '-' && !isDigit(b)) {
            throw mismatch("number");
        }
        return p;
    }

    /** Whether a byte after a run of digits makes the number more than that run: another digit, a fraction, or an exponent. */
    private static boolean continuesNumber(byte b) {
        return isDigit(b) || b == '.' || b == 'e' || b == 'E';
    }

    /**
     * A number as RFC 8259 section 6 writes it: an optional minus, an
     * integer part that is 0 or starts with 1 to 9, then an optional
     * fraction and an optional exponent, each with at least one digit.
     * The grammar is checked here, because Long.parseLong and
     * Double.parseDouble accept forms JSON does not, such as {@code +1},
     * {@code .5}, and {@code 1.}. Answers the index after the token, and
     * leaves what it found out in {@link #numberIntegral} and {@link #numberDigits}.
     */
    private int scanNumber() {
        byte[] in = this.in;
        int p = pos;
        if (in[p] == '-') {
            p++;
        }
        if (p >= in.length || !isDigit(in[p])) {
            throw errorAt(p, p >= in.length ? "Unexpected end of input" : "Unexpected character");
        }
        int digits = 0;
        if (in[p] == '0') {
            p++;
            digits = 1;
            if (p < in.length && isDigit(in[p])) {
                throw errorAt(p, "Leading zero in a number");
            }
        } else {
            while (p < in.length && isDigit(in[p])) {
                p++;
                digits++;
            }
        }
        boolean integral = true;
        if (p < in.length && in[p] == '.') {
            integral = false;
            p = requireDigits(p + 1, "Expected a digit after the decimal point");
        }
        if (p < in.length && (in[p] == 'e' || in[p] == 'E')) {
            integral = false;
            p++;
            if (p < in.length && (in[p] == '+' || in[p] == '-')) {
                p++;
            }
            p = requireDigits(p, "Expected a digit in the exponent");
        }
        numberIntegral = integral;
        numberDigits = digits;
        return p;
    }

    private int requireDigits(int p, String message) {
        if (p >= in.length || !isDigit(in[p])) {
            throw errorAt(p, message);
        }
        while (p < in.length && isDigit(in[p])) {
            p++;
        }
        return p;
    }

    /** An integer token of at most 18 digits, which a long holds whatever they are. */
    private long accumulate(int start, int stop) {
        byte[] in = this.in;
        int p = start;
        boolean negative = in[p] == '-';
        if (negative) {
            p++;
        }
        long magnitude = 0;
        for (; p < stop; p++) {
            magnitude = magnitude * 10 + (in[p] - '0');
        }
        return negative ? -magnitude : magnitude;
    }

    /**
     * The number token at that index as the nearest double, the input moved
     * past it. A token {@link #decimal} reads is converted from its digits,
     * and the rest, or one whose rounding is left undecided, is converted
     * from its text by the JDK.
     */
    private double doubleAt(int start) {
        if (decimal(start)) {
            double value = nearestDouble(decimalSignificand, decimalExponent);
            if (value < Double.POSITIVE_INFINITY) { // neither undecided, which is NaN, nor past the largest double
                return in[start] == '-' ? -value : value;
            }
        }
        return toDouble(numberText(start));
    }

    /**
     * Reads the number token at that index in one pass, into
     * {@link #decimalSignificand} and {@link #decimalExponent}, and moves past
     * it: {@code 37.5665} is 375665 and -4. Answers false and moves nowhere
     * for a token outside the grammar, one with more than 19 significant
     * digits, which a long does not hold, or one with an exponent of more than
     * five digits; {@link #numberText} then checks the token and says what is
     * wrong with it.
     */
    private boolean decimal(int start) {
        byte[] in = this.in;
        int length = in.length;
        int p = start;
        if (in[p] == '-') {
            p++;
        }
        int from = p;
        long significand = 0;
        while (p < length) {
            int digit = in[p] - '0';
            if (digit < 0 || digit > 9) {
                break;
            }
            significand = significand * 10 + digit; // past 19 digits it wraps, and is not used
            p++;
        }
        int digits = p - from;
        if (digits == 0 || (digits > 1 && in[from] == '0')) {
            return false;
        }
        int exponent = 0;
        if (p < length && in[p] == '.') {
            int fraction = ++p;
            while (p < length) {
                int digit = in[p] - '0';
                if (digit < 0 || digit > 9) {
                    break;
                }
                significand = significand * 10 + digit;
                p++;
            }
            if (p == fraction) {
                return false;
            }
            digits += p - fraction;
            exponent = fraction - p;
        }
        if (p < length && (in[p] | 0x20) == 'e') {
            p++;
            boolean negative = false;
            if (p < length && (in[p] == '-' || in[p] == '+')) {
                negative = in[p] == '-';
                p++;
            }
            int powerFrom = p;
            int power = 0;
            while (p < length) {
                int digit = in[p] - '0';
                if (digit < 0 || digit > 9) {
                    break;
                }
                if (power < 100_000) {
                    power = power * 10 + digit;
                }
                p++;
            }
            if (p == powerFrom || power >= 100_000) {
                return false;
            }
            exponent += negative ? -power : power;
        }
        if (digits > 19 && digits - leadingZeros(from) > 19) {
            return false;
        }
        pos = p;
        decimalSignificand = significand;
        decimalExponent = exponent;
        return true;
    }

    /** How many zeros lead the digits from that index, a decimal point among them passed over. */
    private int leadingZeros(int from) {
        byte[] in = this.in;
        int zeros = 0;
        for (int p = from; p < in.length; p++) {
            byte b = in[p];
            if (b == '0') {
                zeros++;
            } else if (b != '.') {
                break;
            }
        }
        return zeros;
    }

    /**
     * The double nearest {@code w × 10^q}, w read as unsigned. When w and
     * {@code 10^q} are both doubles, which they are for w up to 2^53 and q
     * from -22 to 22, one multiplication or division rounds the exact product
     * once, as William Clinger showed, and most numbers a document carries are
     * such. The rest go to {@link EiselLemire}. Answers NaN when the rounding
     * is left undecided, and infinity past the largest double.
     */
    private static double nearestDouble(long w, int q) {
        if (w == 0) {
            return 0.0;
        }
        if (q == 0 && w > 0) {
            return w; // a long is converted with one rounding
        }
        if (w > 0 && w <= 1L << 53 && q >= -22 && q <= 22) {
            double significand = w;
            return q < 0 ? significand / POWERS_OF_TEN[-q] : significand * POWERS_OF_TEN[q];
        }
        return EiselLemire.nearest(w, q);
    }

    /**
     * The float nearest {@code w × 10^q}, w read as unsigned, or NaN when it
     * takes the text. Clinger's case holds for a float with w up to 2^24 and q
     * from -10 to 10. Otherwise the nearest double is rounded to a float, and
     * that is the float nearest the decimal too, unless the double is exactly
     * halfway between two floats, where the decimal may lie on either side of
     * it; such a double, a subnormal float, and a value past the largest float
     * take the text.
     */
    private static float nearestFloat(long w, int q) {
        if (w == 0) {
            return 0.0f;
        }
        if (q == 0 && w > 0) {
            return w; // a long is converted with one rounding
        }
        if (w > 0 && w <= 1L << 24 && q >= -10 && q <= 10) {
            float significand = w;
            return q < 0 ? significand / FLOAT_POWERS_OF_TEN[-q] : significand * FLOAT_POWERS_OF_TEN[q];
        }
        double nearest = nearestDouble(w, q);
        float value = (float) nearest;
        // A double halfway between two floats has the 29 bits a float has no room for set to 1000...0.
        if (nearest < Float.MIN_NORMAL || (Double.doubleToRawLongBits(nearest) & 0x1FFF_FFFFL) == 0x1000_0000L
                || Float.isInfinite(value)) {
            return Float.NaN;
        }
        return value;
    }

    /** The number token at that index as its text, checked against the grammar, the input moved past it. */
    private String numberText(int start) {
        pos = start;
        int stop = scanNumber();
        pos = stop;
        return ascii(start, stop);
    }

    private JsonException outOfRange(String text) {
        return error("Number out of range: " + text);
    }

    private double toDouble(String text) {
        double value;
        try {
            value = Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw outOfRange(text);
        }
        if (!Double.isFinite(value)) {
            throw outOfRange(text);
        }
        return value;
    }

    private float toFloat(String text) {
        float value;
        try {
            value = Float.parseFloat(text);
        } catch (NumberFormatException e) {
            throw outOfRange(text);
        }
        if (!Float.isFinite(value)) {
            throw outOfRange(text);
        }
        return value;
    }

    private static boolean isDigit(byte c) {
        return c >= '0' && c <= '9';
    }

    // ---- the rest ----

    /** The literal at {@link #pos}, its first four bytes compared as one word. */
    private void literal(byte[] literal) {
        byte[] in = this.in;
        int p = pos;
        int length = literal.length;
        if (p + length > in.length || LittleEndian.getInt(in, p) != LittleEndian.getInt(literal, 0)
                || (length > 4 && in[p + 4] != literal[4])) {
            throw error("Unknown literal");
        }
        pos = p + length;
    }

    /** The index of the first byte at or after that one that is not JSON whitespace, or the end of the input. */
    private int skipWhitespace(int from) {
        byte[] in = this.in;
        int p = from;
        while (p < in.length) {
            byte b = in[p];
            if (b > ' ' || (b != ' ' && b != '\n' && b != '\r' && b != '\t')) {
                return p;
            }
            p++;
        }
        return p;
    }

    /**
     * A string of Latin-1 bytes. The constructor that takes a high byte makes
     * one with a copy and nothing else, and it is small enough for the
     * compiler to put inline; the one that takes a charset goes through a
     * method too large for that, once per string. The high byte is zero, so
     * each byte is the character it is in Latin-1, which is what the
     * deprecation warns the constructor does.
     */
    @SuppressWarnings("deprecation")
    private static String latin1(byte[] bytes, int offset, int length) {
        return new String(bytes, 0, offset, length);
    }

    /** Text known to be ASCII, a number's own, as a string. */
    private String ascii(int start, int stop) {
        return new String(in, start, stop - start, StandardCharsets.ISO_8859_1);
    }

    /** A long number's start, since the message of a rejection may travel back to the client. */
    private static String shortened(String text) {
        return text.length() <= 32 ? text : text.substring(0, 32) + "...";
    }

    /** A failure at that index: the input stands there for the message to name it. */
    private JsonException errorAt(int at, String message) {
        pos = at;
        return error(message);
    }

    private JsonException mismatchAt(int at, String expected) {
        pos = at;
        return mismatch(expected);
    }

    /** What a read of one kind found instead: the kind of the value that starts here, which never quotes the body. */
    private JsonException mismatch(String expected) {
        String found = switch (in[pos]) {
            case '{' -> "an object";
            case '[' -> "an array";
            case '"' -> "a string";
            case 't' -> "true";
            case 'f' -> "false";
            case 'n' -> "null";
            default -> in[pos] == '-' || isDigit(in[pos]) ? "a number" : "";
        };
        if (found.isEmpty()) {
            return error("Unexpected character");
        }
        return error("Not a JSON " + expected + ": " + found);
    }

    /** How many characters precede {@link #pos}: the bytes that start one, which leaves out UTF-8 continuation bytes. */
    private int characterIndex() {
        int count = 0;
        int stop = Math.min(pos, in.length);
        for (int i = 0; i < stop; i++) {
            if ((in[i] & 0xC0) != 0x80) {
                count++;
            }
        }
        return count;
    }

    /**
     * The strings of the keys read so far, so that a key repeated across the
     * objects of one document is one string rather than one per object. A key
     * that lands on a taken slot takes it over.
     */
    private static final class KeyTable {

        static final int SLOTS = 64; // a power of two, so a hash picks a slot with a mask

        final byte[][] bytes = new byte[SLOTS][];
        final String[] names = new String[SLOTS];

        /** A slot from the key's length and its first, middle, and last bytes, which tell most keys of one document apart. */
        static int slot(byte[] in, int start, int length) {
            if (length == 0) {
                return 0;
            }
            int hash = length * 31 + in[start];
            hash = hash * 31 + in[start + (length >>> 1)];
            hash = hash * 31 + in[start + length - 1];
            return hash & (SLOTS - 1);
        }
    }
}
