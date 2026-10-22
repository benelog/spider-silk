package net.benelog.silkjson.jsonp;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

import jakarta.json.JsonArray;
import jakarta.json.JsonConfig.KeyStrategy;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonLocation;
import jakarta.json.stream.JsonParsingException;

import org.jspecify.annotations.Nullable;

import net.benelog.silkjson.JsonInput;

/**
 * A pull parser over a document too large to hold, which reads it as it
 * arrives and holds a value at a time. A string, a number, a literal, and an
 * object or an array of at most {@code piece} bytes are each read whole and
 * parsed by a {@link SilkJsonParser} over their bytes, so they take the same
 * {@link JsonInput} a document held whole takes. Only a larger object or
 * array is read here, its brackets, commas, colons, and keys a byte at a time
 * off the stream, so the bytes held at once are those of the largest value
 * that is not such a container.
 *
 * <p>Events, values, failures, and locations are those a {@link SilkJsonParser}
 * over the whole document answers. A failure inside a value read whole is
 * reported at its place in the document.
 */
final class StreamingJsonParser extends EventParser {

    private static final byte OBJECT = 1;
    private static final byte ARRAY = 2;
    private static final int MAX_DEPTH = 256;

    private final Window window;
    private final @Nullable Closeable closeable;
    private final KeyStrategy strategy;
    private final int piece;

    /** The containers read here, innermost last, and whether nothing has been read from the innermost yet. */
    private byte[] containers = new byte[16];
    private int depth;
    private boolean first;

    /** Whether the event before was a key read here, so that its value comes next. */
    private boolean valueNext;

    /** Whether the document's value has been read, and whether what follows it has been checked. */
    private boolean finished;
    private boolean endChecked;

    private @Nullable Event current;

    /** The key of a KEY_NAME read here. */
    private @Nullable String key;

    /** The value read whole whose events are the current ones, and where it starts in the document. */
    private @Nullable SilkJsonParser value;
    private Documents.Location valueStart = new Documents.Location(1, 1, 0);

    StreamingJsonParser(byte[] head, InputStream rest, @Nullable Closeable closeable, KeyStrategy strategy, int piece) {
        this.window = new Window(head, rest);
        this.closeable = closeable;
        this.strategy = strategy;
        this.piece = piece;
    }

    @Override
    public boolean hasNext() {
        if (!finished) {
            return true;
        }
        if (!endChecked) {
            endChecked = true;
            if (window.peek() >= 0) {
                throw fault("Trailing characters after the value");
            }
        }
        return false;
    }

    @Override
    public Event next() {
        if (!hasNext()) {
            throw new NoSuchElementException("The document has no more events");
        }
        SilkJsonParser held = value;
        if (held != null) {
            if (!held.isDone()) {
                Event event = within(held::next);
                if (held.isDone()) {
                    valueRead();
                }
                current = event;
                return event;
            }
            value = null;
        }
        Event event;
        if (depth == 0) {
            event = value();
        } else if (containers[depth - 1] == OBJECT) {
            if (valueNext) {
                valueNext = false;
                event = value();
            } else {
                event = member();
            }
        } else {
            event = element();
        }
        current = event;
        return event;
    }

    /** The next member of an object read here: its key, or the object's end. */
    private Event member() {
        int b = window.peek();
        if (b < 0) {
            throw fault("Unexpected end of input");
        }
        if (b == '}') {
            window.skip(1);
            leave();
            return Event.END_OBJECT;
        }
        if (first) {
            first = false;
        } else {
            if (b != ',') {
                throw fault("Expected ',' or '}'");
            }
            window.skip(1);
            b = window.peek();
        }
        if (b != '"') {
            throw fault("Expected '\"'");
        }
        Documents.Location start = window.location();
        byte[] quoted = window.take(window.stringLength());
        JsonInput in = JsonInput.of(quoted);
        try {
            key = in.readString();
            in.end();
        } catch (net.benelog.silkjson.JsonException e) {
            throw new JsonParsingException(rebased(e.getMessage(), start), start.plus(new Documents.Locator(quoted).at(in.position())));
        }
        if (window.peek() != ':') {
            throw fault("Expected ':'");
        }
        window.skip(1);
        valueNext = true;
        return Event.KEY_NAME;
    }

