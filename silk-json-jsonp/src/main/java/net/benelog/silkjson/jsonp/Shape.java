package net.benelog.silkjson.jsonp;

import jakarta.json.JsonValue;

/**
 * What the tree's typed accessors throw when a member or an element is not
 * there or is another kind: the exception JSON-P names for each case, marked
 * as the tree's own. {@link JsonpCodecs#object} answers these with the
 * message they carry, which names the key or the index and the kind found,
 * and never quotes the document.
 */
sealed interface Shape permits Shape.Missing, Shape.Mismatch, Shape.OutOfRange {

    /** A key the object does not have, where JSON-P names {@link NullPointerException}. */
    final class Missing extends NullPointerException implements Shape {

        private static final long serialVersionUID = 1L;

        Missing(String key) {
            super("Missing key in JSON object: " + key);
        }
    }

    /** A value of another kind than the accessor reads, where JSON-P names {@link ClassCastException}. */
    final class Mismatch extends ClassCastException implements Shape {

        private static final long serialVersionUID = 1L;

        Mismatch(String expected, String where, JsonValue found) {
            super("Not a JSON " + expected + " " + where + ": " + kind(found));
        }
    }

    /** An index past the array, where JSON-P names {@link IndexOutOfBoundsException}. */
    final class OutOfRange extends IndexOutOfBoundsException implements Shape {

        private static final long serialVersionUID = 1L;

        OutOfRange(int index, int size) {
            super("Index %d is out of bounds for a JSON array of %d elements".formatted(index, size));
        }
    }

    /** The kind of a value, as a message names it. */
    static String kind(JsonValue value) {
        return switch (value.getValueType()) {
            case OBJECT -> "an object";
            case ARRAY -> "an array";
            case STRING -> "a string";
            case NUMBER -> "a number";
            case TRUE -> "true";
            case FALSE -> "false";
            case NULL -> "null";
        };
    }
}
