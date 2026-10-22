package benchmark.json;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonWriter;
import jakarta.json.spi.JsonProvider;
import jakarta.json.stream.JsonGenerator;
import jakarta.json.stream.JsonParser;
import net.benelog.silkjson.JsonCodec;
import net.benelog.silkjson.jsonp.JsonpCodecs;
import net.benelog.silkjson.jsonp.SilkJsonProvider;
import org.eclipse.parsson.JsonProviderImpl;
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

/**
 * Jakarta JSON Processing on silk-json against Parsson, its reference
 * implementation, on the 100 records of {@link JsonBench} and its 100 places
 * of decimals. Each provider is made with its constructor, so no case looks a
 * provider up, and each reads, walks as events, writes, generates, and builds
 * the same document. Both are on the class path together, which only
 * {@code jakarta.json.Json} would have to choose between, and no case calls it.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(2)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class JsonpBench {

    static final JsonProvider SILK = new SilkJsonProvider();
    static final JsonProvider PARSSON = new JsonProviderImpl();

    static final JsonCodec<List<Item>> ITEMS = JsonCodec.list(ItemJson.CODEC);
    static final JsonCodec<List<Place>> PLACES = JsonCodec.list(PlaceJson.CODEC);

    byte[] listBytes;
    byte[] placesBytes;
    JsonArray silkTree;
    JsonArray parssonTree;
    final ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);

    @Setup
    public void setup() {
        listBytes = ITEMS.toJsonBytes(JsonBench.ITEMS);
        placesBytes = PLACES.toJsonBytes(JsonBench.PLACES);
        silkTree = SILK.createReader(new ByteArrayInputStream(listBytes)).readArray();
        parssonTree = PARSSON.createReader(new ByteArrayInputStream(listBytes)).readArray();
        if (!silkTree.equals(parssonTree)
                || !read(SILK, placesBytes).equals(read(PARSSON, placesBytes))
                || !JsonpCodecs.ARRAY.fromJsonBytes(listBytes).equals(parssonTree)) {
            throw new IllegalStateException("The providers read the document as different trees");
        }
        byte[] silkText = write(SILK, silkTree);
        if (!Arrays.equals(silkText, write(PARSSON, parssonTree)) || !Arrays.equals(silkText, listBytes)
                || !Arrays.equals(generate(SILK), generate(PARSSON)) || !build(SILK).equals(build(PARSSON))) {
            throw new IllegalStateException("The providers wrote the document as different text");
        }
    }

    private static JsonArray read(JsonProvider provider, byte[] document) {
        return provider.createReader(new ByteArrayInputStream(document)).readArray();
    }

    private byte[] write(JsonProvider provider, JsonArray tree) {
        out.reset();
        try (JsonWriter writer = provider.createWriter(out)) {
            writer.write(tree);
        }
        return out.toByteArray();
    }

    private byte[] generate(JsonProvider provider) {
        out.reset();
        try (JsonGenerator generator = provider.createGenerator(out)) {
            generator.writeStartArray();
            for (Item item : JsonBench.ITEMS) {
                generator.writeStartObject()
                        .write("id", item.id())
                        .write("name", item.name())
                        .write("description", item.description())
                        .write("quantity", item.quantity())
                        .write("available", item.available())
                        .writeEnd();
            }
            generator.writeEnd();
        }
        return out.toByteArray();
    }

    private static JsonArray build(JsonProvider provider) {
        JsonArrayBuilder array = provider.createArrayBuilder();
        for (Item item : JsonBench.ITEMS) {
            array.add(provider.createObjectBuilder()
                    .add("id", item.id())
                    .add("name", item.name())
                    .add("description", item.description())
                    .add("quantity", item.quantity())
                    .add("available", item.available()));
        }
        return array.build();
    }

    /** Every event of the document, with each key, string, and number read as a parser's user reads it. */
    private static long events(JsonProvider provider, byte[] document) {
        long seen = 0;
        try (JsonParser parser = provider.createParser(new ByteArrayInputStream(document))) {
            while (parser.hasNext()) {
                switch (parser.next()) {
                    case KEY_NAME, VALUE_STRING -> seen += parser.getString().length();
                    case VALUE_NUMBER -> seen += parser.isIntegralNumber() ? parser.getLong() : parser.getBigDecimal().scale();
                    default -> seen++;
                }
            }
        }
        return seen;
    }

    // ---- reading the list of 100 records, 10 KB, as a tree ----

    @Benchmark
    public JsonArray readTree_silk() {
        return read(SILK, listBytes);
    }

    @Benchmark
    public JsonArray readTree_parsson() {
        return read(PARSSON, listBytes);
    }

    /** The same tree through silk-json's reader, the way a Spider Silk handler takes a body. */
    @Benchmark
    public JsonArray readTree_codecs() {
        return JsonpCodecs.ARRAY.fromJsonBytes(listBytes);
    }

    // ---- reading 100 places, three decimals each, as a tree ----

    @Benchmark
    public JsonArray readPlaces_silk() {
        return read(SILK, placesBytes);
    }

    @Benchmark
    public JsonArray readPlaces_parsson() {
        return read(PARSSON, placesBytes);
    }

    // ---- walking the list as events ----

    @Benchmark
    public long parseEvents_silk() {
        return events(SILK, listBytes);
    }

    @Benchmark
    public long parseEvents_parsson() {
        return events(PARSSON, listBytes);
    }

    // ---- writing the tree read from the list ----

    @Benchmark
    public byte[] writeTree_silk() {
        return write(SILK, silkTree);
    }

    @Benchmark
    public byte[] writeTree_parsson() {
        return write(PARSSON, parssonTree);
    }

    /** The same tree through silk-json's writer, the way a Spider Silk handler answers with one. */
    @Benchmark
    public byte[] writeTree_codecs() {
        return JsonpCodecs.WRITER.toJsonBytes(silkTree);
    }

    // ---- generating the list from the records ----

    @Benchmark
    public byte[] generate_silk() {
        return generate(SILK);
    }

    @Benchmark
    public byte[] generate_parsson() {
        return generate(PARSSON);
    }

    // ---- building the tree of the list from the records ----

    @Benchmark
    public JsonArray build_silk() {
        return build(SILK);
    }

    @Benchmark
    public JsonArray build_parsson() {
        return build(PARSSON);
    }
}
