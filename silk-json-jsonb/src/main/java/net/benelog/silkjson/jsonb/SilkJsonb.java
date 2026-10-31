package net.benelog.silkjson.jsonb;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbException;

import org.jspecify.annotations.Nullable;

import net.benelog.silkjson.JsonCodec;
import net.benelog.silkjson.JsonException;

/**
 * A {@link Jsonb} over the codecs an application names, for code that takes a
 * {@code Jsonb}. It is created directly, rather than through
 * {@code JsonbBuilder}, which finds a provider by {@code ServiceLoader} and
 * then {@code Class.forName}:
 *
 * <pre>{@code
 * Jsonb jsonb = SilkJsonb.builder()
 *         .bind(Deck.class, DeckJson.CODEC)
 *         .bind(Animal.class, AnimalJson.CODEC)
 *         .build();
 * }</pre>
 *
 * <p>A type is bound here or not at all: nothing is scanned, created by
 * reflection, or shared between two instances, and any other type is a
 * {@link JsonbException}. {@code toJson(object)} goes by the object's own
 * class, so a value of a subtype needs the type it is written as, in
 * {@code toJson(object, Animal.class)}, or a codec of its own. Each document
 * is held whole in memory, and bytes are UTF-8.
 */
@SuppressWarnings("TypeParameterUnusedInFormals") // the signatures are the ones Jsonb declares
public final class SilkJsonb implements Jsonb {

    private final Map<Type, JsonCodec<?>> codecs;

    private SilkJsonb(Map<Type, JsonCodec<?>> codecs) {
        this.codecs = codecs;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The types a {@link SilkJsonb} takes, each with its codec. */
    public static final class Builder {

        private final Map<Type, JsonCodec<?>> codecs = new HashMap<>();

        private Builder() {
        }

        public <T> Builder bind(Class<T> type, JsonCodec<T> codec) {
            codecs.put(type, codec);
            return this;
        }

        /** Binds a type a {@code Class} cannot say, such as a {@code ParameterizedType} of a generic type's codec. */
        public Builder bind(Type type, JsonCodec<?> codec) {
            codecs.put(type, codec);
            return this;
        }

        public SilkJsonb build() {
            return new SilkJsonb(Map.copyOf(codecs));
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends @Nullable Object> JsonCodec<T> codec(Type type) {
        JsonCodec<?> codec = codecs.get(Objects.requireNonNull(type, "type"));
        if (codec == null) {
            throw new JsonbException("No codec is bound for " + type.getTypeName());
        }
        return (JsonCodec<T>) codec;
    }

    @Override
    public <T> T fromJson(String str, Class<T> type) {
        return fromJson(str, (Type) type);
    }

    @Override
    public <T> T fromJson(String str, Type runtimeType) {
        Objects.requireNonNull(str, "str");
        JsonCodec<T> codec = codec(runtimeType);
        try {
            return codec.fromJson(str);
        } catch (JsonException e) {
            throw new JsonbException(String.valueOf(e.getMessage()), e);
        }
    }

    @Override
    public <T> T fromJson(Reader reader, Class<T> type) {
        return fromJson(reader, (Type) type);
    }

    @Override
    public <T> T fromJson(Reader reader, Type runtimeType) {
        StringWriter text = new StringWriter();
        try {
            reader.transferTo(text);
        } catch (IOException e) {
            throw new JsonbException("The document could not be read", e);
        }
        return fromJson(text.toString(), runtimeType);
    }

    @Override
    public <T> T fromJson(InputStream stream, Class<T> type) {
        return fromJson(stream, (Type) type);
    }

    @Override
    public <T> T fromJson(InputStream stream, Type runtimeType) {
        JsonCodec<T> codec = codec(runtimeType);
        try {
            return codec.fromJsonBytes(stream.readAllBytes());
        } catch (JsonException e) {
            throw new JsonbException(String.valueOf(e.getMessage()), e);
        } catch (IOException e) {
            throw new JsonbException("The document could not be read", e);
        }
    }

    @Override
    public String toJson(Object object) {
        return toJson(object, object.getClass());
    }

    @Override
    public String toJson(Object object, Type runtimeType) {
        Objects.requireNonNull(object, "object");
        return this.<Object>codec(runtimeType).toJson(object);
    }

    @Override
    public void toJson(Object object, Writer writer) {
        toJson(object, object.getClass(), writer);
    }

    @Override
    public void toJson(Object object, Type runtimeType, Writer writer) {
        String json = toJson(object, runtimeType);
        try {
            writer.write(json);
            writer.flush();
        } catch (IOException e) {
            throw new JsonbException("The document could not be written", e);
        }
    }

    @Override
    public void toJson(Object object, OutputStream stream) {
        toJson(object, object.getClass(), stream);
    }

    @Override
    public void toJson(Object object, Type runtimeType, OutputStream stream) {
        Objects.requireNonNull(object, "object");
        byte[] json = this.<Object>codec(runtimeType).toJsonBytes(object);
        try {
            stream.write(json);
            stream.flush();
        } catch (IOException e) {
            throw new JsonbException("The document could not be written", e);
        }
    }

    /** Holds nothing that needs closing. */
    @Override
    public void close() {
    }
}
