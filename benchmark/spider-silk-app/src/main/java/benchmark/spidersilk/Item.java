package benchmark.spidersilk;

import net.benelog.silkjson.JsonBound;

/** The codec, {@code ItemJson.CODEC}, is generated from the record at compile time. */
@JsonBound
public record Item(long id, String name, String description, int quantity, boolean available) {
}
