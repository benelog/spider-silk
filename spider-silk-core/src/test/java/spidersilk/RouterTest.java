package spidersilk;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RouterTest {

    private final Handler noop = ctx -> {
    };

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
        Handler first = ctx -> {
        };
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
}
