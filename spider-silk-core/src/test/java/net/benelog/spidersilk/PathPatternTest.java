package net.benelog.spidersilk;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


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
    void bindsANamedTailWithItsSlashes() {
        PathPattern pattern = new PathPattern("/files/{path*}");
        assertThat(pattern.match(PathPattern.split("/files/a.txt")))
                .isEqualTo(Map.of("path", "a.txt"));
        assertThat(pattern.match(PathPattern.split("/files/docs/2026/a.txt")))
                .isEqualTo(Map.of("path", "docs/2026/a.txt"));
    }

    /** The same set of paths a bare "*" matches, the empty tail included. */
    @Test
    void bindsAnEmptyTailToAnEmptyString() {
        PathPattern pattern = new PathPattern("/files/{path*}");
        assertThat(pattern.match(PathPattern.split("/files"))).isEqualTo(Map.of("path", ""));
        assertThat(pattern.match(PathPattern.split("/files/"))).isEqualTo(Map.of("path", ""));
        assertThat(pattern.match(PathPattern.split("/other"))).isNull();
    }

    @Test
    void bindsANamedTailAlongsideTheVariablesBeforeIt() {
        PathPattern pattern = new PathPattern("/decks/{deckId}/files/{path*}");
        assertThat(pattern.match(PathPattern.split("/decks/3/files/a/b.txt")))
                .isEqualTo(Map.of("deckId", "3", "path", "a/b.txt"));
    }

    @Test
    void rejectsANamedTailBeforeTheLastSegment() {
        assertThatThrownBy(() -> new PathPattern("/files/{path*}/edit"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("{path*}");
    }

    /** A named tail matches what "*" matches, so the router sees one shape. */
    @Test
    void aNamedTailCanonicalizesToAWildcard() {
        assertThat(new PathPattern("/files/{path*}").canonicalForm()).isEqualTo("/files/*");
        assertThat(new PathPattern("/{path*}").canonicalForm()).isEqualTo("/*");
    }

    /** The index reads the first segment, and a tail is the last one. */
    @Test
    void aNamedTailLeavesTheFirstSegmentIndexable() {
        assertThat(new PathPattern("/files/{path*}").literalFirstSegment()).isEqualTo("files");
        assertThat(new PathPattern("/{path*}").literalFirstSegment()).isNull();
    }

    @Test
    void treatsSegmentWithDotAsLiteral() {
        PathPattern pattern = new PathPattern("/decks/{deckId}/export.csv");
        assertThat(pattern.match(PathPattern.split("/decks/1/export.csv")))
                .isEqualTo(Map.of("deckId", "1"));
    }
}
