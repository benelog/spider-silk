package flashcard.web;

import java.util.List;
import java.util.Locale;

import spidersilk.Route;
import spidersilk.json.Json;

/**
 * What {@code app.routes()} is for: the route list turned into an OpenAPI
 * document.
 *
 * <p>This does not ship in core. Core hands out the route list as plain data
 * and stops there — an OpenAPI document is a spec format, not the web tier, and
 * its version drift is not something a web framework should own. This is one
 * application's use of the list, and it is about forty lines.
 *
 * <p>There is no handler here. The two routes that use it read {@code app}
 * itself, so they are registered as lambdas in {@code FlashcardApp}, which is
 * the third shape a handler comes in.
 */
public final class OpenApi {

    private OpenApi() {
    }

    /**
     * An OpenAPI 3.1 document. The patterns need no translation: Spider Silk's
     * {@code {deckId}} is OpenAPI's path template verbatim.
     *
     * <p>Only the {@code /api} routes go in — the rest of this app serves HTML —
     * and a route containing {@code *} is skipped, because a wildcard has no
     * OpenAPI equivalent. Both are this application's calls to make, which is
     * the point of getting the routes as a list.
     */
    public static Json.JsonValue document(List<Route> routes) {
        Json.JsonObject paths = Json.obj();
        for (Route route : routes) {
            if (!route.path().startsWith("/api") || route.path().contains("*")) {
                continue;
            }
            Json.JsonObject operations =
                    paths.has(route.path()) ? paths.getObject(route.path()) : Json.obj();
            paths.put(route.path(), operations.put(route.method().toLowerCase(Locale.ROOT),
                    operation(route.path())));
        }
        return Json.obj()
                .put("openapi", "3.1.0")
                .put("info", Json.obj().put("title", "Flashcard API").put("version", "1.0.0"))
                .put("paths", paths);
    }

    private static Json.JsonObject operation(String path) {
        Json.JsonObject operation = Json.obj();
        Json.JsonArray parameters = pathParameters(path);
        if (parameters.size() > 0) {
            operation.put("parameters", parameters);
        }
        return operation.put("responses",
                Json.obj().put("200", Json.obj().put("description", "OK")));
    }

    /** Every {@code {name}} in the pattern, which OpenAPI requires to be declared. */
    private static Json.JsonArray pathParameters(String path) {
        Json.JsonArray parameters = Json.arr();
        for (String segment : path.split("/")) {
            if (segment.length() >= 2 && segment.startsWith("{") && segment.endsWith("}")) {
                parameters.add(Json.obj()
                        .put("name", segment.substring(1, segment.length() - 1))
                        .put("in", "path")
                        .put("required", true)
                        .put("schema", Json.obj().put("type", "string")));
            }
        }
        return parameters;
    }
}
