package spidersilk.json;

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
     * Parses JSON text. Throws IllegalArgumentException on invalid syntax, and
     * on objects and arrays nested deeper than 256 levels.
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
            throw new IllegalArgumentException("Not a JSON object: " + toJson());
        }

        default JsonArray asArray() {
            if (this instanceof JsonArray array) {
                return array;
            }
            throw new IllegalArgumentException("Not a JSON array: " + toJson());
        }

        default String asString() {
            if (this instanceof JsonPrimitive primitive && primitive.value() instanceof String s) {
                return s;
            }
            throw new IllegalArgumentException("Not a JSON string: " + toJson());
        }

        default long asLong() {
            if (this instanceof JsonPrimitive primitive && primitive.value() instanceof Number n) {
                return n.longValue();
            }
            throw new IllegalArgumentException("Not a JSON number: " + toJson());
        }

        default double asDouble() {
            if (this instanceof JsonPrimitive primitive && primitive.value() instanceof Number n) {
                return n.doubleValue();
            }
            throw new IllegalArgumentException("Not a JSON number: " + toJson());
        }

        default boolean asBoolean() {
            if (this instanceof JsonPrimitive primitive && primitive.value() instanceof Boolean b) {
                return b;
            }
            throw new IllegalArgumentException("Not a JSON boolean: " + toJson());
        }

        default boolean isNull() {
            return this instanceof JsonPrimitive primitive && primitive.value() == null;
        }
    }

    /** A string, number, boolean, or null. */
    public static final class JsonPrimitive implements JsonValue {

        static final JsonPrimitive NULL = new JsonPrimitive(null);
        static final JsonPrimitive TRUE = new JsonPrimitive(Boolean.TRUE);
        static final JsonPrimitive FALSE = new JsonPrimitive(Boolean.FALSE);

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

    public static final class JsonObject implements JsonValue {

        private final Map<String, JsonValue> members = new LinkedHashMap<>();

        public JsonObject put(String key, String value) {
            return put(key, value == null ? JsonPrimitive.NULL : new JsonPrimitive(value));
        }

        public JsonObject put(String key, long value) {
            return put(key, new JsonPrimitive(value));
        }

        public JsonObject put(String key, double value) {
            return put(key, new JsonPrimitive(value));
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

        /** Throws IllegalArgumentException when the key is missing. */
        public JsonValue get(String key) {
            JsonValue value = members.get(key);
            if (value == null) {
                throw new IllegalArgumentException("Missing key in JSON object: " + key);
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
            return value == null || value.isNull() ? defaultValue : value.asLong();
        }

        /** Returns defaultValue when the key is missing or the value is null. */
        public double optDouble(String key, double defaultValue) {
            JsonValue value = members.get(key);
            return value == null || value.isNull() ? defaultValue : value.asDouble();
        }

        /** Returns defaultValue when the key is missing or the value is null. */
        public boolean optBoolean(String key, boolean defaultValue) {
            JsonValue value = members.get(key);
            return value == null || value.isNull() ? defaultValue : value.asBoolean();
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

        public JsonArray add(double value) {
            return add(new JsonPrimitive(value));
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
                return integral
                        ? new JsonPrimitive(Long.parseLong(number))
                        : new JsonPrimitive(Double.parseDouble(number));
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

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException(
                    "Near character %d: %s".formatted(pos, message));
        }
    }
}
