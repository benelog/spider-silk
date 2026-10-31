package net.benelog.silkjson.jsonb;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;

import jakarta.json.JsonValue;
import jakarta.json.bind.JsonbException;
import jakarta.json.bind.serializer.DeserializationContext;
import jakarta.json.bind.serializer.JsonbDeserializer;
import jakarta.json.bind.serializer.JsonbSerializer;
import jakarta.json.bind.serializer.SerializationContext;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonParser;

import org.jspecify.annotations.Nullable;

import net.benelog.silkjson.JsonException;
import net.benelog.silkjson.JsonInput;
import net.benelog.silkjson.JsonOutput;
import net.benelog.silkjson.jsonp.JsonpCodecs;

/**
 * Where a generated codec hands a value to a {@code @JsonbTypeSerializer} or a
 * {@code @JsonbTypeDeserializer}, which work in JSON-P's generator and parser.
 * The generated code creates the class with {@code new} and calls this.
 *
 * <p>The contexts these hand over take the values JSON-P has a form for: null,
 * strings, booleans, numbers, and JSON-P's own tree, and on the way out an
 * enum's name. Another type would have to be looked up by its class, which
 * nothing here does, so it is a {@link JsonbException}: a serializer writes
 * such a value through its fields, or holds the generated codec itself. A value
 * of the wrong kind on the way in is the document's fault, a {@link JsonException}.
 */
public final class JsonbCodecs {

    private static final SerializationContext SERIALIZATION = new SerializationContext() {
        @Override
        public <T> void serialize(String key, T object, JsonGenerator generator) {
            generator.writeKey(key);
            serialize(object, generator);
        }

        @Override
        public <T> void serialize(T object, JsonGenerator generator) {
            write(object, generator);
        }
    };

    private static final DeserializationContext DESERIALIZATION = new DeserializationContext() {
        @Override
        public <T extends @Nullable Object> T deserialize(Class<T> clazz, JsonParser parser) {
            return deserialize((Type) clazz, parser);
        }

        // The signature is the one JSON-B declares, whose T is null for a JSON null.
        @Override
        @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals", "NullAway"})
        public <T extends @Nullable Object> T deserialize(Type type, JsonParser parser) {
            if (parser.currentEvent() == JsonParser.Event.KEY_NAME) {
                parser.next();
            }
            return (T) read(type, parser);
        }
    };

    private JsonbCodecs() {
    }

    /** Has the serializer write the value as the output's next value, which it must write whole. */
    public static <T extends @Nullable Object> void write(JsonbSerializer<T> serializer, T value, JsonOutput out) {
        JsonGenerator generator = JsonpCodecs.generator(out);
        serializer.serialize(value, generator, SERIALIZATION);
        generator.close();
    }

    /**
     * Has the deserializer read the input's next value, of which the parser it
     * is handed stands at the first event, {@code START_OBJECT} for an object.
     * The input moves past the value, however much of it the deserializer reads.
     */
    public static <T extends @Nullable Object> T read(JsonbDeserializer<T> deserializer, JsonInput in, Type type) {
        JsonParser parser = JsonpCodecs.parser(in);
        parser.next();
        return deserializer.deserialize(parser, DESERIALIZATION, type);
    }

    private static void write(@Nullable Object object, JsonGenerator generator) {
        switch (object) {
            case null -> generator.writeNull();
            case JsonValue value -> generator.write(value);
            case String value -> generator.write(value);
            case Character value -> generator.write(value.toString());
            case Boolean value -> generator.write(value);
            case Byte value -> generator.write(value.intValue());
            case Short value -> generator.write(value.intValue());
            case Integer value -> generator.write(value.intValue());
            case Long value -> generator.write(value.longValue());
            case Float value -> generator.write(new BigDecimal(value.toString()));
            case Double value -> generator.write(value.doubleValue());
            case BigDecimal value -> generator.write(value);
            case BigInteger value -> generator.write(value);
            case Enum<?> value -> generator.write(value.name());
            default -> throw unbound(object.getClass());
        }
    }

    private static @Nullable Object read(Type type, JsonParser parser) {
        JsonParser.Event event = parser.currentEvent();
        if (type instanceof Class<?> tree && JsonValue.class.isAssignableFrom(tree)) {
            JsonValue value = parser.getValue();
            if (!tree.isInstance(value)) {
                throw new JsonException("Not a JSON value of the kind " + tree.getSimpleName());
            }
            return value;
        }
        if (event == JsonParser.Event.VALUE_NULL && isScalar(type)) {
            return null;
        }
        if (type == String.class || type == Character.class) {
            return readText(type, parser, event);
        }
        if (type == Boolean.class) {
            if (event != JsonParser.Event.VALUE_TRUE && event != JsonParser.Event.VALUE_FALSE) {
                throw new JsonException("Not a JSON boolean");
            }
            return event == JsonParser.Event.VALUE_TRUE;
        }
        if (isScalar(type)) {
            if (event != JsonParser.Event.VALUE_NUMBER) {
                throw new JsonException("Not a JSON number");
            }
            return readNumber(type, parser.getBigDecimal());
        }
        throw unbound(type);
    }

    private static boolean isScalar(Type type) {
        return type == String.class || type == Character.class || type == Boolean.class || type == Byte.class || type == Short.class
                || type == Integer.class || type == Long.class || type == Float.class || type == Double.class
                || type == BigDecimal.class || type == BigInteger.class;
    }

    private static Object readText(Type type, JsonParser parser, JsonParser.Event event) {
        if (event != JsonParser.Event.VALUE_STRING) {
            throw new JsonException("Not a JSON string");
        }
        String text = parser.getString();
        if (type == String.class) {
            return text;
        }
        if (text.length() != 1) {
            throw new JsonException("Not a single character");
        }
        return text.charAt(0);
    }

    private static Object readNumber(Type type, BigDecimal number) {
        try {
            if (type == Byte.class) {
                return number.byteValueExact();
            }
            if (type == Short.class) {
                return number.shortValueExact();
            }
            if (type == Integer.class) {
                return number.intValueExact();
            }
            if (type == Long.class) {
                return number.longValueExact();
            }
            if (type == BigInteger.class) {
                return number.toBigIntegerExact();
            }
        } catch (ArithmeticException e) {
            throw new JsonException("Not a whole number in range of " + type.getTypeName());
        }
        if (type == Float.class) {
            return number.floatValue();
        }
        return type == Double.class ? number.doubleValue() : number;
    }

    private static JsonbException unbound(Type type) {
        return new JsonbException("A context here takes JSON-P's values and no bound type, since nothing is looked up by its class: "
                + type.getTypeName());
    }
}
