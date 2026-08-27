package net.benelog.spidersilk;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class PathPatternTest {

    @Test
    void matchesRootPath() {
        PathPattern pattern = new PathPattern("/");
        assertThat(pattern.match(PathPattern.split("/"))).isEqualTo(Map.of());
    }

    @Test
    void matchesLiteralPath() {
        PathPattern pattern = new PathPattern("/stats");
        assertThat(pattern.match(PathPattern.split("/stats"))).isEqualTo(Map.of());
        assertThat(pattern.match(PathPattern.split("/decks"))).isNull();
    }

    @Test
    void extractsPathVariables() {
        PathPattern pattern = new PathPattern("/decks/{deckId}/cards/{cardId}/edit");
        Map<String, String> params = pattern.match(PathPattern.split("/decks/3/cards/17/edit"));
        assertThat(params.get("deckId")).isEqualTo("3");
        assertThat(params.get("cardId")).isEqualTo("17");
    }

    @Test
    void rejectsDifferentSegmentCount() {
        PathPattern pattern = new PathPattern("/decks/{deckId}");
        assertThat(pattern.match(PathPattern.split("/decks"))).isNull();
        assertThat(pattern.match(PathPattern.split("/decks/1/cards"))).isNull();
    }

    @Test
    void ignoresTrailingSlash() {
        PathPattern pattern = new PathPattern("/decks/{deckId}");
        assertThat(pattern.match(PathPattern.split("/decks/5/"))).isEqualTo(Map.of("deckId", "5"));
    }

    @Test
    void treatsSegmentWithDotAsLiteral() {
        PathPattern pattern = new PathPattern("/decks/{deckId}/export.csv");
        assertThat(pattern.match(PathPattern.split("/decks/1/export.csv")))
                .isEqualTo(Map.of("deckId", "1"));
    }
}
