package net.benelog.spidersilk.json;

/**
 * A JSON builder and parser without reflection.
 * Instead of mapping objects automatically, you state in code what goes out.
 *
 * <pre>{@code
 * String json = Json.object()
 *         .put("id", deck.id())
 *         .put("name", deck.name())
 *         .put("tags", Json.array().addAll(tagNames))
 *         .toJson();
 *
 * JsonValue body = Json.parse(text);
 * String name = body.asObject().getString("name");
 * }</pre>
 *
 * <p>The tree is {@link JsonValue} and its three kinds, {@link JsonObject},
 * {@link JsonArray}, and {@link JsonPrimitive}; this class is where a tree is
 * started or parsed.
 */
public final class Json {

    private Json() {
    }

    /** An empty object, to be filled with {@link JsonObject#put}. */
    public static JsonObject object() {
        return new JsonObject();
    }

    /** An empty array, to be filled with {@link JsonArray#add}. */
    public static JsonArray array() {
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

        /**
         * Most strings in a document hold no escape at all: keys, and every
         * value that is plain text. Those are one substring of the body rather
         * than a StringBuilder filled a character at a time, so the scan reads
         * ahead to the closing quote and only hands over to
         * {@link #parseEscapedString} when a backslash turns up.
         */
        private String parseString() {
            expect('"');
            int start = pos;
            while (!atEnd()) {
                char c = text.charAt(pos);
                if (c == '"') {
                    String value = text.substring(start, pos);
                    pos++;
                    return value;
                }
                if (c == '\\') {
                    return parseEscapedString(start);
                }
                pos++;
            }
            throw error("Unexpected end of input");
        }

        /**
         * The rest of a string that does hold an escape, with the run already
         * scanned copied in as it stood. {@code pos} is at the backslash.
         */
        private String parseEscapedString(int start) {
            StringBuilder sb = new StringBuilder().append(text, start, pos);
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
                    case 'u' -> sb.append(parseHexEscape());
                    default -> throw error("Unknown escape: \\" + escaped);
                }
            }
        }

        /**
         * The character a backslash-u escape names, read as exactly four hex
         * digits. Integer.parseInt would do the arithmetic, but it also accepts
         * a leading sign, which is not a hex digit and which JSON's grammar
         * does not allow here, and it reports what it will not read as a
         * NumberFormatException rather than as this parser's positioned
         * JsonException.
         */
        private char parseHexEscape() {
            int value = 0;
            for (int i = 0; i < 4; i++) {
                if (atEnd()) {
                    throw error("Expected 4 hex digits after \\u");
                }
                int digit = Character.digit(text.charAt(pos), 16);
                if (digit < 0) {
                    throw error("Expected 4 hex digits after \\u");
                }
                value = value * 16 + digit;
                pos++;
            }
            return (char) value;
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
