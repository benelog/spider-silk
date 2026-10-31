package net.benelog.silkjson.jsonb;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.json.bind.JsonbException;
import jakarta.json.bind.serializer.DeserializationContext;
import jakarta.json.bind.serializer.JsonbDeserializer;
import jakarta.json.bind.serializer.JsonbSerializer;
import jakarta.json.bind.serializer.SerializationContext;
import jakarta.json.stream.JsonGenerationException;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonParser;

import org.junit.jupiter.api.Test;

import net.benelog.silkjson.JsonException;
import net.benelog.silkjson.JsonInput;
import net.benelog.silkjson.JsonOutput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The bridge a generated codec calls, and what its contexts take. */
class JsonbCodecsTest {

    private enum Level { HARD }

    private record Unbound(int a) {
    }

    /** Writes each value of the list through the context, as members of an object. */
    private static final JsonbSerializer<List<Object>> MEMBERS = (List<Object> values, JsonGenerator generator, SerializationContext context) -> {
        generator.writeStartObject();
        for (int i = 0; i < values.size(); i++) {
            context.serialize("v" + i, values.get(i), generator);
        }
        generator.writeEnd();
    };

    @Test
    void aSerializerWritesOneValueInPlaceAndTheOutputGoesOn() {
        JsonOutput out = JsonOutput.inMemory();
        out.object();
        out.name("first");
        JsonbCodecs.write((String value, JsonGenerator generator, SerializationContext context) -> generator.write(value), "x", out);
        out.put("second", 2);
        out.name("list");
        out.array();
        JsonbCodecs.write((Integer value, JsonGenerator generator, SerializationContext context) -> generator.write(value), 1, out);
        JsonbCodecs.write((Integer value, JsonGenerator generator, SerializationContext context) -> generator.write(value), 2, out);
        out.end();
        out.end();

        assertThat(out.toJson()).isEqualTo("{\"first\":\"x\",\"second\":2,\"list\":[1,2]}");
    }

    @Test
    void aSerializerThatLeavesTheValueUnfinishedFails() {
        JsonOutput out = JsonOutput.inMemory();
        assertThatThrownBy(() -> JsonbCodecs.write(
                (String value, JsonGenerator generator, SerializationContext context) -> generator.writeStartObject(), "x", out))
                .isInstanceOf(JsonGenerationException.class);
    }

    @Test
    void theSerializationContextTakesJsonPsValues() {
        List<Object> values = new ArrayList<>(List.of("s", 'c', true, (byte) 1, (short) 2, 3, 4L, 0.1f, 0.5, new BigDecimal("1.50"),
                new BigInteger("7"), Level.HARD, Json.createObjectBuilder().add("k", 1).build()));
        values.add(null);
        JsonOutput out = JsonOutput.inMemory();
        JsonbCodecs.write(MEMBERS, values, out);

        assertThat(out.toJson()).isEqualTo("{\"v0\":\"s\",\"v1\":\"c\",\"v2\":true,\"v3\":1,\"v4\":2,\"v5\":3,\"v6\":4,\"v7\":0.1,\"v8\":0.5,"
                + "\"v9\":1.50,\"v10\":7,\"v11\":\"HARD\",\"v12\":{\"k\":1},\"v13\":null}");
        assertThatThrownBy(() -> JsonbCodecs.write(MEMBERS, List.<Object>of(new Unbound(1)), JsonOutput.inMemory()))
                .isInstanceOf(JsonbException.class).hasMessageContaining("nothing is looked up by its class");
    }

    /** Reads a value of the type through the context, from a key or from the value itself. */
    private static <T> T read(String json, Class<T> type) {
        JsonbDeserializer<T> fromValue = (JsonParser parser, DeserializationContext context, Type target) -> context.deserialize(type, parser);
        JsonbDeserializer<T> fromKey = (JsonParser parser, DeserializationContext context, Type target) -> {
            parser.next();
            return context.deserialize(type, parser);
        };
        T read = JsonbCodecs.read(fromValue, JsonInput.of(json), type);
        assertThat(JsonbCodecs.read(fromKey, JsonInput.of("{\"key\":" + json + "}"), type)).isEqualTo(read);
        return read;
    }

    @Test
    void theDeserializationContextTakesJsonPsValues() {
        assertThat(read("\"s\"", String.class)).isEqualTo("s");
        assertThat(read("\"c\"", Character.class)).isEqualTo('c');
        assertThat(read("true", Boolean.class)).isTrue();
        assertThat(read("false", Boolean.class)).isFalse();
        assertThat(read("1", Byte.class)).isEqualTo((byte) 1);
        assertThat(read("2", Short.class)).isEqualTo((short) 2);
        assertThat(read("3", Integer.class)).isEqualTo(3);
        assertThat(read("4", Long.class)).isEqualTo(4L);
        assertThat(read("0.5", Float.class)).isEqualTo(0.5f);
        assertThat(read("0.5", Double.class)).isEqualTo(0.5);
        assertThat(read("1.50", BigDecimal.class)).isEqualTo(new BigDecimal("1.50"));
        assertThat(read("7", BigInteger.class)).isEqualTo(new BigInteger("7"));
        assertThat(read("null", String.class)).isNull();
        assertThat(read("{\"k\":1}", JsonObject.class).getInt("k")).isEqualTo(1);
        assertThat(read("null", JsonValue.class)).isEqualTo(JsonValue.NULL);

        assertThatThrownBy(() -> read("300", Byte.class)).isInstanceOf(JsonException.class)
                .hasMessageContaining("Not a whole number in range");
        assertThatThrownBy(() -> read("1", Boolean.class)).isInstanceOf(JsonException.class).hasMessage("Not a JSON boolean");
        assertThatThrownBy(() -> read("\"1\"", Integer.class)).isInstanceOf(JsonException.class).hasMessage("Not a JSON number");
        assertThatThrownBy(() -> read("1", String.class)).isInstanceOf(JsonException.class).hasMessage("Not a JSON string");
        assertThatThrownBy(() -> read("\"ab\"", Character.class)).isInstanceOf(JsonException.class).hasMessage("Not a single character");
        assertThatThrownBy(() -> read("[]", JsonObject.class)).isInstanceOf(JsonException.class)
                .hasMessageContaining("Not a JSON value of the kind JsonObject");
        assertThatThrownBy(() -> read("1", Unbound.class)).isInstanceOf(JsonbException.class)
                .hasMessageContaining("nothing is looked up by its class");
    }

    /** The input moves past the value, however little of it the deserializer read. */
    @Test
    void theInputMovesPastTheValue() {
        JsonInput in = JsonInput.of("[{\"a\":[1,2,3]},4]");
        in.array();
        assertThat(in.nextElement()).isTrue();
        String first = JsonbCodecs.read((JsonParser parser, DeserializationContext context, Type type) -> parser.currentEvent().name(),
                in, String.class);
        assertThat(first).isEqualTo("START_OBJECT");
        assertThat(in.nextElement()).isTrue();
        assertThat(in.readInt()).isEqualTo(4);
    }
}
