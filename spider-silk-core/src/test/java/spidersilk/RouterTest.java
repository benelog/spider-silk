package spidersilk;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RouterTest {

    private final Handler noop = req -> null;

    @Test
    void findsHandlerByMethodAndPath() {
        Router router = new Router();
        router.add("GET", "/decks/{deckId}", noop);
        router.add("POST", "/decks", noop);

        Router.Match match = router.find("GET", "/decks/7");
        assertNotNull(match);
        assertEquals("7", match.pathParams().get("deckId"));

        assertNull(router.find("GET", "/decks"));
        assertNotNull(router.find("POST", "/decks"));
    }

    @Test
    void firstMatchingRouteInRegistrationOrderWins() {
        Router router = new Router();
        Handler first = req -> null;
        router.add("GET", "/study/today", first);
        router.add("GET", "/study/{mode}", noop);

        assertEquals(first, router.find("GET", "/study/today").handler());
    }

    @Test
    void reportsAllowedMethodsWhenOnlyOtherMethodsMatch() {
        Router router = new Router();
        router.add("POST", "/decks", noop);
        router.add("PUT", "/decks", noop);

        assertNull(router.find("GET", "/decks"));
        assertEquals(Set.of("POST", "PUT"), router.allowedMethods("/decks"));
        assertEquals(Set.of(), router.allowedMethods("/nowhere"));
    }

    /** The index groups by first segment; a variable pattern still has to be considered. */
    @Test
    void aVariableFirstSegmentIsWeighedAgainstLiteralOnes() {
        Router router = new Router();
        Handler byId = req -> null;
        router.add("GET", "/decks", noop);
        router.add("GET", "/{page}", byId);

        assertEquals(byId, router.find("GET", "/about").handler());
        assertNotNull(router.find("GET", "/decks"));
    }

    /** Registration order decides even when the two patterns land in different buckets. */
    @Test
    void aVariablePatternRegisteredFirstStillWins() {
        Router router = new Router();
        Handler first = req -> null;
        router.add("GET", "/{page}", first);
        router.add("GET", "/decks", noop);

        assertEquals(first, router.find("GET", "/decks").handler());
    }

    @Test
    void theRootIsItsOwnBucket() {
        Router router = new Router();
        Handler root = req -> null;
        router.add("GET", "/", root);
        router.add("GET", "/decks", noop);

        assertEquals(root, router.find("GET", "/").handler());
        assertNull(router.find("GET", "/nowhere"));
    }

    /** A pattern that matches the rest of the path can start anywhere. */
    @Test
    void aWildcardPatternMatchesAnyFirstSegment() {
        Router router = new Router();
        Handler everything = req -> null;
        router.add("GET", "/*", everything);

        assertEquals(everything, router.find("GET", "/").handler());
        assertEquals(everything, router.find("GET", "/anything/at/all").handler());
    }

    @Test
    void allowedMethodsSeesVariablePatternsToo() {
        Router router = new Router();
        router.add("POST", "/decks", noop);
        router.add("DELETE", "/{page}", noop);

        assertEquals(Set.of("POST", "DELETE"), router.allowedMethods("/decks"));
    }
}
