package benchmark.json;

import net.benelog.silkjson.JsonBound;

@JsonBound
public record Place(long id, String name, double latitude, double longitude, double rating) {
}
