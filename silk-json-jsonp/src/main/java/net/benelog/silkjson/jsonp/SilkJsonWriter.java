package net.benelog.silkjson.jsonp;

import java.util.Objects;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;
import jakarta.json.JsonWriter;

import net.benelog.silkjson.JsonOutput;

/** Writes one value through a {@link JsonOutput} to a stream, and flushes it. A writer writes once. */
final class SilkJsonWriter implements JsonWriter {

    private final Outputs.Guarded stream;
    private boolean used;

    SilkJsonWriter(Outputs.Guarded stream) {
        this.stream = stream;
    }

    @Override
    public void writeArray(JsonArray array) {
        write((JsonValue) array);
    }

    @Override
    public void writeObject(JsonObject object) {
        write((JsonValue) object);
    }

    @Override
    public void write(JsonStructure value) {
        write((JsonValue) value);
    }

    @Override
    public void write(JsonValue value) {
        Objects.requireNonNull(value, "value");
        if (used) {
            throw new IllegalStateException("A JsonWriter writes one value, and this one has written or been closed");
        }
        used = true;
        JsonOutput out = JsonOutput.to(stream);
        Values.write(value, out);
        out.flush();
    }

    /** Closes the stream or the writer the document went to; a write after it throws. */
    @Override
    public void close() {
        used = true;
        stream.close();
    }
}
