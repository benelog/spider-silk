package net.benelog.spidersilk;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * A path pattern such as "/decks/{deckId}/cards".
 * Compares string segments only; no regular expressions, no reflection.
 *
 * <p>A final "*" segment matches the rest of the path, including nothing at all:
 * "/admin/*" covers "/admin", "/admin/users", and "/admin/users/7". That is what
 * makes it useful for filters, which usually want to guard a prefix and the
 * prefix itself. "*" is only allowed as the last segment.
 */
final class PathPattern {

    private final String[] segments;
    private final boolean matchesRest;

    PathPattern(String pattern) {
        String[] parsed = split(pattern);
        for (int i = 0; i < parsed.length - 1; i++) {
            if (parsed[i].equals("*")) {
                throw new IllegalArgumentException(
                        "\"*\" is only allowed as the last segment: " + pattern);
            }
        }
        this.matchesRest = parsed.length > 0 && parsed[parsed.length - 1].equals("*");
        this.segments = matchesRest ? Arrays.copyOf(parsed, parsed.length - 1) : parsed;
    }

    /** Splits a path into segments. A trailing slash is ignored. */
    static String[] split(String path) {
        String trimmed = path;
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? new String[0] : trimmed.split("/");
    }

    /**
     * The first segment when it is a literal, which is what a router can index
     * by: "" for the root, "decks" for "/decks/{deckId}". Null when the pattern
     * can match any first segment — it starts with a variable, or it is a bare
     * "*".
     */
    String literalFirstSegment() {
        if (segments.length == 0) {
            return matchesRest ? null : "";
        }
        String first = segments[0];
        return isVariable(first) ? null : first;
    }

    private static boolean isVariable(String segment) {
        return segment.length() >= 2 && segment.startsWith("{") && segment.endsWith("}");
    }

    /**
     * The set of paths this pattern matches, as a string: variable names erased,
     * slashes normalized. "/decks/{deckId}" and "decks/{id}/" both come back as
     * "/decks/{}", which is what lets the router spot a second registration that
     * could never run.
     */
    String canonicalForm() {
        StringBuilder sb = new StringBuilder();
        for (String segment : segments) {
            sb.append('/').append(isVariable(segment) ? "{}" : segment);
        }
        if (matchesRest) {
            sb.append("/*");
        }
        return sb.isEmpty() ? "/" : sb.toString();
    }

    /** Returns the path variable map on a match, or null otherwise. */
    Map<String, String> match(String[] actual) {
        if (matchesRest ? actual.length < segments.length : actual.length != segments.length) {
            return null;
        }
        Map<String, String> params = null;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (isVariable(segment)) {
                if (params == null) {
                    params = new HashMap<>();
                }
                params.put(segment.substring(1, segment.length() - 1), actual[i]);
            } else if (!segment.equals(actual[i])) {
                return null;
            }
        }
        return params == null ? Map.of() : params;
    }
}
