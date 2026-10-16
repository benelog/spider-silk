package net.benelog.spidersilk.opentelemetry.agent;

import net.benelog.spidersilk.App;
import net.benelog.spidersilk.WebResponse;
import net.benelog.spidersilk.server.JettyServer;

/** The application the test runs under the agent, in a JVM of its own. */
public final class TracedApplication {

    private TracedApplication() {
    }

    public static void main(String[] args) {
        App app = new App();
        app.get("/decks/{deckId}", req -> WebResponse.text("deck " + req.pathParam("deckId")));
        app.path("/api", api -> api.get("/cards/{cardId}", req -> WebResponse.text("card")));
        String contextPath = System.getProperty("test.contextPath");
        if (contextPath != null) {
            app.server((application, port) -> new JettyServer(application).port(port)
                    .contextPath(contextPath));
        }
        app.start(0);
        System.out.println("PORT " + app.port());
    }
}
