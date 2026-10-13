package benchmark.json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.alibaba.fastjson2.JSON;
import net.benelog.silkjson.Json;
import net.benelog.silkjson.JsonCodec;
import net.benelog.silkjson.JsonOutput;
import net.benelog.silkjson.JsonReader;
import net.benelog.silkjson.JsonWriter;
import net.benelog.spidersilk.jackson.JacksonCodecs;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The same 100 records the HTTP benchmark's json-list case answers with, and
 * its 27-byte json case, written and read four ways: through the tree
 * ({@code Json.object()}), through a hand-written writer and reader, through
 * the codec generated from the record, and through Jackson and fastjson2.
 * 100 places, each with three numbers that have a fraction, measure the
 * numbers the records have none of. The same records with nothing to escape,
 * and with Korean strings, tell the paths a string can take apart. The same
 * records with their keys in reverse order measure a document another program
 * wrote, and records with a 200-character description measure long strings.
 * The same records as Python's {@code json.dumps} spaces them, indented, and
 * with ids of 13 and 18 digits, and with strings of no character or one,
 * measure the shapes a document takes outside this codec. 100 orders with
 * an enum each measure a value written from a fixed set of names. Writing the list to a stream from the thread's buffer measures
 * what a response body costs once the copy {@code toBytes()} makes is gone.
 * The list read through spider-silk-jackson, whole and with a Jackson reader
 * nested in silk-json's list reader, measures the adapter and the path a
 * nested reader takes.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(2)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class JsonBench {

    static final List<Item> ITEMS = items();
    static final List<Item> PLAIN_ITEMS = plainItems();
    static final List<Item> KOREAN_ITEMS = koreanItems();
    static final List<Item> LONG_ITEMS = longItems();
    static final List<Item> LONG_KOREAN_ITEMS = longKoreanItems();
    static final List<Item> SHORT_ITEMS = shortItems();
    static final List<Place> PLACES = places();
    static final List<Order> ORDERS = orders();
    static final Message MESSAGE = new Message("Hello, World!");

    /** The tree, built the way the manual's first example builds one. */
    static final JsonWriter<Item> ITEM_TREE = JsonWriter.tree(item -> Json.object()
            .put("id", item.id())
            .put("name", item.name())
            .put("description", item.description())
            .put("quantity", item.quantity())
            .put("available", item.available()));

    /** The explicit style: a writer that puts each member straight into the output. */
    static final JsonWriter<Item> ITEM_WRITER = (item, out) -> out.object()
            .put("id", item.id())
            .put("name", item.name())
            .put("description", item.description())
            .put("quantity", item.quantity())
            .put("available", item.available())
            .end();

    /** The hand-written reader, over the tree of one element at a time. */
    static final JsonReader<Item> ITEM_READER = JsonReader.object(object -> new Item(
            object.getLong("id"), object.getString("name"), object.getString("description"),
            (int) object.getLong("quantity"), object.getBoolean("available")));

    static final JsonWriter<List<Item>> ITEMS_TREE = JsonWriter.list(ITEM_TREE);
    static final JsonWriter<List<Item>> ITEMS_WRITER = JsonWriter.list(ITEM_WRITER);
    static final JsonReader<List<Item>> ITEMS_READER = JsonReader.list(ITEM_READER);

    /** The records with their keys in reverse order, as a program that is not this codec may write them. */
    static final JsonWriter<List<Item>> ITEMS_REVERSED = JsonWriter.list((item, out) -> out.object()
            .put("available", item.available())
            .put("quantity", item.quantity())
            .put("description", item.description())
            .put("name", item.name())
            .put("id", item.id())
            .end());

    static final JsonCodec<List<Item>> ITEMS_GENERATED = JsonCodec.list(ItemJson.CODEC);
    static final JsonCodec<List<Place>> PLACES_GENERATED = JsonCodec.list(PlaceJson.CODEC);
    static final JsonCodec<List<Order>> ORDERS_GENERATED = JsonCodec.list(OrderJson.CODEC);

    /** Jackson behind silk-json's reader, with the defaults the {@code jackson} field has. */
    static final JacksonCodecs JACKSON = new JacksonCodecs(JsonMapper.builder().build());

    /** A Jackson reader for one record, nested in silk-json's list reader: the path a reader inside another takes. */
    static final JsonReader<List<Item>> ITEMS_JACKSON_NESTED = JsonReader.list(JACKSON.reader(Item.class));

    /** The whole list handed to Jackson through the adapter, with no reader around it. */
    static final JsonReader<List<Item>> ITEMS_JACKSON_CODECS = JACKSON.reader(new TypeReference<List<Item>>() { });

    final JsonMapper jackson = JsonMapper.builder().build();
    final TypeReference<List<Item>> listType = new TypeReference<>() { };
    final TypeReference<List<Place>> placesType = new TypeReference<>() { };

    byte[] listBytes;
    byte[] placesBytes;

    @Setup
    public void setup() {
        listBytes = ITEMS_GENERATED.toJsonBytes(ITEMS);
        placesBytes = PLACES_GENERATED.toJsonBytes(PLACES);
        if (!ITEMS_JACKSON_NESTED.fromJsonBytes(listBytes).equals(ITEMS)
                || !ITEMS_JACKSON_CODECS.fromJsonBytes(listBytes).equals(ITEMS)) {
            throw new IllegalStateException("A Jackson reader read the list as other records");
        }
    }

    /**
     * A document only the cases that read it set up, so that writing it does
     * not train the compiler on strings the other cases never see.
     */
    @State(Scope.Thread)
    public static class Document {

        byte[] korean;
        byte[] reversed;
        byte[] longStrings;
        byte[] python;
        byte[] pretty;
        byte[] shortStrings;
        byte[] orders;

        @Setup
        public void setup(BenchmarkParams params) {
            String benchmark = params.getBenchmark();
            if (benchmark.contains("Korean")) {
                korean = checked(ITEMS_GENERATED.toJsonBytes(KOREAN_ITEMS), KOREAN_ITEMS);
            } else if (benchmark.contains("Reversed")) {
                reversed = checked(ITEMS_REVERSED.toJsonBytes(ITEMS), ITEMS);
            } else if (benchmark.contains("LongString")) {
                longStrings = checked(ITEMS_GENERATED.toJsonBytes(LONG_ITEMS), LONG_ITEMS);
            } else if (benchmark.contains("Python")) {
                python = checked(spaced(ITEMS_GENERATED.toJsonBytes(ITEMS), false), ITEMS);
            } else if (benchmark.contains("Pretty")) {
                pretty = checked(spaced(ITEMS_GENERATED.toJsonBytes(ITEMS), true), ITEMS);
            } else if (benchmark.contains("readOrders")) {
                orders = ORDERS_GENERATED.toJsonBytes(ORDERS);
                if (!ORDERS_GENERATED.fromJsonBytes(orders).equals(ORDERS)
                        || !JSON.parseArray(orders, Order.class).equals(ORDERS)) {
                    throw new IllegalStateException("A parser read the orders as others");
                }
            } else if (benchmark.contains("ShortString")) {
                shortStrings = checked(ITEMS_GENERATED.toJsonBytes(SHORT_ITEMS), SHORT_ITEMS);
            }
        }

        /** The document, once both parsers have read it back as the records it was written from. */
        private static byte[] checked(byte[] document, List<Item> expected) {
            if (!ITEMS_GENERATED.fromJsonBytes(document).equals(expected)
                    || !JSON.parseArray(document, Item.class).equals(expected)) {
                throw new IllegalStateException("A parser read the document as other records");
            }
            return document;
        }
    }

    /**
     * The records with ids of 13 digits, a time in milliseconds, or of 18,
     * an id a generator of unique ids hands out: numbers past the seven
     * digits a word is read in.
     */
    @State(Scope.Thread)
    public static class BigIds {

        @Param({"13", "18"})
        int digits;

        byte[] document;

        @Setup
        public void setup() {
            long base = digits == 13 ? 1_760_000_000_000L : 400_000_000_000_000_000L;
            List<Item> items = new ArrayList<>();
            for (Item item : ITEMS) {
                items.add(new Item(base + item.id() * 7_919, item.name(), item.description(), item.quantity(),
                        item.available()));
            }
            document = Document.checked(ITEMS_GENERATED.toJsonBytes(items), items);
        }
    }

    /**
     * A compact document spaced out: with Python's {@code json.dumps}
     * separators, {@code ", "} and {@code ": "}, or indented by two spaces a
     * level the way a pretty printer writes it.
     */
    static byte[] spaced(byte[] compact, boolean indent) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(compact.length * 2);
        boolean inString = false;
        int depth = 0;
        for (int i = 0; i < compact.length; i++) {
            byte b = compact[i];
            if (inString) {
                out.write(b);
                if (b == '\\') {
                    out.write(compact[++i]);
                } else if (b == '"') {
                    inString = false;
                }
                continue;
            }
            switch (b) {
                case '"' -> {
                    inString = true;
                    out.write(b);
                }
                case ',' -> {
                    out.write(b);
                    if (indent) {
                        lineBreak(out, depth);
                    } else {
                        out.write(' ');
                    }
                }
                case ':' -> {
                    out.write(b);
                    out.write(' ');
                }
                case '{', '[' -> {
                    out.write(b);
                    if (indent) {
                        lineBreak(out, ++depth);
                    }
                }
                case '}', ']' -> {
                    if (indent) {
                        lineBreak(out, --depth);
                    }
                    out.write(b);
                }
                default -> out.write(b);
            }
        }
        return out.toByteArray();
    }

    private static void lineBreak(ByteArrayOutputStream out, int depth) {
        out.write('\n');
        for (int i = 0; i < depth * 2; i++) {
            out.write(' ');
        }
    }

    private static List<Item> items() {
        List<Item> items = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            items.add(new Item(i, "item-" + i, "The \"description\" of item " + i, i * 7 % 50, i % 3 != 0));
        }
        return List.copyOf(items);
    }

    /** The same records with nothing to escape in their strings. */
    private static List<Item> plainItems() {
        List<Item> items = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            items.add(new Item(i, "item-" + i, "The description of item " + i, i * 7 % 50, i % 3 != 0));
        }
        return List.copyOf(items);
    }

    /** The same records with Korean strings, which are written as three bytes a character. */
    private static List<Item> koreanItems() {
        List<Item> items = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            items.add(new Item(i, "품목-" + i, "품목 " + i + "의 설명", i * 7 % 50, i % 3 != 0));
        }
        return List.copyOf(items);
    }

    /** The same records with a description of 200 characters, prose with nothing to escape. */
    private static List<Item> longItems() {
        String prose = "A long description of the item, written the way a product page or an issue tracker "
                + "writes one: sentences of plain text with numbers, commas, and parentheses (but no quotes) ";
        List<Item> items = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            String description = (i + ". " + prose + prose).substring(0, 200);
            items.add(new Item(i, "item-" + i, description, i * 7 % 50, i % 3 != 0));
        }
        return List.copyOf(items);
    }

    /** The same records with a description of 200 characters in Korean. */
    private static List<Item> longKoreanItems() {
        String prose = "상품 페이지나 이슈 트래커가 쓰는 식으로 쓴 품목의 긴 설명이다: 숫자와 쉼표, 괄호(따옴표는 없다)가 섞인 평범한 문장들. ";
        List<Item> items = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            String description = (i + ". " + prose.repeat(4)).substring(0, 200);
            items.add(new Item(i, "item-" + i, description, i * 7 % 50, i % 3 != 0));
        }
        return List.copyOf(items);
    }

    /** The same records with strings of no character or one: a code, a flag, a field left empty. */
    private static List<Item> shortItems() {
        List<Item> items = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            items.add(new Item(i, String.valueOf((char) ('A' + i % 26)), i % 2 == 0 ? "" : "x", i * 7 % 50, i % 3 != 0));
        }
        return List.copyOf(items);
    }

    /** Orders whose status is an enum, written as its constant's name. */
    private static List<Order> orders() {
        Order.Status[] statuses = Order.Status.values();
        List<Order> orders = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            orders.add(new Order(i, "item-" + i, statuses[i * 7 % statuses.length], i * 7 % 50));
        }
        return List.copyOf(orders);
    }

    /** Coordinates with four and six decimals and a rating with one, each the shortest form of its double. */
    private static List<Place> places() {
        List<Place> places = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            places.add(new Place(i, "place-" + i, (375665 + i * 137) / 10_000.0, (126978000 + i * 4321) / 1_000_000.0,
                    (30 + i % 21) / 10.0));
        }
        return List.copyOf(places);
    }

    // ---- the list of 100 records, 10 KB ----

    @Benchmark
    public byte[] writeList_tree() {
        return ITEMS_TREE.toJsonBytes(ITEMS);
    }

    @Benchmark
    public byte[] writeList_writer() {
        return ITEMS_WRITER.toJsonBytes(ITEMS);
    }

    @Benchmark
    public byte[] writeList_generated() {
        return ITEMS_GENERATED.toJsonBytes(ITEMS);
    }

    @Benchmark
    public byte[] writeList_jackson() {
        return jackson.writeValueAsBytes(ITEMS);
    }

    @Benchmark
    public byte[] writeList_fastjson2() {
        return JSON.toJSONBytes(ITEMS);
    }

    /** fastjson2 with its classes made by reflection rather than ASM: the mode a native image takes. */
    @Benchmark
    @Fork(jvmArgsAppend = "-Dfastjson2.creator=reflect")
    public byte[] writeList_fastjson2Reflect() {
        return JSON.toJSONBytes(ITEMS);
    }

    // ---- the same list sent to a stream, as a response body is ----

    /** The document written into the thread's buffer and sent from it, with no copy: a JSON response body. */
    @Benchmark
    public void writeList_stream() throws IOException {
        JsonOutput out = JsonOutput.inMemory();
        try {
            ITEMS_GENERATED.write(ITEMS, out);
            out.writeTo(OutputStream.nullOutputStream());
        } finally {
            out.release();
        }
    }

    @Benchmark
    public void writeList_streamFastjson2() {
        JSON.writeTo(OutputStream.nullOutputStream(), ITEMS);
    }

    // ---- the same records with nothing to escape, and in Korean ----

    @Benchmark
    public byte[] writePlainList_generated() {
        return ITEMS_GENERATED.toJsonBytes(PLAIN_ITEMS);
    }

    @Benchmark
    public byte[] writePlainList_fastjson2() {
        return JSON.toJSONBytes(PLAIN_ITEMS);
    }

    @Benchmark
    public byte[] writeKoreanList_generated() {
        return ITEMS_GENERATED.toJsonBytes(KOREAN_ITEMS);
    }

    @Benchmark
    public byte[] writeKoreanList_fastjson2() {
        return JSON.toJSONBytes(KOREAN_ITEMS);
    }

    @Benchmark
    public List<Item> readKoreanList_generated(Document document) {
        return ITEMS_GENERATED.fromJsonBytes(document.korean);
    }

    @Benchmark
    public List<Item> readKoreanList_fastjson2(Document document) {
        return JSON.parseArray(document.korean, Item.class);
    }

    @Benchmark
    public List<Item> readReversedList_generated(Document document) {
        return ITEMS_GENERATED.fromJsonBytes(document.reversed);
    }

    @Benchmark
    public List<Item> readReversedList_fastjson2(Document document) {
        return JSON.parseArray(document.reversed, Item.class);
    }

    // ---- the same records with a description of 200 characters, 27 KB ----

    @Benchmark
    public byte[] writeLongStringList_generated() {
        return ITEMS_GENERATED.toJsonBytes(LONG_ITEMS);
    }

    @Benchmark
    public byte[] writeLongStringList_fastjson2() {
        return JSON.toJSONBytes(LONG_ITEMS);
    }

    @Benchmark
    public byte[] writeLongKoreanList_generated() {
        return ITEMS_GENERATED.toJsonBytes(LONG_KOREAN_ITEMS);
    }

    @Benchmark
    public byte[] writeLongKoreanList_fastjson2() {
        return JSON.toJSONBytes(LONG_KOREAN_ITEMS);
    }

    @Benchmark
    public List<Item> readLongStringList_generated(Document document) {
        return ITEMS_GENERATED.fromJsonBytes(document.longStrings);
    }

    @Benchmark
    public List<Item> readLongStringList_fastjson2(Document document) {
        return JSON.parseArray(document.longStrings, Item.class);
    }

    @Benchmark
    public List<Item> readList_tree() {
        return ITEMS_READER.fromJsonBytes(listBytes);
    }

    @Benchmark
    public List<Item> readList_generated() {
        return ITEMS_GENERATED.fromJsonBytes(listBytes);
    }

    @Benchmark
    public List<Item> readList_jackson() {
        return jackson.readValue(listBytes, listType);
    }

    /** Jackson through spider-silk-jackson, a record at a time inside silk-json's list reader. */
    @Benchmark
    public List<Item> readList_jacksonNested() {
        return ITEMS_JACKSON_NESTED.fromJsonBytes(listBytes);
    }

    /** Jackson through spider-silk-jackson, the whole list at once: what the adapter adds to {@code readList_jackson}. */
    @Benchmark
    public List<Item> readList_jacksonCodecs() {
        return ITEMS_JACKSON_CODECS.fromJsonBytes(listBytes);
    }

    @Benchmark
    public List<Item> readList_fastjson2() {
        return JSON.parseArray(listBytes, Item.class);
    }

    @Benchmark
    @Fork(jvmArgsAppend = "-Dfastjson2.creator=reflect")
    public List<Item> readList_fastjson2Reflect() {
        return JSON.parseArray(listBytes, Item.class);
    }

    // ---- the same records spaced out, and with large ids ----

    @Benchmark
    public List<Item> readPythonList_generated(Document document) {
        return ITEMS_GENERATED.fromJsonBytes(document.python);
    }

    @Benchmark
    public List<Item> readPythonList_fastjson2(Document document) {
        return JSON.parseArray(document.python, Item.class);
    }

    @Benchmark
    public List<Item> readPrettyList_generated(Document document) {
        return ITEMS_GENERATED.fromJsonBytes(document.pretty);
    }

    @Benchmark
    public List<Item> readPrettyList_fastjson2(Document document) {
        return JSON.parseArray(document.pretty, Item.class);
    }

    @Benchmark
    public List<Item> readShortStringList_generated(Document document) {
        return ITEMS_GENERATED.fromJsonBytes(document.shortStrings);
    }

    @Benchmark
    public List<Item> readShortStringList_fastjson2(Document document) {
        return JSON.parseArray(document.shortStrings, Item.class);
    }

    @Benchmark
    public List<Item> readBigIdList_generated(BigIds ids) {
        return ITEMS_GENERATED.fromJsonBytes(ids.document);
    }

    @Benchmark
    public List<Item> readBigIdList_fastjson2(BigIds ids) {
        return JSON.parseArray(ids.document, Item.class);
    }

    // ---- the list of 100 places, three decimals each ----

    @Benchmark
    public byte[] writePlaces_generated() {
        return PLACES_GENERATED.toJsonBytes(PLACES);
    }

    @Benchmark
    public byte[] writePlaces_jackson() {
        return jackson.writeValueAsBytes(PLACES);
    }

    @Benchmark
    public byte[] writePlaces_fastjson2() {
        return JSON.toJSONBytes(PLACES);
    }

    @Benchmark
    public List<Place> readPlaces_generated() {
        return PLACES_GENERATED.fromJsonBytes(placesBytes);
    }

    @Benchmark
    public List<Place> readPlaces_jackson() {
        return jackson.readValue(placesBytes, placesType);
    }

    @Benchmark
    public List<Place> readPlaces_fastjson2() {
        return JSON.parseArray(placesBytes, Place.class);
    }

    // ---- the list of 100 orders, an enum each ----

    @Benchmark
    public byte[] writeOrders_generated() {
        return ORDERS_GENERATED.toJsonBytes(ORDERS);
    }

    @Benchmark
    public byte[] writeOrders_fastjson2() {
        return JSON.toJSONBytes(ORDERS);
    }

    @Benchmark
    public List<Order> readOrders_generated(Document document) {
        return ORDERS_GENERATED.fromJsonBytes(document.orders);
    }

    @Benchmark
    public List<Order> readOrders_fastjson2(Document document) {
        return JSON.parseArray(document.orders, Order.class);
    }

    // ---- one small object, 27 bytes ----

    @Benchmark
    public byte[] writeMessage_generated() {
        return MessageJson.CODEC.toJsonBytes(MESSAGE);
    }

    @Benchmark
    public byte[] writeMessage_jackson() {
        return jackson.writeValueAsBytes(MESSAGE);
    }

    @Benchmark
    public byte[] writeMessage_fastjson2() {
        return JSON.toJSONBytes(MESSAGE);
    }

    /** The documents the read cases parse, for a look at what is measured. */
    public static void main(String[] args) {
        System.out.println(new String(ITEMS_GENERATED.toJsonBytes(ITEMS), StandardCharsets.UTF_8));
        System.out.println(new String(spaced(ITEMS_GENERATED.toJsonBytes(ITEMS.subList(0, 2)), false), StandardCharsets.UTF_8));
        System.out.println(new String(spaced(ITEMS_GENERATED.toJsonBytes(ITEMS.subList(0, 2)), true), StandardCharsets.UTF_8));
        System.out.println(new String(PLACES_GENERATED.toJsonBytes(PLACES), StandardCharsets.UTF_8));
    }
}
