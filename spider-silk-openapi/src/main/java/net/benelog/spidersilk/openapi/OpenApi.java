package net.benelog.spidersilk.openapi;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import net.benelog.spidersilk.Route;
import net.benelog.spidersilk.json.Json;
import net.benelog.spidersilk.json.JsonArray;
import net.benelog.spidersilk.json.JsonObject;
import net.benelog.spidersilk.json.JsonValue;

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
 * {@code {deckId}} is OpenAPI's path template verbatim, so there is almost
 * nothing to translate and nothing at all to reflect over. The one translation
 * is the star of a named tail, which a template has no room for.
 * The module exists because that reading
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

    /** What a named tail is, said in the document, since the star cannot be. */
    private static final String TAIL_DESCRIPTION = "The rest of the path, slashes included.";

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
     *
     * <p>A named tail, {@code /files/{path*}}, comes out as
     * {@code /files/{path}} with the star dropped, and its parameter carries a
     * description saying it is the rest of the path. OpenAPI has no wildcard
     * path template, so the star is core's syntax and not a template's, and the
     * variable is the one thing there is to say about the route.
     * A route whose pattern contains a bare {@code *} throws:
     * that wildcard has no OpenAPI equivalent and no name to give it, and
     * dropping it quietly would publish a document that claims the application
     * answers less than it does.
     *
     * <p>Two routes whose templates OpenAPI would read as one path throw as
     * well, naming both. OpenAPI identifies a templated path by its hierarchy
     * alone, so {@code /decks/{deckId}} and {@code /decks/{id}} are the same
     * path and MUST NOT both appear, and {@code /files/{name}} and
     * {@code /files/{name*}}, which the router tells apart, have one template
     * between them, under which one operation would quietly replace the other.
     *
     * @throws IllegalArgumentException if a route's path contains a bare
     *         wildcard, or two routes come out as one OpenAPI path under
     *         different variable names or router shapes
     */
    public static JsonValue document(String title, String version, List<Route> routes) {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(version, "version");
        JsonObject paths = Json.object();
        Map<String, Route> firstByIdentity = new HashMap<>();
        for (Route route : Objects.requireNonNull(routes, "routes")) {
            String pattern = normalized(route.path());
            String path = template(pattern);
            if (path.contains("*")) {
                throw new IllegalArgumentException(
                        "A wildcard route has no OpenAPI path template: " + route.method() + " "
                                + route.path() + ". Name the tail as {name*}, or leave the route out of"
                                + " the list passed here.");
            }
            Route first = firstByIdentity.putIfAbsent(unnamed(path), route);
            if (first != null) {
                String firstPattern = normalized(first.path());
                if (!firstPattern.equals(pattern)) {
                    throw new IllegalArgumentException("Two routes come out as one OpenAPI path: "
                            + first.method() + " " + first.path() + " and " + route.method() + " "
                            + route.path() + ". OpenAPI tells templated paths apart by their segments"
                            + " alone, so give the variables one name, or leave one route out of the"
                            + " list passed here.");
                }
            }
            JsonObject operations = paths.has(path) ? paths.getObject(path) : Json.object();
            paths.put(path, operations.put(route.method().toLowerCase(Locale.ROOT),
                    operation(pattern, route.description())));
        }
        return Json.object()
                .put("openapi", OPENAPI_VERSION)
                .put("info", Json.object().put("title", title).put("version", version))
                .put("paths", paths);
    }

    /**
     * The pattern as OpenAPI spells a path: a leading {@code /}, and no trailing
     * one except on the root. The router reads {@code "decks"} and
     * {@code "/decks/"} as {@code "/decks"}, and {@link Route#path()} keeps the
     * spelling it was registered under, so without this one path could come out
     * as a key OpenAPI refuses, or as two items.
     */
    private static String normalized(String pattern) {
        String path = pattern.startsWith("/") ? pattern.substring(1) : pattern;
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return "/" + path;
    }

    /**
     * The OpenAPI path template of a route's pattern, which differs from the
     * pattern only in a named tail: {@code /files/{path*}} is written
     * {@code /files/{path}} here, since a template has no star to carry.
     */
    private static String template(String pattern) {
        return isTail(lastSegment(pattern))
                ? pattern.substring(0, pattern.length() - 2) + "}"
                : pattern;
    }

    /**
     * The path with every variable's name left out, which is how OpenAPI tells
     * two templated paths apart: {@code /decks/{deckId}} and {@code /decks/{id}}
     * are both {@code /decks/{}}.
     */
    private static String unnamed(String path) {
        return path.replaceAll("\\{[^/}]*}", "{}");
    }

    private static String lastSegment(String pattern) {
        return pattern.substring(pattern.lastIndexOf('/') + 1);
    }

    /** A "{path*}" segment: a variable that matches the rest of the path. */
    private static boolean isTail(String segment) {
        return segment.length() >= 4 && segment.startsWith("{") && segment.endsWith("*}");
    }

    /** One operation: its summary, its path parameters, and the 200 every path answers with. */
    private static JsonObject operation(String path, String description) {
        JsonObject operation = Json.object();
        if (!description.isEmpty()) {
            operation.put("summary", description);
        }
        JsonArray parameters = pathParameters(path);
        if (parameters.size() > 0) {
            operation.put("parameters", parameters);
        }
        return operation.put("responses",
                Json.object().put("200", Json.object().put("description", "OK")));
    }

    /** Every {@code {name}} in the pattern, which OpenAPI requires to be declared. */
    private static JsonArray pathParameters(String path) {
        JsonArray parameters = Json.array();
        for (String segment : path.split("/", -1)) {
            if (isTail(segment)) {
                parameters.add(parameter(segment.substring(1, segment.length() - 2))
                        .put("description", TAIL_DESCRIPTION));
            } else if (segment.length() >= 2 && segment.startsWith("{") && segment.endsWith("}")) {
                parameters.add(parameter(segment.substring(1, segment.length() - 1)));
            }
        }
        return parameters;
    }

    /** One required path parameter of type string, which is all a pattern says. */
    private static JsonObject parameter(String name) {
        return Json.object()
                .put("name", name)
                .put("in", "path")
                .put("required", true)
                .put("schema", Json.object().put("type", "string"));
    }
}
