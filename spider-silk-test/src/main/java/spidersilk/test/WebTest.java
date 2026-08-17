package spidersilk.test;

import java.net.CookieManager;
import java.net.http.HttpClient;
import java.util.function.Consumer;

import spidersilk.App;

/**
 * Runs an {@link App} on a free port for the duration of a test.
 *
 * <pre>{@code
 * @Test
 * void listsDecks() {
 *     WebTest.test(app, client -> {
 *         HttpResponse<String> response = client.get("/api/decks");
 *         assertEquals(200, response.statusCode());
 *     });
 * }
 * }</pre>
 *
 * <p>The port is 0, so tests never collide and never need one configured. The
 * client keeps cookies, so a login in one call is still in effect for the next.
 * The server is stopped even when the body throws.
 */
public final class WebTest {

    private WebTest() {
    }

    /** Starts the app, runs the body against it, and stops it again. */
    public static void test(App app, Consumer<TestClient> body) {
        app.start(0);
        try (HttpClient httpClient =
                HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            body.accept(new TestClient(httpClient, "http://localhost:" + app.port()));
        } finally {
            app.stop();
        }
    }
}
