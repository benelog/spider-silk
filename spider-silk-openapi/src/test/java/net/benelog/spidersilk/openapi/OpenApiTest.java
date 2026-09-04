package net.benelog.spidersilk.openapi;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.App;
import net.benelog.spidersilk.Route;
import net.benelog.spidersilk.WebResponse;
import net.benelog.spidersilk.json.Json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The export is a pure function of app.routes(), so it needs no server. */
class OpenApiTest {

    private final List<Route> routes = List.of(
            new Route("GET", "/api/decks"),
            new Route("POST", "/api/decks"),
            new Route("GET", "/api/decks/{deckId}/cards"));

    @Test
    void mapsEveryMethodUnderItsPathTemplate() {
        Json.JsonObject document = OpenApi.document("Flashcard API", "1.0.0", routes).asObject();
        Json.JsonObject decks = document.getObject("paths").getObject("/api/decks");

        assertThat(document.getString("openapi")).isEqualTo("3.1.0");
        assertThat(decks.has("get")).isTrue();
        assertThat(decks.has("post")).isTrue();
        assertThat(decks.getObject("get").getObject("responses").getObject("200").getString("description"))
                .isEqualTo("OK");
    }

    /** Both are required by the spec, so both are arguments rather than defaults. */
    @Test
    void carriesTheTitleAndVersionItWasGiven() {
        Json.JsonObject info = OpenApi.document("Flashcard API", "1.0.0", routes)
                .asObject().getObject("info");

        assertThat(info.getString("title")).isEqualTo("Flashcard API");
        assertThat(info.getString("version")).isEqualTo("1.0.0");
    }

    /** "{deckId}" is OpenAPI's own syntax, so the parameter falls out of the pattern. */
    @Test
    void declaresEveryPathVariableAsAParameter() {
        Json.JsonObject operation = OpenApi.document("Flashcard API", "1.0.0", routes).asObject()
                .getObject("paths").getObject("/api/decks/{deckId}/cards").getObject("get");

        Json.JsonObject parameter = operation.getArray("parameters").get(0).asObject();
        assertThat(parameter.getString("name")).isEqualTo("deckId");
        assertThat(parameter.getString("in")).isEqualTo("path");
        assertThat(parameter.getBoolean("required")).isTrue();
        assertThat(parameter.getObject("schema").getString("type")).isEqualTo("string");
    }

    /** The one thing a method and a path do not imply, carried through from registration. */
    @Test
    void carriesARouteDescriptionAsTheOperationSummary() {
        List<Route> described = List.of(
                new Route("GET", "/api/decks", "List every deck"),
                new Route("POST", "/api/decks"));
        Json.JsonObject decks = OpenApi.document("Flashcard API", "1.0.0", described)
                .asObject().getObject("paths").getObject("/api/decks");

        assertThat(decks.getObject("get").getString("summary")).isEqualTo("List every deck");
        assertThat(decks.getObject("post").has("summary")).isFalse();
    }

    @Test
    void leavesParametersOutOfAPathThatHasNone() {
        Json.JsonObject paths = OpenApi.document("Flashcard API", "1.0.0", routes).asObject()
                .getObject("paths");

        assertThat(paths.getObject("/api/decks").getObject("get").has("parameters")).isFalse();
    }

    /** A bare wildcard has no path template, and a document quietly missing it would lie. */
    @Test
    void refusesAWildcardRouteInsteadOfDroppingIt() {
        List<Route> withWildcard = List.of(new Route("GET", "/api/*"));

        assertThatThrownBy(() -> OpenApi.document("Flashcard API", "1.0.0", withWildcard))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GET /api/*");
    }

    /** A named tail has a template: the star is core's syntax, the variable is OpenAPI's. */
    @Test
    void writesANamedTailAsAPathVariable() {
        List<Route> withTail = List.of(new Route("GET", "/api/files/{path*}"));
        Json.JsonObject paths = OpenApi.document("Flashcard API", "1.0.0", withTail)
                .asObject().getObject("paths");

        assertThat(paths.has("/api/files/{path*}")).isFalse();
        Json.JsonObject parameter = paths.getObject("/api/files/{path}").getObject("get")
                .getArray("parameters").get(0).asObject();
        assertThat(parameter.getString("name")).isEqualTo("path");
        assertThat(parameter.getString("in")).isEqualTo("path");
        assertThat(parameter.getBoolean("required")).isTrue();
        assertThat(parameter.getString("description"))
                .isEqualTo("The rest of the path, slashes included.");
    }

    /** The tail is one variable among the others, and the others say nothing new. */
    @Test
    void declaresTheVariablesBeforeATailToo() {
        List<Route> withTail = List.of(new Route("GET", "/api/decks/{deckId}/files/{path*}"));
        Json.JsonArray parameters = OpenApi.document("Flashcard API", "1.0.0", withTail).asObject()
                .getObject("paths").getObject("/api/decks/{deckId}/files/{path}")
                .getObject("get").getArray("parameters");

        assertThat(parameters.size()).isEqualTo(2);
        assertThat(parameters.get(0).asObject().getString("name")).isEqualTo("deckId");
        assertThat(parameters.get(0).asObject().has("description")).isFalse();
        assertThat(parameters.get(1).asObject().getString("name")).isEqualTo("path");
    }

    /** Which routes go in is the application's call, made before the list gets here. */
    @Test
    void documentsTheSelectionItWasGivenAndNothingElse() {
        App app = new App();
        app.get("/", req -> WebResponse.text("home"));
        app.get("/api/decks", req -> WebResponse.text("decks"));

        List<Route> api = app.routes().stream()
                .filter(route -> route.path().startsWith("/api"))
                .toList();
        Json.JsonObject paths = OpenApi.document("Flashcard API", "1.0.0", api)
                .asObject().getObject("paths");

        assertThat(paths.has("/api/decks")).isTrue();
        assertThat(paths.has("/")).isFalse();
    }
}