    /** The next element of an array read here, or the array's end. */
    private Event element() {
        int b = window.peek();
        if (b < 0) {
            throw fault("Unexpected end of input");
        }
        if (b == ']') {
            window.skip(1);
            leave();
            return Event.END_ARRAY;
        }
        if (first) {
            first = false;
        } else {
            if (b != ',') {
                throw fault("Expected ',' or ']'");
            }
            window.skip(1);
        }
        return value();
    }

    /** The value that comes next: an object or an array of more than a piece entered here, and anything else read whole. */
    private Event value() {
        int b = window.peek();
        if (b < 0) {
            throw fault("Missing value");
        }
        if (b == '{' || b == '[') {
            int length = window.containerLength(piece, MAX_DEPTH - depth);
            if (length == Window.NESTED_TOO_DEEP) {
                throw fault("Nested deeper than " + MAX_DEPTH + " levels");
            }
            if (length < 0) {
                window.skip(1);
                enter(b == '{' ? OBJECT : ARRAY);
                return b == '{' ? Event.START_OBJECT : Event.START_ARRAY;
            }
            return whole(length);
        }
        if (b == '"') {
            return whole(window.stringLength());
        }
        if (b == '-' || (b >= '0' && b <= '9')) {
            return whole(window.tokenLength(true));
        }
        if (b >= 'a' && b <= 'z') {
            return whole(window.tokenLength(false));
        }
        throw fault("Unexpected character");
    }

    /**
     * Reads the value of that many bytes whole, and answers its first event.
     * A number or a literal is taken as the bytes a number or a word is made
     * of, and what its parser leaves of them, the {@code E5} of
     * {@code 0.1E5E5}, goes back to be read here next, as it would be read
     * after the value in the document held whole.
     */
    private Event whole(int length) {
        valueStart = window.location();
        SilkJsonParser held = new SilkJsonParser(window.take(length), null, strategy);
        value = held;
        Event event = within(held::next);
        if (held.isDone()) {
            window.unread(held.unread());
            valueRead();
        }
        return event;
    }

    /** A value read whole has had its last event, which ends the document when it was the document's value. */
    private void valueRead() {
        if (depth == 0) {
            finished = true;
        }
    }

    private void enter(byte container) {
        if (depth == MAX_DEPTH) {
            throw fault("Nested deeper than " + MAX_DEPTH + " levels");
        }
        if (depth == containers.length) {
            containers = Arrays.copyOf(containers, depth * 2);
        }
        containers[depth++] = container;
        first = true;
    }

    /** Leaves the innermost container read here, whose closing bracket has just been read. */
    private void leave() {
        depth--;
        first = false;
        finished = depth == 0;
    }

    /** A call to the value read whole, with a failure in it reported at its place in the document. */
    private <T> T within(Supplier<T> call) {
        try {
            return call.get();
        } catch (JsonParsingException e) {
            throw new JsonParsingException(rebased(e.getMessage(), valueStart), valueStart.plus(e.getLocation()));
        }
    }

    /** A message that names a character of a value read whole, naming it in the document instead. */
    private static String rebased(@Nullable String message, Documents.Location start) {
        String text = String.valueOf(message);
        String prefix = "Near character ";
        int colon = text.indexOf(':');
        if (!text.startsWith(prefix) || colon <= prefix.length()) {
            return text;
        }
        String at = text.substring(prefix.length(), colon);
        for (int i = 0; i < at.length(); i++) {
            if (at.charAt(i) < '0' || at.charAt(i) > '9') {
                return text;
            }
        }
        return prefix + (start.offset() + Long.parseLong(at)) + text.substring(colon);
    }

    private JsonParsingException fault(String message) {
        Documents.Location at = window.location();
        return new JsonParsingException("Near character " + at.offset() + ": " + message, at);
    }

    @Override
    boolean inside() {
        SilkJsonParser held = value;
        return depth > 0 || (held != null && held.inside());
    }

    @Override
    public @Nullable Event currentEvent() {
        return current;
    }

    @Override
    public String getString() {
        SilkJsonParser held = value;
        if (held != null) {
            return held.getString();
        }
        String name = key;
        if (current != Event.KEY_NAME || name == null) {
            throw new IllegalStateException("getString() reads a key, a string, or a number, not " + current);
        }
        return name;
    }

    @Override
    public boolean isIntegralNumber() {
        return number("isIntegralNumber()").isIntegralNumber();
    }

    @Override
    public int getInt() {
        return number("getInt()").getInt();
    }

