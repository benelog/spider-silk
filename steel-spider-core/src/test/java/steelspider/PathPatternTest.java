package steelspider;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PathPatternTest {

    @Test
    void matchesRootPath() {
        PathPattern pattern = new PathPattern("/");
        assertEquals(Map.of(), pattern.match(PathPattern.split("/")));
    }

    @Test
    void matchesLiteralPath() {
        PathPattern pattern = new PathPattern("/stats");
        assertEquals(Map.of(), pattern.match(PathPattern.split("/stats")));
        assertNull(pattern.match(PathPattern.split("/decks")));
    }

    @Test
    void extractsPathVariables() {
        PathPattern pattern = new PathPattern("/decks/{deckId}/cards/{cardId}/edit");
        Map<String, String> params = pattern.match(PathPattern.split("/decks/3/cards/17/edit"));
        assertEquals("3", params.get("deckId"));
        assertEquals("17", params.get("cardId"));
    }

    @Test
    void rejectsDifferentSegmentCount() {
        PathPattern pattern = new PathPattern("/decks/{deckId}");
        assertNull(pattern.match(PathPattern.split("/decks")));
        assertNull(pattern.match(PathPattern.split("/decks/1/cards")));
    }

    @Test
    void ignoresTrailingSlash() {
        PathPattern pattern = new PathPattern("/decks/{deckId}");
        assertEquals(Map.of("deckId", "5"), pattern.match(PathPattern.split("/decks/5/")));
    }

    @Test
    void treatsSegmentWithDotAsLiteral() {
        PathPattern pattern = new PathPattern("/decks/{deckId}/export.csv");
        assertEquals(Map.of("deckId", "1"),
                pattern.match(PathPattern.split("/decks/1/export.csv")));
    }
}
