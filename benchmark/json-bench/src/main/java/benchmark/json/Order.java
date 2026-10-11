package benchmark.json;

import net.benelog.silkjson.JsonBound;

@JsonBound
public record Order(long id, String item, Status status, int quantity) {

    public enum Status { PENDING, PAID, SHIPPED, DELIVERED, CANCELLED }
}
