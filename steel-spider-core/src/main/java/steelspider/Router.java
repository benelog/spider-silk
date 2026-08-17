package steelspider;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A simple routing table scanned in registration order. */
final class Router {

    record Route(String method, PathPattern pattern, Handler handler) {
    }

    record Match(Handler handler, Map<String, String> pathParams) {
    }

    private final List<Route> routes = new ArrayList<>();

    void add(String method, String path, Handler handler) {
        routes.add(new Route(method, new PathPattern(path), handler));
    }

    Match find(String method, String path) {
        String[] segments = PathPattern.split(path);
        for (Route route : routes) {
            if (!route.method().equals(method)) {
                continue;
            }
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
        for (Route route : routes) {
            if (route.pattern().match(segments) != null) {
                methods.add(route.method());
            }
        }
        return methods;
    }
}
