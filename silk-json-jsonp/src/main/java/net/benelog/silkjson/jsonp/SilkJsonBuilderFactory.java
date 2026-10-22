package net.benelog.silkjson.jsonp;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;

import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonBuilderFactory;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

/** Makes object and array builders whose objects keep a repeated key as the configuration's key strategy says. */
final class SilkJsonBuilderFactory implements JsonBuilderFactory {

    private final Config config;

    SilkJsonBuilderFactory(Config config) {
        this.config = config;
    }

    @Override
    public JsonObjectBuilder createObjectBuilder() {
        return new SilkJsonObjectBuilder(new Members(config.keyStrategy));
    }

    @Override
    public JsonObjectBuilder createObjectBuilder(JsonObject object) {
        return new SilkJsonObjectBuilder(new Members(config.keyStrategy, Objects.requireNonNull(object, "object")));
    }

    /** Takes each value as {@link SilkJsonProvider#createObjectBuilder(Map)} describes. */
    @Override
    public JsonObjectBuilder createObjectBuilder(Map<String, Object> object) {
        return new SilkJsonObjectBuilder(Values.members(Objects.requireNonNull(object, "object"), config.keyStrategy));
    }

    @Override
    public JsonArrayBuilder createArrayBuilder() {
        return new SilkJsonArrayBuilder();
    }

    @Override
    public JsonArrayBuilder createArrayBuilder(JsonArray array) {
        return new SilkJsonArrayBuilder(Objects.requireNonNull(array, "array"));
    }

    @Override
    public JsonArrayBuilder createArrayBuilder(Collection<?> collection) {
        return new SilkJsonArrayBuilder(Values.array(Objects.requireNonNull(collection, "collection"), config.keyStrategy));
    }

    @Override
    public Map<String, ?> getConfigInUse() {
        return config.keysInUse();
    }
}