    @Override
    public long getLong() {
        return number("getLong()").getLong();
    }

    @Override
    public BigDecimal getBigDecimal() {
        return number("getBigDecimal()").getBigDecimal();
    }

    /** The value read whole a number accessor reads from: a number is never read here. */
    private SilkJsonParser number(String method) {
        SilkJsonParser held = value;
        if (held == null) {
            throw new IllegalStateException(method + " reads a number, not " + current);
        }
        return held;
    }

    @Override
    public JsonLocation getLocation() {
        SilkJsonParser held = value;
        return held != null ? valueStart.plus(held.getLocation()) : window.location();
    }

    @Override
    public JsonValue getValue() {
        SilkJsonParser held = value;
        if (held != null) {
            JsonValue read = within(held::getValue);
            settle(held);
            return read;
        }
        Event event = current;
        if (event == Event.START_OBJECT) {
            return getObject();
        }
        if (event == Event.START_ARRAY) {
            return getArray();
        }
        if (event == Event.KEY_NAME) {
            return new SilkJsonString(getString());
        }
        throw new IllegalStateException("getValue() has no value to read at " + event);
    }

    @Override
    public JsonObject getObject() {
        if (current != Event.START_OBJECT) {
            throw new IllegalStateException("getObject() reads an object from its START_OBJECT, not from " + current);
        }
        SilkJsonParser held = value;
        if (held != null) {
            JsonObject read = within(held::getObject);
            settle(held);
            return read;
        }
        Members members = new Members(strategy);
        while (next() != Event.END_OBJECT) {
            String name = getString();
            if (strategy == KeyStrategy.NONE && members.has(name)) {
                throw fault("Duplicate key in a JSON object: " + TreeReader.shortened(name));
            }
            next();
            members.put(name, getValue());
        }
        return members.build();
    }

    @Override
    public JsonArray getArray() {
        if (current != Event.START_ARRAY) {
            throw new IllegalStateException("getArray() reads an array from its START_ARRAY, not from " + current);
        }
        SilkJsonParser held = value;
        if (held != null) {
            JsonArray read = within(held::getArray);
            settle(held);
            return read;
        }
        List<JsonValue> elements = new ArrayList<>();
        while (next() != Event.END_ARRAY) {
            elements.add(getValue());
        }
        return new SilkJsonArray(elements.toArray(new JsonValue[0]), elements.size());
    }

    /** Passes over the rest of the innermost array, to its END_ARRAY; does nothing when the innermost container is an object. */
    @Override
    public void skipArray() {
        SilkJsonParser held = value;
        if (held != null && held.inside()) {
            within(() -> {
                held.skipArray();
                return held;
            });
            settle(held);
        } else if (depth > 0 && containers[depth - 1] == ARRAY) {
            skip();
        }
    }

    /** Passes over the rest of the innermost object, to its END_OBJECT; does nothing when the innermost container is an array. */
    @Override
    public void skipObject() {
        SilkJsonParser held = value;
        if (held != null && held.inside()) {
            within(() -> {
                held.skipObject();
                return held;
            });
            settle(held);
        } else if (depth > 0 && containers[depth - 1] == OBJECT) {
            skip();
        }
    }

    /** Reads the events of the innermost container read here to its end. */
    private void skip() {
        int inside = depth;
        while (depth >= inside) {
            next();
        }
    }

    /** Takes the event a call left the value read whole at as the current one. */
    private void settle(SilkJsonParser held) {
        current = held.currentEvent();
        if (held.isDone()) {
            valueRead();
        }
    }

    /** Closes the reader or the stream the document came from. */
    @Override
    public void close() {
        Closeable from = closeable;
        if (from == null) {
            return;
        }
        try {
            from.close();
        } catch (IOException e) {
            throw new JsonException("Could not close the JSON document's source: " + e.getMessage(), e);
        }
    }

    /**
     * The document's bytes from the place the parser stands, read off the
     * stream as they are needed. The bytes behind that place are counted for
     * the location and let go of when the buffer is refilled, so the buffer
     * holds the value being read and what has arrived after it.
     */
    private static final class Window {

        /** What {@link #containerLength} answers for a value nested deeper than it may be. */
        static final int NESTED_TOO_DEEP = -2;

        private final InputStream in;
        private byte[] buffer;
        private int pos;
        private int limit;
        private boolean end;
        private final Documents.Counter counted = new Documents.Counter();
        private int countedTo;

