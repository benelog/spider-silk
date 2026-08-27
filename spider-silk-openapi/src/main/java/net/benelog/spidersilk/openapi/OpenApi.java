package net.benelog.spidersilk.openapi;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import net.benelog.spidersilk.Route;
import net.benelog.spidersilk.json.Json;

/**
 * {@link net.benelog.spidersilk.App#routes()} as an OpenAPI 3.1 document.
 *
 * <pre>{@code
 * app.get("/openapi.json", req -> WebResponse.json(
 *         OpenApi.document("Flashcard API", "1.0.0", apiRoutes(app))));
 * }</pre>
 *
 * <p>A reader over the route list, and nothing more. Core hands the list out as
 * plain data and needs no change to allow this: {@code PathPattern}'s
 * {@code {deckId}} is OpenAPI's path template verbatim, so there is nothing to
 * translate and nothing to reflect over. The module exists because that reading
 * is worth writing once rather than in every application, not because a spec
 * format belongs in the web tier — which is why it is a module of its own, the
 * way {@code spider-silk-test} is.
 *
 * <p>The version this writes is pinned at {@code 3.1.0} and the shape is the
 * minimum a valid document needs, plus the one thing the route list now carries
 * that a method and a path do not imply: a route registered with a description
 * becomes an operation {@code summary}. Anything richer — request and response
 * schemas, servers, security schemes — is still not derivable from a route, so
 * it would have to be declared somewhere, and this module has no opinion on
 * where.
 */
public final class OpenApi {

    /** The one version this writes. */
    private static final String OPENAPI_VERSION = "3.1.0";

    private OpenApi() {
    }

    /**
     * An OpenAPI 3.1 document over these routes, under this title and version —
     * both of which OpenAPI requires, so both are arguments rather than
     * defaults.
     *
     * <p><em>Which</em> routes go in stays the caller's call. An application
     * that serves HTML alongside its API passes the API's routes and not the
     * whole list; that selection is the reason {@code routes()} hands back a
     * list in the first place, and guessing at it here would be this module
     * deciding what an application's API is.
     *
     * <p>Every {@code {name}} in a path becomes a required path parameter,
     * which OpenAPI insists on declaring, and the paths come out in the order
     * they were registered. A route registered with a description gets it as
     * the operation's {@code summary} — the field a spec UI shows beside the
     * route — and a route without one gets no {@code summary} at all, rather
     * than an empty string that would render as a blank line.
     * A route whose pattern contains {@code *} throws:
     * a wildcard has no OpenAPI equivalent, and dropping it quietly would
     * publish a document that claims the application answers less than it does.
     *
     * @throws IllegalArgumentException if a route's path contains a wildcard
     */
    public static Json.JsonValue document(String title, String version, List<Route> routes) {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(version, "version");
        Json.JsonObject paths = Json.obj();
        for (Route route : Objects.requireNonNull(routes, "routes")) {
            String path = route.path();
            if (path.contains("*")) {
                throw new IllegalArgumentException(
                        "A wildcard route has no OpenAPI path template: " + route.method() + " "
                                + path + ". Leave it out of the list passed here.");
            }
            Json.JsonObject operations = paths.has(path) ? paths.getObject(path) : Json.obj();
            paths.put(path, operations.put(route.method().toLowerCase(Locale.ROOT),
                    operation(path, route.description())));
        }
        return Json.obj()
                .put("openapi", OPENAPI_VERSION)
                .put("info", Json.obj().put("title", title).put("version", version))
                .put("paths", paths);
    }

    /** One operation: its summary, its path parameters, and the 200 every path answers with. */
    private static Json.JsonObject operation(String path, String description) {
        Json.JsonObject operation = Json.obj();
        if (!description.isEmpty()) {
            operation.put("summary", description);
        }
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
