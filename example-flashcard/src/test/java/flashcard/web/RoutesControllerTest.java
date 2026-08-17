package flashcard.web;

import java.util.List;

import org.junit.jupiter.api.Test;

import spidersilk.Route;
import spidersilk.json.Json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The OpenAPI export is a pure function of app.routes(), so it needs no server. */
class RoutesControllerTest {

    private final List<Route> routes = List.of(
            new Route("GET", "/"),
            new Route("GET", "/api/decks"),
            new Route("POST", "/api/decks"),
            new Route("GET", "/api/decks/{deckId}/cards"),
            new Route("GET", "/api/*"));

    @Test
    void mapsEveryMethodUnderItsPathTemplate() {
        Json.JsonObject document = RoutesController.openApi(routes).asObject();
        Json.JsonObject decks = document.getObject("paths").getObject("/api/decks");

        assertEquals("3.1.0", document.getString("openapi"));
        assertTrue(decks.has("get"));
        assertTrue(decks.has("post"));
        assertEquals("OK", decks.getObject("get")
                .getObject("responses").getObject("200").getString("description"));
    }

    /** "{deckId}" is OpenAPI's own syntax, so the parameter falls out of the pattern. */
    @Test
    void declaresEveryPathVariableAsAParameter() {
        Json.JsonObject operation = RoutesController.openApi(routes).asObject()
                .getObject("paths").getObject("/api/decks/{deckId}/cards").getObject("get");

        Json.JsonObject parameter = operation.getArray("parameters").get(0).asObject();
        assertEquals("deckId", parameter.getString("name"));
        assertEquals("path", parameter.getString("in"));
        assertTrue(parameter.getBoolean("required"));
    }

    @Test
    void leavesOutHtmlRoutesAndWildcards() {
        Json.JsonObject paths = RoutesController.openApi(routes).asObject().getObject("paths");

        assertFalse(paths.has("/"));
        assertFalse(paths.has("/api/*"));
        assertFalse(paths.getObject("/api/decks").getObject("get").has("parameters"));
    }
}