        Window(byte[] head, InputStream in) {
            this.buffer = head;
            this.limit = head.length;
            this.in = in;
        }

        /** Whether at least that many bytes from the place the parser stands are buffered, once more have been read if they must be. */
        boolean has(int n) {
            while (limit - pos < n) {
                if (end) {
                    return false;
                }
                fill(n);
            }
            return true;
        }

        private void fill(int n) {
            if (pos > 0) {
                counted.count(buffer, countedTo, pos);
                System.arraycopy(buffer, pos, buffer, 0, limit - pos);
                limit -= pos;
                pos = 0;
                countedTo = 0;
            }
            if (n > buffer.length || limit == buffer.length) {
                buffer = Arrays.copyOf(buffer, Math.max(n, Math.max(buffer.length * 2, 8192)));
            }
            try {
                int read = in.read(buffer, limit, buffer.length - limit);
                if (read < 0) {
                    end = true;
                } else {
                    limit += read;
                }
            } catch (IOException e) {
                throw Documents.unreadable(e);
            }
        }

        /** The next byte that is not whitespace, the whitespace before it passed over, or -1 at the end of the document. */
        int peek() {
            while (true) {
                while (pos < limit) {
                    byte b = buffer[pos];
                    if (b != ' ' && b != '\n' && b != '\r' && b != '\t') {
                        return b & 0xFF;
                    }
                    pos++;
                }
                if (!has(1)) {
                    return -1;
                }
            }
        }

        /** Passes over bytes already buffered. */
        void skip(int n) {
            pos += n;
        }

        /** Steps back over that many of the bytes {@link #take} passed over last, which are still in the buffer. */
        void unread(int n) {
            pos -= n;
        }

        /** A copy of that many buffered bytes, passed over. */
        byte[] take(int n) {
            byte[] bytes = Arrays.copyOfRange(buffer, pos, pos + n);
            pos += n;
            return bytes;
        }

        Documents.Location location() {
            counted.count(buffer, countedTo, pos);
            countedTo = pos;
            return counted.location();
        }

        /** The length of the string at the place the parser stands, its quotes included, or of the rest of the document when it ends inside the string. */
        int stringLength() {
            int r = 1;
            boolean escaped = false;
            while (true) {
                for (int i = pos + r; i < limit; i++) {
                    byte b = buffer[i];
                    r++;
                    if (escaped) {
                        escaped = false;
                    } else if (b == '\\') {
                        escaped = true;
                    } else if (b == '"') {
                        return r;
                    }
                }
                if (!has(r + 1)) {
                    return limit - pos;
                }
            }
        }

        /** The length of the number or the literal at the place the parser stands: the bytes a number or a word of letters is made of. */
        int tokenLength(boolean number) {
            int r = 0;
            while (true) {
                for (int i = pos + r; i < limit; i++) {
                    byte b = buffer[i];
                    boolean part = number
                            ? (b >= '0' && b <= '9') || b == '-' || b == '+' || b == '.' || b == 'e' || b == 'E'
                            : b >= 'a' && b <= 'z';
                    if (!part) {
                        return r;
                    }
                    r++;
                }
                if (!has(r + 1)) {
                    return r;
                }
            }
        }

        /**
         * The length of the object or the array at the place the parser
         * stands, when it closes within that many bytes; -1 when it does not,
         * or the rest of the document when the document ends inside it. Its
         * strings are passed over, and its brackets counted, which is all it
         * takes to find the end; its syntax is the parser's of the value.
         */
        int containerLength(int max, int depthLeft) {
            int r = 0;
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            while (true) {
                for (int i = pos + r; i < limit; i++) {
                    if (r >= max) {
                        return -1;
                    }
                    byte b = buffer[i];
                    r++;
                    if (inString) {
                        if (escaped) {
                            escaped = false;
                        } else if (b == '\\') {
                            escaped = true;
                        } else if (b == '"') {
                            inString = false;
                        }
                    } else if (b == '"') {
                        inString = true;
                    } else if (b == '{' || b == '[') {
                        if (++depth > depthLeft) {
                            return NESTED_TOO_DEEP;
                        }
                    } else if (b == '}' || b == ']') {
                        if (--depth == 0) {
                            return r;
                        }
                    }
                }
                if (r >= max) {
                    return -1;
                }
                if (!has(r + 1)) {
                    return r;
                }
            }
        }
    }
}
