package net.benelog.silkjson.jsonp;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.json.JsonArray;
import jakarta.json.JsonConfig.KeyStrategy;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonPointer;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;

import org.jspecify.annotations.Nullable;

/**
 * An RFC 6901 JSON Pointer. Its tokens are unescaped once, here, and a
 * pointer that does not start with a slash is refused here too. A tilde
 * followed by anything but {@code 0} or {@code 1} stands for itself, which the
 * RFC leaves to the application and the JSON-P TCK accepts. An array index is
 * the RFC's, digits with no leading zero, and {@code -} names the place after
 * the last element, which only {@link #add} takes.
 *
 * <p>The tree is immutable, so {@link #add}, {@link #remove}, and
 * {@link #replace} answer a new one, which shares every value off the path
 * with the target.
 */
final class SilkJsonPointer implements JsonPointer {

    private enum Change { ADD, REMOVE, REPLACE }

    private final String pointer;
    private final String[] tokens;

    SilkJsonPointer(String pointer) {
        this.pointer = Objects.requireNonNull(pointer, "jsonPointer");
        this.tokens = tokens(pointer);
    }

    private static String[] tokens(String pointer) {
        if (pointer.isEmpty()) {
            return new String[0];
        }
        if (pointer.charAt(0) != '/') {
            throw new JsonException("A JSON Pointer is empty or starts with '/': " + pointer);
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        for (int i = 1; i < pointer.length(); i++) {
            char c = pointer.charAt(i);
            if (c == '/') {
                tokens.add(token.toString());
                token.setLength(0);
            } else if (c == '~' && i + 1 < pointer.length() && (pointer.charAt(i + 1) == '0' || pointer.charAt(i + 1) == '1')) {
                token.append(pointer.charAt(i + 1) == '0' ? '~' : '/');
                i++;
            } else {
                token.append(c);
            }
        }
        tokens.add(token.toString());
        return tokens.toArray(new String[0]);
    }

    @Override
    public boolean containsValue(JsonStructure target) {
        Objects.requireNonNull(target, "target");
        JsonValue node = target;
        for (String token : tokens) {
            JsonValue child = null;
            if (node instanceof JsonObject object) {
                child = object.get(token);
            } else if (node instanceof JsonArray array) {
                int index = index(token);
                child = index >= 0 && index < array.size() ? array.get(index) : null;
            }
            if (child == null) {
                return false;
            }
            node = child;
        }
        return true;
    }

    @Override
    public JsonValue getValue(JsonStructure target) {
        Objects.requireNonNull(target, "target");
        JsonValue node = target;
        for (int at = 0; at < tokens.length; at++) {
            node = existing(node, at);
        }
        return node;
    }

    /** Adds a member, puts a new value in an object's member, or inserts an element, as RFC 6902's add does. */
    @Override
    public <T extends JsonStructure> T add(T target, JsonValue value) {
        return change(target, Change.ADD, Objects.requireNonNull(value, "value"));
    }

    @Override
    public <T extends JsonStructure> T remove(T target) {
        return change(target, Change.REMOVE, null);
    }

    @Override
    public <T extends JsonStructure> T replace(T target, JsonValue value) {
        return change(target, Change.REPLACE, Objects.requireNonNull(value, "value"));
    }

    /** The target with the change made, as the type the caller holds it as: a change of the whole document to the other kind fails where the caller reads it, as JSON-P has it. */
    @SuppressWarnings("unchecked")
    private <T extends JsonStructure> T change(T target, Change change, @Nullable JsonValue value) {
        Objects.requireNonNull(target, "target");
        if (tokens.length == 0) {
            if (change == Change.REMOVE) {
                throw new JsonException("The JSON Pointer \"\" names the whole document, which cannot be removed");
            }
            if (!(value instanceof JsonStructure structure)) {
                throw new JsonException("The JSON Pointer \"\" puts a new document in place, an object or an array, not "
                        + (value == null ? "nothing" : Shape.kind(value)));
            }
            return (T) structure;
        }
        return (T) changed(target, 0, change, value);
    }

    /** The node with the change made at the token in that place or under it, as a new node; the node itself stays as it is. */
    private JsonValue changed(JsonValue node, int at, Change change, @Nullable JsonValue value) {
        String token = tokens[at];
        boolean last = at == tokens.length - 1;
        if (node instanceof JsonObject object) {
            Members members = new Members(KeyStrategy.LAST, object);
            if (!last) {
                members.put(token, changed(existing(node, at), at + 1, change, value));
            } else if (change == Change.ADD) {
                members.put(token, Objects.requireNonNull(value));
            } else {
                existing(node, at);
                if (change == Change.REMOVE) {
                    members.remove(token);
                } else {
                    members.put(token, Objects.requireNonNull(value));
                }
            }
            return members.build();
        }
        if (node instanceof JsonArray array) {
            List<JsonValue> elements = new ArrayList<>(array);
            if (last && change == Change.ADD) {
                int index = token.equals("-") ? elements.size() : index(token);
                if (index < 0 || index > elements.size()) {
                    throw new JsonException(fault(at, "is not an index of an array of " + elements.size()
                            + " elements, nor the place after its last"));
                }
                elements.add(index, Objects.requireNonNull(value));
            } else {
                JsonValue element = existing(node, at);
                int index = index(token);
                if (!last) {
                    elements.set(index, changed(element, at + 1, change, value));
                } else if (change == Change.REMOVE) {
                    elements.remove(index);
                } else {
                    elements.set(index, Objects.requireNonNull(value));
                }
            }
            return new SilkJsonArray(elements.toArray(new JsonValue[0]), elements.size());
        }
        throw new JsonException(fault(at, "is under " + Shape.kind(node) + ", which holds nothing"));
    }

    /** The value the token in that place names in a node, or a {@link JsonException} that says why there is none. */
    private JsonValue existing(JsonValue node, int at) {
        String token = tokens[at];
        if (node instanceof JsonObject object) {
            JsonValue value = object.get(token);
            if (value == null) {
                throw new JsonException(fault(at, "names no member of the object"));
            }
            return value;
        }
        if (node instanceof JsonArray array) {
            int index = index(token);
            if (index < 0 || index >= array.size()) {
                throw new JsonException(fault(at, "is not an index of an array of " + array.size() + " elements"));
            }
            return array.get(index);
        }
        throw new JsonException(fault(at, "is under " + Shape.kind(node) + ", which holds nothing"));
    }

    /** An array index as RFC 6901 writes one, digits with no leading zero, or -1 for any other token. */
    private static int index(String token) {
        int length = token.length();
        if (length == 0 || length > 9 || (length > 1 && token.charAt(0) == '0')) {
            return -1;
        }
        int index = 0;
        for (int i = 0; i < length; i++) {
            char c = token.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            index = index * 10 + (c - '0');
        }
        return index;
    }

    private String fault(int at, String what) {
        return "The token \"" + tokens[at] + "\" of the JSON Pointer " + pointer + " " + what;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof SilkJsonPointer that && pointer.equals(that.pointer));
    }

    @Override
    public int hashCode() {
        return pointer.hashCode();
    }

    /** The pointer as it was written. */
    @Override
    public String toString() {
        return pointer;
    }
}
