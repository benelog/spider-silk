package net.benelog.spidersilk.jackson;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.jspecify.annotations.Nullable;

import tools.jackson.core.Base64Variant;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.SerializableString;
import tools.jackson.core.StreamWriteCapability;
import tools.jackson.core.StreamWriteConstraints;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.TokenStreamContext;
import tools.jackson.core.TreeNode;
import tools.jackson.core.Version;
import tools.jackson.core.exc.StreamWriteException;
import tools.jackson.core.json.DupDetector;
import tools.jackson.core.util.JacksonFeatureSet;
import tools.jackson.core.util.SimpleStreamWriteContext;

import net.benelog.silkjson.Json;
import net.benelog.silkjson.JsonInput;
import net.benelog.silkjson.JsonOutput;

/**
 * A Jackson generator whose tokens go into a silk-json {@link JsonOutput}: the
 * extension point Jackson's Smile and CBOR backends write through. Every token
 * passes the output's own checks, so what Jackson serializes lands in the
 * buffer the response is sent from, with nothing serialized twice.
 *
 * <p>Raw output is the one thing the output has no method for, since it would
 * write text nobody checked. A raw value, which {@code @JsonRawValue} writes,
 * is parsed and written as the value it is; a raw fragment is refused.
 */
final class OutputGenerator extends JsonGenerator {

    private static final JacksonFeatureSet<StreamWriteCapability> CAPABILITIES =
            JacksonFeatureSet.fromDefaults(StreamWriteCapability.values());

    private final JsonOutput out;
    private final ObjectWriteContext objectContext;
    private final StreamWriteConstraints constraints;
    private int features;
    private SimpleStreamWriteContext context;
    private boolean closed;

    OutputGenerator(JsonOutput out, ObjectWriteContext objectContext, StreamWriteConstraints constraints, int features) {
        this.out = out;
        this.objectContext = objectContext;
        this.constraints = constraints;
        this.features = features;
        DupDetector dups = StreamWriteFeature.STRICT_DUPLICATE_DETECTION.enabledIn(features)
                ? DupDetector.rootDetector(this)
                : null;
        this.context = SimpleStreamWriteContext.createRootContext(dups);
    }

    // ---- configuration ----

    @Override
    public Version version() {
        return Version.unknownVersion();
    }

    @Override
    public TokenStreamContext streamWriteContext() {
        return context;
    }

    @Override
    public ObjectWriteContext objectWriteContext() {
        return objectContext;
    }

    @Override
    public @Nullable Object streamWriteOutputTarget() {
        return null;
    }

    @Override
    public int streamWriteOutputBuffered() {
        return -1;
    }

    @Override
    public @Nullable Object currentValue() {
        return context.currentValue();
    }

    @Override
    public void assignCurrentValue(@Nullable Object value) {
        context.assignCurrentValue(value);
    }

    @Override
    public JsonGenerator configure(StreamWriteFeature feature, boolean state) {
        features = state ? features | feature.getMask() : features & ~feature.getMask();
        return this;
    }

    @Override
    public boolean isEnabled(StreamWriteFeature feature) {
        return feature.enabledIn(features);
    }

    @Override
    public int streamWriteFeatures() {
        return features;
    }

    @Override
    public StreamWriteConstraints streamWriteConstraints() {
        return constraints;
    }

    @Override
    public boolean has(StreamWriteCapability capability) {
        return CAPABILITIES.isEnabled(capability);
    }

    @Override
    public JacksonFeatureSet<StreamWriteCapability> streamWriteCapabilities() {
        return CAPABILITIES;
    }

    // ---- structure ----

    @Override
    public JsonGenerator writeStartArray() {
        return writeStartArray(null);
    }

    @Override
    public JsonGenerator writeStartArray(@Nullable Object forValue) {
        beforeValue();
        context = context.createChildArrayContext(forValue);
        constraints.validateNestingDepth(context.getNestingDepth());
        out.array();
        return this;
    }

