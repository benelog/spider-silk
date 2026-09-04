package net.benelog.spidersilk;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The headers of a {@link WebResponse}: one value per field, compared the way
 * HTTP compares field names, and read back in the order they were set.
 *
 * <p>It is a {@code Map<String, String>} because that is what
 * {@link WebResponse#headers()} hands out, and it is this class rather than a
 * {@link LinkedHashMap} because a field name is case-insensitive:
 * {@code get("content-type")} answers what {@code header("Content-Type", ...)}
 * set. A {@code TreeMap} with {@link String#CASE_INSENSITIVE_ORDER} would
 * compare names the same way and sort the fields alphabetically, which throws
 * away an order the response promises.
 *
 * <p>The spelling a field was first set under is the one that goes on the wire.
 * Setting it again under another spelling replaces the value and leaves the name
 * and its position alone, which is what a {@code put} on a key already there
 * does.
 *
 * <p>Every instance is unmodifiable, and {@link #with} and {@link #without}
 * answer with a new one: a response is an immutable value, and the map it hands
 * out is part of it.
 */
final class Headers extends AbstractMap<String, String> {

    static final Headers EMPTY = new Headers(new LinkedHashMap<>());

    /**
     * Keyed by the lower-cased field name, so a lookup is one hash and not a
     * scan; each entry carries the spelling the field was first set under and
     * its value.
     */
    private final Map<String, Entry<String, String>> fields;

    private Headers(Map<String, Entry<String, String>> fields) {
        this.fields = fields;
    }

    /** These headers with that field set, replacing any spelling of it. */
    Headers with(String name, String value) {
        Objects.requireNonNull(name, "name");
        Map<String, Entry<String, String>> copy = new LinkedHashMap<>(fields);
        putField(copy, name, value);
        return new Headers(copy);
    }

    /** These headers without that field, whatever its spelling. */
    Headers without(String name) {
        if (!fields.containsKey(key(name))) {
            return this;
        }
        Map<String, Entry<String, String>> copy = new LinkedHashMap<>(fields);
        copy.remove(key(name));
        return new Headers(copy);
    }

    /**
     * These headers laid over those, which is what
     * {@link WebResponse#over(WebResponse)} does: the base keeps its fields and
     * their order, and a field set on both answers with this one's value.
     */
    Headers over(Headers base) {
        if (fields.isEmpty()) {
            return base;
        }
        Map<String, Entry<String, String>> copy = new LinkedHashMap<>(base.fields);
        for (Entry<String, String> field : fields.values()) {
            putField(copy, field.getKey(), field.getValue());
        }
        return new Headers(copy);
    }

    @Override
    public String get(Object name) {
        Entry<String, String> field = field(name);
        return field == null ? null : field.getValue();
    }

    @Override
    public boolean containsKey(Object name) {
        return field(name) != null;
    }

    @Override
    public int size() {
        return fields.size();
    }

    /**
     * The fields in the order they were set, each under the spelling it was set
     * under. Unmodifiable, which is what makes every view over it unmodifiable
     * too.
     */
    @Override
    public Set<Entry<String, String>> entrySet() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(fields.values()));
    }

    /**
     * A field already there keeps its name and its position, so only its value
     * changes; a new one goes on the end.
     */
    private static void putField(Map<String, Entry<String, String>> target, String name,
            String value) {
        Entry<String, String> existing = target.get(key(name));
        String spelling = existing == null ? name : existing.getKey();
        target.put(key(name), new SimpleImmutableEntry<>(spelling, value));
    }

    private Entry<String, String> field(Object name) {
        return name instanceof String field ? fields.get(key(field)) : null;
    }

    /**
     * Field names are ASCII, and {@code ROOT} is what keeps a Turkish default
     * locale from lower-casing {@code Content-Type} into something that no
     * longer matches {@code content-type}.
     */
    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
