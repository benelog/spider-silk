package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.test.WebTest;

/**
 * {@code req.route()}: the route that answered, as the request itself reports it.
 * A tracing filter or a request logger names the endpoint by the pattern rather
 * than by the path, and this is where the pattern comes from.
 */
class MatchedRouteTest {

    @Test
    void aHandlerReadsTheRouteItWasRegisteredAs() {
        App app = new App().path("/api/decks", decks ->
                decks.get("/{deckId}", "One deck", req -> WebResponse.text(
                        req.route().method() + " " + req.route().path() + ": " + req.route().description())));

        WebTest.test(app, client ->
                assertThat(client.get("/api/decks/7").body()).isEqualTo("GET /api/decks/{deckId}: One deck"));
    }

    /** The route is known from the moment routing picked it, and stays known until the request is logged. */
    @Test
    void everyStageAfterRoutingSeesTheSameRoute() {
        List<String> seen = new CopyOnWriteArrayList<>();
        App app = new App()
                .beforeRequest(req -> {
                    seen.add("request:" + req.route());
                    return null;
                })
                .beforeRoute(req -> {
                    seen.add("before:" + req.route().path());
                    return null;
                })
                .get("/decks/{deckId}", req -> WebResponse.text("ok"))
                .afterRoute((req, res) -> {
                    seen.add("after:" + req.route().path());
                    return res;
                })
                .responseFilter((req, res) -> {
                    seen.add("response:" + req.route().path());
                    return res;
                })
                .requestLogger((req, completion) -> seen.add("log:" + req.route().path()));

        WebTest.test(app, client -> client.get("/decks/7"));

        assertThat(seen).containsExactly("request:null", "before:/decks/{deckId}",
                "after:/decks/{deckId}", "response:/decks/{deckId}", "log:/decks/{deckId}");
    }

    @Test
    void exceptionAndErrorHandlersSeeTheRouteThatThrew() {
        List<String> seen = new ArrayList<>();
        App app = new App()
                .get("/decks/{deckId}", req -> {
                    throw new IllegalStateException("boom");
                })
                .get("/cards/{cardId}", req -> {
                    throw new HttpException(HttpStatus.NOT_FOUND, "no card");
                })
                .exception(IllegalStateException.class, (req, e) -> {
                    seen.add("exception:" + req.route().path());
                    return WebResponse.text("mapped").status(HttpStatus.CONFLICT);
                })
                .statusPage(HttpStatus.NOT_FOUND, req -> {
                    seen.add("error:" + req.route());
                    return WebResponse.text("filled");
                });

        WebTest.test(app, client -> {
            client.get("/decks/7");
            client.get("/cards/9");
            client.get("/nothing");
        });

        assertThat(seen).containsExactly("exception:/decks/{deckId}",
                "error:" + new Route("GET", "/cards/{cardId}"), "error:null");
    }

    /** Where nothing was routed there is nothing to report: a static file, a 404, a 405, and OPTIONS. */
    @Test
    void isNullWhereNoRouteAnswered() {
        List<String> seen = new CopyOnWriteArrayList<>();
        App app = new App()
                .staticFiles("/public")
                .post("/decks", req -> WebResponse.text("created"))
                .requestLogger((req, completion) -> seen.add(
                        req.method() + " " + req.path() + " -> " + completion.statusCode() + " " + req.route()));

        WebTest.test(app, client -> {
            client.get("/app.css");
            client.get("/missing");
            client.get("/decks");
            client.options("/decks");
        });

        assertThat(seen).containsExactly(
                "GET /app.css -> 200 null",
                "GET /missing -> 404 null",
                "GET /decks -> 405 null",
                "OPTIONS /decks -> 200 null");
    }

    /** A HEAD nobody registered is answered by the GET route, and that is the route it reports. */
    @Test
    void aHeadAnsweredByTheGetRouteReportsTheGetRoute() {
        List<Route> seen = new CopyOnWriteArrayList<>();
        App app = new App()
                .get("/decks", "List every deck", req -> WebResponse.text("list"))
                .requestLogger((req, completion) -> seen.add(req.route()));

        WebTest.test(app, client -> client.head("/decks"));

        assertThat(seen).containsExactly(new Route("GET", "/decks", "List every deck"));
    }

    /** The route reported is the one the router chose, and the router breaks ties by registration order. */
    @Test
    void reportsTheRouteTheRouterChoseNotTheMostLiteralOne() {
        App app = new App()
                .get("/study/{mode}", req -> WebResponse.text(req.route().path()))
                .get("/study/today", req -> WebResponse.text(req.route().path()));

        WebTest.test(app, client ->
                assertThat(client.get("/study/today").body()).isEqualTo("/study/{mode}"));
    }
}
