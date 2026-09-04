package net.benelog.spidersilk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The routing table, indexed by method and by the first path segment.
 *
 * <p>The index is only a way of not looking at routes that cannot match:
 * registration order still decides between two patterns that both do, so a
 * "/study/today" registered before "/study/{mode}" keeps winning.
 */
final class Router {

    /** {@code order} is the registration index — the tie-breaker the index must not lose. */
    record Entry(String method, String path, String description, PathPattern pattern,
            Handler handler, int order) {
    }

    record Match(Handler handler, Map<String, String> pathParams) {
    }

    private final Map<String, MethodRoutes> byMethod = new LinkedHashMap<>();

    /** Every route in registration order, which the index alone no longer preserves. */
    private final List<Entry> registrations = new ArrayList<>();

    /** What each registration matches, keyed by "GET /decks/{}". Spots dead re-registrations. */
    private final Map<String, Entry> byShape = new HashMap<>();

    /** A route with nothing said about what it is for. */
    void add(String method, String path, Handler handler) {
        add(method, path, "", handler);
    }

    void add(String method, String path, String description, Handler handler) {
        // At the registration site rather than in routes(), where the stack trace
        // would name the reader instead of the line that left the argument out.
        Objects.requireNonNull(description, "description");
        Entry entry = new Entry(method, path, description, new PathPattern(path), handler,
                registrations.size());
        Entry existing = byShape.putIfAbsent(method + " " + entry.pattern().canonicalForm(), entry);
        if (existing != null) {
            throw new IllegalStateException(duplicateMessage(entry, existing));
        }
        registrations.add(entry);
        byMethod.computeIfAbsent(method, key -> new MethodRoutes()).add(entry);
    }

    /**
     * Registration order breaks ties between overlapping patterns, so a second
     * route matching exactly the same requests could never run — a mistake worth
     * a failure at registration rather than a silent 200 from the wrong handler.
     */
    private static String duplicateMessage(Entry entry, Entry existing) {
        String base = entry.method() + " " + entry.path() + " is already registered";
        return entry.path().equals(existing.path())
                ? base
                : base + " as " + existing.path() + ", which matches the same requests";
    }

    /** The path arrives already split, because one request asks this more than once. */
    Match find(String method, String[] segments) {
        MethodRoutes routes = byMethod.get(method);
        if (routes == null) {
            return null;
        }
        for (Entry entry : routes.candidates(segments)) {
            Map<String, String> params = entry.pattern().match(segments);
            if (params != null) {
                return new Match(entry.handler(), params);
            }
        }
        return null;
    }

    /** Whether the path matches under other methods. Feeds the Allow header of a 405. */
    Set<String> allowedMethods(String[] segments) {
        Set<String> methods = new LinkedHashSet<>();
        byMethod.forEach((method, routes) -> {
            for (Entry entry : routes.candidates(segments)) {
                if (entry.pattern().match(segments) != null) {
                    methods.add(method);
                    return;
                }
            }
        });
        return methods;
    }

    /** An immutable snapshot of what was registered, in registration order. */
    List<Route> routes() {
        return registrations.stream()
                .map(entry -> new Route(entry.method(), entry.path(), entry.description()))
                .toList();
    }

    /** The routes of one method, split by the first segment they can match. */
    private static final class MethodRoutes {

        private final Map<String, List<Entry>> byFirstSegment = new HashMap<>();
        private final List<Entry> anyFirstSegment = new ArrayList<>();

        void add(Entry entry) {
            String first = entry.pattern().literalFirstSegment();
            if (first == null) {
                anyFirstSegment.add(entry);
            } else {
                byFirstSegment.computeIfAbsent(first, key -> new ArrayList<>()).add(entry);
            }
        }

        /** Everything that could match this path, in registration order. */
        List<Entry> candidates(String[] segments) {
            List<Entry> literal = byFirstSegment.getOrDefault(
                    segments.length == 0 ? "" : segments[0], List.of());
            if (anyFirstSegment.isEmpty()) {
                return literal;
            }
            if (literal.isEmpty()) {
                return anyFirstSegment;
            }
            return mergeByOrder(literal, anyFirstSegment);
        }

        /** Both lists are in registration order already, so this is one merge step. */
        private static List<Entry> mergeByOrder(List<Entry> left, List<Entry> right) {
            List<Entry> merged = new ArrayList<>(left.size() + right.size());
            int i = 0;
            int j = 0;
            while (i < left.size() && j < right.size()) {
                merged.add(left.get(i).order() < right.get(j).order()
                        ? left.get(i++) : right.get(j++));
            }
            merged.addAll(left.subList(i, left.size()));
            merged.addAll(right.subList(j, right.size()));
            return merged;
        }
    }
}
