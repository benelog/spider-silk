package steelspider;

import java.util.HashMap;
import java.util.Map;

/**
 * A path pattern such as "/decks/{deckId}/cards".
 * Compares string segments only; no regular expressions, no reflection.
 */
final class PathPattern {

    private final String[] segments;

    PathPattern(String pattern) {
        this.segments = split(pattern);
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

    /** Returns the path variable map on a match, or null otherwise. */
    Map<String, String> match(String[] actual) {
        if (actual.length != segments.length) {
            return null;
        }
        Map<String, String> params = null;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.length() >= 2 && segment.startsWith("{") && segment.endsWith("}")) {
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
