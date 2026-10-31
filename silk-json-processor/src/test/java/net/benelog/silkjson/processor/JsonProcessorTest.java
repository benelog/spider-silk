package net.benelog.silkjson.processor;

import java.util.Map;

import org.junit.jupiter.api.Test;

import net.benelog.silkjson.JsonCodec;
import net.benelog.silkjson.JsonException;

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
            import net.benelog.silkjson.*;
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
        assertThat(source).contains("public final class DeckJson implements net.benelog.silkjson.JsonCodec<example.Deck>")
                .contains("JsonKey.of(\"deck_name\")")
                .contains("if (!$in.nextKeyIs(K_note)) {")
                .doesNotContain("? 2 : keyIndex($in);")
                .contains("if ($in.keyIs(K_name)) {")
                .contains("return new example.Deck(p_id, p_name, p_note, p_active);")
                .doesNotContain("reflect");
    }

    /** The chain takes the declared order, the switch on a key's head any other, and the scan a key written another way. */
    @Test
    void keysAreFoundInAnyOrderAndHoweverTheyAreWritten() throws Exception {
        Compilation.Result result = compile(Map.of("example.Card", "package example;" + IMPORTS + """
                @JsonBound
                public record Card(long id, String desc, String description, @JsonbProperty("한글") @Nullable String korean,
                        @JsonbProperty("a") int a) {
                }
                """));
        JsonCodec<Object> card = codec(result, "example.CardJson");
        String expected = "Card[id=7, desc=d, description=long, korean=값, a=3]";

        for (String json : new String[] {
                "{\"id\":7,\"desc\":\"d\",\"description\":\"long\",\"한글\":\"값\",\"a\":3}",
                "{\"a\":3,\"한글\":\"값\",\"description\":\"long\",\"desc\":\"d\",\"id\":7}",
                "{\"description\":\"long\",\"id\":7,\"descr\":0,\"a\":3,\"de\":[1],\"desc\":\"d\",\"한\":1,\"한글\":\"값\"}",
                "{ \"a\" : 3 , \"id\" : 7 , \"desc\" : \"d\" , \"description\" : \"long\" , \"한글\" : \"값\" }",
                "{\"\\u0069d\":7,\"desc\":\"d\",\"descriptio\\u006e\":\"long\",\"\\ud55c글\":\"값\",\"a\":3}",
                "{\"id\":7,\"desc\":\"d\",\"description\":\"long\",\"한글\":\"값\",\"a\":3,\"unknown\":{\"id\":1}}",
                "{\"id\": 7, \"desc\": \"d\", \"description\": \"long\", \"한글\": \"값\", \"a\": 3}",
                "{\n  \"id\": 7,\n  \"desc\": \"d\",\n  \"description\": \"long\",\n  \"한글\": \"값\",\n  \"a\": 3\n}",
                "{\n  \"a\": 3,\n  \"한글\": \"값\",\n  \"description\": \"long\",\n  \"desc\": \"d\",\n  \"id\": 7\n}",
        }) {
            assertThat(card.fromJson(json).toString()).as(json).isEqualTo(expected);
        }
        Object read = card.fromJson("{\"id\":7,\"desc\":\"d\",\"description\":\"long\",\"한글\":\"값\",\"a\":3}");
        assertThat(card.toJson(card.fromJson(card.toJson(read)))).isEqualTo(card.toJson(read));
        assertThatThrownBy(() -> card.fromJson("{\"a\":3,\"desc\":\"d\",\"description\":\"long\"}"))
                .hasMessage("Missing key in JSON object: id");

        String source = result.generated("example.CardJson");
        assertThat(source).contains("break chain;")
                .contains("case 0x6469222c -> {") // ,"id
                .contains("case 0x22646922 -> {") // "id"
                // ,"de: two keys share the head, and are tried in declaration order
                .contains("case 0x6564222c -> {\n                if ($in.nextKeyIs(K_desc)) {\n                    return 1;\n"
                        + "                }\n                if ($in.nextKeyIs(K_description)) {\n                    return 2;");
    }

    /** A type too wide for the chain reads in its loop, which tries the key declared next in place first. */
    @Test
    void aWideTypeReadsInItsLoopInAnyOrder() throws Exception {
        StringBuilder components = new StringBuilder();
        StringBuilder declared = new StringBuilder("{");
        StringBuilder reversed = new StringBuilder("{");
        for (int i = 0; i < 33; i++) {
            components.append(i == 0 ? "" : ", ").append("int p").append(i);
            declared.append(i == 0 ? "" : ",").append("\"p").append(i).append("\":").append(i);
            reversed.append(i == 0 ? "" : ",").append("\"p").append(32 - i).append("\":").append(32 - i);
        }
        Compilation.Result result = compile(Map.of("example.Wide", "package example;" + IMPORTS
                + "@JsonBound public record Wide(" + components + ") { }"));
        JsonCodec<Object> wide = codec(result, "example.WideJson");

        String json = declared.append("}").toString();
        assertThat(wide.toJson(wide.fromJson(json))).isEqualTo(json);
        assertThat(wide.toJson(wide.fromJson(reversed.append("}").toString()))).isEqualTo(json);
        assertThat(result.generated("example.WideJson")).doesNotContain("break chain;")
                .contains("$key = $in.nextKeyIs(K_p1) ? 1 : keyIndex($in);");
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
                                List<@Nullable String> notes, Optional<String> hint, OptionalInt weight, Level level, List<Level> path,
                                Map<String, Level> byStep,
                                LocalDate due, @JsonbDateFormat("dd/MM/yyyy") LocalDate reviewed, BigDecimal price,
                                UUID uuid, JsonValue raw, Instant at, Duration took, short small, byte tiny, float ratio, char initial) {
                        }
                        """));
        JsonCodec<Object> card = codec(result, "example.CardJson");

        String document = "{\"text\":\"t\",\"tags\":[{\"label\":\"a\"},{\"label\":\"b\"}],\"scores\":[1,2],\"byName\":{\"x\":{\"label\":\"c\"}},"
                + "\"notes\":[\"n\",null],\"hint\":\"h\",\"weight\":3,\"level\":\"HARD\",\"path\":[\"EASY\",\"HARD\"],"
                + "\"byStep\":{\"one\":\"EASY\"},\"due\":\"2026-10-09\",\"reviewed\":\"09/10/2026\","
                + "\"price\":1.50,\"uuid\":\"123e4567-e89b-12d3-a456-426614174000\",\"raw\":{\"k\":[1,true,null]},"
                + "\"at\":\"2026-10-09T12:00:00Z\",\"took\":\"PT1H\",\"small\":7,\"tiny\":-1,\"ratio\":0.5,\"initial\":\"c\"}";
        Object read = card.fromJson(document);

        assertThat(card.toJson(read)).isEqualTo(document);
        assertThat(read.toString()).contains("tags=[Tag[label=a], Tag[label=b]]").contains("scores=[1, 2]")
                .contains("byName={x=Tag[label=c]}").contains("notes=[n, null]").contains("hint=Optional[h]")
                .contains("weight=OptionalInt[3]").contains("level=HARD").contains("path=[EASY, HARD]")
                .contains("byStep={one=EASY}").contains("due=2026-10-09")
                .contains("reviewed=2026-10-09").contains("price=1.50").contains("at=2026-10-09T12:00:00Z")
                .contains("took=PT1H").contains("small=7").contains("tiny=-1").contains("ratio=0.5").contains("initial=c");

        String sparse = document.replace("\"hint\":\"h\",\"weight\":3,", "\"hint\":null,\"weight\":null,");
        Object sparseRead = card.fromJson(sparse);
        assertThat(sparseRead.toString()).contains("hint=Optional.empty").contains("weight=OptionalInt.empty");
        assertThat(card.toJson(sparseRead)).doesNotContain("hint").doesNotContain("weight");

        assertThatThrownBy(() -> card.fromJson(document.replace("\"level\":\"HARD\"", "\"level\":\"MEDIUM\"")))
                .isInstanceOf(JsonException.class).hasMessageContaining("Not a name this value takes: MEDIUM");
        assertThatThrownBy(() -> card.fromJson(document.replace("[\"EASY\",", "[\"MEDIUM\",")))
                .isInstanceOf(JsonException.class).hasMessageContaining("Not a name this value takes: MEDIUM");
        assertThat(card.fromJson(document.replace("\"HARD\"", "\"\\u0048ARD\"")).toString())
                .contains("level=HARD").contains("path=[EASY, HARD]");
        assertThat(result.generated("example.CardJson"))
                .containsOnlyOnce("private static final example.Level[] C_Level = example.Level.values();")
                .contains("JsonKey.names(C_Level);")
                .contains("$out.put(K_level, V_Level[p_level.ordinal()]);")
                .contains("$out.value(V_Level[$element.ordinal()]);")
                .contains("C_Level[$in.readOneOf(V_Level)]")
                .doesNotContain("Level.valueOf").doesNotContain(".name()");
        assertThatThrownBy(() -> card.fromJson(document.replace("\"2026-10-09\"", "\"yesterday\"")))
                .isInstanceOf(java.time.DateTimeException.class);
        assertThatThrownBy(() -> card.fromJson(document.replace("\"small\":7", "\"small\":70000")))
                .hasMessageContaining("Not a 16-bit integer");
        assertThatThrownBy(() -> card.fromJson(document.replace("\"initial\":\"c\"", "\"initial\":\"cd\"")))
                .hasMessageContaining("Not a single character");
    }

    /**
     * A float is rounded once from the decimal, which (float) of the nearest double is not, and the sign of a zero is kept.
     * A float is written as Float.toString writes it, not as the double it widens to.
     */
    @Test
    void floatsAndDoublesRoundTripToTheBit() throws Exception {
        Compilation.Result result = compile(Map.of("example.Reading", "package example;" + IMPORTS + """
                @JsonBound
                public record Reading(float f, Float boxedFloat, double d, Double boxed, OptionalDouble maybe) {
                }
                """));
        JsonCodec<Object> reading = codec(result, "example.ReadingJson");

        Object read = reading.fromJson("{\"f\":1.00000017881393432617187499,\"boxedFloat\":-0,"
                + "\"d\":0.30000000000000004,\"boxed\":-0.0,\"maybe\":4.9e-324}");
        assertThat(read.toString()).isEqualTo(
                "Reading[f=1.0000001, boxedFloat=-0.0, d=0.30000000000000004, boxed=-0.0, maybe=OptionalDouble[4.9E-324]]");
        assertThat(reading.fromJson(reading.toJson(read))).isEqualTo(read);
        String tenths = "{\"f\":0.1,\"boxedFloat\":-0.1,\"d\":0.1,\"boxed\":-0.1,\"maybe\":0.1}";
        assertThat(reading.toJson(reading.fromJson(tenths))).isEqualTo(tenths);
        Object extremes = reading.fromJson("{\"f\":3.4028235e38,\"boxedFloat\":1.4e-45,"
                + "\"d\":1.7976931348623157e308,\"boxed\":-2.2250738585072014E-308,\"maybe\":null}");
        assertThat(reading.fromJson(reading.toJson(extremes))).isEqualTo(extremes);
        assertThatThrownBy(() -> reading.fromJson("{\"f\":1e39,\"boxedFloat\":0,\"d\":0,\"boxed\":0}"))
                .hasMessageContaining("Number out of range: 1e39");
        assertThat(result.generated("example.ReadingJson")).contains("in.readFloat()").doesNotContain("readFloat(in)");
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
        assertError("package example;" + IMPORTS + "@JsonBound public record Bad(Object thing) { }",
                "cannot be bound: annotate it @JsonBound, or adapt it with @JsonbTypeAdapter");
        assertError("package example;" + IMPORTS + "@JsonBound public record Keys(Map<Integer, String> byNumber) { }",
                "has String keys, not java.lang.Integer");
        assertError("package example;" + IMPORTS + "@JsonBound public record Twice(@JsonbProperty(\"x\") String a, @JsonbProperty(\"x\") String b) { }",
                "Two properties are named \"x\" in JSON");
        assertError("package example;" + IMPORTS + "@JsonBound @JsonbVisibility(jakarta.json.bind.config.PropertyVisibilityStrategy.class) public record Seen(String a) { }",
                "@JsonbVisibility is not supported");
        assertError("package example;" + IMPORTS + "@JsonBound public record Num(@JsonbNumberFormat(\"0.0.0\") double d) { }",
                "The number format 0.0.0 is not a pattern");
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
                "-Asilkjson.names=explicit");
        assertThat(explicit.errors()).anySatisfy(e -> assertThat(e).contains("The property b has no @JsonbProperty name"));
        assertThat(Compilation.compile(
                Map.of("example.Named", "package example;" + IMPORTS + "@JsonBound public record Named(@JsonbProperty(\"a\") String a, @JsonbProperty(\"b\") String b) { }"),
                "-Asilkjson.names=explicit").errors()).isEmpty();
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
        assertThat(result.generated("example.OuterPairJson")).contains("example.OuterInnerJson.CODEC.write(p_left, $out);");
    }

    /** A collection inside a collection gets a helper of its own, after the outer one rather than inside it. */
    @Test
    void collectionsNestInCollectionsAndMaps() throws Exception {
        Compilation.Result result = compile(Map.of("example.Grid", "package example;" + IMPORTS + """
                @JsonBound
                public record Grid(List<List<Integer>> rows, Map<String, List<String>> byKey, List<Map<String, Set<Long>>> deep) {
                }
                """));
        JsonCodec<Object> grid = codec(result, "example.GridJson");

        String document = "{\"rows\":[[1,2],[],[3]],\"byKey\":{\"a\":[\"x\",\"y\"]},\"deep\":[{\"k\":[7]}]}";
        assertThat(grid.toJson(grid.fromJson(document))).isEqualTo(document);
    }

    /** A class's properties include those it inherits, the superclass's first, with a subclass's override read first. */
    @Test
    void aClassBindsTheGettersAndSettersItInherits() throws Exception {
        Compilation.Result result = compile(Map.of(
                "example.base.Entity", "package example.base;" + IMPORTS + """
                        public class Entity<T> {
                            private long id;
                            private @Nullable T payload;
                            public long getId() { return id; }
                            public void setId(long id) { this.id = id; }
                            public @Nullable T getPayload() { return payload; }
                            public void setPayload(@Nullable T payload) { this.payload = payload; }
                            public String getKind() { return "entity"; }
                        }
                        """,
                "example.Child", "package example;" + IMPORTS + """
                        @JsonBound
                        public class Child extends example.base.Entity<String> {
                            private @Nullable String label;
                            public @Nullable String getLabel() { return label; }
                            public void setLabel(@Nullable String label) { this.label = label; }
                            @Override @JsonbProperty("type") public String getKind() { return "child"; }
                        }
                        """));
        JsonCodec<Object> child = codec(result, "example.ChildJson");

        Object read = child.fromJson("{\"id\":7,\"payload\":\"p\",\"label\":\"y\"}");
        assertThat(child.toJson(read)).isEqualTo("{\"id\":7,\"payload\":\"p\",\"type\":\"child\",\"label\":\"y\"}");
    }

    /** The generated names never shadow a package whose first segment is a common word. */
    @Test
    void aPackageNamedLikeAGeneratedLocalStillCompiles() throws Exception {
        for (String pkg : new String[] {"in.co.acme", "out.x", "value.x", "key.x", "result.x", "values.x", "element.x", "entry.x"}) {
            Compilation.Result result = compile(Map.of(
                    pkg + ".Tag", "package " + pkg + ";" + IMPORTS + "@JsonBound public record Tag(String label) { }",
                    pkg + ".Pair", "package " + pkg + ";" + IMPORTS + """
                            @JsonBound
                            public record Pair(Tag left, List<Tag> tags, Map<String, Tag> byName) {
                            }
                            """,
                    pkg + ".Holder", "package " + pkg + ";" + IMPORTS + """
                            @JsonBound
                            public class Holder {
                                private @Nullable Tag tag;
                                public @Nullable Tag getTag() { return tag; }
                                public void setTag(@Nullable Tag tag) { this.tag = tag; }
                            }
                            """));
            JsonCodec<Object> pair = codec(result, pkg + ".PairJson");
            String document = "{\"left\":{\"label\":\"l\"},\"tags\":[{\"label\":\"t\"}],\"byName\":{\"n\":{\"label\":\"b\"}}}";
            assertThat(pair.toJson(pair.fromJson(document))).as(pkg).isEqualTo(document);
            JsonCodec<Object> holder = codec(result, pkg + ".HolderJson");
            assertThat(holder.toJson(holder.fromJson("{\"tag\":{\"label\":\"h\"}}"))).as(pkg).isEqualTo("{\"tag\":{\"label\":\"h\"}}");
        }
    }

    /** A generated codec can give @JsonbVisibility no meaning; a package's date format, number format, and nillable apply. */
    @Test
    void typeAndPackageAnnotationsAreReadOrRefused() throws Exception {
        Compilation.Result visibility = Compilation.compile(Map.of(
                "example.package-info", "@jakarta.json.bind.annotation.JsonbVisibility(jakarta.json.bind.config.PropertyVisibilityStrategy.class)"
                        + " package example;",
                "example.Seen", "package example;" + IMPORTS + "@JsonBound public record Seen(String a) { }"));
        assertThat(visibility.errors()).anySatisfy(e -> assertThat(e).contains("@JsonbVisibility is not supported by the generated codec,"
                + " here on package example"));

        Compilation.Result defaults = compile(Map.of(
                "example.package-info", "@jakarta.json.bind.annotation.JsonbDateFormat(\"dd.MM.yyyy\")"
                        + " @jakarta.json.bind.annotation.JsonbNumberFormat(\"0.0\")"
                        + " @jakarta.json.bind.annotation.JsonbNillable package example;",
                "example.Due", "package example;" + IMPORTS
                        + "@JsonBound public record Due(LocalDate on, @Nullable String note, double rate, @JsonbNumberFormat int plain) { }"));
        JsonCodec<Object> due = codec(defaults, "example.DueJson");
        assertThat(due.toJson(due.fromJson("{\"on\":\"09.10.2026\",\"rate\":\"1.5\",\"plain\":3}")))
                .isEqualTo("{\"on\":\"09.10.2026\",\"note\":null,\"rate\":\"1.5\",\"plain\":3}");
    }

    /** A number in a format is a string on the wire, as JSON-B has it, and reads back only when the whole string is the number. */
    @Test
    void aNumberFormatWritesAndReadsEveryNumberType() throws Exception {
        Compilation.Result result = compile(Map.of("example.Price", "package example;" + IMPORTS + """
                @JsonBound
                public record Price(@JsonbNumberFormat("#,##0.00") double amount, @JsonbNumberFormat("000") int code,
                        @JsonbNumberFormat("#.##########") float ratio, @JsonbNumberFormat("0.0") BigDecimal exact,
                        @JsonbNumberFormat("#,##0") BigInteger big, @JsonbNumberFormat("0") Long boxed, @JsonbNumberFormat("0") byte tiny,
                        @JsonbNumberFormat("0.0") List<Double> rates, @JsonbNumberFormat("0.0") Optional<Double> hint,
                        @JsonbNumberFormat("00") OptionalInt level, @JsonbNumberFormat(value = "#,##0.00", locale = "de") double german) {
                }
                """));
        JsonCodec<Object> price = codec(result, "example.PriceJson");

        String document = "{\"amount\":\"1,234.50\",\"code\":\"007\",\"ratio\":\"0.1\",\"exact\":\"2.5\","
                + "\"big\":\"123,456,789,012,345,678,901\",\"boxed\":\"9\",\"tiny\":\"-3\",\"rates\":[\"1.5\",\"2.5\"],"
                + "\"hint\":\"0.5\",\"level\":\"04\",\"german\":\"1.234,50\"}";
        Object read = price.fromJson(document);
        assertThat(read.toString()).contains("amount=1234.5").contains("code=7").contains("ratio=0.1").contains("exact=2.5")
                .contains("big=123456789012345678901").contains("tiny=-3").contains("rates=[1.5, 2.5]").contains("level=OptionalInt[4]");
        assertThat(price.toJson(read)).isEqualTo(document);

        assertThatThrownBy(() -> price.fromJson(document.replace("\"007\"", "\"7 apples\"")))
                .isInstanceOf(JsonException.class).hasMessageContaining("Not a number in the format");
        assertThatThrownBy(() -> price.fromJson(document.replace("\"007\"", "\"7.5\"")))
                .isInstanceOf(JsonException.class).hasMessageContaining("Not a whole number in range");
        assertThatThrownBy(() -> price.fromJson(document.replace("\"123,456,789,012,345,678,901\"", "\"1.5\"")))
                .isInstanceOf(JsonException.class).hasMessageContaining("Not a whole number");
        assertThatThrownBy(() -> price.fromJson(document.replace("\"-3\"", "\"300\"")))
                .isInstanceOf(JsonException.class).hasMessageContaining("Not a whole number in range");
    }

    /** TIME_IN_MILLIS is a JSON number, as Yasson writes it, and a time with no zone stands in UTC. */
    @Test
    void timeInMillisIsANumber() throws Exception {
        Compilation.Result result = compile(Map.of("example.Stamps", "package example;" + IMPORTS + """
                @JsonBound
                public record Stamps(@JsonbDateFormat(JsonbDateFormat.TIME_IN_MILLIS) Instant at,
                        @JsonbDateFormat(JsonbDateFormat.TIME_IN_MILLIS) ZonedDateTime zoned,
                        @JsonbDateFormat(JsonbDateFormat.TIME_IN_MILLIS) OffsetDateTime offset,
                        @JsonbDateFormat(JsonbDateFormat.TIME_IN_MILLIS) LocalDateTime local,
                        @JsonbDateFormat(JsonbDateFormat.TIME_IN_MILLIS) LocalDate day,
                        @JsonbDateFormat(JsonbDateFormat.TIME_IN_MILLIS) List<Instant> all) {
                }
                """));
        JsonCodec<Object> stamps = codec(result, "example.StampsJson");

        String document = "{\"at\":1500,\"zoned\":1500,\"offset\":1500,\"local\":1500,\"day\":0,\"all\":[1500]}";
        Object read = stamps.fromJson(document);
        assertThat(read.toString()).contains("at=1970-01-01T00:00:01.500Z").contains("zoned=1970-01-01T00:00:01.500Z")
                .contains("local=1970-01-01T00:00:01.500").contains("day=1970-01-01");
        assertThat(stamps.toJson(read)).isEqualTo(document);
        assertError("package example;" + IMPORTS
                + "@JsonBound public record Later(@JsonbDateFormat(JsonbDateFormat.TIME_IN_MILLIS) LocalTime at) { }",
                "TIME_IN_MILLIS takes an instant or a date, not java.time.LocalTime");
    }

    private static final Map<String, String> ANIMALS = Map.of(
            "example.Animal", "package example;" + IMPORTS + """
                    @JsonBound
                    @JsonbTypeInfo(key = "kind", value = {
                        @JsonbSubtype(alias = "dog", type = Dog.class),
                        @JsonbSubtype(alias = "puppy", type = Puppy.class),
                        @JsonbSubtype(alias = "cat", type = Cat.class),
                        @JsonbSubtype(alias = "bird", type = Bird.class)
                    })
                    public interface Animal {
                    }
                    """,
            "example.Dog", "package example;" + IMPORTS + """
                    @JsonBound
                    public class Dog implements Animal {
                        public String name = "";
                    }
                    """,
            "example.Puppy", "package example;" + IMPORTS + """
                    @JsonBound
                    public class Puppy extends Dog {
                        public int weeks;
                    }
                    """,
            "example.Cat", "package example;" + IMPORTS + "@JsonBound public record Cat(int lives) implements Animal { }",
            "example.Bird", "package example;" + IMPORTS + """
                    @JsonBound
                    @JsonbTypeInfo(key = "wings", value = {
                        @JsonbSubtype(alias = "owl", type = Owl.class)
                    })
                    public abstract class Bird implements Animal {
                    }
                    """,
            "example.Owl", "package example;" + IMPORTS + "@JsonBound public class Owl extends Bird { public int age; }",
            "example.Zoo", "package example;" + IMPORTS + "@JsonBound public record Zoo(Animal star, List<Animal> all, @Nullable Animal spare) { }");

    /** The key goes first, a subclass is tried before its superclass, and a subtype can have subtypes under a key of its own. */
    @Test
    void jsonbTypeInfoChoosesTheSubtypeWhereverItsKeyStands() throws Exception {
        Compilation.Result result = compile(ANIMALS);
        JsonCodec<Object> zoo = codec(result, "example.ZooJson");
        JsonCodec<Object> animal = codec(result, "example.AnimalJson");

        String document = "{\"star\":{\"kind\":\"cat\",\"lives\":9},\"all\":[{\"kind\":\"dog\",\"name\":\"Rex\"},"
                + "{\"kind\":\"puppy\",\"name\":\"Bo\",\"weeks\":6},{\"kind\":\"bird\",\"wings\":\"owl\",\"age\":3}]}";
        assertThat(zoo.toJson(zoo.fromJson(document))).isEqualTo(document);
        assertThat(animal.fromJson("{\"lives\":7,\"name\":\"x\",\"kind\":\"cat\"}").toString()).isEqualTo("Cat[lives=7]");
        assertThat(animal.fromJson("{ \"lives\" : 7 , \"kind\" : \"cat\" }").toString()).isEqualTo("Cat[lives=7]");

        assertThatThrownBy(() -> animal.fromJson("{\"lives\":7}"))
                .isInstanceOf(JsonException.class).hasMessage("Missing key in JSON object: kind");
        assertThatThrownBy(() -> animal.fromJson("{\"kind\":\"fish\"}"))
                .isInstanceOf(JsonException.class).hasMessageContaining("Not a name this value takes: fish");
        assertThatThrownBy(() -> animal.fromJson("[]"))
                .isInstanceOf(JsonException.class).hasMessageContaining("Not a JSON object: an array");

        assertThat(result.generated("example.AnimalJson")).doesNotContain("reflect");
        assertThat(result.generated("example.AnimalJson").indexOf("instanceof example.Puppy"))
                .isLessThan(result.generated("example.AnimalJson").indexOf("instanceof example.Dog"));
    }

    /** The manual's example: a sealed interface, whose subtypes are records. */
    @Test
    void aSealedInterfaceListsItsRecords() throws Exception {
        Compilation.Result result = compile(Map.of(
                "example.Animal", "package example;" + IMPORTS + """
                        @JsonBound
                        @JsonbTypeInfo({
                            @JsonbSubtype(alias = "dog", type = Dog.class),
                            @JsonbSubtype(alias = "cat", type = Cat.class)
                        })
                        public sealed interface Animal permits Dog, Cat {
                        }
                        """,
                "example.Dog", "package example;" + IMPORTS + "@JsonBound public record Dog(String name) implements Animal { }",
                "example.Cat", "package example;" + IMPORTS + "@JsonBound public record Cat(int lives) implements Animal { }"));
        JsonCodec<Object> animal = codec(result, "example.AnimalJson");
        JsonCodec<Object> cat = codec(result, "example.CatJson");

        Object read = animal.fromJson("{\"lives\":9,\"@type\":\"cat\"}");
        assertThat(animal.toJson(read)).isEqualTo("{\"@type\":\"cat\",\"lives\":9}");
        assertThat(cat.toJson(cat.fromJson("{\"lives\":9,\"@type\":\"cat\"}"))).isEqualTo("{\"lives\":9}");
    }

    /** A class that can be a value itself reads an object without the key as itself, and writes one with no key. */
    @Test
    void aConcreteClassWithSubtypesIsAValueItself() throws Exception {
        Compilation.Result result = compile(Map.of(
                "example.Shape", "package example;" + IMPORTS + """
                        @JsonBound
                        @JsonbTypeInfo(@JsonbSubtype(alias = "round", type = Round.class))
                        public class Shape {
                            public String label = "";
                        }
                        """,
                "example.Round", "package example;" + IMPORTS + "@JsonBound public class Round extends Shape { public int radius; }"));
        JsonCodec<Object> shape = codec(result, "example.ShapeJson");

        assertThat(shape.toJson(shape.fromJson("{\"label\":\"a\"}"))).isEqualTo("{\"label\":\"a\"}");
        assertThat(shape.toJson(shape.fromJson("{\"@type\":\"round\",\"label\":\"b\",\"radius\":2}")))
                .isEqualTo("{\"@type\":\"round\",\"label\":\"b\",\"radius\":2}");
    }

    @Test
    void whatJsonbTypeInfoCannotDoIsACompileError() {
        String sub = "@JsonBound public record Sub(int a) implements Root { }";
        assertTypeInfoError("@JsonbTypeInfo({})", "public interface Root { }", sub, "lists no subtypes");
        assertTypeInfoError("@JsonbTypeInfo({@JsonbSubtype(alias = \"a\", type = Sub.class), @JsonbSubtype(alias = \"a\", type = Sub2.class)})",
                "public interface Root { }", sub, "go by the alias a");
        assertTypeInfoError("@JsonbTypeInfo(@JsonbSubtype(alias = \"a\", type = Other.class))", "public interface Root { }", sub,
                "example.Other is not a subtype of example.Root");
        assertTypeInfoError("@JsonbTypeInfo(@JsonbSubtype(alias = \"a\", type = Sub.class))", "public interface Root { }",
                "public record Sub(int a) implements Root { }", "needs @JsonBound");
        assertTypeInfoError("@JsonbTypeInfo(@JsonbSubtype(alias = \"a\", type = Sub.class))", "public interface Root<T> { }",
                "@JsonBound public record Sub(int a) implements Root<String> { }", "A generic type cannot carry @JsonbTypeInfo");
        assertTypeInfoError("@JsonbTypeInfo(@JsonbSubtype(alias = \"a\", type = Sub.class))", "public interface Root { }",
                "@JsonBound public record Sub(@JsonbProperty(\"@type\") int a) implements Root { }", "the key @JsonbTypeInfo keeps");
    }

    private static void assertTypeInfoError(String typeInfo, String root, String sub, String message) {
        Compilation.Result result = Compilation.compile(Map.of(
                "example.Root", "package example;" + IMPORTS + "@JsonBound " + typeInfo + " " + root,
                "example.Sub", "package example;" + IMPORTS + sub,
                "example.Sub2", "package example;" + IMPORTS + "@JsonBound public record Sub2(int b) implements Root { }".replace(
                        "implements Root", root.contains("<T>") ? "implements Root<String>" : "implements Root"),
                "example.Other", "package example;" + IMPORTS + "@JsonBound public record Other(int c) { }"));
        assertThat(result.success()).as(typeInfo).isFalse();
        assertThat(result.errors()).as(typeInfo).anySatisfy(e -> assertThat(e).contains(message));
    }

    /** A generic type's codec takes a codec for each type variable, and a type that names its arguments builds it where it is used. */
    @Test
    void aGenericTypeTakesACodecForEachTypeVariable() throws Exception {
        Compilation.Result result = compile(Map.of(
                "example.Tag", "package example;" + IMPORTS + "@JsonBound public record Tag(String label) { }",
                "example.Level", "package example; public enum Level { EASY, HARD }",
                "example.Box", "package example;" + IMPORTS + """
                        @JsonBound
                        public record Box<T>(T value, @Nullable T spare, List<T> more, Map<String, T> byName, Optional<T> maybe) {
                        }
                        """,
                "example.Page", "package example;" + IMPORTS + """
                        @JsonBound
                        public class Page<K extends Comparable<K>, V> {
                            private @Nullable K next;
                            public List<V> items = List.of();
                            public @Nullable K getNext() { return next; }
                            public void setNext(@Nullable K next) { this.next = next; }
                        }
                        """,
                "example.Node", "package example;" + IMPORTS + "@JsonBound public record Node<T>(T value, @Nullable Node<T> next) { }",
                "example.Holder", "package example;" + IMPORTS + """
                        @JsonBound
                        public record Holder(Box<Tag> tag, Box<String> text, Box<Integer> number, Box<List<Level>> levels,
                                Box<Box<Tag>> nested, Page<String, Tag> page, Node<Long> chain) {
                        }
                        """,
                "example.Outer", "package example;" + IMPORTS + "@JsonBound public record Outer<U>(Box<U> inner, Box<List<U>> list) { }"));
        JsonCodec<Object> holder = codec(result, "example.HolderJson");

        String tag = "{\"value\":{\"label\":\"a\"},\"more\":[],\"byName\":{}}";
        String document = "{\"tag\":" + tag + ","
                + "\"text\":{\"value\":\"t\",\"spare\":\"s\",\"more\":[\"m\"],\"byName\":{\"k\":\"v\"},\"maybe\":\"o\"},"
                + "\"number\":{\"value\":1,\"more\":[2],\"byName\":{}},"
                + "\"levels\":{\"value\":[\"EASY\",\"HARD\"],\"more\":[],\"byName\":{}},"
                + "\"nested\":{\"value\":" + tag + ",\"more\":[],\"byName\":{}},"
                + "\"page\":{\"next\":\"n\",\"items\":[{\"label\":\"b\"}]},"
                + "\"chain\":{\"value\":1,\"next\":{\"value\":2}}}";
        assertThat(holder.toJson(holder.fromJson(document))).isEqualTo(document);
        assertThatThrownBy(() -> holder.fromJson(document.replace("\"value\":\"t\",", "")))
                .isInstanceOf(JsonException.class).hasMessage("Missing key in JSON object: value");

        assertThat(result.generated("example.BoxJson"))
                .contains("public final class BoxJson<T> implements net.benelog.silkjson.JsonCodec<example.Box<T>>")
                .contains("public static <T> BoxJson<T> of(net.benelog.silkjson.JsonCodec<T> $T)")
                .doesNotContain("CODEC");
        assertThat(result.generated("example.PageJson"))
                .contains("public final class PageJson<K extends java.lang.Comparable<K>, V>");
        assertThat(result.generated("example.NodeJson")).contains("this.write(p_next, $out);");
        assertThat(result.generated("example.HolderJson")).contains("example.BoxJson.of(example.TagJson.CODEC).write(p_tag, $out);");

        // what the application writes for a generic type at the top of a document
        Class<?> box = result.load("example.BoxJson");
        @SuppressWarnings("unchecked")
        JsonCodec<Object> ofStrings = (JsonCodec<Object>) box.getMethod("of", JsonCodec.class).invoke(null,
                JsonCodec.of((String value, net.benelog.silkjson.JsonOutput out) -> out.value(value),
                        net.benelog.silkjson.JsonInput::readString));
        assertThat(ofStrings.toJson(ofStrings.fromJson("{\"value\":\"x\",\"more\":[],\"byName\":{}}")))
                .isEqualTo("{\"value\":\"x\",\"more\":[],\"byName\":{}}");

        assertError("package example;" + IMPORTS + """
                @JsonBound public class Own {
                    public <X> X getThing() { return null; }
                }
                """, "The type variable X cannot be bound");
    }

    /** An adapter on the type converts the whole value, wherever the type is used, and an interface can then be bound. */
    @Test
    void anAdapterOnATypeConvertsItWhole() throws Exception {
        Compilation.Result result = compile(Map.of(
                "example.Money", "package example;" + IMPORTS + """
                        @JsonBound
                        @JsonbTypeAdapter(Money.Adapter.class)
                        public record Money(long cents) {
                            public static class Adapter implements JsonbAdapter<Money, String> {
                                @Override public String adaptToJson(Money money) { return money.cents() + "c"; }
                                @Override public Money adaptFromJson(String text) { return new Money(Long.parseLong(text.replace("c", ""))); }
                            }
                        }
                        """,
                "example.Unit", "package example;" + IMPORTS + """
                        @JsonBound
                        @JsonbTypeAdapter(Unit.Adapter.class)
                        public interface Unit {
                            String code();
                            class Adapter implements JsonbAdapter<Unit, String> {
                                public Adapter() { }
                                @Override public String adaptToJson(Unit unit) { return unit.code(); }
                                @Override public Unit adaptFromJson(String text) { return () -> text; }
                            }
                        }
                        """,
                "example.Bill", "package example;" + IMPORTS + "@JsonBound public record Bill(Money total, List<Money> lines, Unit unit) { }"));
        JsonCodec<Object> bill = codec(result, "example.BillJson");

        String document = "{\"total\":\"150c\",\"lines\":[\"100c\",\"50c\"],\"unit\":\"kg\"}";
        assertThat(bill.toJson(bill.fromJson(document))).isEqualTo(document);
    }

    private static final String SERIALIZERS = IMPORTS + """
            import jakarta.json.bind.serializer.*;
            import jakarta.json.stream.*;
            import java.lang.reflect.Type;
            """;

    /** A serializer and a deserializer work in JSON-P's generator and parser, through silk-json-jsonb, on a property or a type. */
    @Test
    void aSerializerAndADeserializerRunThroughJsonP() throws Exception {
        Compilation.Result result = compile(Map.of(
                "example.Point", "package example; public record Point(int x, int y) { }",
                "example.PointSerializer", "package example;" + SERIALIZERS + """
                        public class PointSerializer implements JsonbSerializer<Point> {
                            @Override public void serialize(Point point, JsonGenerator generator, SerializationContext context) {
                                generator.writeStartArray().write(point.x()).write(point.y()).writeEnd();
                            }
                        }
                        """,
                "example.PointDeserializer", "package example;" + SERIALIZERS + """
                        public class PointDeserializer implements JsonbDeserializer<Point> {
                            @Override public Point deserialize(JsonParser parser, DeserializationContext context, Type type) {
                                jakarta.json.JsonArray array = parser.getArray();
                                return new Point(array.getInt(0), array.getInt(1));
                            }
                        }
                        """,
                "example.Count", "package example;" + SERIALIZERS + """
                        public class Count implements JsonbSerializer<Integer> {
                            @Override public void serialize(Integer n, JsonGenerator generator, SerializationContext context) {
                                context.serialize("n" + n, generator);
                            }
                        }
                        """,
                "example.Whole", "package example;" + SERIALIZERS + """
                        @JsonBound
                        @JsonbTypeSerializer(Whole.Out.class)
                        @JsonbTypeDeserializer(Whole.In.class)
                        public interface Whole {
                            String text();
                            class Out implements JsonbSerializer<Whole> {
                                public Out() { }
                                @Override public void serialize(Whole whole, JsonGenerator generator, SerializationContext context) {
                                    generator.writeStartObject();
                                    context.serialize("text", whole.text(), generator);
                                    generator.writeEnd();
                                }
                            }
                            class In implements JsonbDeserializer<Whole> {
                                public In() { }
                                @Override public Whole deserialize(JsonParser parser, DeserializationContext context, Type type) {
                                    parser.next();
                                    String text = context.deserialize(String.class, parser);
                                    return () -> text + "!";
                                }
                            }
                        }
                        """,
                "example.Route", "package example;" + SERIALIZERS + """
                        @JsonBound
                        public record Route(
                                @JsonbTypeSerializer(PointSerializer.class) @JsonbTypeDeserializer(PointDeserializer.class) Point from,
                                @JsonbTypeSerializer(PointSerializer.class) @JsonbTypeDeserializer(PointDeserializer.class) @Nullable Point to,
                                @JsonbTypeSerializer(Count.class) int stops, Whole whole, String after) {
                        }
                        """));
        JsonCodec<Object> route = codec(result, "example.RouteJson");

        Object read = route.fromJson("{\"from\":[1,2],\"to\":null,\"stops\":3,\"whole\":{\"text\":\"w\"},\"after\":\"a\"}");
        assertThat(read.toString()).contains("from=Point[x=1, y=2]").contains("to=null").contains("stops=3").contains("after=a");
        assertThat(route.toJson(route.fromJson("{\"from\":[1,2],\"stops\":3,\"whole\":{\"text\":\"w\"},\"after\":\"a\"}")))
                .isEqualTo("{\"from\":[1,2],\"stops\":\"n3\",\"whole\":{\"text\":\"w!\"},\"after\":\"a\"}");
        assertThat(result.generated("example.RouteJson")).contains("new example.PointSerializer()")
                .contains("net.benelog.silkjson.jsonb.JsonbCodecs.write(S_from, p_from, $out);")
                .contains("net.benelog.silkjson.jsonb.JsonbCodecs.read(D_from, $in, example.Point.class)");
    }

    @Test
    void aConverterThatDoesNotFitIsACompileError() {
        assertError("package example;" + SERIALIZERS + """
                @JsonBound public record Wrong(@JsonbTypeSerializer(Wrong.Out.class) long n) {
                    public static class Out implements JsonbSerializer<String> {
                        @Override public void serialize(String s, JsonGenerator generator, SerializationContext context) { }
                    }
                }
                """, "The serializer example.Wrong.Out serializes java.lang.String, not long");
        assertError("package example;" + SERIALIZERS + """
                @JsonBound public record Wrong(@JsonbTypeDeserializer(Wrong.In.class) String s) {
                    public static class In implements JsonbDeserializer<String> {
                        public In(int size) { }
                        @Override public String deserialize(JsonParser parser, DeserializationContext context, Type type) { return ""; }
                    }
                }
                """, "The deserializer example.Wrong.In needs a public no-argument constructor");
    }

    /** A creator parameter is matched to its property by its @JsonbProperty name, whatever its Java name. */
    @Test
    void aCreatorParameterIsNamedByItsJsonbProperty() throws Exception {
        Compilation.Result result = compile(Map.of("example.Item", "package example;" + IMPORTS + """
                @JsonBound
                public class Item {
                    private final String name;
                    private final int count;
                    @JsonbCreator
                    public Item(@JsonbProperty("name") String n, @JsonbProperty("qty") int c) { this.name = n; this.count = c; }
                    public String getName() { return name; }
                    @JsonbProperty("qty") public int getCount() { return count; }
                }
                """));
        JsonCodec<Object> item = codec(result, "example.ItemJson");

        assertThat(item.toJson(item.fromJson("{\"name\":\"x\",\"qty\":3}"))).isEqualTo("{\"name\":\"x\",\"qty\":3}");
        assertThatThrownBy(() -> item.fromJson("{\"qty\":3}")).hasMessage("Missing key in JSON object: name");
    }

    /** A record component or a creator parameter that says @Nullable reads a missing member as null, whatever its accessor says. */
    @Test
    void nullableOnAComponentOrACreatorParameterIsRead() throws Exception {
        Compilation.Result result = compile(Map.of(
                "example.Note", "package example;" + IMPORTS + """
                        @JsonBound
                        public record Note(String title, @Nullable String body) {
                            @Override public String body() { return body == null ? "" : body; }
                        }
                        """,
                "example.Memo", "package example;" + IMPORTS + """
                        @JsonBound
                        public class Memo {
                            private final @Nullable String text;
                            @JsonbCreator public Memo(@Nullable String text) { this.text = text; }
                            public String getText() { return text == null ? "" : text; }
                        }
                        """));
        assertThat(codec(result, "example.NoteJson").fromJson("{\"title\":\"t\"}").toString()).isEqualTo("Note[title=t, body=null]");
        JsonCodec<Object> memo = codec(result, "example.MemoJson");
        assertThat(memo.toJson(memo.fromJson("{}"))).isEqualTo("{\"text\":\"\"}");
    }

    /** An Instant with a date format is parsed through the formatter and written in UTC, and a format reaches into collections. */
    @Test
    void aDateFormatAppliesToInstantsAndToElements() throws Exception {
        Compilation.Result result = compile(Map.of("example.Log", "package example;" + IMPORTS + """
                @JsonBound
                public record Log(@JsonbDateFormat("yyyy-MM-dd HH:mm") Instant at,
                        @JsonbDateFormat("dd/MM/yyyy") List<LocalDate> days,
                        @JsonbDateFormat("dd/MM/yyyy") Map<String, LocalDate> byName,
                        @JsonbDateFormat("dd/MM/yyyy") Optional<LocalDate> maybe) {
                }
                """));
        JsonCodec<Object> log = codec(result, "example.LogJson");

        String document = "{\"at\":\"2026-10-09 12:30\",\"days\":[\"09/10/2026\"],\"byName\":{\"x\":\"10/10/2026\"},\"maybe\":\"11/10/2026\"}";
        Object read = log.fromJson(document);
        assertThat(read.toString()).contains("at=2026-10-09T12:30:00Z").contains("days=[2026-10-09]").contains("byName={x=2026-10-10}");
        assertThat(log.toJson(read)).isEqualTo(document);
    }

    /** A mixin on a record names the record's accessors as they are, and Boolean isActive() is a getter. */
    @Test
    void aRecordMixinTakesAccessorNamesAsTheyAreAndBooleanIsAGetter() throws Exception {
        Compilation.Result result = compile(Map.of(
                "example.User", "package example; public record User(boolean isAdmin, String getaway) { }",
                "example.UserWire", "package example;" + IMPORTS + """
                        @JsonBound(User.class)
                        interface UserWire {
                            @JsonbProperty("admin_flag") boolean isAdmin();
                            @JsonbProperty("escape") String getaway();
                        }
                        """,
                "example.Flag", "package example;" + IMPORTS + """
                        @JsonBound
                        public class Flag {
                            private @Nullable Boolean active;
                            public @Nullable Boolean isActive() { return active; }
                            public void setActive(@Nullable Boolean active) { this.active = active; }
                        }
                        """));
        JsonCodec<Object> user = codec(result, "example.UserJson");
        assertThat(user.toJson(user.fromJson("{\"admin_flag\":true,\"escape\":\"x\"}"))).isEqualTo("{\"admin_flag\":true,\"escape\":\"x\"}");
        JsonCodec<Object> flag = codec(result, "example.FlagJson");
        assertThat(flag.toJson(flag.fromJson("{\"active\":true}"))).isEqualTo("{\"active\":true}");
    }

    /** A primitive optional is an element like any other type. */
    @Test
    void primitiveOptionalsAreElements() throws Exception {
        Compilation.Result result = compile(Map.of("example.Scores", "package example;" + IMPORTS + """
                @JsonBound
                public record Scores(List<OptionalInt> ints, Map<String, OptionalLong> longs, List<OptionalDouble> doubles) {
                }
                """));
        JsonCodec<Object> scores = codec(result, "example.ScoresJson");

        String document = "{\"ints\":[1,null],\"longs\":{\"a\":2,\"b\":null},\"doubles\":[0.5]}";
        assertThat(scores.toJson(scores.fromJson(document))).isEqualTo(document);
    }

    /** What the generated code could not compile is a compile error on the bound type instead, and a crash is a diagnostic. */
    @Test
    void whatTheCodecCouldNotReachIsACompileErrorOnTheType() {
        assertError("package example;" + IMPORTS + "public class Outer { @JsonBound public class Inner { } }", "is an inner class");
        assertError("package example;" + IMPORTS + "public class Outer { @JsonBound private record Hidden(String a) { } }",
                "example.Outer.Hidden is private");
        assertError("package example;" + IMPORTS + """
                @JsonBound public class Closed {
                    private Closed() { }
                    public String getA() { return ""; }
                    public void setA(String a) { }
                }
                """, "is private, so the generated codec cannot call it");
        assertError("package example;" + IMPORTS + """
                @JsonBound public class Made {
                    private final String a;
                    @JsonbCreator private Made(String a) { this.a = a; }
                    public String getA() { return a; }
                }
                """, "A @JsonbCreator cannot be private");
        assertError("package example;" + IMPORTS + """
                @JsonBound public class Mixed {
                    public String getA() { return ""; }
                    public void setA(int a) { }
                }
                """, "The setter setA takes a int where the property a is a java.lang.String");
        assertError("package example;" + IMPORTS + "@JsonBound public record Wild(List<? extends Number> numbers) { }",
                "The wildcard ? extends java.lang.Number cannot be bound");
        assertError("package example;" + IMPORTS + "@SuppressWarnings(\"rawtypes\") @JsonBound public record Raw(List items) { }",
                "The raw type java.util.List cannot be bound");
        assertError("package example;" + IMPORTS + "@JsonBound public record Lost(Missing thing) { }", "was never resolved");
    }

    /** A type another processor generates in a later round defers the codec that refers to it until it exists. */
    @Test
    void aTypeGeneratedInALaterRoundDefersTheCodec() throws Exception {
        javax.annotation.processing.Processor generator = new javax.annotation.processing.AbstractProcessor() {
            private boolean done;

            @Override
            public java.util.Set<String> getSupportedAnnotationTypes() {
                return java.util.Set.of("*");
            }

            @Override
            public javax.lang.model.SourceVersion getSupportedSourceVersion() {
                return javax.lang.model.SourceVersion.latestSupported();
            }

            @Override
            public boolean process(java.util.Set<? extends javax.lang.model.element.TypeElement> annotations,
                    javax.annotation.processing.RoundEnvironment round) {
                if (!done) {
                    done = true;
                    try (java.io.Writer writer = processingEnv.getFiler().createSourceFile("example.Later").openWriter()) {
                        writer.write("package example; @net.benelog.silkjson.JsonBound public record Later(String v) { }");
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }
                return false;
            }
        };
        Compilation.Result result = Compilation.compile(Map.of("example.Early", "package example;" + IMPORTS
                + "@JsonBound public record Early(Later later, List<Later> all) { }"), java.util.List.of(generator));
        assertThat(result.errors()).isEmpty();
        JsonCodec<Object> early = codec(result, "example.EarlyJson");
        assertThat(early.toJson(early.fromJson("{\"later\":{\"v\":\"a\"},\"all\":[]}"))).isEqualTo("{\"later\":{\"v\":\"a\"},\"all\":[]}");
    }

    /** Two types whose codecs would share a name are a compile error naming both, not a failure to write the file. */
    @Test
    void twoTypesWithOneCodecNameAreACompileError() {
        Compilation.Result result = Compilation.compile(Map.of(
                "example.Outer", "package example;" + IMPORTS + "public class Outer { @JsonBound public record Inner(String a) { } }",
                "example.OuterInner", "package example;" + IMPORTS + "@JsonBound public record OuterInner(String b) { }"));
        assertThat(result.errors()).anySatisfy(e -> assertThat(e).contains("would be example.OuterInnerJson, the name the codec of"));
    }
}
