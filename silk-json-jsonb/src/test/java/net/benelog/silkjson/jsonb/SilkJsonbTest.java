package net.benelog.silkjson.jsonb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbException;
import jakarta.json.bind.annotation.JsonbProperty;
import jakarta.json.bind.annotation.JsonbSubtype;
import jakarta.json.bind.annotation.JsonbTypeInfo;

import org.junit.jupiter.api.Test;

import net.benelog.silkjson.JsonBound;
import net.benelog.silkjson.JsonCodec;
import net.benelog.silkjson.JsonInput;
import net.benelog.silkjson.JsonOutput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link SilkJsonb} over codecs the processor generates for the types here. */
class SilkJsonbTest {

    @JsonBound
    public record Deck(long id, @JsonbProperty("deck_name") String name) {
    }

    @JsonBound
    @JsonbTypeInfo(@JsonbSubtype(alias = "cat", type = Cat.class))
    public interface Animal {
    }

    @JsonBound
    public record Cat(int lives) implements Animal {
    }

    @JsonBound
    public record Box<T>(T value) {
    }

    /** The type a caller of {@code fromJson(text, type)} holds for {@code Box<String>}, however it came by it. */
    private static final Type BOX_OF_STRINGS = new ParameterizedType() {
        @Override
        public Type[] getActualTypeArguments() {
            return new Type[] {String.class};
        }

        @Override
        public Type getRawType() {
            return Box.class;
        }

        @Override
        public Type getOwnerType() {
            return SilkJsonbTest.class;
        }
    };

    private static final JsonCodec<String> STRING = JsonCodec.of((String value, JsonOutput out) -> out.value(value), JsonInput::readString);

    private final Jsonb jsonb = SilkJsonb.builder()
            .bind(Deck.class, SilkJsonbTestDeckJson.CODEC)
            .bind(Animal.class, SilkJsonbTestAnimalJson.CODEC)
            .bind(BOX_OF_STRINGS, SilkJsonbTestBoxJson.of(STRING))
            .build();

    @Test
    void aBoundTypeGoesBothWaysOverEveryForm() throws Exception {
        String document = "{\"id\":7,\"deck_name\":\"English\"}";
        Deck deck = new Deck(7, "English");

        assertThat(jsonb.toJson(deck)).isEqualTo(document);
        assertThat(jsonb.toJson(deck, Deck.class)).isEqualTo(document);
        assertThat(jsonb.fromJson(document, Deck.class)).isEqualTo(deck);
        assertThat((Deck) jsonb.fromJson(document, (Type) Deck.class)).isEqualTo(deck);
        assertThat(jsonb.fromJson(new StringReader(document), Deck.class)).isEqualTo(deck);
        assertThat(jsonb.fromJson(new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)), Deck.class)).isEqualTo(deck);

        StringWriter writer = new StringWriter();
        jsonb.toJson(deck, writer);
        assertThat(writer).hasToString(document);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        jsonb.toJson(deck, stream);
        assertThat(stream.toString(StandardCharsets.UTF_8)).isEqualTo(document);
        jsonb.close();
    }

    @Test
    void aSubtypeIsWrittenAsTheTypeItIsBoundAs() {
        assertThat(jsonb.toJson(new Cat(9), Animal.class)).isEqualTo("{\"@type\":\"cat\",\"lives\":9}");
        assertThat(jsonb.fromJson("{\"lives\":9,\"@type\":\"cat\"}", Animal.class)).isEqualTo(new Cat(9));
        assertThatThrownBy(() -> jsonb.toJson(new Cat(9))).isInstanceOf(JsonbException.class)
                .hasMessageContaining("No codec is bound for ");
    }

    @Test
    void aGenericTypeIsBoundByTheTypeItsCallerHolds() {
        assertThat(jsonb.toJson(new Box<>("x"), BOX_OF_STRINGS)).isEqualTo("{\"value\":\"x\"}");
        assertThat(jsonb.<Box<String>>fromJson("{\"value\":\"x\"}", BOX_OF_STRINGS)).isEqualTo(new Box<>("x"));
    }

    @Test
    void whatIsNotBoundOrNotADocumentIsAJsonbException() {
        assertThatThrownBy(() -> jsonb.toJson("text")).isInstanceOf(JsonbException.class)
                .hasMessage("No codec is bound for java.lang.String");
        assertThatThrownBy(() -> jsonb.fromJson("{}", String.class)).isInstanceOf(JsonbException.class);
        assertThatThrownBy(() -> jsonb.fromJson("{\"id\":1}", Deck.class)).isInstanceOf(JsonbException.class)
                .hasMessage("Missing key in JSON object: deck_name");
        assertThatThrownBy(() -> jsonb.fromJson(new ByteArrayInputStream(new byte[] {'['}), Deck.class))
                .isInstanceOf(JsonbException.class);
    }
}
