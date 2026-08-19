package flashcard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.servlet.MultipartConfigElement;

import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import spidersilk.App;
import spidersilk.server.JettyServer;
import spidersilk.server.WebServerFactory;
import spidersilk.tomcat.TomcatServer;
import spidersilk.undertow.UndertowServer;

/**
 * The example app, served by each of the three servers in turn.
 *
 * <p>The server modules carry their own acceptance tests for what core
 * promises. What this one adds is a whole application on top: templates,
 * static files, a form post through a redirect, JSON, and the upload limit
 * `main` actually configures. If a server only looked interchangeable, this is
 * where it would stop being so.
 */
class ServerCompatibilityTest {

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private App app;

    static Object[][] servers() {
        return new Object[][] {
            {"Jetty", (WebServerFactory) (a, port) ->
                    new JettyServer(a).port(port).multipart(uploadLimits())},
            {"Tomcat", (WebServerFactory) (a, port) ->
                    new TomcatServer(a).port(port).multipart(uploadLimits())},
            {"Undertow", (WebServerFactory) (a, port) ->
                    new UndertowServer(a).port(port).multipart(uploadLimits())},
        };
    }

    @AfterEach
    void stopApp() {
        if (app != null) {
            app.stop();
            app = null;
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("servers")
    void theExampleAppRunsOn(String name, WebServerFactory factory) throws Exception {
        app = FlashcardApp.createApp(freshDatabase()).server(factory).start(0);

        // A jte template, rendered.
        HttpResponse<String> home = get("/");
        assertEquals(200, home.statusCode(), name + " failed to render the home page");
        assertTrue(home.body().contains("<html"), name + " served no HTML: " + home.body());

        // A static file off the classpath, with the cache headers StaticFiles adds.
        HttpResponse<String> stylesheet = get("/style.css");
        assertEquals(200, stylesheet.statusCode(), name + " failed to serve the stylesheet");
        assertTrue(stylesheet.headers().firstValue("ETag").isPresent(),
                name + " served the stylesheet without an ETag");

        // A form post that answers with a redirect, then the page it points at.
        HttpResponse<String> created = postForm("/decks", "name=Servers");
        assertEquals(302, created.statusCode(), name + " did not redirect after the form post");
        String location = created.headers().firstValue("Location").orElseThrow();
        assertEquals(200, get(location).statusCode(), name + " failed to serve " + location);

        // JSON, over the route group.
        HttpResponse<String> decks = get("/api/decks");
        assertEquals(200, decks.statusCode(), name + " failed on the JSON API");
        assertTrue(decks.body().contains("\"Servers\""),
                name + " did not list the deck just created: " + decks.body());

        // Route introspection, which is the same list on every server.
        assertEquals(200, get("/openapi.json").statusCode(), name + " failed on /openapi.json");
        assertEquals(200, get("/_routes").statusCode(), name + " failed on /_routes");

        // A CSV upload, which is what the multipart config above is for. A failed
        // import also redirects — to "/" with a flash — so the deck is what says
        // the upload was actually read, and the cards prove it was parsed.
        HttpResponse<String> imported = postCsv(location + "/import", "front,back\nhello,안녕\n");
        assertEquals(302, imported.statusCode(), name + " failed on the CSV import");
        assertEquals(location, imported.headers().firstValue("Location").orElseThrow(),
                name + " bounced the CSV import to the error page");
        HttpResponse<String> cards = get("/api/decks/" + deckId(location) + "/cards");
        assertTrue(cards.body().contains("안녕"),
                name + " did not import the card, or mangled its encoding: " + cards.body());
    }

    private static String deckId(String deckPath) {
        return deckPath.substring(deckPath.lastIndexOf('/') + 1);
    }

    private static DataSource freshDatabase() throws Exception {
        DataSource dataSource = JdbcConnectionPool.create(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        FlashcardApp.initSchema(dataSource);
        return dataSource;
    }

    /** The same limits {@code FlashcardApp.main} sets, so this covers that path too. */
    private static MultipartConfigElement uploadLimits() {
        return new MultipartConfigElement(
                System.getProperty("java.io.tmpdir"), 10 * 1024 * 1024L, 10 * 1024 * 1024L,
                1024 * 1024);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForm(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postCsv(String path, String csv) throws Exception {
        String boundary = "flashcardboundary";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"deck.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n"
                + csv
                + "\r\n--" + boundary + "--\r\n";
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + app.port() + path);
    }
}
