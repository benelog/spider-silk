package net.benelog.silkjson.jsonp;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

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
 * arrives, in runs: the bytes buffered from the place the parser stands, at
 * most {@code piece} of them, go to one {@link SilkJsonParser}, which reads as
 * many values as they hold, so the values of a large document share a
 * {@link JsonInput} a run at a time. The bytes end where the buffer or the
 * piece does, inside a value too. The run stops before the step that reaches
 * their end, and the parser takes over there: it enters the containers the
 * run was inside, reads that step off the stream, and hands what follows to
 * the next run. A string or a number no run holds is read whole by a parser
 * of its own. The bytes held at once are a run's, or those of the largest
 * value that is not an object or an array.
 *
 * <p>Events, values, failures, and locations are those a {@link SilkJsonParser}
 * over the whole document answers. A failure inside a value read whole is
 * reported at its place in the document, and the step a run fails on is read
 * again here, which names the failure where it is.
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

    /** The parser of a value read whole or of a run, whose events are the current ones, and where its bytes start in the document. */
    private @Nullable SilkJsonParser held;
    private Documents.Location heldStart = new Documents.Location(1, 1, 0);

    /** The most bytes a value of the last run took, or the bytes of the last value read whole, fewer than which buffered most likely end inside the next one. */
    private int widest;

    /** Whether the document's object or array runs past the piece its run holds. */
    private boolean documentLonger;

    StreamingJsonParser(byte[] head, InputStream rest, @Nullable Closeable closeable, KeyStrategy strategy, int piece) {
        this.window = new Window(head, rest, piece);
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
        Event event = heldNext();
        if (event == null) {
            event = step();
        }
        current = event;
        return event;
    }

    /** The next event of the parser held, or null once it has none, and the next event is read here. */
    private @Nullable Event heldNext() {
        SilkJsonParser parser = held;
        if (parser == null) {
            return null;
        }
        if (parser.isRun()) {
            if (parser.runEnded()) {
                window.skip(parser.position() - parser.floor());
                widest = parser.widest();
                held = null;
                return null;
            }
            int at = parser.position();
            Event before = parser.currentEvent();
            try {
                Event event = parser.next();
                if (event != Event.VALUE_NUMBER || !parser.atEnd()) {
                    finish(parser);
                    return event;
                }
            } catch (JsonParsingException e) {
                // The bytes end inside the value, or it is at fault, which a read here names where it is.
            }
            stopRun(parser, at, before);
            return null;
        }
        if (parser.isDone()) {
            held = null;
            return null;
        }
        Event event;
        try {
            event = parser.next();
        } catch (JsonParsingException e) {
            throw placed(e);
        }
        finish(parser);
        return event;
    }

    /** Ends the document once the parser held has read the document's value to its end, a run's bytes passed over. */
    private void finish(SilkJsonParser parser) {
        if (parser.isDone()) {
            if (parser.isRun()) {
                window.skip(parser.position());
            }
            valueRead();
        }
    }

    /** The next event read here, off the stream. */
    private Event step() {
        if (depth == 0) {
            return value();
        }
        if (containers[depth - 1] == OBJECT) {
            if (valueNext) {
                valueNext = false;
                return value();
            }
            return member();
        }
        return element();
    }

    /** The next member of an object read here: the first event of a run of them, its key, or the object's end. */
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
        }
        Event run = run(OBJECT);
        return run != null ? run : key();
    }

    /** The key of the member that comes next, read here. */
    private Event key() {
        if (window.peek() != '"') {
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

    /** The next element of an array read here: the first event of a run of them, of the element, or the array's end. */
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
        Event run = run(ARRAY);
        return run != null ? run : value();
    }

    /**
     * Hands the elements or the members that come next to a run, and answers
     * its first event. Answers null when the next one is read here instead:
     * when what comes next starts no element or member, which a read here
     * names as a fault, when fewer bytes are buffered than the {@link #widest}
     * value before took, so that they most likely end inside the next one,
     * or when the bytes buffered do not hold its first event.
     */
    private @Nullable Event run(byte container) {
        int b = window.peek();
        boolean starts = container == OBJECT
                ? b == '"'
                : b == '{' || b == '[' || b == '"' || b == '-' || (b >= '0' && b <= '9') || b == 't' || b == 'f' || b == 'n';
        return starts && window.buffered(piece) >= widest ? runFrom(depth) : null;
    }

    /**
     * Hands the bytes buffered from the place the parser stands, at most a
     * piece of them, to a run, and answers its first event, or null when they
     * do not hold it. The bytes are copied after a bracket for each container
     * read here, so the run parses them in the containers they are in.
     */
    private @Nullable Event runFrom(int floor) {
        int length = window.buffered(piece);
        byte[] bytes = window.copy(floor, length);
        for (int i = 0; i < floor; i++) {
            bytes[i] = containers[i] == OBJECT ? (byte) '{' : (byte) '[';
        }
        Documents.Location start = window.location();
        heldStart = new Documents.Location(start.line(), start.column() - floor, start.offset() - floor);
        held = SilkJsonParser.run(bytes, floor, strategy);
        Event event = heldNext();
        if (event == null) {
            // The first value runs past the bytes, a long string or number most likely: no run is
            // tried for the values that follow until one read here is shorter.
            widest = Integer.MAX_VALUE;
        }
        return event;
    }

    /**
     * Takes over from a run whose step the bytes ended inside, or failed, at
     * the place the run stood before that step: the containers the run had
     * entered are entered here, and the step is read here, off the stream.
     */
    private void stopRun(SilkJsonParser run, int at, @Nullable Event before) {
        held = null;
        window.skip(at - run.floor());
        for (int level = run.floor(); level < run.depth(); level++) {
            enter(run.isObject(level) ? OBJECT : ARRAY);
        }
        if (run.depth() > run.floor()) {
            first = before == Event.START_OBJECT || before == Event.START_ARRAY;
        }
        valueNext = before == Event.KEY_NAME;
    }

    /**
     * The value that comes next. The document's object or array starts a run,
     * which reads as much of it as the bytes buffered hold. Inside, an object
     * or an array of more than a piece is entered here, and anything else is
     * read whole.
     */
    private Event value() {
        int b = window.peek();
        if (b < 0) {
            throw fault("Missing value");
        }
        if (b == '{' || b == '[') {
            if (depth == 0) {
                window.topUp(piece);
                documentLonger = window.buffered(piece) == piece;
                Event run = runFrom(0);
                if (run != null) {
                    return run;
                }
            }
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
        widest = length;
        heldStart = window.location();
        SilkJsonParser parser = new SilkJsonParser(window.take(length), null, strategy);
        held = parser;
        Event event;
        try {
            event = parser.next();
        } catch (JsonParsingException e) {
            throw placed(e);
        }
        if (parser.isDone()) {
            window.unread(parser.unread());
            valueRead();
        }
        return event;
    }

    /** A value read whole, or a run of the document's value, has had its last event, which ends the document when it was the document's value. */
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

    /** A failure of a value read whole, reported at its place in the document. */
    private JsonParsingException placed(JsonParsingException e) {
        return new JsonParsingException(rebased(e.getMessage(), heldStart), heldStart.plus(e.getLocation()));
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
        return fault(message, window.location());
    }

    private static JsonParsingException fault(String message, Documents.Location at) {
        return new JsonParsingException("Near character " + at.offset() + ": " + message, at);
    }

    @Override
    boolean inside() {
        SilkJsonParser parser = held;
        return depth > 0 || (parser != null && parser.inside());
    }

    @Override
    public @Nullable Event currentEvent() {
        return current;
    }

    @Override
    public String getString() {
        SilkJsonParser parser = held;
        if (parser != null) {
            return parser.getString();
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

    /** The parser held, which a number accessor reads from: a number is never read here. */
    private SilkJsonParser number(String method) {
        SilkJsonParser parser = held;
        if (parser == null) {
            throw new IllegalStateException(method + " reads a number, not " + current);
        }
        return parser;
    }

    @Override
    public JsonLocation getLocation() {
        return location();
    }

    private Documents.Location location() {
        SilkJsonParser parser = held;
        return parser != null ? heldStart.plus(parser.getLocation()) : window.location();
    }

    @Override
    public JsonValue getValue() {
        Event event = current;
        if (event == Event.START_OBJECT) {
            return getObject();
        }
        if (event == Event.START_ARRAY) {
            return getArray();
        }
        SilkJsonParser parser = held;
        if (parser != null) {
            return parser.getValue();
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
        SilkJsonParser parser = held;
        if (parser != null) {
            int at = parser.position();
            if (holdsWhole(parser)) {
                try {
                    JsonObject read = parser.getObject();
                    settle(parser);
                    return read;
                } catch (JsonParsingException e) {
                    if (!parser.isRun()) {
                        throw placed(e);
                    }
                }
            }
            stopRun(parser, at, Event.START_OBJECT);
        }
        Members members = new Members(strategy);
        while (next() != Event.END_OBJECT) {
            String name = getString();
            if (strategy == KeyStrategy.NONE && members.has(name)) {
                throw fault("Duplicate key in a JSON object: " + TreeReader.shortened(name), location());
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
        SilkJsonParser parser = held;
        if (parser != null) {
            int at = parser.position();
            if (holdsWhole(parser)) {
                try {
                    JsonArray read = parser.getArray();
                    settle(parser);
                    return read;
                } catch (JsonParsingException e) {
                    if (!parser.isRun()) {
                        throw placed(e);
                    }
                }
            }
            stopRun(parser, at, Event.START_ARRAY);
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
        SilkJsonParser parser = held;
        if (parser != null && parser.inside()) {
            int at = parser.position();
            Event before = parser.currentEvent();
            if (holdsWhole(parser)) {
                try {
                    parser.skipArray();
                    settle(parser);
                    return;
                } catch (JsonParsingException e) {
                    if (!parser.isRun()) {
                        throw placed(e);
                    }
                }
            }
            stopRun(parser, at, before);
        }
        if (depth > 0 && containers[depth - 1] == ARRAY) {
            skip();
        }
    }

    /** Passes over the rest of the innermost object, to its END_OBJECT; does nothing when the innermost container is an array. */
    @Override
    public void skipObject() {
        SilkJsonParser parser = held;
        if (parser != null && parser.inside()) {
            int at = parser.position();
            Event before = parser.currentEvent();
            if (holdsWhole(parser)) {
                try {
                    parser.skipObject();
                    settle(parser);
                    return;
                } catch (JsonParsingException e) {
                    if (!parser.isRun()) {
                        throw placed(e);
                    }
                }
            }
            stopRun(parser, at, before);
        }
        if (depth > 0 && containers[depth - 1] == OBJECT) {
            skip();
        }
    }

    /**
     * Whether the parser held reads the rest of its innermost container
     * whole, all or nothing. A value read whole does, and so does a run, but
     * for the document's own object or array when that runs past the run's
     * piece: it is read here, a value at a time, rather than parsed to the
     * piece's end for nothing.
     */
    private boolean holdsWhole(SilkJsonParser parser) {
        return !parser.isRun() || parser.floor() > 0 || parser.depth() > 1 || !documentLonger;
    }

    /** Reads the events of the innermost container read here to its end. */
    private void skip() {
        int inside = depth;
        while (depth >= inside) {
            next();
        }
    }

    /** Takes the event a call left the parser held at as the current one. */
    private void settle(SilkJsonParser parser) {
        current = parser.currentEvent();
        finish(parser);
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
        private final int least;
        private byte[] buffer;
        private int pos;
        private int limit;
        private boolean end;

        /** Whether the last read filled the buffer to its end, as a stream with more to come does. */
        private boolean filled;
        private final Documents.Counter counted = new Documents.Counter();
        private int countedTo;

        /**
         * Takes the bytes read ahead and the stream of the rest. The buffer
         * doubles, up to a piece, each time a read fills it, so that a run of
         * a long document has a piece to read, and a short one takes no more
         * than it needs.
         */
        Window(byte[] head, InputStream in, int piece) {
            this.buffer = head;
            this.limit = head.length;
            this.in = in;
            this.least = piece;
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
            } else if (filled && buffer.length < least) {
                buffer = Arrays.copyOf(buffer, Math.min(buffer.length * 2, least));
            }
            try {
                int read = in.read(buffer, limit, buffer.length - limit);
                if (read < 0) {
                    end = true;
                } else {
                    limit += read;
                }
                filled = limit == buffer.length;
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

        /** How many bytes are buffered from the place the parser stands, at most that many; none are read for it. */
        int buffered(int max) {
            return Math.min(limit - pos, max);
        }

        /** Reads off the stream until that many bytes are buffered or the document ends, a read at a time, as many as the buffer takes. */
        void topUp(int max) {
            while (limit - pos < max && !end) {
                fill(limit - pos + 1);
            }
        }

        /**
         * A copy of that many buffered bytes from the place the parser
         * stands, after that much room for the caller to write; the parser
         * stays where it stands. The room is copied from the bytes passed
         * over, when there are as many, so the array is not cleared first.
         */
        byte[] copy(int room, int n) {
            if (pos >= room) {
                return Arrays.copyOfRange(buffer, pos - room, pos + n);
            }
            byte[] bytes = new byte[room + n];
            System.arraycopy(buffer, pos, bytes, room, n);
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
