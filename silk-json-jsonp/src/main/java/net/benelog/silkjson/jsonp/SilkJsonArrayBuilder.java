package net.benelog.silkjson.jsonp;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

/**
 * Builds an immutable array, adding, inserting, replacing, and removing
 * elements by index as a list does. {@link #build()} hands the elements over
 * and starts this builder again empty.
 */
final class SilkJsonArrayBuilder implements JsonArrayBuilder {

    private List<JsonValue> values;

    SilkJsonArrayBuilder() {
        this.values = new ArrayList<>();
    }

    /** Starts with the elements of an array, of this module's or any other. */
    SilkJsonArrayBuilder(JsonArray array) {
        this.values = new ArrayList<>(array);
    }

    @Override
    public JsonArrayBuilder add(JsonValue value) {
        values.add(Objects.requireNonNull(value, "value"));
        return this;
    }

    @Override
    public JsonArrayBuilder add(String value) {
        return add(string(value));
    }

    @Override
    public JsonArrayBuilder add(BigDecimal value) {
        return add(SilkJsonNumber.of(Objects.requireNonNull(value, "value")));
    }

    @Override
    public JsonArrayBuilder add(BigInteger value) {
        return add(SilkJsonNumber.of(Objects.requireNonNull(value, "value")));
    }

    @Override
    public JsonArrayBuilder add(int value) {
        return add(SilkJsonNumber.of(value));
    }

    @Override
    public JsonArrayBuilder add(long value) {
        return add(SilkJsonNumber.of(value));
    }

    /** Throws {@link NumberFormatException} for NaN and for an infinity. */
    @Override
    public JsonArrayBuilder add(double value) {
        return add(SilkJsonNumber.of(value));
    }

    @Override
    public JsonArrayBuilder add(boolean value) {
        return add(Values.of(value));
    }

    @Override
    public JsonArrayBuilder addNull() {
        return add(JsonValue.NULL);
    }

    @Override
    public JsonArrayBuilder add(JsonObjectBuilder builder) {
        return add(Objects.requireNonNull(builder, "builder").build());
    }

    @Override
    public JsonArrayBuilder add(JsonArrayBuilder builder) {
        return add(Objects.requireNonNull(builder, "builder").build());
    }

    /** Adds the elements the other builder holds, which that builder's {@code build()} hands over. */
    @Override
    public JsonArrayBuilder addAll(JsonArrayBuilder builder) {
        values.addAll(Objects.requireNonNull(builder, "builder").build());
        return this;
    }

    @Override
    public JsonArrayBuilder add(int index, JsonValue value) {
        values.add(index, Objects.requireNonNull(value, "value"));
        return this;
    }

    @Override
    public JsonArrayBuilder add(int index, String value) {
        return add(index, string(value));
    }

    @Override
    public JsonArrayBuilder add(int index, BigDecimal value) {
        return add(index, SilkJsonNumber.of(Objects.requireNonNull(value, "value")));
    }

    @Override
    public JsonArrayBuilder add(int index, BigInteger value) {
        return add(index, SilkJsonNumber.of(Objects.requireNonNull(value, "value")));
    }

    @Override
    public JsonArrayBuilder add(int index, int value) {
        return add(index, SilkJsonNumber.of(value));
    }

    @Override
    public JsonArrayBuilder add(int index, long value) {
        return add(index, SilkJsonNumber.of(value));
    }

    @Override
    public JsonArrayBuilder add(int index, double value) {
        return add(index, SilkJsonNumber.of(value));
    }

    @Override
    public JsonArrayBuilder add(int index, boolean value) {
        return add(index, Values.of(value));
    }

    @Override
    public JsonArrayBuilder addNull(int index) {
        return add(index, JsonValue.NULL);
    }

    @Override
    public JsonArrayBuilder add(int index, JsonObjectBuilder builder) {
        return add(index, Objects.requireNonNull(builder, "builder").build());
    }

    @Override
    public JsonArrayBuilder add(int index, JsonArrayBuilder builder) {
        return add(index, Objects.requireNonNull(builder, "builder").build());
    }

    @Override
    public JsonArrayBuilder set(int index, JsonValue value) {
        values.set(index, Objects.requireNonNull(value, "value"));
        return this;
    }

    @Override
    public JsonArrayBuilder set(int index, String value) {
        return set(index, string(value));
    }

    @Override
    public JsonArrayBuilder set(int index, BigDecimal value) {
        return set(index, SilkJsonNumber.of(Objects.requireNonNull(value, "value")));
    }

    @Override
    public JsonArrayBuilder set(int index, BigInteger value) {
        return set(index, SilkJsonNumber.of(Objects.requireNonNull(value, "value")));
    }

    @Override
    public JsonArrayBuilder set(int index, int value) {
        return set(index, SilkJsonNumber.of(value));
    }

    @Override
    public JsonArrayBuilder set(int index, long value) {
        return set(index, SilkJsonNumber.of(value));
    }

    @Override
    public JsonArrayBuilder set(int index, double value) {
        return set(index, SilkJsonNumber.of(value));
    }

    @Override
    public JsonArrayBuilder set(int index, boolean value) {
        return set(index, Values.of(value));
    }

    @Override
    public JsonArrayBuilder setNull(int index) {
        return set(index, JsonValue.NULL);
    }

    @Override
    public JsonArrayBuilder set(int index, JsonObjectBuilder builder) {
        return set(index, Objects.requireNonNull(builder, "builder").build());
    }

    @Override
    public JsonArrayBuilder set(int index, JsonArrayBuilder builder) {
        return set(index, Objects.requireNonNull(builder, "builder").build());
    }

    @Override
    public JsonArrayBuilder remove(int index) {
        values.remove(index);
        return this;
    }

    @Override
    public JsonArray build() {
        JsonValue[] elements = values.toArray(new JsonValue[0]);
        values = new ArrayList<>();
        return new SilkJsonArray(elements, elements.length);
    }

    private static JsonValue string(String value) {
        return new SilkJsonString(Objects.requireNonNull(value, "value"));
    }
}
