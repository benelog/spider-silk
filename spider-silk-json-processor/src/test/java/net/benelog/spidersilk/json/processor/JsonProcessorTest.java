package net.benelog.spidersilk.json.processor;

import java.util.Map;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.json.JsonCodec;
import net.benelog.spidersilk.json.JsonException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The processor, run by javac in memory over sources given here, and the codecs it generates, run against core. */
class JsonProcessorTest {

    private static final String IMPORTS = """
            import java.util.*;
            import java.math.*;
            import java.time.*;
            import jakarta.json.bind.annotation.*;
            import jakarta.json.bind.adapter.JsonbAdapter;
            import org.jspecify.annotations.Nullable;
            import net.benelog.spidersilk.json.*;
            """;

    @SuppressWarnings("unchecked")
    private static JsonCodec<Object> codec(Compilation.Result result, String generatedName) throws Exception {
        return (JsonCodec<Object>) result.load(generatedName).getField("CODEC").get(null);
    }

    private static Compilation.Result compile(Map<String, String> sources, String... options) {
        Compilation.Result result = Compilation.compile(sources, options);
        assertThat(result.errors()).as("compile errors").isEmpty();
        return result;
    }

    @Test
    void aRecordRoundTripsWithRenamedNullableAndUnknownKeys() throws Exception {
        Compilation.Result result = compile(Map.of("example.Deck", "package example;" + IMPORTS + """
                @JsonBound
                public record Deck(long id, @JsonbProperty("deck_name") String name, @Nullable String note, boolean active) {
                }
                """));
        JsonCodec<Object> deck = codec(result, "example.DeckJson");

        Object read = deck.fromJson("{\"id\":1,\"deck_name\":\"English\",\"note\":null,\"active\":true,\"extra\":[1,{\"a\":2}]}");
        assertThat(read.toString()).isEqualTo("Deck[id=1, name=English, note=null, active=true]");
        assertThat(deck.toJson(read)).isEqualTo("{\"id\":1,\"deck_name\":\"English\",\"active\":true}");
        assertThat(deck.toJson(deck.fromJson("{\"active\":false,\"note\":\"n\",\"deck_name\":\"x\",\"id\":-5}")))
                .isEqualTo("{\"id\":-5,\"deck_name\":\"x\",\"note\":\"n\",\"active\":false}");

        assertThatThrownBy(() -> deck.fromJson("{\"id\":1,\"active\":true}"))
                .isInstanceOf(JsonException.class)
                .hasMessage("Missing key in JSON object: deck_name");
        assertThatThrownBy(() -> deck.fromJson("{\"deck_name\":\"x\",\"active\":true}"))
                .hasMessage("Missing key in JSON object: id");
        assertThatThrownBy(() -> deck.fromJson("{\"id\":1,\"deck_name\":null,\"active\":true}"))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Not a JSON string: null");
        assertThatThrownBy(() -> deck.fromJson("{\"id\":\"1\",\"deck_name\":\"x\",\"active\":true}"))
                .hasMessageContaining("Not a JSON number: a string");
        assertThatThrownBy(() -> deck.fromJson("[]")).hasMessageContaining("Not a JSON object: an array");

        String source = result.generated("example.DeckJson");
        assertThat(source).contains("public final class DeckJson implements net.benelog.spidersilk.json.JsonCodec<example.Deck>")
                .contains("JsonKey.of(\"deck_name\")")
                .contains("key = in.nextKeyIs(K_note) ? 2 : keyIndex(in);")
                .contains("if (in.keyIs(K_name)) {")
                .contains("return new example.Deck(p_id, p_name, p_note, p_active);")
                .doesNotContain("reflect");
    }

