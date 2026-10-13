package benchmark.springboot;

public record Item(long id, String name, String description, int quantity, boolean available) {
}
