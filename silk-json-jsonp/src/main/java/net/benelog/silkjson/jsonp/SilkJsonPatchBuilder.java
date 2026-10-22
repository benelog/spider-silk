package net.benelog.silkjson.jsonp;

import java.util.Objects;

import jakarta.json.JsonArray;
import jakarta.json.JsonConfig.KeyStrategy;
import jakarta.json.JsonPatch;
import jakarta.json.JsonPatch.Operation;
import jakarta.json.JsonPatchBuilder;
import jakarta.json.JsonValue;

/** Builds a JSON Patch an operation at a time, each an object of {@code op}, {@code path}, and {@code value} or {@code from}. */
final class SilkJsonPatchBuilder implements JsonPatchBuilder {

    private final SilkJsonArrayBuilder operations;

    SilkJsonPatchBuilder() {
        this.operations = new SilkJsonArrayBuilder();
    }

    /** Starts with the operations of a patch's array. */
    SilkJsonPatchBuilder(JsonArray operations) {
        this.operations = new SilkJsonArrayBuilder(operations);
    }

    @Override
    public JsonPatchBuilder add(String path, JsonValue value) {
        return withValue(Operation.ADD, path, Objects.requireNonNull(value, "value"));
    }

    @Override
    public JsonPatchBuilder add(String path, String value) {
        return withValue(Operation.ADD, path, new SilkJsonString(Objects.requireNonNull(value, "value")));
    }

    @Override
    public JsonPatchBuilder add(String path, int value) {
        return withValue(Operation.ADD, path, SilkJsonNumber.of(value));
    }

    @Override
    public JsonPatchBuilder add(String path, boolean value) {
        return withValue(Operation.ADD, path, Values.of(value));
    }

    @Override
    public JsonPatchBuilder remove(String path) {
        operations.add(operation(Operation.REMOVE, path).build());
        return this;
    }

    @Override
    public JsonPatchBuilder replace(String path, JsonValue value) {
        return withValue(Operation.REPLACE, path, Objects.requireNonNull(value, "value"));
    }

    @Override
    public JsonPatchBuilder replace(String path, String value) {
        return withValue(Operation.REPLACE, path, new SilkJsonString(Objects.requireNonNull(value, "value")));
    }

    @Override
    public JsonPatchBuilder replace(String path, int value) {
        return withValue(Operation.REPLACE, path, SilkJsonNumber.of(value));
    }

    @Override
    public JsonPatchBuilder replace(String path, boolean value) {
        return withValue(Operation.REPLACE, path, Values.of(value));
    }

    @Override
    public JsonPatchBuilder move(String path, String from) {
        return withFrom(Operation.MOVE, path, from);
    }

    @Override
    public JsonPatchBuilder copy(String path, String from) {
        return withFrom(Operation.COPY, path, from);
    }

    @Override
    public JsonPatchBuilder test(String path, JsonValue value) {
        return withValue(Operation.TEST, path, Objects.requireNonNull(value, "value"));
    }

    @Override
    public JsonPatchBuilder test(String path, String value) {
        return withValue(Operation.TEST, path, new SilkJsonString(Objects.requireNonNull(value, "value")));
    }

    @Override
    public JsonPatchBuilder test(String path, int value) {
        return withValue(Operation.TEST, path, SilkJsonNumber.of(value));
    }

    @Override
    public JsonPatchBuilder test(String path, boolean value) {
        return withValue(Operation.TEST, path, Values.of(value));
    }

    /** The patch of the operations added so far; the builder starts again empty. */
    @Override
    public JsonPatch build() {
        return new SilkJsonPatch(operations());
    }

    JsonArray operations() {
        return operations.build();
    }

    private JsonPatchBuilder withValue(Operation op, String path, JsonValue value) {
        operations.add(operation(op, path).add("value", value).build());
        return this;
    }

    private JsonPatchBuilder withFrom(Operation op, String path, String from) {
        operations.add(operation(op, path).add("from", Objects.requireNonNull(from, "from")).build());
        return this;
    }

    private static SilkJsonObjectBuilder operation(Operation op, String path) {
        SilkJsonObjectBuilder operation = new SilkJsonObjectBuilder(new Members(KeyStrategy.LAST));
        operation.add("op", op.operationName()).add("path", Objects.requireNonNull(path, "path"));
        return operation;
    }
}
