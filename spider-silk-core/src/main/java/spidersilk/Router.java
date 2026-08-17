package spidersilk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    record Route(String method, PathPattern pattern, Handler handler, int order) {
    }

    record Match(Handler handler, Map<String, String> pathParams) {
    }

    private final Map<String, MethodRoutes> byMethod = new LinkedHashMap<>();
    private int registered;

    void add(String method, String path, Handler handler) {
        Route route = new Route(method, new PathPattern(path), handler, registered++);
        byMethod.computeIfAbsent(method, key -> new MethodRoutes()).add(route);
    }

    Match find(String method, String path) {
        MethodRoutes routes = byMethod.get(method);
        if (routes == null) {
            return null;
        }
        String[] segments = PathPattern.split(path);
        for (Route route : routes.candidates(segments)) {
            Map<String, String> params = route.pattern().match(segments);
            if (params != null) {
                return new Match(route.handler(), params);
            }
        }
        return null;
    }

    /** Whether the path matches under other methods. Feeds the Allow header of a 405. */
    Set<String> allowedMethods(String path) {
        String[] segments = PathPattern.split(path);
        Set<String> methods = new LinkedHashSet<>();
        byMethod.forEach((method, routes) -> {
            for (Route route : routes.candidates(segments)) {
                if (route.pattern().match(segments) != null) {
                    methods.add(method);
                    return;
                }
            }
        });
        return methods;
    }

    /** The routes of one method, split by the first segment they can match. */
    private static final class MethodRoutes {

        private final Map<String, List<Route>> byFirstSegment = new HashMap<>();
        private final List<Route> anyFirstSegment = new ArrayList<>();

        void add(Route route) {
            String first = route.pattern().literalFirstSegment();
            if (first == null) {
                anyFirstSegment.add(route);
            } else {
                byFirstSegment.computeIfAbsent(first, key -> new ArrayList<>()).add(route);
            }
        }

        /** Everything that could match this path, in registration order. */
        List<Route> candidates(String[] segments) {
            List<Route> literal = byFirstSegment.getOrDefault(
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
        private static List<Route> mergeByOrder(List<Route> left, List<Route> right) {
            List<Route> merged = new ArrayList<>(left.size() + right.size());
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
