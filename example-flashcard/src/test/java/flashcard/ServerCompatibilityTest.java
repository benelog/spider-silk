package flashcard;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(home.statusCode()).as(name + " failed to render the home page").isEqualTo(200);
        assertThat(home.body()).as(name + " served no HTML: " + home.body()).contains("<html");

        // A static file off the classpath, with the cache headers StaticFiles adds.
        HttpResponse<String> stylesheet = get("/style.css");
        assertThat(stylesheet.statusCode())
                .as(name + " failed to serve the stylesheet")
                .isEqualTo(200);
        assertThat(stylesheet.headers().firstValue("ETag"))
                .as(name + " served the stylesheet without an ETag")
                .isPresent();

        // A form post that answers with a redirect, then the page it points at.
        HttpResponse<String> created = postForm("/decks", "name=Servers");
        assertThat(created.statusCode())
                .as(name + " did not redirect after the form post")
                .isEqualTo(302);
        String location = created.headers().firstValue("Location").orElseThrow();
        assertThat(get(location).statusCode())
                .as(name + " failed to serve " + location)
                .isEqualTo(200);

        // JSON, over the route group.
        HttpResponse<String> decks = get("/api/decks");
        assertThat(decks.statusCode()).as(name + " failed on the JSON API").isEqualTo(200);
        assertThat(decks.body())
                .as(name + " did not list the deck just created: " + decks.body())
                .contains("\"Servers\"");

        // Route introspection, which is the same list on every server.
        assertThat(get("/openapi.json").statusCode())
                .as(name + " failed on /openapi.json")
                .isEqualTo(200);
        assertThat(get("/_routes").statusCode()).as(name + " failed on /_routes").isEqualTo(200);

        // A CSV upload, which is what the multipart config above is for. A failed
        // import also redirects — to "/" with a flash — so the deck is what says
        // the upload was actually read, and the cards prove it was parsed.
        HttpResponse<String> imported = postCsv(location + "/import", "front,back\nhello,안녕\n");
        assertThat(imported.statusCode()).as(name + " failed on the CSV import").isEqualTo(302);
        assertThat(imported.headers().firstValue("Location").orElseThrow())
                .as(name + " bounced the CSV import to the error page")
                .isEqualTo(location);
        HttpResponse<String> cards = get("/api/decks/" + deckId(location) + "/cards");
        assertThat(cards.body())
                .as(name + " did not import the card, or mangled its encoding: " + cards.body())
                .contains("안녕");
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
