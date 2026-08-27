package flashcard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.test.TestClient;
import net.benelog.spidersilk.test.WebTest;

import flashcard.repository.RepositoryTestSupport;

/**
 * What this application turns on for every answer rather than for a route:
 * security headers, compression, and CORS on the one document meant to be read
 * from somewhere else.
 *
 * <p>These are the configuration choices {@code FlashcardApp.createApp} makes,
 * not the framework features themselves — core's own tests cover those. What is
 * asserted here is that this app's policy is the one that goes out, including on
 * the answers no filter would have reached.
 */
class ResponseWideConcernsTest extends RepositoryTestSupport {

    @Test
    void everyPageCarriesTheSecurityHeaders() {
        WebTest.test(FlashcardApp.createApp(dataSource), client -> {
            HttpResponse<String> home = client.get("/");

            assertThat(home.statusCode()).isEqualTo(200);
            assertThat(header(home, "X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(header(home, "X-Frame-Options")).isEqualTo("DENY");
            assertThat(header(home, "Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        });
    }

    /**
     * The policy this application can actually run under. {@code 'self'} covers
     * everything it loads except one thing: {@code stats.jte} sizes its chart
     * bars with a style attribute, and a height computed per row cannot come out
     * of a stylesheet.
     */
    @Test
    void theContentSecurityPolicyAllowsWhatTheStatsChartNeedsAndNoMore() {
        WebTest.test(FlashcardApp.createApp(dataSource), client ->
                assertThat(header(client.get("/"), "Content-Security-Policy"))
                        .isEqualTo("default-src 'self'; style-src 'self' 'unsafe-inline'"));
    }

    /** This app is served over http://localhost, so it must not claim otherwise. */
    @Test
    void thereIsNoHsts() {
        WebTest.test(FlashcardApp.createApp(dataSource), client ->
                assertThat(client.get("/").headers()
                        .firstValue("Strict-Transport-Security")).isEmpty());
    }

    /** Neither a 404 nor a static file reaches an after-filter, and both are pages. */
    @Test
    void soDoTheAnswersNoFilterWouldHaveReached() {
        WebTest.test(FlashcardApp.createApp(dataSource), client -> {
            assertThat(client.get("/nope").statusCode()).isEqualTo(404);
            assertThat(header(client.get("/nope"), "X-Frame-Options")).isEqualTo("DENY");
            assertThat(header(client.get("/style.css"), "X-Content-Type-Options"))
                    .isEqualTo("nosniff");
        });
    }

    @Test
    void pagesAndTheStylesheetAreCompressed() {
        WebTest.test(FlashcardApp.createApp(dataSource), client -> {
            HttpResponse<byte[]> home = gzipped(client, "/");
            assertThat(header(home, "Content-Encoding")).isEqualTo("gzip");
            assertThat(inflate(home.body())).contains("<html");

            HttpResponse<byte[]> stylesheet = gzipped(client, "/style.css");
            assertThat(header(stylesheet, "Content-Encoding")).isEqualTo("gzip");
            assertThat(inflate(stylesheet.body())).contains("{");
        });
    }

    /**
     * The OpenAPI document is the one thing here meant for a caller that is not
     * this application — a Swagger UI or a client generator, served from
     * somewhere else.
     */
    @Test
    void theOpenApiDocumentIsReadableFromAnotherSite() {
        WebTest.test(FlashcardApp.createApp(dataSource), client -> {
            HttpResponse<String> document = client.send(request -> request
                    .uri(URI.create(client.url("/openapi.json")))
                    .header("Origin", "https://editor.swagger.io")
                    .GET());

            assertThat(document.statusCode()).isEqualTo(200);
            assertThat(header(document, "Access-Control-Allow-Origin")).isEqualTo("*");
        });
    }

    /** And it is the only one: /api/decks creates decks, so no origin gets it. */
    @Test
    void theRestOfTheApiIsNot() {
        WebTest.test(FlashcardApp.createApp(dataSource), client -> {
            HttpResponse<String> decks = client.send(request -> request
                    .uri(URI.create(client.url("/api/decks")))
                    .header("Origin", "https://editor.swagger.io")
                    .GET());

            assertThat(decks.statusCode()).isEqualTo(200);
            assertThat(decks.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        });
    }

    private static HttpResponse<byte[]> gzipped(TestClient client, String path) {
        return client.send(request -> request
                .uri(URI.create(client.url(path)))
                .header("Accept-Encoding", "gzip")
                .GET(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String inflate(byte[] compressed) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElseThrow(
                () -> new AssertionError("No " + name + " header: " + response.headers().map()));
    }
}
