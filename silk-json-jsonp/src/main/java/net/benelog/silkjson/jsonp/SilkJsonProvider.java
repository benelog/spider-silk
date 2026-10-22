package net.benelog.silkjson.jsonp;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonBuilderFactory;
import jakarta.json.JsonMergePatch;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonPatch;
import jakarta.json.JsonPatchBuilder;
import jakarta.json.JsonPointer;
import jakarta.json.JsonReader;
import jakarta.json.JsonReaderFactory;
import jakarta.json.JsonString;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;
import jakarta.json.JsonWriter;
import jakarta.json.JsonWriterFactory;
import jakarta.json.spi.JsonProvider;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonGeneratorFactory;
import jakarta.json.stream.JsonParser;
import jakarta.json.stream.JsonParserFactory;

import org.jspecify.annotations.Nullable;

import net.benelog.silkjson.JsonInput;
import net.benelog.silkjson.JsonOutput;

/**
 * Jakarta JSON Processing 2.1 on silk-json. The parser and the reader read
 * with a {@link JsonInput}, the generator and the writer write with a
 * {@link JsonOutput}, and the tree they read into and write from is immutable
 * and built with no reflection. JSON Pointer, JSON Patch, and JSON Merge
 * Patch work on any provider's tree.
 *
 * <p>A provider made with its constructor is found by nothing, and is the
 * way to use one:
 *
 * <pre>{@code
 * static final JsonProvider JSON = new SilkJsonProvider();
 *
 * JsonObject deck = JSON.createObjectBuilder().add("name", "French").build();
 * JsonObject read = JSON.createReader(new StringReader(text)).readObject();
 * }</pre>
 *
 * <p>The module also names this class in
 * {@code META-INF/services/jakarta.json.spi.JsonProvider}, so the static
 * methods of {@link jakarta.json.Json} find it. They look the provider up
 * with a {@link java.util.ServiceLoader} on every call, which a provider of
 * your own skips, as does a factory made from one.
 *
 * <p>A document goes to the parser as UTF-8: a byte stream in UTF-8 as it is
 * read, and any other input decoded and encoded once more, a buffer at a
 * time. A {@link Reader}'s document, and a byte stream in UTF-16 or UTF-32,
 * which the stream's first bytes tell apart, cost that second pass. A
 * document of at most 1 MiB is read whole and parsed by one
 * {@link JsonInput}; a larger one is parsed as it is read, a value at a time,
 * and is never held whole.
 *
 * <p>All methods are safe for concurrent use, and the provider holds nothing:
 * every factory method makes a new object.
 */
public final class SilkJsonProvider extends JsonProvider {

    private static final SilkJsonParserFactory PARSERS = new SilkJsonParserFactory(Config.DEFAULT);
    private static final SilkJsonGeneratorFactory GENERATORS = new SilkJsonGeneratorFactory(Config.DEFAULT);
    private static final SilkJsonReaderFactory READERS = new SilkJsonReaderFactory(Config.DEFAULT);
    private static final SilkJsonWriterFactory WRITERS = new SilkJsonWriterFactory(Config.DEFAULT);
    private static final SilkJsonBuilderFactory BUILDERS = new SilkJsonBuilderFactory(Config.DEFAULT);

    /** The provider. {@link java.util.ServiceLoader} calls this too, when {@link jakarta.json.Json} looks for one. */
    public SilkJsonProvider() {
    }

    @Override
    public JsonParser createParser(Reader reader) {
        return PARSERS.createParser(reader);
    }

    /** A parser of a byte stream, whose encoding is told from its first bytes as RFC 4627 has it. */
    @Override
    public JsonParser createParser(InputStream in) {
        return PARSERS.createParser(in);
    }

    /** Parser factories honour {@link jakarta.json.JsonConfig#KEY_STRATEGY}, which {@code getObject()} keeps a repeated key by. */
    @Override
    public JsonParserFactory createParserFactory(@Nullable Map<String, ?> config) {
        return new SilkJsonParserFactory(Config.of(config));
    }

    @Override
    public JsonGenerator createGenerator(Writer writer) {
        return GENERATORS.createGenerator(writer);
    }

    /** A generator writing UTF-8. */
    @Override
    public JsonGenerator createGenerator(OutputStream out) {
        return GENERATORS.createGenerator(out);
    }

    /** Generator factories honour {@link JsonGenerator#PRETTY_PRINTING}, on for any value but {@code false}. */
    @Override
    public JsonGeneratorFactory createGeneratorFactory(@Nullable Map<String, ?> config) {
        return new SilkJsonGeneratorFactory(Config.of(config));
    }

    @Override
    public JsonReader createReader(Reader reader) {
        return READERS.createReader(reader);
    }

    /** A reader of a byte stream, whose encoding is told from its first bytes as RFC 4627 has it. */
    @Override
    public JsonReader createReader(InputStream in) {
        return READERS.createReader(in);
    }

    @Override
    public JsonWriter createWriter(Writer writer) {
        return WRITERS.createWriter(writer);
    }

