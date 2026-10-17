package net.benelog.spidersilk.jackson;

import java.time.DateTimeException;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import tools.jackson.core.FormatSchema;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.PrettyPrinter;
import tools.jackson.core.SerializableString;
import tools.jackson.core.StreamWriteConstraints;
import tools.jackson.core.TokenStreamFactory;
import tools.jackson.core.TreeNode;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.io.CharacterEscapes;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.core.tree.ArrayTreeNode;
import tools.jackson.core.tree.ObjectTreeNode;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.ValueInstantiationException;
import tools.jackson.databind.json.JsonMapper;

import net.benelog.silkjson.JsonCodec;
import net.benelog.silkjson.JsonException;
import net.benelog.silkjson.JsonInput;
import net.benelog.silkjson.JsonOutput;
import net.benelog.silkjson.JsonReader;
import net.benelog.silkjson.JsonWriter;

/**
 * Jackson (https://github.com/FasterXML/jackson) behind the {@link JsonWriter}
 * and {@link JsonReader} a handler already hands to {@code WebResponse.json},
 * {@code req.bodyJson}, {@code req.bodyNdjson}, and a {@code JsonSink}.
 *
 * <p>The mapper is handed over once, here, and every writer and reader made
 * from this object binds with it, so a handler names a type and nothing else:
 *
 * <pre>{@code
 * static final JacksonCodecs JACKSON = new JacksonCodecs(JsonMapper.builder().build());
 * static final JsonWriter<Deck> DECK = JACKSON.writer(Deck.class);
 * static final JsonReader<NewDeck> NEW_DECK = JACKSON.reader(NewDeck.class);
 *
 * NewDeck body = req.bodyJson(NEW_DECK);
 * return WebResponse.json(deckService.create(body.name()), DECK);
 * }</pre>
 *
 * <p>A writer hands Jackson a generator over the {@link JsonOutput} it is
 * given, so the document is serialized once, straight into the buffer the
 * response goes out from, with its {@code Content-Length} counted as before.
 *
 * <p>A reader parses the body's bytes with Jackson. Input Jackson rejects —
 * bad syntax, a value of the wrong type, a creator that throws
 * {@link IllegalArgumentException}, or {@link NullPointerException} for a
 * property the body left out — becomes a {@link JsonException}, which
 * {@code req.bodyJson(reader)} answers with 400, as it does a hand-written
 * reader's. A mapping Jackson cannot build is the program's mistake rather
 * than the client's, and stays a 500. A body that is JSON null is refused as
 * well, so a handler never receives null in place of a value.
 *
 * <p>Jackson binds by reflection, which core does not: this module is where
 * an application chooses it, and a native image then needs reflection
 * entries for the types it binds.
 */
public final class JacksonCodecs {

    private final ObjectMapper mapper;
    private final ObjectWriter anyType;
    private final ObjectWriteContext writeContext;
    private final StreamWriteConstraints constraints;

    /** A {@link JsonMapper} with Jackson's defaults. */
    public JacksonCodecs() {
        this(JsonMapper.builder().build());
    }

    /**
     * Binds with a mapper of your own, for its modules, naming strategy, or features.
     *
     * @throws IllegalArgumentException if the mapper has
     *         {@link JsonWriteFeature#ESCAPE_NON_ASCII} on. The output writes
     *         every string as UTF-8 and has no ASCII-only mode, so the feature
     *         would be ignored without a word; it is refused here instead.
     */
    public JacksonCodecs(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        if (JsonWriteFeature.ESCAPE_NON_ASCII.enabledIn(mapper.serializationConfig().getFormatWriteFeatures())) {
            throw new IllegalArgumentException("JsonWriteFeature.ESCAPE_NON_ASCII is not supported:"
                    + " the output writes every string as UTF-8. Disable it on the mapper JacksonCodecs takes");
        }
        this.anyType = mapper.writer();
        this.writeContext = new MapperWriteContext(mapper);
        this.constraints = mapper.tokenStreamFactory().streamWriteConstraints();
    }

    /**
     * A writer for any value, serialized as the type it is at run time:
     * {@code WebResponse.json(deck, JACKSON.writer())}.
     */
    public JsonWriter<Object> writer() {
        return (value, out) -> write(anyType, value, out);
    }

    /**
     * A writer for values of that type. Jackson looks its serializer up once,
     * here, and a polymorphic type is written with the type information its
     * declared type carries.
     */
    public <T> JsonWriter<T> writer(Class<T> type) {
        return codec(type);
    }

    /** A writer for a generic type, such as {@code new TypeReference<List<Deck>>() {}}. */
    public <T> JsonWriter<T> writer(TypeReference<T> type) {
        return codec(type);
    }

    /** A reader for values of that type: {@code req.bodyJson(JACKSON.reader(NewDeck.class))}. */
    public <T> JsonReader<T> reader(Class<T> type) {
        return codec(type);
    }

    /** A reader for a generic type, such as {@code new TypeReference<List<NewCard>>() {}}. */
    public <T> JsonReader<T> reader(TypeReference<T> type) {
        return codec(type);
    }

