package net.benelog.spidersilk.json;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A JSON builder and parser without reflection.
 * Instead of mapping objects automatically, you state in code what goes out.
 *
 * <pre>{@code
 * String json = Json.obj()
 *         .put("id", deck.id())
 *         .put("name", deck.name())
 *         .put("tags", Json.arr().addAll(tagNames))
 *         .toJson();
 *
 * Json.JsonValue body = Json.parse(text);
 * String name = body.asObject().getString("name");
 * }</pre>
 */
public final class Json {

    private Json() {
    }

    public static JsonObject obj() {
        return new JsonObject();
    }

    public static JsonArray arr() {
        return new JsonArray();
    }

    /**
     * Parses JSON text. Throws {@link JsonException} on invalid syntax, on
     * objects and arrays nested deeper than 256 levels, and on a number too
     * large for a {@code double} to hold, which would otherwise read back as an
     * infinity and serialize as text no JSON parser accepts.
     */
    public static JsonValue parse(String text) {
        Parser parser = new Parser(text);
        JsonValue value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("Trailing characters after the value");
        }
        return value;
    }

    /**
     * What every accessor here throws: a missing key, a value of the wrong
     * type, or text that is not JSON.
     *
     * <p>It is an {@link IllegalArgumentException}, so a {@link JsonReader}
     * that throws that type for a rule of its own is rejected the same way, and
     * {@code req.bodyJson(reader)} turns both into a 400. It is also a type of
     * its own, so an application that maps {@code IllegalArgumentException} to
     * a status can map this one to another: a body that failed to parse is a
     * 400 whatever the application says a bad argument is.
     */
    public static final class JsonException extends IllegalArgumentException {

        JsonException(String message) {
            super(message);
        }
    }

    public sealed interface JsonValue permits JsonObject, JsonArray, JsonPrimitive {

        void write(StringBuilder sb);

        default String toJson() {
            StringBuilder sb = new StringBuilder();
            write(sb);
            return sb.toString();
        }

        default JsonObject asObject() {
            if (this instanceof JsonObject object) {
                return object;
            }
            throw new JsonException("Not a JSON object: " + toJson());
        }

        default JsonArray asArray() {
            if (this instanceof JsonArray array) {
                return array;
            }
            throw new JsonException("Not a JSON array: " + toJson());
        }

        default String asString() {
            if (this instanceof JsonPrimitive primitive && primitive.value() instanceof String s) {
                return s;
            }
            throw new JsonException("Not a JSON string: " + toJson());
        }

        /**
         * The value as a {@code long}. A number with a fractional part is not
         * one, and is rejected rather than truncated: {@code 1.5} is not 1.
         * {@code 1e3} and {@code 2.0} are whole and read as 1000 and 2.
         */
        default long asLong() {
            if (this instanceof JsonPrimitive primitive && primitive.value() instanceof Number n) {
                if (n instanceof Long whole) {
                    return whole;
                }
                double d = n.doubleValue();
                if (d == Math.rint(d) && d >= -0x1p63 && d < 0x1p63) {
                    return (long) d;
                }
                throw new JsonException("Not a JSON integer: " + toJson());
            }
            throw new JsonException("Not a JSON number: " + toJson());
        }

        default double asDouble() {
            if (this instanceof JsonPrimitive primitive && primitive.value() instanceof Number n) {
                return n.doubleValue();
            }
            throw new JsonException("Not a JSON number: " + toJson());
        }

        default boolean asBoolean() {
            if (this instanceof JsonPrimitive primitive && primitive.value() instanceof Boolean b) {
                return b;
            }
            throw new JsonException("Not a JSON boolean: " + toJson());
        }

        default boolean isNull() {
            return this instanceof JsonPrimitive primitive && primitive.value() == null;
        }

        /** True for a JSON string, so a value of either type is told apart before it is read. */
        default boolean isString() {
            return this instanceof JsonPrimitive primitive && primitive.value() instanceof String;
        }

        /** True for a JSON number, whether it is whole or has a fractional part. */
        default boolean isNumber() {
            return this instanceof JsonPrimitive primitive && primitive.value() instanceof Number;
        }

        /** True for {@code true} and for {@code false}. */
        default boolean isBoolean() {
            return this instanceof JsonPrimitive primitive && primitive.value() instanceof Boolean;
        }
    }

    /** A string, number, boolean, or null. */
    public static final class JsonPrimitive implements JsonValue {

        static final JsonPrimitive NULL = new JsonPrimitive(null);
        static final JsonPrimitive TRUE = new JsonPrimitive(true);
        static final JsonPrimitive FALSE = new JsonPrimitive(false);

        private final Object value;   // String | Long | Double | Boolean | null

        JsonPrimitive(Object value) {
            this.value = value;
        }

        Object value() {
            return value;
        }

        @Override
        public void write(StringBuilder sb) {
            if (value instanceof String s) {
                writeString(sb, s);
            } else {
                sb.append(value);   // null, true/false, numbers
            }
        }
    }

    /**
     * Iterable, so an object whose keys are not known in advance reads with
     * for-each: {@code for (var member : object)}, each member a
     * {@link Map.Entry} of the key and its value.
     *
     * <p>Members keep the order they were put in, and a parsed object keeps
     * document order.
     */
    public static final class JsonObject implements JsonValue, Iterable<Map.Entry<String, JsonValue>> {

        private final Map<String, JsonValue> members = new LinkedHashMap<>();

        public JsonObject put(String key, String value) {
            return put(key, value == null ? JsonPrimitive.NULL : new JsonPrimitive(value));
        }

        public JsonObject put(String key, long value) {
            return put(key, new JsonPrimitive(value));
        }

        /** Throws {@link JsonException} for NaN and for an infinity, which JSON has no syntax for. */
        public JsonObject put(String key, double value) {
            return put(key, new JsonPrimitive(finite(value)));
        }

        public JsonObject put(String key, boolean value) {
            return put(key, value ? JsonPrimitive.TRUE : JsonPrimitive.FALSE);
        }

        public JsonObject put(String key, JsonValue value) {
            members.put(key, value == null ? JsonPrimitive.NULL : value);
            return this;
        }

        public JsonObject putNull(String key) {
            return put(key, JsonPrimitive.NULL);
        }

        public boolean has(String key) {
            return members.containsKey(key);
        }

        /** How many members the object has. */
        public int size() {
            return members.size();
        }

        /** The keys, in document order, as a list that cannot be modified. */
        public List<String> keys() {
            return List.copyOf(members.keySet());
        }

        /** Throws {@link JsonException} when the key is missing. */
        public JsonValue get(String key) {
            JsonValue value = members.get(key);
            if (value == null) {
                throw new JsonException("Missing key in JSON object: " + key);
            }
            return value;
        }

        public String getString(String key) {
            return get(key).asString();
        }

        public long getLong(String key) {
            return get(key).asLong();
        }

        public double getDouble(String key) {
            return get(key).asDouble();
        }

        public boolean getBoolean(String key) {
            return get(key).asBoolean();
        }

        public JsonObject getObject(String key) {
            return get(key).asObject();
        }

        public JsonArray getArray(String key) {
            return get(key).asArray();
        }

        /** Returns defaultValue when the key is missing or the value is null. */
        public String optString(String key, String defaultValue) {
            JsonValue value = members.get(key);
            return value == null || value.isNull() ? defaultValue : value.asString();
        }

        /** Returns defaultValue when the key is missing or the value is null. */
        public long optLong(String key, long defaultValue) {
            JsonValue value = members.get(key);
            return (value == null || value.isNull()) ? defaultValue : value.asLong();
        }

        /** Returns defaultValue when the key is missing or the value is null. */
        public double optDouble(String key, double defaultValue) {
            JsonValue value = members.get(key);
            return (value == null || value.isNull()) ? defaultValue : value.asDouble();
        }

        /** Returns defaultValue when the key is missing or the value is null. */
        public boolean optBoolean(String key, boolean defaultValue) {
            JsonValue value = members.get(key);
            return (value == null || value.isNull()) ? defaultValue : value.asBoolean();
        }

        /**
         * Returns null when the key is missing or the value is JSON null, and
         * throws {@link JsonException} when it is present and not an object.
         */
        public JsonObject optObject(String key) {
            JsonValue value = members.get(key);
            return (value == null || value.isNull()) ? null : value.asObject();
        }

        /**
         * Returns null when the key is missing or the value is JSON null, and
         * throws {@link JsonException} when it is present and not an array.
         */
        public JsonArray optArray(String key) {
            JsonValue value = members.get(key);
            return (value == null || value.isNull()) ? null : value.asArray();
        }

        @Override
        public Iterator<Map.Entry<String, JsonValue>> iterator() {
            List<Map.Entry<String, JsonValue>> snapshot = new ArrayList<>(members.size());
            for (var entry : members.entrySet()) {
                snapshot.add(Map.entry(entry.getKey(), entry.getValue()));
            }
            return snapshot.iterator();
        }

        @Override
        public void write(StringBuilder sb) {
            sb.append('{');
            boolean first = true;
            for (var entry : members.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, entry.getKey());
                sb.append(':');
                entry.getValue().write(sb);
            }
            sb.append('}');
        }
    }

    /** Iterable, so a parsed array reads with for-each: {@code for (JsonValue v : array)}. */
    public static final class JsonArray implements JsonValue, Iterable<JsonValue> {

        private final List<JsonValue> values = new ArrayList<>();

        public JsonArray add(String value) {
            return add(value == null ? JsonPrimitive.NULL : new JsonPrimitive(value));
        }

        public JsonArray add(long value) {
            return add(new JsonPrimitive(value));
        }

        /** Throws {@link JsonException} for NaN and for an infinity, which JSON has no syntax for. */
        public JsonArray add(double value) {
            return add(new JsonPrimitive(finite(value)));
        }

        public JsonArray add(boolean value) {
            return add(value ? JsonPrimitive.TRUE : JsonPrimitive.FALSE);
        }

        public JsonArray add(JsonValue value) {
            values.add(value == null ? JsonPrimitive.NULL : value);
            return this;
        }

        public JsonArray addAll(Iterable<String> strings) {
            for (String s : strings) {
                add(s);
            }
            return this;
        }

        public int size() {
            return values.size();
        }

        public JsonValue get(int index) {
            return values.get(index);
        }

        public List<JsonValue> values() {
            return List.copyOf(values);
        }

        @Override
        public Iterator<JsonValue> iterator() {
            return values().iterator();
        }

        @Override
        public void write(StringBuilder sb) {
            sb.append('[');
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                values.get(i).write(sb);
            }
            sb.append(']');
        }
    }

    /**
     * The value itself, unless it is NaN or an infinity. JSON's grammar has no
     * syntax for either, so a document holding one is not JSON and this file's
     * own parser rejects it. A double is checked where it enters the tree, so
     * the failure names the call that made the value rather than turning up as
     * a response body no client can read.
     */
    static double finite(double value) {
        if (!Double.isFinite(value)) {
            throw new JsonException("Not a JSON number: " + value);
        }
        return value;
    }

    static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u%04x".formatted((int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** A recursive descent parser. */
    private static final class Parser {

        /**
         * How deeply objects and arrays may nest. The parser recurses once per
         * level, so a body nested past any depth the JDK stack can hold would
         * fail as a StackOverflowError instead of a rejected request.
         */
        private static final int MAX_DEPTH = 256;

        private final String text;
        private int pos;
        private int depth;

        Parser(String text) {
            this.text = text;
        }

        JsonValue parseValue() {
            skipWhitespace();
            if (atEnd()) {
                throw error("Missing value");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> new JsonPrimitive(parseString());
                case 't' -> parseLiteral("true", JsonPrimitive.TRUE);
                case 'f' -> parseLiteral("false", JsonPrimitive.FALSE);
                case 'n' -> parseLiteral("null", JsonPrimitive.NULL);
                default -> parseNumber();
            };
        }

        private JsonObject parseObject() {
            expect('{');
            enter();
            JsonObject object = new JsonObject();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                depth--;
                return object;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                object.put(key, parseValue());
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    depth--;
                    return object;
                }
                if (c != ',') {
                    throw error("Expected ',' or '}'");
                }
            }
        }

        private JsonArray parseArray() {
            expect('[');
            enter();
            JsonArray array = new JsonArray();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                depth--;
                return array;
            }
            while (true) {
                array.add(parseValue());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    depth--;
                    return array;
                }
                if (c != ',') {
                    throw error("Expected ',' or ']'");
                }
            }
        }

        private void enter() {
            if (++depth > MAX_DEPTH) {
                throw error("Nested deeper than " + MAX_DEPTH + " levels");
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char escaped = next();
                switch (escaped) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw error("Expected 4 hex digits after \\u");
                        }
                        sb.append((char) Integer.parseInt(text, pos, pos + 4, 16));
                        pos += 4;
                    }
                    default -> throw error("Unknown escape: \\" + escaped);
                }
            }
        }

        private JsonValue parseNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            boolean integral = true;
            while (!atEnd()) {
                char c = text.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    integral = false;
                    pos++;
                } else {
                    break;
                }
            }
            String number = text.substring(start, pos);
            try {
                if (integral) {
                    return new JsonPrimitive(Long.parseLong(number));
                }
                double value = Double.parseDouble(number);
                if (!Double.isFinite(value)) {
                    throw error("Number out of range: " + number);
                }
                return new JsonPrimitive(value);
            } catch (NumberFormatException e) {
                throw error("Invalid number format: " + number);
            }
        }

        private JsonValue parseLiteral(String literal, JsonValue value) {
            if (text.startsWith(literal, pos)) {
                pos += literal.length();
                return value;
            }
            throw error("Unknown literal");
        }

        void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        private char peek() {
            if (atEnd()) {
                throw error("Unexpected end of input");
            }
            return text.charAt(pos);
        }

        private char next() {
            char c = peek();
            pos++;
            return c;
        }

        private void expect(char expected) {
            if (next() != expected) {
                pos--;
                throw error("Expected '" + expected + "'");
            }
        }

        JsonException error(String message) {
            return new JsonException(
                    "Near character %d: %s".formatted(pos, message));
        }
    }
}
