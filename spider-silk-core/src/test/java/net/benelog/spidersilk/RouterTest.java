package net.benelog.spidersilk;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouterTest {

    private final Handler noop = req -> null;

    @Test
    void findsHandlerByMethodAndPath() {
        Router router = new Router();
        router.add("GET", "/decks/{deckId}", noop);
        router.add("POST", "/decks", noop);

        Router.Match match = router.find("GET", "/decks/7");
        assertThat(match).isNotNull();
        assertThat(match.pathParams().get("deckId")).isEqualTo("7");

        assertThat(router.find("GET", "/decks")).isNull();
        assertThat(router.find("POST", "/decks")).isNotNull();
    }

    @Test
    void firstMatchingRouteInRegistrationOrderWins() {
        Router router = new Router();
        Handler first = req -> null;
        router.add("GET", "/study/today", first);
        router.add("GET", "/study/{mode}", noop);

        assertThat(router.find("GET", "/study/today").handler()).isEqualTo(first);
    }

    @Test
    void reportsAllowedMethodsWhenOnlyOtherMethodsMatch() {
        Router router = new Router();
        router.add("POST", "/decks", noop);
        router.add("PUT", "/decks", noop);

        assertThat(router.find("GET", "/decks")).isNull();
        assertThat(router.allowedMethods("/decks")).isEqualTo(Set.of("POST", "PUT"));
        assertThat(router.allowedMethods("/nowhere")).isEqualTo(Set.of());
    }

    /** The index groups by first segment; a variable pattern still has to be considered. */
    @Test
    void aVariableFirstSegmentIsWeighedAgainstLiteralOnes() {
        Router router = new Router();
        Handler byId = req -> null;
        router.add("GET", "/decks", noop);
        router.add("GET", "/{page}", byId);

        assertThat(router.find("GET", "/about").handler()).isEqualTo(byId);
        assertThat(router.find("GET", "/decks")).isNotNull();
    }

    /** Registration order decides even when the two patterns land in different buckets. */
    @Test
    void aVariablePatternRegisteredFirstStillWins() {
        Router router = new Router();
        Handler first = req -> null;
        router.add("GET", "/{page}", first);
        router.add("GET", "/decks", noop);

        assertThat(router.find("GET", "/decks").handler()).isEqualTo(first);
    }

    @Test
    void theRootIsItsOwnBucket() {
        Router router = new Router();
        Handler root = req -> null;
        router.add("GET", "/", root);
        router.add("GET", "/decks", noop);

        assertThat(router.find("GET", "/").handler()).isEqualTo(root);
        assertThat(router.find("GET", "/nowhere")).isNull();
    }

    /** A pattern that matches the rest of the path can start anywhere. */
    @Test
    void aWildcardPatternMatchesAnyFirstSegment() {
        Router router = new Router();
        Handler everything = req -> null;
        router.add("GET", "/*", everything);

        assertThat(router.find("GET", "/").handler()).isEqualTo(everything);
        assertThat(router.find("GET", "/anything/at/all").handler()).isEqualTo(everything);
    }

    @Test
    void allowedMethodsSeesVariablePatternsToo() {
        Router router = new Router();
        router.add("POST", "/decks", noop);
        router.add("DELETE", "/{page}", noop);

        assertThat(router.allowedMethods("/decks")).isEqualTo(Set.of("POST", "DELETE"));
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