    /** Both directions for one type, for a value that goes out and comes back in. */
    public <T> JsonCodec<T> codec(Class<T> type) {
        Objects.requireNonNull(type, "type");
        return new Binding<>(mapper.writerFor(type), mapper.readerFor(type));
    }

    /** Both directions for a generic type. */
    public <T> JsonCodec<T> codec(TypeReference<T> type) {
        Objects.requireNonNull(type, "type");
        return new Binding<>(mapper.writerFor(type), mapper.readerFor(type));
    }

    private void write(ObjectWriter writer, Object value, JsonOutput out) {
        SerializationConfig config = writer.getConfig();
        writer.writeValue(new OutputGenerator(out, writeContext, constraints,
                config.getStreamWriteFeatures(), config.getFormatWriteFeatures()), value);
    }

    /** One type bound both ways, with the serializer and deserializer Jackson looked up for it. */
    private final class Binding<T> implements JsonCodec<T> {

        private final ObjectWriter writer;
        private final ObjectReader reader;

        Binding(ObjectWriter writer, ObjectReader reader) {
            this.writer = writer;
            this.reader = reader;
        }

        @Override
        public void write(T value, JsonOutput out) {
            JacksonCodecs.this.write(writer, value, out);
        }

        /**
         * The next value, as a reader nested in another one reads it, such as
         * the element reader of {@link JsonReader#list}: silk-json takes the
         * value's bytes as they are written, its syntax checked, and Jackson
         * binds what they hold. Reading the whole document with one reader,
         * {@code new TypeReference<List<NewCard>>() {}} for a list, hands
         * Jackson the bytes once rather than a value at a time.
         */
        @Override
        public T read(JsonInput in) {
            return fromJsonBytes(in.readValueBytes());
        }

        @Override
        public T fromJsonBytes(byte[] utf8) {
            try {
                return present(reader.readValue(utf8));
            } catch (JacksonException e) {
                throw rejected(e);
            }
        }

        @Override
        public T fromJson(String text) {
            try {
                return present(reader.readValue(text));
            } catch (JacksonException e) {
                throw rejected(e);
            }
        }

        private T present(@Nullable T value) {
            if (value == null) {
                throw new JsonException("Expected a value, found null");
            }
            return value;
        }
    }

    /**
     * What a failed read becomes. The input's faults become a
     * {@link JsonException}, or the {@link IllegalArgumentException} a creator
     * threw for a rule of its own, and both answer 400; anything else, a
     * mapping Jackson cannot build above all, is rethrown as it was.
     *
     * <p>A creator's {@link NullPointerException} is the input's fault too:
     * a record's {@code requireNonNull} meets the null Jackson passes for a
     * property the body left out.
     */
    private static RuntimeException rejected(JacksonException e) {
        if (e instanceof ValueInstantiationException
                && (e.getCause() instanceof IllegalArgumentException || e.getCause() instanceof DateTimeException)) {
            return (RuntimeException) e.getCause();
        }
        if (e instanceof StreamReadException || e instanceof StreamConstraintsException
                || e instanceof MismatchedInputException
                || (e instanceof ValueInstantiationException && e.getCause() instanceof NullPointerException)) {
            JsonException rejected = new JsonException(describe(e));
            rejected.initCause(e);
            return rejected;
        }
        return e;
    }

    /** Jackson's message without the source excerpt it appends, and with the property path when it has one. */
    private static String describe(JacksonException e) {
        String message = Objects.requireNonNullElse(e.getOriginalMessage(), e.getClass().getSimpleName());
        if (e instanceof DatabindException) {
            String path = e.getPathReference();
            if (!path.isEmpty()) {
                return message + " (through reference chain: " + path + ")";
            }
        }
        return message;
    }

    /**
     * What a generator asks of the mapper behind it: a value a serializer
     * hands back with {@code writePOJO} or {@code writeTree} is serialized by
     * the same mapper, and the rest is the mapper's defaults.
     */
    private static final class MapperWriteContext implements ObjectWriteContext {

        private final ObjectMapper mapper;

        MapperWriteContext(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public @Nullable FormatSchema getSchema() {
            return null;
        }

        @Override
        public @Nullable CharacterEscapes getCharacterEscapes() {
            return null;
        }

        @Override
        public @Nullable PrettyPrinter getPrettyPrinter() {
            return null;
        }

        @Override
        public boolean hasPrettyPrinter() {
            return false;
        }

        @Override
        public @Nullable SerializableString getRootValueSeparator(@Nullable SerializableString defaultSeparator) {
            return defaultSeparator;
        }

        @Override
        public int getStreamWriteFeatures(int defaults) {
            return defaults;
        }

        @Override
        public int getFormatWriteFeatures(int defaults) {
            return defaults;
        }

        @Override
        public TokenStreamFactory tokenStreamFactory() {
            return mapper.tokenStreamFactory();
        }

        @Override
        public ArrayTreeNode createArrayNode() {
            return mapper.createArrayNode();
        }

        @Override
        public ObjectTreeNode createObjectNode() {
            return mapper.createObjectNode();
        }

        @Override
        public void writeValue(JsonGenerator g, @Nullable Object value) {
            mapper.writeValue(g, value);
        }

        @Override
        public void writeTree(JsonGenerator g, TreeNode tree) {
            mapper.writeValue(g, tree);
        }
    }
}
