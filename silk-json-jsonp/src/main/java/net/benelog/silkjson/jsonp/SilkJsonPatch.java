package net.benelog.silkjson.jsonp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonPatch;
import jakarta.json.JsonString;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;

import org.jspecify.annotations.Nullable;

/**
 * An RFC 6902 JSON Patch: a list of operations, each applied through a
 * {@link SilkJsonPointer} to the result of the one before, so the target
 * itself is never changed.
 */
final class SilkJsonPatch implements JsonPatch {

    /** How large a pair of arrays may be, their lengths multiplied, before a diff replaces the whole array rather than tabulate their common elements. */
    private static final long DIFF_CELLS = 4_000_000;

    private final JsonArray operations;

    SilkJsonPatch(JsonArray operations) {
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    @Override
    public <T extends JsonStructure> T apply(T target) {
        T result = Objects.requireNonNull(target, "target");
        for (JsonValue operation : operations) {
            if (!(operation instanceof JsonObject object)) {
                throw new JsonException("A JSON Patch operation is an object, not " + Shape.kind(operation));
            }
            result = apply(result, object);
        }
        return result;
    }

    private static <T extends JsonStructure> T apply(T target, JsonObject operation) {
        Operation op = Operation.fromOperationName(string(operation, "op"));
        SilkJsonPointer path = pointer(operation, "path");
        return switch (op) {
            case ADD -> path.add(target, value(operation));
            case REMOVE -> path.remove(target);
            case REPLACE -> path.replace(target, value(operation));
            case MOVE -> {
                SilkJsonPointer from = pointer(operation, "from");
                JsonValue moved = from.getValue(target);
                if (path.equals(from)) {
                    yield target;
                }
                if (path.toString().startsWith(from + "/")) {
                    throw new JsonException("A JSON Patch move takes a value out of " + from + " and cannot put it inside itself, at " + path);
                }
                yield path.add(from.remove(target), moved);
            }
            case COPY -> path.add(target, pointer(operation, "from").getValue(target));
            case TEST -> {
                if (!path.getValue(target).equals(value(operation))) {
                    throw new JsonException("A JSON Patch test failed: the value at " + path + " is not the one the operation names");
                }
                yield target;
            }
        };
    }

    private static String string(JsonObject operation, String member) {
        if (operation.get(member) instanceof JsonString string) {
            return string.getString();
        }
        throw new JsonException("A JSON Patch operation has a string \"" + member + "\": " + operation);
    }

    private static SilkJsonPointer pointer(JsonObject operation, String member) {
        return new SilkJsonPointer(string(operation, member));
    }

    private static JsonValue value(JsonObject operation) {
        JsonValue value = operation.get("value");
        if (value == null) {
            throw new JsonException("A JSON Patch " + string(operation, "op") + " operation has a \"value\": " + operation);
        }
        return value;
    }

    @Override
    public JsonArray toJsonArray() {
        return operations;
    }

    /**
     * The operations that turn the source into the target: a member that
     * differs is diffed or replaced, one only in the source removed, and one
     * only in the target added. Two arrays are compared through the longest
     * run of elements they share in order, which leaves the fewest elements
     * to add and remove, an element changed in place where that ties with
     * removing it and adding another; arrays too large to tabulate are
     * replaced whole, a patch JSON-P lets a diff answer.
     */
    static JsonArray diff(JsonValue source, JsonValue target) {
        SilkJsonPatchBuilder operations = new SilkJsonPatchBuilder();
        diff("", source, target, operations);
        return operations.operations();
    }

    private static void diff(String path, JsonValue source, JsonValue target, SilkJsonPatchBuilder operations) {
        if (source.equals(target)) {
            return;
        }
        if (source instanceof JsonObject from && target instanceof JsonObject to) {
            from.forEach((key, value) -> {
                JsonValue other = to.get(key);
                if (other == null) {
                    operations.remove(path + "/" + escape(key));
                } else {
                    diff(path + "/" + escape(key), value, other, operations);
                }
            });
            to.forEach((key, value) -> {
                if (!from.containsKey(key)) {
                    operations.add(path + "/" + escape(key), value);
                }
            });
        } else if (source instanceof JsonArray from && target instanceof JsonArray to
                && (long) from.size() * to.size() <= DIFF_CELLS) {
            diffArrays(path, from, to, operations);
        } else {
            operations.replace(path, target);
        }
    }

    /**
     * The longest common subsequence of the two arrays, tabulated, then
     * walked back from the end. Walking back finds the operations last
     * first, so each is placed where it keeps the indexes of the others
     * right: a removal and a change in place go out in the order found, at
     * an index the elements before it still hold, and an addition goes out
     * after them, in reverse, once the elements before its place are the
     * target's.
     */
    private static void diffArrays(String path, JsonArray source, JsonArray target, SilkJsonPatchBuilder operations) {
        int m = source.size();
        int n = target.size();
        // Twice the length of the common subsequence of the first i and j elements, plus one where the last two are equal.
        int[][] lengths = new int[m + 1][n + 1];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                lengths[i + 1][j + 1] = source.get(i).equals(target.get(j))
                        ? (lengths[i][j] & ~1) + 3
                        : Math.max(lengths[i + 1][j], lengths[i][j + 1]) & ~1;
            }
        }
        List<Runnable> inOrder = new ArrayList<>();
        Deque<Runnable> reversed = new ArrayDeque<>();
        int i = m;
        int j = n;
        while (i > 0 || j > 0) {
            int sourceAt = i - 1;
            int targetAt = j - 1;
            if (i == 0) {
                reversed.push(() -> operations.add(path + "/" + targetAt, target.get(targetAt)));
                j--;
            } else if (j == 0) {
                inOrder.add(() -> operations.remove(path + "/" + sourceAt));
                i--;
            } else if ((lengths[i][j] & 1) == 1) {
                i--;
                j--;
            } else {
                int withoutTarget = lengths[i][j - 1] >> 1;
                int withoutSource = lengths[i - 1][j] >> 1;
                if (withoutTarget > withoutSource) {
                    reversed.push(() -> operations.add(path + "/" + targetAt, target.get(targetAt)));
                    j--;
                } else if (withoutTarget < withoutSource) {
                    inOrder.add(() -> operations.remove(path + "/" + sourceAt));
                    i--;
                } else {
                    inOrder.add(() -> diff(path + "/" + sourceAt, source.get(sourceAt), target.get(targetAt), operations));
                    i--;
                    j--;
                }
            }
        }
        inOrder.forEach(Runnable::run);
        reversed.forEach(Runnable::run);
    }

    /** A key as a JSON Pointer token: a tilde as {@code ~0} and a slash as {@code ~1}. */
    static String escape(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof SilkJsonPatch that && operations.equals(that.operations));
    }

    @Override
    public int hashCode() {
        return operations.hashCode();
    }

    /** The operations as JSON text. */
    @Override
    public String toString() {
        return operations.toString();
    }
}
