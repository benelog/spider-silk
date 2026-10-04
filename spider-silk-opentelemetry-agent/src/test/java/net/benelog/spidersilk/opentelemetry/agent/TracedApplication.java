package net.benelog.spidersilk.opentelemetry.agent;

import net.benelog.spidersilk.App;
import net.benelog.spidersilk.WebResponse;

/** The application the test runs under the agent, in a JVM of its own. */
public final class TracedApplication {

    private TracedApplication() {
    }

    public static void main(String[] args) {
        App app = new App();
        app.get("/decks/{deckId}", req -> WebResponse.text("deck " + req.pathParam("deckId")));
        app.path("/api", api -> api.get("/cards/{cardId}", req -> WebResponse.text("card")));
        app.start(0);
        System.out.println("PORT " + app.port());
    }
}
