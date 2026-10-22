package net.benelog.silkjson.jsonp;

import java.util.Objects;

import jakarta.json.JsonConfig.KeyStrategy;
import jakarta.json.JsonMergePatch;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import org.jspecify.annotations.Nullable;

/**
 * An RFC 7396 JSON Merge Patch: an object whose members say what to put in
 * the target's, a null among them what to remove, and any other value what
 * to put in place of the whole target.
 */
final class SilkJsonMergePatch implements JsonMergePatch {

    private final JsonValue patch;

    SilkJsonMergePatch(JsonValue patch) {
        this.patch = Objects.requireNonNull(patch, "patch");
    }

    @Override
    public JsonValue apply(JsonValue target) {
        return merge(Objects.requireNonNull(target, "target"), patch);
    }

    /** The RFC's MergePatch(Target, Patch), with a target that is not an object read as an empty one. */
    private static JsonValue merge(JsonValue target, JsonValue patch) {
        if (!(patch instanceof JsonObject changes)) {
            return patch;
        }
        JsonObject object = target instanceof JsonObject o ? o : JsonValue.EMPTY_JSON_OBJECT;
        Members members = new Members(KeyStrategy.LAST, object);
        changes.forEach((key, value) -> {
            if (value.getValueType() == JsonValue.ValueType.NULL) {
                members.remove(key);
            } else {
                JsonValue current = object.get(key);
                members.put(key, merge(current == null ? JsonValue.EMPTY_JSON_OBJECT : current, value));
            }
        });
        return members.build();
    }

    /**
     * The merge patch that turns the source into the target: between two
     * objects, a member removed as null, a member added or changed as its
     * new value or its own diff, and the target itself between anything else.
     */
    static JsonValue diff(JsonValue source, JsonValue target) {
        if (!(source instanceof JsonObject from) || !(target instanceof JsonObject to)) {
            return target;
        }
        Members members = new Members(KeyStrategy.LAST);
        from.forEach((key, value) -> {
            JsonValue other = to.get(key);
            if (other == null) {
                members.put(key, JsonValue.NULL);
            } else if (!value.equals(other)) {
                members.put(key, diff(value, other));
            }
        });
        to.forEach((key, value) -> {
            if (!from.containsKey(key)) {
                members.put(key, value);
            }
        });
        return members.build();
    }

    @Override
    public JsonValue toJsonValue() {
        return patch;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof SilkJsonMergePatch that && patch.equals(that.patch));
    }

    @Override
    public int hashCode() {
        return patch.hashCode();
    }

    /** The patch as JSON text. */
    @Override
    public String toString() {
        return patch.toString();
    }
}
