package benchmark.json;

import net.benelog.spidersilk.json.JsonBound;

@JsonBound
public record Item(long id, String name, String description, int quantity, boolean available) {
}
