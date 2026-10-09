package benchmark.json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.alibaba.fastjson2.JSON;
import net.benelog.spidersilk.json.Json;
import net.benelog.spidersilk.json.JsonCodec;
import net.benelog.spidersilk.json.JsonReader;
import net.benelog.spidersilk.json.JsonWriter;
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
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(2)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class JsonBench {

    static final List<Item> ITEMS = items();
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

    final JsonMapper jackson = JsonMapper.builder().build();
    final TypeReference<List<Item>> listType = new TypeReference<>() { };

    byte[] listBytes;

    @Setup
    public void setup() {
        listBytes = ITEMS_GENERATED.toJsonBytes(ITEMS);
    }

    private static List<Item> items() {
        List<Item> items = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            items.add(new Item(i, "item-" + i, "The \"description\" of item " + i, i * 7 % 50, i % 3 != 0));
        }
        return List.copyOf(items);
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

    /** The document the read cases parse, for a look at what is measured. */
    public static void main(String[] args) {
        System.out.println(new String(ITEMS_GENERATED.toJsonBytes(ITEMS), StandardCharsets.UTF_8));
    }
}
