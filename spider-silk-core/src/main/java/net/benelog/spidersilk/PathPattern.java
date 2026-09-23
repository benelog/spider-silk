package net.benelog.spidersilk;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * A path pattern such as "/decks/{deckId}/cards".
 * Compares string segments only; no regular expressions, no reflection.
 *
 * <p>A final "*" segment matches the rest of the path, including nothing at all:
 * "/admin/*" covers "/admin", "/admin/users", and "/admin/users/7". That is what
 * makes it useful for filters, which usually want to guard a prefix and the
 * prefix itself. "*" is only allowed as the last segment.
 *
 * <p>A final "{name*}" segment matches exactly the same paths and binds what it
 * matched: "/files/{path*}" covers "/files", "/files/a.txt", and "/files/a/b.txt",
 * binding "", "a.txt", and "a/b.txt" in turn. The slashes inside the tail are
 * kept, since the tail is a remainder rather than a segment. Like "*", it is
 * only allowed as the last segment, and the two forms match the same set of
 * paths, so {@link #canonicalForm()} reports them as one shape.
 *
 * <p>A variable name appears once per pattern, a named tail included:
 * "/tenants/{id}/items/{id}" and "/decks/{id}/{id*}" are rejected, since one
 * binding would silently overwrite the other.
 */
final class PathPattern {

    private final String[] segments;
    private final boolean matchesRest;

    /** The name a "{name*}" tail binds under, or null when there is no such tail. */
    private final @Nullable String tailName;

    PathPattern(String pattern) {
        rejectWhitespace(pattern);
        String[] parsed = split(pattern);
        for (int i = 0; i < parsed.length - 1; i++) {
            if (parsed[i].equals("*")) {
                throw new IllegalArgumentException(
                        "\"*\" is only allowed as the last segment: " + pattern);
            }
            if (tailVariableName(parsed[i]) != null) {
                throw new IllegalArgumentException(
                        "\"" + parsed[i] + "\" is only allowed as the last segment: " + pattern);
            }
        }
        rejectDuplicateNames(pattern, parsed);
        String last = parsed.length == 0 ? null : parsed[parsed.length - 1];
        this.tailName = last == null ? null : tailVariableName(last);
        this.matchesRest = last != null && (last.equals("*") || tailName != null);
        this.segments = matchesRest ? Arrays.copyOf(parsed, parsed.length - 1) : parsed;
    }

    /**
     * A pattern never holds whitespace, since a request path cannot: a space in
     * a URL arrives percent-encoded. Registering one is almost always a
     * description passed where the path goes — {@code app.get("List decks",
     * "/decks", handler)} — and without this check it would register a route
     * nothing can reach, and compile.
     */
    private static void rejectWhitespace(String pattern) {
        for (int i = 0; i < pattern.length(); i++) {
            if (Character.isWhitespace(pattern.charAt(i))) {
                throw new IllegalArgumentException(("A path pattern has no whitespace: \"%s\"."
                        + " With a description, the path comes first: get(path, description, handler).")
                        .formatted(pattern));
            }
        }
    }

    /**
     * Each variable binds under its own name. Two segments binding one name —
     * "/tenants/{id}/items/{id}", or "/decks/{id}/{id*}" with a named tail —
     * would leave the later value in place of the earlier one, and a lookup
     * or an authorization check would read the wrong identifier.
     */
    private static void rejectDuplicateNames(String pattern, String[] parsed) {
        Set<String> names = new HashSet<>();
        for (String segment : parsed) {
            String tail = tailVariableName(segment);
            String name = tail != null ? tail
                    : isVariable(segment) ? segment.substring(1, segment.length() - 1)
                    : null;
            if (name != null && !names.add(name)) {
                throw new IllegalArgumentException(("A path pattern binds each variable once: \"%s\" repeats {%s}."
                        + " Give each segment its own name, such as {tenantId} and {itemId}.")
                        .formatted(pattern, name));
            }
        }
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
    @Nullable String literalFirstSegment() {
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
     * The name inside a "{path*}" segment, or null when the segment is not one.
     * "{*}" is not one: a tail has a name, and that is the whole point of it.
     */
    private static @Nullable String tailVariableName(String segment) {
        return segment.length() >= 4 && segment.startsWith("{") && segment.endsWith("*}")
                ? segment.substring(1, segment.length() - 2)
                : null;
    }

    /**
     * The set of paths this pattern matches, as a string: variable names erased,
     * slashes normalized. "/decks/{deckId}" and "decks/{id}/" both come back as
     * "/decks/{}", which is what lets the router spot a second registration that
     * could never run. A named tail erases to the "*" it matches the same paths
     * as, so "/files/{path*}" and "/files/*" are one shape.
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
    @Nullable Map<String, String> match(String[] actual) {
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
        if (tailName != null) {
            if (params == null) {
                params = new HashMap<>();
            }
            params.put(tailName, tail(actual));
        }
        return params == null ? Map.of() : params;
    }

    /**
     * Everything past the pattern's own segments, joined by the slashes that
     * separated them, and "" when the tail matched nothing.
     */
    private String tail(String[] actual) {
        return String.join("/", Arrays.copyOfRange(actual, segments.length, actual.length));
    }
}
