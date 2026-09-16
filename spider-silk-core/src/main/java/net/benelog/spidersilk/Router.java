package net.benelog.spidersilk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The routing table, indexed by method and by the first path segment.
 *
 * <p>The index is only a way of not looking at routes that cannot match:
 * registration order still decides between two patterns that both do, so a
 * "/study/today" registered before "/study/{mode}" keeps winning.
 */
final class Router {

    /**
     * One registered route. Registration order is the tie-breaker between two
     * patterns that both match, and it is carried by the order of the lists the
     * index holds rather than by a number on the route.
     */
    record Entry(Route route, PathPattern pattern, Handler handler) {

        String method() {
            return route.method();
        }

        String path() {
            return route.path();
        }
    }

    /** A route that answers a request: the route as registered, its handler, and what its variables matched. */
    record Match(Route route, Handler handler, Map<String, String> pathParams) {
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
        Entry entry = new Entry(new Route(method, path, description), new PathPattern(path), handler);
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

    /**
     * A router holding the same routes in the same order, which a later
     * registration on this one does not reach. Built once per deployment, so the
     * index is rebuilt at startup rather than shared with a table that can still
     * change.
     */
    Router copy() {
        Router copy = new Router();
        for (Entry entry : registrations) {
            copy.add(entry.method(), entry.path(), entry.route().description(), entry.handler());
        }
        return copy;
    }

    /** The path arrives already split, because one request asks this more than once. */
    @Nullable Match find(String method, String[] segments) {
        MethodRoutes routes = byMethod.get(method);
        if (routes == null) {
            return null;
        }
        for (Entry entry : routes.candidates(segments)) {
            Map<String, String> params = entry.pattern().match(segments);
            if (params != null) {
                return new Match(entry.route(), entry.handler(), params);
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
        return registrations.stream().map(Entry::route).toList();
    }

    /**
     * The routes of one method, split by the first segment they can match.
     *
     * <p>Each bucket already holds everything that could match a path starting
     * with that segment — the routes whose own first segment is that literal, and
     * the ones that can start with anything — in registration order. Registration
     * happens at startup and a lookup happens per request, so the merging is done
     * there rather than here: a route that starts with a variable is written into
     * every bucket, and a bucket that comes later starts as a copy of them.
     */
    private static final class MethodRoutes {

        private final Map<String, List<Entry>> byFirstSegment = new HashMap<>();

        /** Routes that can match any first segment: what a path with no bucket asks. */
        private final List<Entry> anyFirstSegment = new ArrayList<>();

        void add(Entry entry) {
            String first = entry.pattern().literalFirstSegment();
            if (first == null) {
                anyFirstSegment.add(entry);
                byFirstSegment.values().forEach(bucket -> bucket.add(entry));
            } else {
                // Order rises with every registration, so appending keeps every
                // list sorted by it, and a new bucket starts with what it missed.
                byFirstSegment.computeIfAbsent(first, key -> new ArrayList<>(anyFirstSegment))
                        .add(entry);
            }
        }

        /** Everything that could match this path, in registration order. */
        List<Entry> candidates(String[] segments) {
            List<Entry> bucket = byFirstSegment.get(segments.length == 0 ? "" : segments[0]);
            return bucket == null ? anyFirstSegment : bucket;
        }
    }
}
