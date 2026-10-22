package net.benelog.silkjson.jsonp;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;

import jakarta.json.JsonValue;
import jakarta.json.stream.JsonGenerationException;
import jakarta.json.stream.JsonGenerator;

import net.benelog.silkjson.JsonOutput;

/**
 * A generator over a {@link JsonOutput} writing to a stream. Each call goes
 * to the output's method of the same shape, {@code writeStartObject(name)} to
 * {@code object(name)} and {@code write(name, value)} to {@code put}, and the
 * output escapes the strings, writes the numbers, and places the commas.
 *
 * <p>The generator keeps its own account of where it stands, so that a call
 * out of place is a {@link JsonGenerationException} that says what the call
 * needs, as JSON-P has it, before anything is written.
 */
final class SilkJsonGenerator implements JsonGenerator {

    private static final byte OBJECT = 1;
    private static final byte ARRAY = 2;

    private final Outputs.Guarded stream;
    private final JsonOutput out;

    private byte[] containers = new byte[16];
    private int depth;

    /** Whether {@link #writeKey} has named a member whose value is still to come. */
    private boolean named;

    /** Whether the document's one value has been started. */
    private boolean started;

    private boolean closed;

    SilkJsonGenerator(Outputs.Guarded stream) {
        this.stream = stream;
        this.out = JsonOutput.to(stream);
    }

    @Override
    public JsonGenerator writeStartObject() {
        beforeValue("writeStartObject()");
        out.object();
        enter(OBJECT);
        return this;
    }

    @Override
    public JsonGenerator writeStartObject(String name) {
        beforeMember("writeStartObject(name)", name);
        out.object(name);
        enter(OBJECT);
        return this;
    }

    @Override
    public JsonGenerator writeKey(String name) {
        beforeMember("writeKey(name)", name);
        out.name(name);
        named = true;
        return this;
    }

    @Override
    public JsonGenerator writeStartArray() {
        beforeValue("writeStartArray()");
        out.array();
        enter(ARRAY);
        return this;
    }

    @Override
    public JsonGenerator writeStartArray(String name) {
        beforeMember("writeStartArray(name)", name);
        out.array(name);
        enter(ARRAY);
        return this;
    }

    @Override
    public JsonGenerator write(String name, JsonValue value) {
        Objects.requireNonNull(value, "value");
        beforeMember("write(name, value)", name);
        out.name(name);
        Values.write(value, out);
        return this;
    }

    @Override
    public JsonGenerator write(String name, String value) {
        Objects.requireNonNull(value, "value");
        beforeMember("write(name, value)", name);
        out.put(name, value);
        return this;
    }

    @Override
    public JsonGenerator write(String name, BigInteger value) {
        Objects.requireNonNull(value, "value");
        beforeMember("write(name, value)", name);
        out.name(name).value(value);
        return this;
    }

    @Override
    public JsonGenerator write(String name, BigDecimal value) {
        Objects.requireNonNull(value, "value");
        beforeMember("write(name, value)", name);
        out.name(name).value(value);
        return this;
    }

    @Override
    public JsonGenerator write(String name, int value) {
        beforeMember("write(name, value)", name);
        out.put(name, value);
        return this;
    }

    @Override
    public JsonGenerator write(String name, long value) {
        beforeMember("write(name, value)", name);
        out.put(name, value);
        return this;
    }

    /** Writes the double as {@link Double#toString(double)} does; NaN and the infinities throw {@link NumberFormatException}. */
    @Override
    public JsonGenerator write(String name, double value) {
        SilkJsonNumber.finite(value);
        beforeMember("write(name, value)", name);
        out.put(name, value);
        return this;
    }

    @Override
    public JsonGenerator write(String name, boolean value) {
        beforeMember("write(name, value)", name);
        out.put(name, value);
        return this;
    }

    @Override
    public JsonGenerator writeNull(String name) {
        beforeMember("writeNull(name)", name);
        out.putNull(name);
        return this;
    }

    @Override
    public JsonGenerator writeEnd() {
        if (depth == 0) {
            throw new JsonGenerationException("writeEnd() closes an object or an array, and none is open");
        }
        if (named) {
            throw new JsonGenerationException("writeEnd() closes an object whose last key, written with writeKey(), has no value");
        }
        out.end();
        depth--;
        return this;
    }

    @Override
    public JsonGenerator write(JsonValue value) {
        Objects.requireNonNull(value, "value");
        beforeValue("write(value)");
        Values.write(value, out);
        return this;
    }

    @Override
    public JsonGenerator write(String value) {
        Objects.requireNonNull(value, "value");
        beforeValue("write(value)");
        out.value(value);
        return this;
    }

    @Override
    public JsonGenerator write(BigDecimal value) {
        Objects.requireNonNull(value, "value");
        beforeValue("write(value)");
        out.value(value);
        return this;
    }

    @Override
    public JsonGenerator write(BigInteger value) {
        Objects.requireNonNull(value, "value");
        beforeValue("write(value)");
        out.value(value);
        return this;
    }

    @Override
    public JsonGenerator write(int value) {
        beforeValue("write(value)");
        out.value(value);
        return this;
    }

    @Override
    public JsonGenerator write(long value) {
        beforeValue("write(value)");
        out.value(value);
        return this;
    }

    /** Writes the double as {@link Double#toString(double)} does; NaN and the infinities throw {@link NumberFormatException}. */
    @Override
    public JsonGenerator write(double value) {
        SilkJsonNumber.finite(value);
        beforeValue("write(value)");
        out.value(value);
        return this;
    }

    @Override
    public JsonGenerator write(boolean value) {
        beforeValue("write(value)");
        out.value(value);
        return this;
    }

    @Override
    public JsonGenerator writeNull() {
        beforeValue("writeNull()");
        out.nullValue();
        return this;
    }

    /**
     * Sends what the output holds and closes the stream, once the document's
     * value is whole. A document left open, or never started, is a
     * {@link JsonGenerationException}, and the stream is left open.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (depth > 0 || named || !started) {
            throw new JsonGenerationException("close() ends a whole JSON value, and "
                    + (started ? "an object or an array is still open" : "none was written"));
        }
        closed = true;
        out.flush();
        stream.close();
    }

    @Override
    public void flush() {
        out.flush();
    }

    /** A value is written in an array, after a key, or as the document's one value. */
    private void beforeValue(String method) {
        if (named) {
            named = false;
        } else if (depth > 0) {
            if (containers[depth - 1] != ARRAY) {
                throw new JsonGenerationException(method + " writes a value in an array or after writeKey(),"
                        + " and an object takes a name with it");
            }
        } else if (started) {
            throw new JsonGenerationException(method + " writes the document's one value, which is already written");
        } else {
            started = true;
        }
    }

    /** A member is written in an object, and not after a key whose value is still to come. */
    private void beforeMember(String method, String name) {
        Objects.requireNonNull(name, "name");
        if (depth == 0 || containers[depth - 1] != OBJECT) {
            throw new JsonGenerationException(method + " writes a member of an object, and "
                    + (depth == 0 ? "no object is open" : "an array is open"));
        }
        if (named) {
            throw new JsonGenerationException(method + " writes a member, and the key written with writeKey() still waits for its value");
        }
    }

    private void enter(byte container) {
        if (depth == containers.length) {
            containers = Arrays.copyOf(containers, depth * 2);
        }
        containers[depth++] = container;
    }
}
