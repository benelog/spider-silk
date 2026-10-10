package benchmark.json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.alibaba.fastjson2.JSON;
import net.benelog.silkjson.Json;
import net.benelog.silkjson.JsonCodec;
import net.benelog.silkjson.JsonReader;
import net.benelog.silkjson.JsonWriter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The same 100 records the HTTP benchmark's json-list case answers with, and
 * its 27-byte json case, written and read four ways: through the tree
 * ({@code Json.object()}), through a hand-written writer and reader, through
 * the codec generated from the record, and through Jackson and fastjson2.
 * 100 places, each with three numbers that have a fraction, measure the
 * numbers the records have none of. The same records with nothing to escape,
 * and with Korean strings, tell the paths a string can take apart.
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
    static final List<Place> PLACES = places();
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
    static final JsonCodec<List<Item>> ITEMS_GENERATED = JsonCodec.list(ItemJson.CODEC);
    static final JsonCodec<List<Place>> PLACES_GENERATED = JsonCodec.list(PlaceJson.CODEC);

    final JsonMapper jackson = JsonMapper.builder().build();
    final TypeReference<List<Item>> listType = new TypeReference<>() { };
    final TypeReference<List<Place>> placesType = new TypeReference<>() { };

    byte[] listBytes;
    byte[] placesBytes;

    @Setup
    public void setup() {
        listBytes = ITEMS_GENERATED.toJsonBytes(ITEMS);
        placesBytes = PLACES_GENERATED.toJsonBytes(PLACES);
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

    @Benchmark
    public List<Item> readList_fastjson2() {
        return JSON.parseArray(listBytes, Item.class);
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
        System.out.println(new String(PLACES_GENERATED.toJsonBytes(PLACES), StandardCharsets.UTF_8));
    }
}