    @Test
    void nestedTypesCollectionsOptionalsEnumsTimesAndNumbersAreBound() throws Exception {
        Compilation.Result result = compile(Map.of(
                "example.Tag", "package example;" + IMPORTS + """
                        @JsonBound
                        public record Tag(String label) {
                        }
                        """,
                "example.Level", "package example; public enum Level { EASY, HARD }",
                "example.Card", "package example;" + IMPORTS + """
                        @JsonBound
                        public record Card(String text, List<Tag> tags, Set<Integer> scores, Map<String, Tag> byName,
                                List<@Nullable String> notes, Optional<String> hint, OptionalInt weight, Level level,
                                LocalDate due, @JsonbDateFormat("dd/MM/yyyy") LocalDate reviewed, BigDecimal price,
                                UUID uuid, JsonValue raw, Instant at, Duration took, short small, byte tiny, float ratio, char initial) {
                        }
                        """));
        JsonCodec<Object> card = codec(result, "example.CardJson");

        String document = "{\"text\":\"t\",\"tags\":[{\"label\":\"a\"},{\"label\":\"b\"}],\"scores\":[1,2],\"byName\":{\"x\":{\"label\":\"c\"}},"
                + "\"notes\":[\"n\",null],\"hint\":\"h\",\"weight\":3,\"level\":\"HARD\",\"due\":\"2026-10-09\",\"reviewed\":\"09/10/2026\","
                + "\"price\":1.50,\"uuid\":\"123e4567-e89b-12d3-a456-426614174000\",\"raw\":{\"k\":[1,true,null]},"
                + "\"at\":\"2026-10-09T12:00:00Z\",\"took\":\"PT1H\",\"small\":7,\"tiny\":-1,\"ratio\":0.5,\"initial\":\"c\"}";
        Object read = card.fromJson(document);

        assertThat(card.toJson(read)).isEqualTo(document);
        assertThat(read.toString()).contains("tags=[Tag[label=a], Tag[label=b]]").contains("scores=[1, 2]")
                .contains("byName={x=Tag[label=c]}").contains("notes=[n, null]").contains("hint=Optional[h]")
                .contains("weight=OptionalInt[3]").contains("level=HARD").contains("due=2026-10-09")
                .contains("reviewed=2026-10-09").contains("price=1.50").contains("at=2026-10-09T12:00:00Z")
                .contains("took=PT1H").contains("small=7").contains("tiny=-1").contains("ratio=0.5").contains("initial=c");

        String sparse = document.replace("\"hint\":\"h\",\"weight\":3,", "\"hint\":null,\"weight\":null,");
        Object sparseRead = card.fromJson(sparse);
        assertThat(sparseRead.toString()).contains("hint=Optional.empty").contains("weight=OptionalInt.empty");
        assertThat(card.toJson(sparseRead)).doesNotContain("hint").doesNotContain("weight");

        assertThatThrownBy(() -> card.fromJson(document.replace("\"HARD\"", "\"MEDIUM\"")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> card.fromJson(document.replace("\"2026-10-09\"", "\"yesterday\"")))
                .isInstanceOf(java.time.DateTimeException.class);
        assertThatThrownBy(() -> card.fromJson(document.replace("\"small\":7", "\"small\":70000")))
                .hasMessageContaining("Not a 16-bit integer");
        assertThatThrownBy(() -> card.fromJson(document.replace("\"initial\":\"c\"", "\"initial\":\"cd\"")))
                .hasMessageContaining("Not a single character");
    }

    @Test
    void aClassIsCreatedThroughItsCreatorAndSetThroughSettersAndFields() throws Exception {
        Compilation.Result result = compile(Map.of("example.Account", "package example;" + IMPORTS + """
                @JsonBound
                public class Account {
                    private final long id;
                    private final String owner;
                    private @Nullable String note;
                    public int limit;
                    @JsonbTransient
                    public String secret = "s";

                    @JsonbCreator
                    public Account(@JsonbProperty("id") long id, @JsonbProperty("owner") String owner) {
                        this.id = id;
                        this.owner = owner;
                    }

                    public long getId() { return id; }
                    public String getOwner() { return owner; }
                    public @Nullable String getNote() { return note; }
                    public void setNote(@Nullable String note) { this.note = note; }
                }
                """));
        JsonCodec<Object> account = codec(result, "example.AccountJson");

        assertThat(account.toJson(account.fromJson("{\"id\":7,\"owner\":\"me\",\"note\":\"n\",\"limit\":5,\"secret\":\"x\"}")))
                .isEqualTo("{\"id\":7,\"owner\":\"me\",\"note\":\"n\",\"limit\":5}");
        assertThat(account.toJson(account.fromJson("{\"id\":7,\"owner\":\"me\"}")))
                .isEqualTo("{\"id\":7,\"owner\":\"me\",\"limit\":0}");
        assertThatThrownBy(() -> account.fromJson("{\"id\":7}")).hasMessage("Missing key in JSON object: owner");
    }

    @Test
    void aBeanKeepsItsDefaultsForWhatTheDocumentLeavesOut() throws Exception {
        Compilation.Result result = compile(Map.of("example.Settings", "package example;" + IMPORTS + """
                @JsonBound
                public class Settings {
                    private String theme = "light";
                    private int size = 12;
                    private boolean bold;

                    public String getTheme() { return theme; }
                    public void setTheme(String theme) { this.theme = theme; }
                    public int getSize() { return size; }
                    public void setSize(int size) { this.size = size; }
                    public boolean isBold() { return bold; }
                    public void setBold(boolean bold) { this.bold = bold; }
                }
                """));
        JsonCodec<Object> settings = codec(result, "example.SettingsJson");

        assertThat(settings.toJson(settings.fromJson("{\"size\":14}"))).isEqualTo("{\"theme\":\"light\",\"size\":14,\"bold\":false}");
        assertThat(settings.toJson(settings.fromJson("{\"bold\":true,\"theme\":\"dark\"}")))
                .isEqualTo("{\"theme\":\"dark\",\"size\":12,\"bold\":true}");
    }

    @Test
    void aMixinNamesAndAnnotatesATypeItDoesNotOwn() throws Exception {
        Compilation.Result result = compile(Map.of(
                "example.Person", "package example; public record Person(String name, int age, @org.jspecify.annotations.Nullable String password) { }",
                "example.web.PersonWire", "package example.web;" + IMPORTS + """
                        @JsonBound(example.Person.class)
                        @JsonbPropertyOrder({"age", "full_name"})
                        interface PersonWire {
                            @JsonbProperty("full_name") String name();
                            @JsonbTransient String password();
                        }
                        """));
        JsonCodec<Object> person = codec(result, "example.web.PersonJson");

        Object read = person.fromJson("{\"full_name\":\"Al\",\"age\":3,\"password\":\"p\"}");
        assertThat(read.toString()).isEqualTo("Person[name=Al, age=3, password=null]");
        assertThat(person.toJson(read)).isEqualTo("{\"age\":3,\"full_name\":\"Al\"}");
        assertThat(result.generated("example.web.PersonJson")).contains("return new example.Person(p_name, p_age, null);");
    }

    @Test
    void nillableWritesNullsAndTheOrderIsTheAnnotationsThenDeclaration() throws Exception {
        Compilation.Result result = compile(Map.of("example.Triple", "package example;" + IMPORTS + """
                @JsonBound
                @JsonbNillable
                @JsonbPropertyOrder({"c"})
                public record Triple(@Nullable String a, @JsonbNillable(false) @Nullable String b, @Nullable String c, Optional<Integer> d) {
                }
                """));
        JsonCodec<Object> triple = codec(result, "example.TripleJson");

        assertThat(triple.toJson(triple.fromJson("{}"))).isEqualTo("{\"c\":null,\"a\":null,\"d\":null}");
        assertThat(triple.toJson(triple.fromJson("{\"a\":\"x\",\"b\":\"y\",\"c\":\"z\",\"d\":4}")))
                .isEqualTo("{\"c\":\"z\",\"a\":\"x\",\"b\":\"y\",\"d\":4}");
    }

    @Test
    void anAdapterIsCreatedWithNewAndCheckedAtCompileTime() throws Exception {
        Compilation.Result result = compile(Map.of(
                "example.Money", "package example; public record Money(long cents) { }",
                "example.MoneyAdapter", "package example;" + IMPORTS + """
                        public class MoneyAdapter implements JsonbAdapter<Money, String> {
                            @Override public String adaptToJson(Money money) { return money.cents() + "c"; }
                            @Override public Money adaptFromJson(String text) {
                                if (!text.endsWith("c")) { throw new IllegalStateException("not money: " + text); }
                                return new Money(Long.parseLong(text.substring(0, text.length() - 1)));
                            }
                        }
                        """,
                "example.Price", "package example;" + IMPORTS + """
                        @JsonBound
                        public record Price(@JsonbTypeAdapter(MoneyAdapter.class) Money amount, @JsonbTypeAdapter(MoneyAdapter.class) @Nullable Money tax) {
                        }
                        """));
        JsonCodec<Object> price = codec(result, "example.PriceJson");

        Object read = price.fromJson("{\"amount\":\"150c\",\"tax\":null}");
        assertThat(read.toString()).isEqualTo("Price[amount=Money[cents=150], tax=null]");
        assertThat(price.toJson(read)).isEqualTo("{\"amount\":\"150c\"}");
        assertThat(price.toJson(price.fromJson("{\"amount\":\"1c\",\"tax\":\"2c\"}"))).isEqualTo("{\"amount\":\"1c\",\"tax\":\"2c\"}");
        assertThatThrownBy(() -> price.fromJson("{\"amount\":\"150\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not money");
        assertThat(result.generated("example.PriceJson")).contains("new example.MoneyAdapter()");

        Compilation.Result noConstructor = Compilation.compile(Map.of(
                "example.Money", "package example; public record Money(long cents) { }",
                "example.MoneyAdapter", "package example;" + IMPORTS + """
                        public class MoneyAdapter implements JsonbAdapter<Money, String> {
                            public MoneyAdapter(String unit) { }
                            @Override public String adaptToJson(Money money) { return ""; }
                            @Override public Money adaptFromJson(String text) { return new Money(0); }
                        }
                        """,
                "example.Price", "package example;" + IMPORTS + """
                        @JsonBound
                        public record Price(@JsonbTypeAdapter(MoneyAdapter.class) Money amount) {
                        }
                        """));
        assertThat(noConstructor.errors()).anySatisfy(e -> assertThat(e).contains("needs a public no-argument constructor"));
    }

    @Test
    void whatCannotBeBoundIsACompileErrorThatSaysWhy() {
        assertError("package example;" + IMPORTS + "@JsonBound public interface Shape { }", "binds a record or a class");
        assertError("package example;" + IMPORTS + "@JsonBound public record Box<T>(T value) { }", "generic type cannot be bound");
        assertError("package example;" + IMPORTS + "@JsonBound public record Bad(Object thing) { }",
                "cannot be bound: annotate it @JsonBound, or adapt it with @JsonbTypeAdapter");
        assertError("package example;" + IMPORTS + "@JsonBound public record Keys(Map<Integer, String> byNumber) { }",
                "has String keys, not java.lang.Integer");
        assertError("package example;" + IMPORTS + "@JsonBound public record Twice(@JsonbProperty(\"x\") String a, @JsonbProperty(\"x\") String b) { }",
                "Two properties are named \"x\" in JSON");
        assertError("package example;" + IMPORTS + "@JsonBound @JsonbVisibility(jakarta.json.bind.config.PropertyVisibilityStrategy.class) public record Seen(String a) { }",
                "@JsonbVisibility is not supported");
        assertError("package example;" + IMPORTS + "@JsonBound public record Num(@JsonbNumberFormat(\"#.##\") double d) { }",
                "@JsonbNumberFormat is not supported");
        assertError("package example;" + IMPORTS + "@JsonBound public record Prim(@JsonbTransient int n) { }",
                "@JsonbTransient creator parameter cannot be a primitive");
        assertError("package example;" + IMPORTS + """
                @JsonBound public class NoWay {
                    public NoWay(String a) { }
                    public String getA() { return ""; }
                }
                """, "no @JsonbCreator and no public no-argument constructor");
        assertError("package example;" + IMPORTS + "@JsonBound public abstract class Base { }", "abstract class cannot be created");

        Compilation.Result explicit = Compilation.compile(
                Map.of("example.Named", "package example;" + IMPORTS + "@JsonBound public record Named(@JsonbProperty(\"a\") String a, String b) { }"),
                "-Aspidersilk.json.names=explicit");
        assertThat(explicit.errors()).anySatisfy(e -> assertThat(e).contains("The property b has no @JsonbProperty name"));
        assertThat(Compilation.compile(
                Map.of("example.Named", "package example;" + IMPORTS + "@JsonBound public record Named(@JsonbProperty(\"a\") String a, @JsonbProperty(\"b\") String b) { }"),
                "-Aspidersilk.json.names=explicit").errors()).isEmpty();
    }

    private static void assertError(String source, String message) {
        String name = "example." + source.replaceAll("(?s).*?(record|class|interface) (\\w+).*", "$2");
        Compilation.Result result = Compilation.compile(Map.of(name, source));
        assertThat(result.success()).as(source).isFalse();
        assertThat(result.errors()).as(source).anySatisfy(e -> assertThat(e).contains(message));
    }

    /** A nested type generates a flat name, and the codec of one bound type calls another's. */
    @Test
    void aNestedBoundTypeIsNamedByItsOuterTypeToo() throws Exception {
        Compilation.Result result = compile(Map.of("example.Outer", "package example;" + IMPORTS + """
                public final class Outer {
                    @JsonBound
                    public record Inner(String v) {
                    }
                    @JsonBound
                    public record Pair(Inner left, @Nullable Inner right) {
                    }
                }
                """));
        JsonCodec<Object> pair = codec(result, "example.OuterPairJson");

        assertThat(pair.toJson(pair.fromJson("{\"left\":{\"v\":\"l\"}}"))).isEqualTo("{\"left\":{\"v\":\"l\"}}");
        assertThat(pair.toJson(pair.fromJson("{\"left\":{\"v\":\"l\"},\"right\":{\"v\":\"r\"}}")))
                .isEqualTo("{\"left\":{\"v\":\"l\"},\"right\":{\"v\":\"r\"}}");
        assertThat(result.generated("example.OuterPairJson")).contains("example.OuterInnerJson.CODEC.write(p_left, out);");
    }
}
