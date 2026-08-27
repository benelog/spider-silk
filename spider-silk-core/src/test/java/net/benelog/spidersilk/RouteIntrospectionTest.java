package net.benelog.spidersilk;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code app.routes()}: the routing table read back as data, without reflection. */
class RouteIntrospectionTest {

    private final Handler noop = req -> null;

    @Test
    void listsRoutesInRegistrationOrder() {
        App app = new App()
                .get("/{page}", noop)
                .get("/decks", noop)
                .post("/decks", noop);

        assertThat(app.routes()).isEqualTo(List.of(
                new Route("GET", "/{page}"),
                new Route("GET", "/decks"),
                new Route("POST", "/decks")));
    }

    /** The order the router resolves ties in is the order routes() reports. */
    @Test
    void theOrderSurvivesTheFirstSegmentIndex() {
        App app = new App()
                .get("/study/today", noop)
                .get("/{page}", noop)
                .get("/study/{mode}", noop);

        assertThat(app.routes().stream().map(Route::path).toList())
                .isEqualTo(List.of("/study/today", "/{page}", "/study/{mode}"));
    }

    @Test
    void groupPrefixesAreAlreadyResolved() {
        App app = new App().path("/api", api -> api
                .get("/decks", noop)
                .path("/decks/{deckId}", deck -> deck.get("/cards", noop)));

        assertThat(app.routes().stream().map(Route::path).toList())
                .isEqualTo(List.of("/api/decks", "/api/decks/{deckId}/cards"));
    }

    /**
     * What a route is for is said at the registration site, since nothing else
     * knows it and digging it out of the handler would be reflection.
     */
    @Test
    void reportsTheDescriptionEachRegistrationPassed() {
        App app = new App()
                .get("/decks", "List every deck", noop)
                .post("/decks", "Create a deck", noop)
                .get("/style.css", noop);

        assertThat(app.routes()).isEqualTo(List.of(
                new Route("GET", "/decks", "List every deck"),
                new Route("POST", "/decks", "Create a deck"),
                new Route("GET", "/style.css", "")));
    }

    /** An undescribed route is one that was never described, not one missing a component. */
    @Test
    void anUndescribedRouteReportsAnEmptyStringRatherThanNull() {
        App app = new App().get("/decks", noop);

        assertThat(app.routes().get(0).description()).isEmpty();
        assertThat(new Route("GET", "/decks")).isEqualTo(new Route("GET", "/decks", ""));
    }

    /** Every registration method takes one, on the group as much as on the app. */
    @Test
    void everyMethodAndEveryGroupTakesADescription() {
        App app = new App()
                .put("/decks/{id}", "Rename a deck", noop)
                .patch("/decks/{id}", "Move a deck", noop)
                .delete("/decks/{id}", "Delete a deck", noop)
                .head("/decks", "Whether there are decks", noop)
                .options("/decks", "The CORS preflight", noop)
                .path("/api", api -> api
                        .get("/cards", "List every card", noop)
                        .post("/cards", "Add a card", noop));

        assertThat(app.routes().stream().map(Route::description).toList()).isEqualTo(List.of(
                "Rename a deck", "Move a deck", "Delete a deck", "Whether there are decks",
                "The CORS preflight", "List every card", "Add a card"));
        assertThat(app.routes().stream().map(Route::path).toList())
                .contains("/api/cards");
    }

    /** Only what was registered: the servlet's automatic HEAD and OPTIONS are not routes. */
    @Test
    void automaticHeadAndOptionsAnswersAreNotListed() {
        App app = new App().get("/decks", noop);

        assertThat(app.routes()).isEqualTo(List.of(new Route("GET", "/decks")));
    }

    @Test
    void theSnapshotIsImmutableAndTakenPerCall() {
        App app = new App().get("/decks", noop);
        List<Route> before = app.routes();

        app.get("/stats", noop);

        assertThat(before).hasSize(1);
        assertThat(app.routes()).hasSize(2);
        assertThatThrownBy(() -> before.add(new Route("GET", "/nowhere")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