    @Override
    public JsonGenerator writeStartArray(@Nullable Object forValue, int size) {
        return writeStartArray(forValue);
    }

    @Override
    public JsonGenerator writeEndArray() {
        if (!context.inArray()) {
            throw new StreamWriteException(this, "Current context not an Array but " + context.typeDesc());
        }
        out.end();
        context = parentOf(context);
        return this;
    }

    @Override
    public JsonGenerator writeStartObject() {
        return writeStartObject(null);
    }

    @Override
    public JsonGenerator writeStartObject(@Nullable Object forValue) {
        beforeValue();
        context = context.createChildObjectContext(forValue);
        constraints.validateNestingDepth(context.getNestingDepth());
        out.object();
        return this;
    }

    @Override
    public JsonGenerator writeStartObject(@Nullable Object forValue, int size) {
        return writeStartObject(forValue);
    }

    @Override
    public JsonGenerator writeEndObject() {
        if (!context.inObject()) {
            throw new StreamWriteException(this, "Current context not an Object but " + context.typeDesc());
        }
        out.end();
        context = parentOf(context);
        return this;
    }

    @Override
    public JsonGenerator writeName(String name) {
        if (!context.writeName(name)) {
            throw new StreamWriteException(this, "Cannot write a property name, expecting a value");
        }
        out.name(name);
        return this;
    }

    @Override
    public JsonGenerator writeName(SerializableString name) {
        return writeName(name.getValue());
    }

    @Override
    public JsonGenerator writePropertyId(long id) {
        return writeName(Long.toString(id));
    }

    // ---- strings ----

    @Override
    public JsonGenerator writeString(@Nullable String text) {
        if (text == null) {
            return writeNull();
        }
        beforeValue();
        out.value(text);
        return this;
    }

