package benchmark.springmvc;

public record Item(long id, String name, String description, int quantity, boolean available) {
}
