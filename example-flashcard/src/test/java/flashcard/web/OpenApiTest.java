package flashcard.web;

import java.util.List;

import org.junit.jupiter.api.Test;

import spidersilk.Route;
import spidersilk.json.Json;

import static org.assertj.core.api.Assertions.assertThat;


/** The OpenAPI export is a pure function of app.routes(), so it needs no server. */
class OpenApiTest {

    private final List<Route> routes = List.of(
            new Route("GET", "/"),
            new Route("GET", "/api/decks"),
            new Route("POST", "/api/decks"),
            new Route("GET", "/api/decks/{deckId}/cards"),
            new Route("GET", "/api/*"));

    @Test
    void mapsEveryMethodUnderItsPathTemplate() {
        Json.JsonObject document = OpenApi.document(routes).asObject();
        Json.JsonObject decks = document.getObject("paths").getObject("/api/decks");

        assertThat(document.getString("openapi")).isEqualTo("3.1.0");
        assertThat(decks.has("get")).isTrue();
        assertThat(decks.has("post")).isTrue();
        assertThat(decks.getObject("get").getObject("responses").getObject("200").getString("description"))
                .isEqualTo("OK");
    }

    /** "{deckId}" is OpenAPI's own syntax, so the parameter falls out of the pattern. */
    @Test
    void declaresEveryPathVariableAsAParameter() {
        Json.JsonObject operation = OpenApi.document(routes).asObject()
                .getObject("paths").getObject("/api/decks/{deckId}/cards").getObject("get");

        Json.JsonObject parameter = operation.getArray("parameters").get(0).asObject();
        assertThat(parameter.getString("name")).isEqualTo("deckId");
        assertThat(parameter.getString("in")).isEqualTo("path");
        assertThat(parameter.getBoolean("required")).isTrue();
    }

    @Test
    void leavesOutHtmlRoutesAndWildcards() {
        Json.JsonObject paths = OpenApi.document(routes).asObject().getObject("paths");

        assertThat(paths.has("/")).isFalse();
        assertThat(paths.has("/api/*")).isFalse();
        assertThat(paths.getObject("/api/decks").getObject("get").has("parameters")).isFalse();
    }
}
