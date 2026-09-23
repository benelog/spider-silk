package net.benelog.spidersilk;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code app.hooks()}: the filters and status pages read back beside the routes. */
class HookIntrospectionTest {

    private final Handler noop = req -> null;
    private final BeforeFilter passBefore = req -> null;
    private final AfterFilter passAfter = (req, res) -> null;

    @Test
    void groupsByWhenItRunsAndKeepsRegistrationOrderWithin() {
        App app = new App()
                .afterRoute("/api/*", passAfter)
                .beforeRoute("/admin/*", passBefore)
                .statusPage(HttpStatus.NOT_FOUND, noop)
                .responseFilter((req, res) -> null)
                .beforeRoute("/api/*", passBefore)
                .statusPage(HttpStatus.INTERNAL_SERVER_ERROR, noop);

        assertThat(app.hooks()).isEqualTo(List.of(
                new Hook.BeforeRoute("/admin/*"),
                new Hook.BeforeRoute("/api/*"),
                new Hook.AfterRoute("/api/*"),
                new Hook.StatusPage(HttpStatus.NOT_FOUND),
                new Hook.StatusPage(HttpStatus.INTERNAL_SERVER_ERROR),
                new Hook.EveryResponse()));
    }

    /** The coverage is the pattern as written, not the paths it expands to. */
    @Test
    void thePatternIsReportedAsItWasRegistered() {
        App app = new App().beforeRoute("/decks/{deckId}/*", passBefore);

        assertThat(app.hooks()).isEqualTo(List.of(new Hook.BeforeRoute("/decks/{deckId}/*")));
    }

    /** The whole-app overloads register "/*", so that is what they report. */
    @Test
    void theNoPathOverloadsReportTheStarTheyRegister() {
        App app = new App().beforeRoute(passBefore).afterRoute(passAfter);

        assertThat(app.hooks()).isEqualTo(List.of(
                new Hook.BeforeRoute("/*"),
                new Hook.AfterRoute("/*")));
    }

    /** A group's filter reports the resolved path, the way its routes do. */
    @Test
    void groupPrefixesAreAlreadyResolved() {
        App app = new App().path("/api/decks", decks -> decks
                .beforeRoute(passBefore)
                .beforeRoute("/{deckId}/cards", passBefore));

        assertThat(app.hooks()).isEqualTo(List.of(
                new Hook.BeforeRoute("/api/decks/*"),
                new Hook.BeforeRoute("/api/decks/{deckId}/cards")));
    }

    /** One body per status: registering a second replaces the first, and stays in its place. */
    @Test
    void aStatusRegisteredTwiceIsListedOnce() {
        App app = new App()
                .statusPage(HttpStatus.NOT_FOUND, noop)
                .statusPage(HttpStatus.FORBIDDEN, noop)
                .statusPage(HttpStatus.NOT_FOUND, noop);

        assertThat(app.hooks()).isEqualTo(List.of(
                new Hook.StatusPage(HttpStatus.NOT_FOUND),
                new Hook.StatusPage(HttpStatus.FORBIDDEN)));
    }

    /** An exception handler is scoped to a type, so no path or status describes it. */
    @Test
    void exceptionHandlersAreNotHooks() {
        App app = new App().exception(IllegalStateException.class,
                (req, e) -> WebResponse.text(e.getMessage()));

        assertThat(app.hooks()).isEmpty();
    }

    /** CORS, gzip, and the security headers are named on App, not registered as filters. */
    @Test
    void theNamedConcernsAreNotFilters() {
        App app = new App()
                .cors(Cors.anyOrigin())
                .gzip()
                .securityHeaders();

        assertThat(app.hooks()).isEmpty();
    }

    @Test
    void theSnapshotIsImmutableAndTakenPerCall() {
        App app = new App().beforeRoute("/admin/*", passBefore);
        List<Hook> before = app.hooks();

        app.afterRoute("/admin/*", passAfter);

        assertThat(before).hasSize(1);
        assertThat(app.hooks()).hasSize(2);
        assertThatThrownBy(() -> before.add(new Hook.BeforeRoute("/nowhere")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** Hooks are a list of their own: routes() is left exactly as it was. */
    @Test
    void routesAreUnaffected() {
        App app = new App()
                .beforeRoute("/admin/*", passBefore)
                .get("/admin/users", noop);

        assertThat(app.routes()).isEqualTo(List.of(new Route("GET", "/admin/users")));
    }
}