    @Override
    public JsonGenerator writeString(Reader reader, int length) {
        StringBuilder text = new StringBuilder();
        char[] chunk = new char[1024];
        try {
            int left = length < 0 ? Integer.MAX_VALUE : length;
            while (left > 0) {
                int read = reader.read(chunk, 0, Math.min(chunk.length, left));
                if (read < 0) {
                    break;
                }
                text.append(chunk, 0, read);
                left -= read;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (length >= 0 && text.length() < length) {
            throw new StreamWriteException(this,
                    "Didn't read enough from reader: expected " + length + ", read " + text.length());
        }
        return writeString(text.toString());
    }

    @Override
    public JsonGenerator writeString(char[] text, int offset, int length) {
        return writeString(new String(text, offset, length));
    }

    @Override
    public JsonGenerator writeString(SerializableString text) {
        return writeString(text.getValue());
    }

    /** A string whose bytes are already escaped: read back through the parser, which checks and unescapes them. */
    @Override
    public JsonGenerator writeRawUTF8String(byte[] text, int offset, int length) {
        byte[] quoted = new byte[length + 2];
        quoted[0] = '"';
        System.arraycopy(text, offset, quoted, 1, length);
        quoted[length + 1] = '"';
        JsonInput in = JsonInput.of(quoted);
        String value = in.readString();
        in.end();
        return writeString(value);
    }

    @Override
    public JsonGenerator writeUTF8String(byte[] text, int offset, int length) {
        return writeString(new String(text, offset, length, StandardCharsets.UTF_8));
    }

    // ---- raw output ----

    @Override
    public JsonGenerator writeRaw(String text) {
        throw rawRefused();
    }

    @Override
    public JsonGenerator writeRaw(String text, int offset, int length) {
        throw rawRefused();
    }

    @Override
    public JsonGenerator writeRaw(char[] text, int offset, int length) {
        throw rawRefused();
    }

    @Override
    public JsonGenerator writeRaw(char c) {
        throw rawRefused();
    }

    private UnsupportedOperationException rawRefused() {
        return new UnsupportedOperationException(
                "A JsonOutput writes values, not text: a raw fragment cannot be checked as JSON");
    }

    /** A value already serialized, parsed first so that the output never holds text that is not JSON. */
    @Override
    public JsonGenerator writeRawValue(String text) {
        beforeValue();
        out.value(Json.parse(text));
        return this;
    }

    @Override
    public JsonGenerator writeRawValue(String text, int offset, int length) {
        return writeRawValue(text.substring(offset, offset + length));
    }

    @Override
    public JsonGenerator writeRawValue(char[] text, int offset, int length) {
        return writeRawValue(new String(text, offset, length));
    }

    // ---- binary ----

    @Override
    public JsonGenerator writeBinary(Base64Variant variant, byte[] data, int offset, int length) {
        return writeString(variant.encode(Arrays.copyOfRange(data, offset, offset + length)));
    }

    @Override
    public int writeBinary(Base64Variant variant, InputStream data, int length) {
        byte[] bytes;
        try {
            bytes = length < 0 ? data.readAllBytes() : data.readNBytes(length);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (length >= 0 && bytes.length < length) {
            throw new StreamWriteException(this,
                    "Too few bytes available: missing " + (length - bytes.length) + " bytes (out of " + length + ")");
        }
        writeString(variant.encode(bytes));
        return bytes.length;
    }

    // ---- numbers and literals ----

    @Override
    public JsonGenerator writeNumber(short value) {
        return writeNumber((long) value);
    }

    @Override
    public JsonGenerator writeNumber(int value) {
        return writeNumber((long) value);
    }

    @Override
    public JsonGenerator writeNumber(long value) {
        beforeValue();
        out.value(value);
        return this;
    }

    @Override
    public JsonGenerator writeNumber(@Nullable BigInteger value) {
        if (value == null) {
            return writeNull();
        }
        beforeValue();
        out.value(value);
        return this;
    }

    @Override
    public JsonGenerator writeNumber(double value) {
        beforeValue();
        out.value(value);
        return this;
    }

    @Override
    public JsonGenerator writeNumber(float value) {
        beforeValue();
        out.value(value);
        return this;
    }

    @Override
    public JsonGenerator writeNumber(@Nullable BigDecimal value) {
        if (value == null) {
            return writeNull();
        }
        beforeValue();
        if (StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN.enabledIn(features)) {
            out.value(new BigDecimal(value.toPlainString()));
        } else {
            out.value(value);
        }
        return this;
    }

    /** A number already written as text, read as a decimal so that text which is no number is refused. */
    @Override
    public JsonGenerator writeNumber(@Nullable String encodedValue) {
        if (encodedValue == null) {
            return writeNull();
        }
        BigDecimal value;
        try {
            value = new BigDecimal(encodedValue);
        } catch (NumberFormatException e) {
            throw new StreamWriteException(this, "Not a JSON number: " + encodedValue, e);
        }
        beforeValue();
        out.value(value);
        return this;
    }

    @Override
    public JsonGenerator writeBoolean(boolean value) {
        beforeValue();
        out.value(value);
        return this;
    }

    @Override
    public JsonGenerator writeNull() {
        beforeValue();
        out.nullValue();
        return this;
    }

    // ---- values Jackson serializes ----

    @Override
    public JsonGenerator writePOJO(@Nullable Object value) {
        if (value == null) {
            return writeNull();
        }
        objectContext.writeValue(this, value);
        return this;
    }

    @Override
    public JsonGenerator writeTree(@Nullable TreeNode tree) {
        if (tree == null) {
            return writeNull();
        }
        objectContext.writeTree(this, tree);
        return this;
    }

    // ---- life cycle ----

    /** Nothing to send: the output belongs to whoever handed it over, and they flush it. */
    @Override
    public void flush() {
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
    }

    private void beforeValue() {
        if (!context.writeValue()) {
            throw new StreamWriteException(this, "Cannot write a value, expecting a property name");
        }
    }

    private static SimpleStreamWriteContext parentOf(SimpleStreamWriteContext child) {
        SimpleStreamWriteContext parent = child.clearAndGetParent();
        if (parent == null) {
            throw new IllegalStateException("The root context has no parent");
        }
        return parent;
    }
}
