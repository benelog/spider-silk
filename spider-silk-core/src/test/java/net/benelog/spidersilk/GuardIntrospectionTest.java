package net.benelog.spidersilk;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code app.guards()}: the filters and error handlers read back beside the routes. */
class GuardIntrospectionTest {

    private final Handler noop = req -> null;
    private final BeforeFilter passBefore = req -> null;
    private final AfterFilter passAfter = (req, res) -> null;

    @Test
    void groupsByWhenItRunsAndKeepsRegistrationOrderWithin() {
        App app = new App()
                .afterRoute("/api/*", passAfter)
                .beforeRoute("/admin/*", passBefore)
                .error(HttpStatus.NOT_FOUND, noop)
                .responseFilter((req, res) -> null)
                .beforeRoute("/api/*", passBefore)
                .error(HttpStatus.INTERNAL_SERVER_ERROR, noop);

        assertThat(app.guards()).isEqualTo(List.of(
                new Guard.BeforeRoute("/admin/*"),
                new Guard.BeforeRoute("/api/*"),
                new Guard.AfterRoute("/api/*"),
                new Guard.Error(HttpStatus.NOT_FOUND),
                new Guard.Error(HttpStatus.INTERNAL_SERVER_ERROR),
                new Guard.ResponseFilter()));
    }

    /** The coverage is the pattern as written, not the paths it expands to. */
    @Test
    void thePatternIsReportedAsItWasRegistered() {
        App app = new App().beforeRoute("/decks/{deckId}/*", passBefore);

        assertThat(app.guards()).isEqualTo(List.of(new Guard.BeforeRoute("/decks/{deckId}/*")));
    }

    /** The whole-app overloads register "/*", so that is what they report. */
    @Test
    void theNoPathOverloadsReportTheStarTheyRegister() {
        App app = new App().beforeRoute(passBefore).afterRoute(passAfter);

        assertThat(app.guards()).isEqualTo(List.of(
                new Guard.BeforeRoute("/*"),
                new Guard.AfterRoute("/*")));
    }

    /** A group's filter reports the resolved path, the way its routes do. */
    @Test
    void groupPrefixesAreAlreadyResolved() {
        App app = new App().path("/api/decks", decks -> decks
                .beforeRoute(passBefore)
                .beforeRoute("/{deckId}/cards", passBefore));

        assertThat(app.guards()).isEqualTo(List.of(
                new Guard.BeforeRoute("/api/decks/*"),
                new Guard.BeforeRoute("/api/decks/{deckId}/cards")));
    }

    /** One body per status: registering a second replaces the first, and stays in its place. */
    @Test
    void aStatusRegisteredTwiceIsListedOnce() {
        App app = new App()
                .error(HttpStatus.NOT_FOUND, noop)
                .error(HttpStatus.FORBIDDEN, noop)
                .error(HttpStatus.NOT_FOUND, noop);

        assertThat(app.guards()).isEqualTo(List.of(
                new Guard.Error(HttpStatus.NOT_FOUND),
                new Guard.Error(HttpStatus.FORBIDDEN)));
    }

    /** An exception handler is scoped to a type, so no path or status describes it. */
    @Test
    void exceptionHandlersAreNotGuards() {
        App app = new App().exception(IllegalStateException.class,
                (req, e) -> WebResponse.text(e.getMessage()));

        assertThat(app.guards()).isEmpty();
    }

    /** CORS, gzip, and the security headers are named on App, not registered as filters. */
    @Test
    void theNamedConcernsAreNotFilters() {
        App app = new App()
                .cors(Cors.anyOrigin())
                .gzip()
                .securityHeaders();

        assertThat(app.guards()).isEmpty();
    }

    @Test
    void theSnapshotIsImmutableAndTakenPerCall() {
        App app = new App().beforeRoute("/admin/*", passBefore);
        List<Guard> before = app.guards();

        app.afterRoute("/admin/*", passAfter);

        assertThat(before).hasSize(1);
        assertThat(app.guards()).hasSize(2);
        assertThatThrownBy(() -> before.add(new Guard.BeforeRoute("/nowhere")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** Guards are a list of their own: routes() is left exactly as it was. */
    @Test
    void routesAreUnaffected() {
        App app = new App()
                .beforeRoute("/admin/*", passBefore)
                .get("/admin/users", noop);

        assertThat(app.routes()).isEqualTo(List.of(new Route("GET", "/admin/users")));
    }
}
