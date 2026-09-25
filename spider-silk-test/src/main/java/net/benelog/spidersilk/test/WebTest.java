package net.benelog.spidersilk.test;

import java.net.CookieManager;
import java.net.http.HttpClient;
import java.util.function.Consumer;

import net.benelog.spidersilk.App;

/**
 * Runs an {@link App} on a free port for the duration of a test.
 *
 * <pre>{@code
 * @Test
 * void listsDecks() {
 *     WebTest.test(app, client -> {
 *         HttpResponse<String> response = client.get("/api/decks");
 *         assertThat(response.statusCode()).isEqualTo(200);
 *     });
 * }
 * }</pre>
 *
 * <p>The port is 0, so tests never collide and never need one configured. The
 * client keeps cookies, so a login in one call is still in effect for the next.
 * The server is stopped even when the body throws, and even when it leaves a
 * streamed response open, such as an SSE stream it read one event from.
 */
public final class WebTest {

    private WebTest() {
    }

    /** Starts the app, runs the body against it, and stops it again. */
    public static void test(App app, Consumer<TestClient> body) {
        app.start(0);
        HttpClient httpClient = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        try {
            body.accept(new TestClient(httpClient, "http://localhost:" + app.port()));
        } finally {
            // The app first: closing the client waits for every response to
            // complete, and a stream the body left open ends only when the app
            // stops, so closing it first waited for good.
            try {
                app.stop();
            } finally {
                httpClient.shutdownNow();
                httpClient.close();
            }
        }
    }
}
