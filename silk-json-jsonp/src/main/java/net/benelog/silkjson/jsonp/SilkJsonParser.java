package net.benelog.silkjson.jsonp;

import java.io.Closeable;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Arrays;
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
 * A pull parser over a {@link JsonInput}. Each event is the input's next
 * step: {@link JsonInput#nextKey()} and {@link JsonInput#nextElement()}
 * inside a container, and the value {@link JsonInput#peek()} says comes next
 * read whole, so a string, a number, and a key are read once and held for
 * {@link #getString()}.
 *
 * <p>It parses a document held whole: one of at most
 * {@link Documents#STREAM_ABOVE} bytes, or a value a
 * {@link StreamingJsonParser} reads whole out of a larger one. One value is
 * the whole document, as RFC 8259 has it: {@link #hasNext()} answers false
 * after it, and throws {@link JsonParsingException} when anything but
 * whitespace follows.
 */
final class SilkJsonParser extends EventParser {

    private static final byte OBJECT = 1;
    private static final byte ARRAY = 2;

    private final JsonInput in;
    private final int length;
    private final Documents.Locator locator;
    private final @Nullable Closeable closeable;
    private final KeyStrategy strategy;

    /** The containers the parser is inside, innermost last. */
    private byte[] containers = new byte[16];
    private int depth;

    /** Whether the event before was a key, so that its value comes next. */
    private boolean valueNext;

    /** Whether the top-level value has been read, and whether what follows it has been checked. */
    private boolean finished;
    private boolean endChecked;

    private @Nullable Event current;

    /** The key, the string, or the number's text of the current event. */
    private @Nullable String text;

    SilkJsonParser(byte[] utf8, @Nullable Closeable closeable, KeyStrategy strategy) {
        this.length = utf8.length;
        this.in = JsonInput.of(utf8);
        this.locator = new Documents.Locator(utf8);
        this.closeable = closeable;
        this.strategy = strategy;
    }

    @Override
    public boolean hasNext() {
        if (!finished) {
            return true;
        }
        if (!endChecked) {
            endChecked = true;
            try {
                in.end();
            } catch (net.benelog.silkjson.JsonException e) {
                throw failure(e);
            }
        }
        return false;
    }

    @Override
    public Event next() {
        if (!hasNext()) {
            throw new NoSuchElementException("The document has no more events");
        }
        try {
            Event event;
            if (depth == 0) {
                event = value();
                finished = depth == 0;
            } else if (containers[depth - 1] == OBJECT) {
                if (valueNext) {
                    valueNext = false;
                    event = value();
                } else if (in.nextKey()) {
                    text = in.key();
                    valueNext = true;
                    event = Event.KEY_NAME;
                } else {
                    leave();
                    event = Event.END_OBJECT;
                }
            } else if (in.nextElement()) {
                event = value();
            } else {
                leave();
                event = Event.END_ARRAY;
            }
            current = event;
            return event;
        } catch (net.benelog.silkjson.JsonException e) {
            throw failure(e);
        }
    }

    private Event value() {
        return switch (in.peek()) {
            case OBJECT -> {
                in.object();
                enter(OBJECT);
                yield Event.START_OBJECT;
            }
            case ARRAY -> {
                in.array();
                enter(ARRAY);
                yield Event.START_ARRAY;
            }
            case STRING -> {
                text = in.readString();
                yield Event.VALUE_STRING;
            }
            case NUMBER -> {
                text = in.readNumber();
                yield Event.VALUE_NUMBER;
            }
            case BOOLEAN -> in.readBoolean() ? Event.VALUE_TRUE : Event.VALUE_FALSE;
            case NULL -> {
                in.readNull();
                yield Event.VALUE_NULL;
            }
        };
    }

    private void enter(byte container) {
        if (depth == containers.length) {
            containers = Arrays.copyOf(containers, depth * 2);
        }
        containers[depth++] = container;
    }

    /** Leaves the innermost container, whose closing bracket the input has just read. */
    private void leave() {
        depth--;
        finished = depth == 0;
    }

    private JsonParsingException failure(net.benelog.silkjson.JsonException e) {
        return new JsonParsingException(e.getMessage(), getLocation());
    }

    @Override
    public @Nullable Event currentEvent() {
        return current;
    }

    @Override
    public String getString() {
        String value = text;
        if (value == null || (current != Event.KEY_NAME && current != Event.VALUE_STRING && current != Event.VALUE_NUMBER)) {
            throw new IllegalStateException("getString() reads a key, a string, or a number, not " + current);
        }
        return value;
    }

    @Override
    public boolean isIntegralNumber() {
        String number = number("isIntegralNumber()");
        return SilkJsonNumber.isWhole(number) || new BigDecimal(number).scale() == 0;
    }

    /** {@code new BigDecimal(getString()).intValue()}, with no decimal made for a whole number a long holds. */
    @Override
    public int getInt() {
        String number = number("getInt()");
        return fitsLong(number) ? (int) Long.parseLong(number) : new BigDecimal(number).intValue();
    }

    /** {@code new BigDecimal(getString()).longValue()}, with no decimal made for a whole number a long holds. */
    @Override
    public long getLong() {
        String number = number("getLong()");
        return fitsLong(number) ? Long.parseLong(number) : new BigDecimal(number).longValue();
    }

    @Override
    public BigDecimal getBigDecimal() {
        return new BigDecimal(number("getBigDecimal()"));
    }

    private String number(String method) {
        String value = text;
        if (value == null || current != Event.VALUE_NUMBER) {
            throw new IllegalStateException(method + " reads a number, not " + current);
        }
        return value;
    }

    /** Whether a number is whole and has at most 18 digits, which a long holds whatever they are. */
    private static boolean fitsLong(String number) {
        return SilkJsonNumber.isWhole(number) && number.length() - (number.charAt(0) == '-' ? 1 : 0) <= 18;
    }

    /** Where the input stands: past the current event, or at the fault a {@link JsonParsingException} names. */
    @Override
    public JsonLocation getLocation() {
        return locator.at(in.position());
    }

    @Override
    public JsonObject getObject() {
        if (current != Event.START_OBJECT) {
            throw new IllegalStateException("getObject() reads an object from its START_OBJECT, not from " + current);
        }
        try {
            SilkJsonObject object = TreeReader.restOfObject(in, strategy);
            leave();
            current = Event.END_OBJECT;
            return object;
        } catch (net.benelog.silkjson.JsonException e) {
            throw failure(e);
        }
    }

    @Override
    public JsonArray getArray() {
        if (current != Event.START_ARRAY) {
            throw new IllegalStateException("getArray() reads an array from its START_ARRAY, not from " + current);
        }
        try {
            SilkJsonArray array = TreeReader.restOfArray(in, strategy);
            leave();
            current = Event.END_ARRAY;
            return array;
        } catch (net.benelog.silkjson.JsonException e) {
            throw failure(e);
        }
    }

    /** The value of the current event: a whole object or array from its start, a key as a string, or the scalar just read. */
    @Override
    public JsonValue getValue() {
        Event event = current;
        if (event == null) {
            throw new IllegalStateException("getValue() reads the value of an event, and next() has not been called");
        }
        return switch (event) {
            case START_OBJECT -> getObject();
            case START_ARRAY -> getArray();
            case KEY_NAME, VALUE_STRING -> new SilkJsonString(getString());
            case VALUE_NUMBER -> SilkJsonNumber.parsed(getString());
            case VALUE_TRUE -> JsonValue.TRUE;
            case VALUE_FALSE -> JsonValue.FALSE;
            case VALUE_NULL -> JsonValue.NULL;
            case END_OBJECT, END_ARRAY -> throw new IllegalStateException("getValue() has no value to read at " + event);
        };
    }

    @Override
    boolean inside() {
        return depth > 0;
    }

    /** Whether the document's value has been read to its end, for a parser that reads a value of a larger document through this one. */
    boolean isDone() {
        return finished;
    }

    /** How many bytes are left after the value, which that parser reads itself. */
    int unread() {
        return length - in.position();
    }

    /** Passes over the rest of the array the parser is in, to its END_ARRAY; does nothing outside an array. */
    @Override
    public void skipArray() {
        if (depth == 0 || containers[depth - 1] != ARRAY) {
            return;
        }
        try {
            while (in.nextElement()) {
                in.skipValue();
            }
        } catch (net.benelog.silkjson.JsonException e) {
            throw failure(e);
        }
        leave();
        current = Event.END_ARRAY;
    }

    /** Passes over the rest of the object the parser is in, to its END_OBJECT; does nothing outside an object. */
    @Override
    public void skipObject() {
        if (depth == 0 || containers[depth - 1] != OBJECT) {
            return;
        }
        try {
            if (valueNext) {
                valueNext = false;
                in.skipValue();
            }
            while (in.nextKey()) {
                in.skipValue();
            }
        } catch (net.benelog.silkjson.JsonException e) {
            throw failure(e);
        }
        leave();
        current = Event.END_OBJECT;
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
}
