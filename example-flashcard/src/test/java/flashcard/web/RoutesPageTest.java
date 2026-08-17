package flashcard.web;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import spidersilk.JteTemplates;
import spidersilk.Route;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The overview page renders straight from app.routes(), so a list is the whole model. */
class RoutesPageTest {

    @Test
    void rendersARowPerRoute() {
        StringWriter html = new StringWriter();

        new JteTemplates("jte").render("routes.jte", Map.of("routes", List.of(
                new Route("GET", "/api/decks"),
                new Route("POST", "/api/decks/{deckId}/cards"))), html);

        String page = html.toString();
        assertTrue(page.contains("2 registered"), page);
        assertTrue(page.contains(">GET</span>"), page);
        assertTrue(page.contains("<td>/api/decks</td>"), page);
        assertTrue(page.contains(">POST</span>"), page);
        assertTrue(page.contains("<td>/api/decks/{deckId}/cards</td>"), page);
    }
}
