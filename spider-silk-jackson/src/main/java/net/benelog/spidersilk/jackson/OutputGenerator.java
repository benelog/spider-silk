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
import tools.jackson.core.io.NumberOutput;
import tools.jackson.core.json.DupDetector;
import tools.jackson.core.json.JsonWriteFeature;
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
 *
 * <p>The features Jackson's own JSON generator reads are read here too, with
 * two that ask for text JSON has no syntax for refused instead: NaN and an
 * infinity with {@link JsonWriteFeature#WRITE_NAN_AS_STRINGS} disabled, and a
 * raw fragment. {@link JsonWriteFeature#ESCAPE_NON_ASCII} has no effect: the
 * output writes every string as UTF-8, which reads back as the same string.
 */
final class OutputGenerator extends JsonGenerator {

    private static final JacksonFeatureSet<StreamWriteCapability> CAPABILITIES =
            JacksonFeatureSet.fromDefaults(StreamWriteCapability.values());

    /** The largest scale, either way, of a decimal written plain: Jackson's own limit, which keeps {@code 1E+999999999} from becoming a gigabyte of zeros. */
    private static final int MAX_PLAIN_SCALE = 9999;

    private final JsonOutput out;
    private final ObjectWriteContext objectContext;
    private final StreamWriteConstraints constraints;
    private int features;
    private final int formatFeatures;
    private final boolean numbersAsStrings;
    private SimpleStreamWriteContext context;
    private boolean closed;

    OutputGenerator(JsonOutput out, ObjectWriteContext objectContext, StreamWriteConstraints constraints,
            int features, int formatFeatures) {
        this.out = out;
        this.objectContext = objectContext;
        this.constraints = constraints;
        this.features = features;
        this.formatFeatures = formatFeatures;
        this.numbersAsStrings = JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS.enabledIn(formatFeatures);
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
        return writeString(base64(variant, Arrays.copyOfRange(data, offset, offset + length)));
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
        writeString(base64(variant, bytes));
        return bytes.length;
    }

    /**
     * The base64 text of the bytes, with the line feed a MIME or PEM variant
     * breaks its lines with, as Jackson's generator writes it. The variant's
     * own {@code encode} writes the two characters of a JSON escape instead,
     * which the output would escape a second time.
     */
    private static String base64(Base64Variant variant, byte[] bytes) {
        return variant.encode(bytes, false, "\n");
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
        if (numbersAsStrings) {
            return writeString(Long.toString(value));
        }
        beforeValue();
        out.value(value);
        return this;
    }

    @Override
    public JsonGenerator writeNumber(@Nullable BigInteger value) {
        if (value == null) {
            return writeNull();
        }
        if (numbersAsStrings) {
            return writeString(value.toString());
        }
        beforeValue();
        out.value(value);
        return this;
    }

    /** NaN and an infinity go out as the strings Jackson writes them as, {@code "NaN"} and {@code "Infinity"}. */
    @Override
    public JsonGenerator writeNumber(double value) {
        if (numbersAsStrings || (!Double.isFinite(value) && nanAsString(value))) {
            return writeString(NumberOutput.toString(value, isEnabled(StreamWriteFeature.USE_FAST_DOUBLE_WRITER)));
        }
        beforeValue();
        out.value(value);
        return this;
    }

    @Override
    public JsonGenerator writeNumber(float value) {
        if (numbersAsStrings || (!Float.isFinite(value) && nanAsString(value))) {
            return writeString(NumberOutput.toString(value, isEnabled(StreamWriteFeature.USE_FAST_DOUBLE_WRITER)));
        }
        beforeValue();
        out.value(value);
        return this;
    }

    /** Whether a value that is not finite goes out as a string; Jackson writes it bare otherwise, which is not JSON, and is refused here. */
    private boolean nanAsString(double value) {
        if (!JsonWriteFeature.WRITE_NAN_AS_STRINGS.enabledIn(formatFeatures)) {
            throw new StreamWriteException(this, "Not a JSON number: " + value
                    + " (enable JsonWriteFeature.WRITE_NAN_AS_STRINGS to write it as a string)");
        }
        return true;
    }

    @Override
    public JsonGenerator writeNumber(@Nullable BigDecimal value) {
        if (value == null) {
            return writeNull();
        }
        if (!StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN.enabledIn(features)) {
            if (numbersAsStrings) {
                return writeString(value.toString());
            }
            beforeValue();
            out.value(value);
            return this;
        }
        int scale = value.scale();
        if (scale < -MAX_PLAIN_SCALE || scale > MAX_PLAIN_SCALE) {
            throw new StreamWriteException(this, String.format(
                    "Attempt to write plain `java.math.BigDecimal` (see JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)"
                            + " with illegal scale (%d): needs to be between [-%d, %d]",
                    scale, MAX_PLAIN_SCALE, MAX_PLAIN_SCALE));
        }
        String plain = value.toPlainString();
        if (numbersAsStrings) {
            return writeString(plain);
        }
        beforeValue();
        out.value(new PlainDecimal(value, plain));
        return this;
    }

    /**
     * A decimal whose text is its plain form. The output writes a decimal as
     * its {@code toString}, which would put {@code 1E-7} back, and parsing the
     * plain text instead would refuse a number past the range of a double.
     */
    @SuppressWarnings("serial")
    private static final class PlainDecimal extends BigDecimal {

        private final String plain;

        PlainDecimal(BigDecimal value, String plain) {
            super(value.unscaledValue(), value.scale());
            this.plain = plain;
        }

        @Override
        public String toString() {
            return plain;
        }
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
        if (numbersAsStrings) {
            return writeString(encodedValue);
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