    /** A writer writing UTF-8. */
    @Override
    public JsonWriter createWriter(OutputStream out) {
        return WRITERS.createWriter(out);
    }

    /** Writer factories honour {@link JsonGenerator#PRETTY_PRINTING}, on for any value but {@code false}. */
    @Override
    public JsonWriterFactory createWriterFactory(@Nullable Map<String, ?> config) {
        return new SilkJsonWriterFactory(Config.of(config));
    }

    /** Reader factories honour {@link jakarta.json.JsonConfig#KEY_STRATEGY}, which keeps the last value of a repeated key unless it says otherwise. */
    @Override
    public JsonReaderFactory createReaderFactory(@Nullable Map<String, ?> config) {
        return new SilkJsonReaderFactory(Config.of(config));
    }

    @Override
    public JsonObjectBuilder createObjectBuilder() {
        return BUILDERS.createObjectBuilder();
    }

    @Override
    public JsonObjectBuilder createObjectBuilder(JsonObject object) {
        return BUILDERS.createObjectBuilder(object);
    }

    /**
     * A builder holding a map's entries, each value taken as the JSON-P value
     * it stands for: null, a JSON-P value or builder, a {@code String}, a
     * {@code Boolean}, a number as {@link #createValue(Number)} takes it, or a
     * collection, a map, or an array of these. An empty {@link java.util.Optional} leaves its key out,
     * and a present one stands for its value. The types are told apart with
     * {@code instanceof}; anything else is an {@link IllegalArgumentException}.
     */
    @Override
    @SuppressWarnings("unchecked")
    public JsonObjectBuilder createObjectBuilder(Map<String, ?> map) {
        return BUILDERS.createObjectBuilder((Map<String, Object>) map);
    }

    @Override
    public JsonArrayBuilder createArrayBuilder() {
        return BUILDERS.createArrayBuilder();
    }

    @Override
    public JsonArrayBuilder createArrayBuilder(JsonArray array) {
        return BUILDERS.createArrayBuilder(array);
    }

    /** A builder holding a collection's elements, each taken as {@link #createObjectBuilder(Map)} takes a value. */
    @Override
    public JsonArrayBuilder createArrayBuilder(Collection<?> collection) {
        return BUILDERS.createArrayBuilder(collection);
    }

    /** Builder factories honour {@link jakarta.json.JsonConfig#KEY_STRATEGY}, which the object builders keep a repeated key by. */
    @Override
    public JsonBuilderFactory createBuilderFactory(@Nullable Map<String, ?> config) {
        return new SilkJsonBuilderFactory(Config.of(config));
    }

    @Override
    public JsonPointer createPointer(String jsonPointer) {
        return new SilkJsonPointer(jsonPointer);
    }

    @Override
    public JsonPatchBuilder createPatchBuilder() {
        return new SilkJsonPatchBuilder();
    }

    @Override
    public JsonPatchBuilder createPatchBuilder(JsonArray array) {
        return new SilkJsonPatchBuilder(Objects.requireNonNull(array, "array"));
    }

    @Override
    public JsonPatch createPatch(JsonArray array) {
        return new SilkJsonPatch(array);
    }

    @Override
    public JsonPatch createDiff(JsonStructure source, JsonStructure target) {
        return new SilkJsonPatch(SilkJsonPatch.diff(Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(target, "target")));
    }

    @Override
    public JsonMergePatch createMergePatch(JsonValue patch) {
        return new SilkJsonMergePatch(patch);
    }

    @Override
    public JsonMergePatch createMergeDiff(JsonValue source, JsonValue target) {
        return new SilkJsonMergePatch(SilkJsonMergePatch.diff(Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(target, "target")));
    }

    @Override
    public JsonString createValue(String value) {
        return new SilkJsonString(Objects.requireNonNull(value, "value"));
    }

    @Override
    public JsonNumber createValue(int value) {
        return SilkJsonNumber.of(value);
    }

    @Override
    public JsonNumber createValue(long value) {
        return SilkJsonNumber.of(value);
    }

    /** The number {@link BigDecimal#valueOf(double)} makes; NaN and the infinities throw {@link NumberFormatException}. */
    @Override
    public JsonNumber createValue(double value) {
        return SilkJsonNumber.of(value);
    }

    @Override
    public JsonNumber createValue(BigDecimal value) {
        return SilkJsonNumber.of(Objects.requireNonNull(value, "value"));
    }

    @Override
    public JsonNumber createValue(BigInteger value) {
        return SilkJsonNumber.of(Objects.requireNonNull(value, "value"));
    }

    /**
     * Any number: a whole number of a primitive type as a long, a
     * {@code Double} as {@link #createValue(double)} takes it, and any other,
     * a {@code Float} or a {@link Number} of your own included, as the
     * decimal its {@code toString()} writes.
     */
    @Override
    public JsonNumber createValue(Number number) {
        return SilkJsonNumber.of(Objects.requireNonNull(number, "number"));
    }
}
