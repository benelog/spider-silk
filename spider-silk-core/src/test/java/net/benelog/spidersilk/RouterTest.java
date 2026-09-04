package net.benelog.spidersilk;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouterTest {

    private final Handler noop = req -> null;

    /** The router is asked in segments, which the servlet splits once per request. */
    private static String[] path(String path) {
        return PathPattern.split(path);
    }

    @Test
    void findsHandlerByMethodAndPath() {
        Router router = new Router();
        router.add("GET", "/decks/{deckId}", noop);
        router.add("POST", "/decks", noop);

        Router.Match match = router.find("GET", path("/decks/7"));
        assertThat(match).isNotNull();
        assertThat(match.pathParams().get("deckId")).isEqualTo("7");

        assertThat(router.find("GET", path("/decks"))).isNull();
        assertThat(router.find("POST", path("/decks"))).isNotNull();
    }

    @Test
    void firstMatchingRouteInRegistrationOrderWins() {
        Router router = new Router();
        Handler first = req -> null;
        router.add("GET", "/study/today", first);
        router.add("GET", "/study/{mode}", noop);

        assertThat(router.find("GET", path("/study/today")).handler()).isEqualTo(first);
    }

    @Test
    void reportsAllowedMethodsWhenOnlyOtherMethodsMatch() {
        Router router = new Router();
        router.add("POST", "/decks", noop);
        router.add("PUT", "/decks", noop);

        assertThat(router.find("GET", path("/decks"))).isNull();
        assertThat(router.allowedMethods(path("/decks"))).isEqualTo(Set.of("POST", "PUT"));
        assertThat(router.allowedMethods(path("/nowhere"))).isEqualTo(Set.of());
    }

    /** The index groups by first segment; a variable pattern still has to be considered. */
    @Test
    void aVariableFirstSegmentIsWeighedAgainstLiteralOnes() {
        Router router = new Router();
        Handler byId = req -> null;
        router.add("GET", "/decks", noop);
        router.add("GET", "/{page}", byId);

        assertThat(router.find("GET", path("/about")).handler()).isEqualTo(byId);
        assertThat(router.find("GET", path("/decks"))).isNotNull();
    }

    /** Registration order decides even when the two patterns land in different buckets. */
    @Test
    void aVariablePatternRegisteredFirstStillWins() {
        Router router = new Router();
        Handler first = req -> null;
        router.add("GET", "/{page}", first);
        router.add("GET", "/decks", noop);

        assertThat(router.find("GET", path("/decks")).handler()).isEqualTo(first);
    }

    @Test
    void theRootIsItsOwnBucket() {
        Router router = new Router();
        Handler root = req -> null;
        router.add("GET", "/", root);
        router.add("GET", "/decks", noop);

        assertThat(router.find("GET", path("/")).handler()).isEqualTo(root);
        assertThat(router.find("GET", path("/nowhere"))).isNull();
    }

    /** A pattern that matches the rest of the path can start anywhere. */
    @Test
    void aWildcardPatternMatchesAnyFirstSegment() {
        Router router = new Router();
        Handler everything = req -> null;
        router.add("GET", "/*", everything);

        assertThat(router.find("GET", path("/")).handler()).isEqualTo(everything);
        assertThat(router.find("GET", path("/anything/at/all")).handler()).isEqualTo(everything);
    }

    /** The index reads the first segment, which a tail at the end does not touch. */
    @Test
    void aNamedTailIsIndexedByItsFirstSegment() {
        Router router = new Router();
        Handler files = req -> null;
        router.add("GET", "/files/{path*}", files);
        router.add("GET", "/decks", noop);

        Router.Match match = router.find("GET", path("/files/docs/a.txt"));
        assertThat(match.handler()).isEqualTo(files);
        assertThat(match.pathParams().get("path")).isEqualTo("docs/a.txt");

        assertThat(router.find("GET", path("/files")).pathParams().get("path")).isEmpty();
        assertThat(router.find("GET", path("/elsewhere/a.txt"))).isNull();
    }

    /** A tail as the whole pattern can start anywhere, the way a bare "*" does. */
    @Test
    void aNamedTailAtTheRootMatchesAnyFirstSegment() {
        Router router = new Router();
        Handler everything = req -> null;
        router.add("GET", "/{path*}", everything);

        assertThat(router.find("GET", path("/")).pathParams().get("path")).isEmpty();
        assertThat(router.find("GET", path("/anything/at/all")).pathParams().get("path"))
                .isEqualTo("anything/at/all");
    }

    /** A literal route registered first still wins over a tail that also covers it. */
    @Test
    void registrationOrderStillDecidesAgainstANamedTail() {
        Router router = new Router();
        Handler index = req -> null;
        router.add("GET", "/files/index.html", index);
        router.add("GET", "/files/{path*}", noop);

        assertThat(router.find("GET", path("/files/index.html")).handler()).isEqualTo(index);
    }

    /** Both spellings match the same requests, so the second could never run. */
    @Test
    void aNamedTailDuplicatesABareWildcardOverTheSamePrefix() {
        Router router = new Router();
        router.add("GET", "/files/*", noop);

        assertThatThrownBy(() -> router.add("GET", "/files/{path*}", noop))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GET /files/{path*} is already registered as /files/*,"
                        + " which matches the same requests");
    }

    @Test
    void allowedMethodsSeesVariablePatternsToo() {
        Router router = new Router();
        router.add("POST", "/decks", noop);
        router.add("DELETE", "/{page}", noop);

        assertThat(router.allowedMethods(path("/decks"))).isEqualTo(Set.of("POST", "DELETE"));
    }

    /** A second route matching the same requests could never run, so it fails right away. */
    @Test
    void rejectsASecondRouteMatchingTheSameRequests() {
        Router router = new Router();
        router.add("GET", "/decks", noop);

        assertThatThrownBy(() -> router.add("GET", "/decks", noop))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GET /decks is already registered");
    }

    /** The same shape spelled differently — renamed variable, stray slashes — is still dead. */
    @Test
    void aRenamedVariableOrAStraySlashIsStillADuplicate() {
        Router router = new Router();
        router.add("GET", "/decks/{deckId}", noop);

        assertThatThrownBy(() -> router.add("GET", "decks/{id}/", noop))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GET decks/{id}/ is already registered as /decks/{deckId},"
                        + " which matches the same requests");
    }

    @Test
    void anotherMethodOrAMerelyOverlappingPatternIsNotADuplicate() {
        Router router = new Router();
        router.add("GET", "/decks", noop);
        router.add("POST", "/decks", noop);
        router.add("GET", "/decks/{deckId}", noop);
        router.add("GET", "/decks/new", noop);

        assertThat(router.routes()).hasSize(4);
    }
}
