package spidersilk;

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
