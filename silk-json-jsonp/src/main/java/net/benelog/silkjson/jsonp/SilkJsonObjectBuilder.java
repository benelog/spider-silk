package net.benelog.silkjson.jsonp;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

/**
 * Builds an immutable object. A key added twice keeps its first place, with
 * the value the factory's {@link jakarta.json.JsonConfig#KEY_STRATEGY} says,
 * the last one when it says nothing. {@link #build()} hands the members over
 * and starts this builder again empty.
 */
final class SilkJsonObjectBuilder implements JsonObjectBuilder {

    private final Members members;

    SilkJsonObjectBuilder(Members members) {
        this.members = members;
    }

    @Override
    public JsonObjectBuilder add(String name, JsonValue value) {
        return member(name, Objects.requireNonNull(value, "value"));
    }

    @Override
    public JsonObjectBuilder add(String name, String value) {
        return member(name, new SilkJsonString(Objects.requireNonNull(value, "value")));
    }

    @Override
    public JsonObjectBuilder add(String name, BigInteger value) {
        return member(name, SilkJsonNumber.of(Objects.requireNonNull(value, "value")));
    }

    @Override
    public JsonObjectBuilder add(String name, BigDecimal value) {
        return member(name, SilkJsonNumber.of(Objects.requireNonNull(value, "value")));
    }

    @Override
    public JsonObjectBuilder add(String name, int value) {
        return member(name, SilkJsonNumber.of(value));
    }

    @Override
    public JsonObjectBuilder add(String name, long value) {
        return member(name, SilkJsonNumber.of(value));
    }

    /** Throws {@link NumberFormatException} for NaN and for an infinity. */
    @Override
    public JsonObjectBuilder add(String name, double value) {
        Objects.requireNonNull(name, "name");
        return member(name, SilkJsonNumber.of(value));
    }

    @Override
    public JsonObjectBuilder add(String name, boolean value) {
        return member(name, Values.of(value));
    }

    @Override
    public JsonObjectBuilder addNull(String name) {
        return member(name, JsonValue.NULL);
    }

    @Override
    public JsonObjectBuilder add(String name, JsonObjectBuilder builder) {
        Objects.requireNonNull(name, "name");
        return member(name, Objects.requireNonNull(builder, "builder").build());
    }

    @Override
    public JsonObjectBuilder add(String name, JsonArrayBuilder builder) {
        Objects.requireNonNull(name, "name");
        return member(name, Objects.requireNonNull(builder, "builder").build());
    }

    /** Adds the members the other builder holds, which that builder's {@code build()} hands over. */
    @Override
    public JsonObjectBuilder addAll(JsonObjectBuilder builder) {
        Objects.requireNonNull(builder, "builder").build().forEach(members::put);
        return this;
    }

    @Override
    public JsonObjectBuilder remove(String name) {
        members.remove(Objects.requireNonNull(name, "name"));
        return this;
    }

    @Override
    public JsonObject build() {
        return members.build();
    }

    private JsonObjectBuilder member(String name, JsonValue value) {
        members.put(Objects.requireNonNull(name, "name"), value);
        return this;
    }
}
