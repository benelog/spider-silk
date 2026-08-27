package flashcard.web;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import spidersilk.JteTemplates;
import spidersilk.Route;

import static org.assertj.core.api.Assertions.assertThat;


/** The overview page renders straight from app.routes(), so a list is the whole model. */
class RoutesPageTest {

    @Test
    void rendersARowPerRoute() {
        StringWriter html = new StringWriter();

        new JteTemplates("jte").render("routes", Map.of("routes", List.of(
                new Route("GET", "/api/decks", "List every deck"),
                new Route("POST", "/api/decks/{deckId}/cards"))), html);

        String page = html.toString();
        assertThat(page).contains("2 registered");
        assertThat(page).contains(">GET</span>");
        assertThat(page).contains("<td>/api/decks</td>");
        assertThat(page).contains("List every deck");
        assertThat(page).contains(">POST</span>");
        assertThat(page).contains("<td>/api/decks/{deckId}/cards</td>");
    }
}
