package spidersilk;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@code app.routes()}: the routing table read back as data, without reflection. */
class RouteIntrospectionTest {

    private final Handler noop = req -> null;

    @Test
    void listsRoutesInRegistrationOrder() {
        App app = new App()
                .get("/{page}", noop)
                .get("/decks", noop)
                .post("/decks", noop);

        assertEquals(List.of(
                new Route("GET", "/{page}"),
                new Route("GET", "/decks"),
                new Route("POST", "/decks")), app.routes());
    }

    /** The order the router resolves ties in is the order routes() reports. */
    @Test
    void theOrderSurvivesTheFirstSegmentIndex() {
        App app = new App()
                .get("/study/today", noop)
                .get("/{page}", noop)
                .get("/study/{mode}", noop);

        assertEquals(List.of("/study/today", "/{page}", "/study/{mode}"),
                app.routes().stream().map(Route::path).toList());
    }

    @Test
    void groupPrefixesAreAlreadyResolved() {
        App app = new App().path("/api", api -> api
                .get("/decks", noop)
                .path("/decks/{deckId}", deck -> deck.get("/cards", noop)));

        assertEquals(List.of("/api/decks", "/api/decks/{deckId}/cards"),
                app.routes().stream().map(Route::path).toList());
    }

    /** Only what was registered: the servlet's automatic HEAD and OPTIONS are not routes. */
    @Test
    void automaticHeadAndOptionsAnswersAreNotListed() {
        App app = new App().get("/decks", noop);

        assertEquals(List.of(new Route("GET", "/decks")), app.routes());
    }

    @Test
    void theSnapshotIsImmutableAndTakenPerCall() {
        App app = new App().get("/decks", noop);
        List<Route> before = app.routes();

        app.get("/stats", noop);

        assertEquals(1, before.size());
        assertEquals(2, app.routes().size());
        assertThrows(UnsupportedOperationException.class,
                () -> before.add(new Route("GET", "/nowhere")));
    }
}
