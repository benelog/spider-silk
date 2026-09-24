package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.test.TestClient;
import net.benelog.spidersilk.test.WebTest;

/** Cross-origin answers: the preflight, the actual request, and what neither covers. */
class CorsTest {

    private static final String ORIGIN = "https://app.example.com";

    private static App api() {
        return new App()
                .get("/api/decks", req -> WebResponse.rawJson("[]"))
                .post("/api/decks", req -> WebResponse.rawJson("{}"));
    }

    // ---- The preflight ----

    @Test
    void aPreflightIsAnsweredWithTheMethodsThePathAllows() {
        WebTest.test(api().cors(Cors.allowOrigin(ORIGIN)), client -> {
            HttpResponse<String> response = preflight(client, "/api/decks", "POST");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(header(response, "Access-Control-Allow-Origin")).isEqualTo(ORIGIN);
            assertThat(header(response, "Access-Control-Allow-Methods"))
                    .contains("GET", "POST", "OPTIONS");
            assertThat(response.body()).isEmpty();
        });
    }

    /** The request headers a preflight asks about come back allowed, unless configured. */
    @Test
    void aPreflightReflectsTheHeadersItAsksFor() {
        WebTest.test(api().cors(Cors.allowOrigin(ORIGIN)), client -> {
            HttpResponse<String> response = client.send(request -> request
                    .uri(URI.create(client.url("/api/decks")))
                    .header("Origin", ORIGIN)
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "Content-Type, X-Trace")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()));

            assertThat(header(response, "Access-Control-Allow-Headers"))
                    .isEqualTo("Content-Type, X-Trace");
            assertThat(header(response, "Vary")).contains("Access-Control-Request-Headers");
        });
    }

    @Test
    void configuredHeadersAndMethodsReplaceTheReflectedOnes() {
        Cors cors = Cors.allowOrigin(ORIGIN)
                .allowMethods("GET")
                .allowHeaders("Content-Type")
                .maxAge(Duration.ofHours(2));

        WebTest.test(api().cors(cors), client -> {
            HttpResponse<String> response = client.send(request -> request
                    .uri(URI.create(client.url("/api/decks")))
                    .header("Origin", ORIGIN)
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "X-Trace")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()));

            assertThat(header(response, "Access-Control-Allow-Methods")).isEqualTo("GET");
            assertThat(header(response, "Access-Control-Allow-Headers")).isEqualTo("Content-Type");
            assertThat(header(response, "Access-Control-Max-Age")).isEqualTo("7200");
        });
    }

    @Test
    void anUnknownOriginGetsThePlainOptionsAnswer() {
        WebTest.test(api().cors(Cors.allowOrigin(ORIGIN)), client -> {
            HttpResponse<String> response =
                    preflight(client, "/api/decks", "POST", "https://evil.example.com");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(header(response, "Allow")).contains("GET", "POST");
            assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        });
    }

    /** A preflight is only the answer where nobody registered one of their own. */
    @Test
    void anOptionsRouteTakesThePreflightBack() {
        App app = api()
                .options("/api/decks", req -> WebResponse.text("mine"))
                .cors(Cors.allowOrigin(ORIGIN));

        WebTest.test(app, client -> {
            HttpResponse<String> response = preflight(client, "/api/decks", "POST");

            assertThat(response.body()).isEqualTo("mine");
            assertThat(response.headers().firstValue("Access-Control-Allow-Methods")).isEmpty();
            // The actual-request headers still go out; only the preflight is theirs.
            assertThat(header(response, "Access-Control-Allow-Origin")).isEqualTo(ORIGIN);
        });
    }

    @Test
    void aPreflightForAnUnknownPathIsStillANotFound() {
        WebTest.test(api().cors(Cors.allowOrigin(ORIGIN)), client ->
                assertThat(preflight(client, "/api/nope", "POST").statusCode()).isEqualTo(404));
    }

    // ---- The actual request ----

    @Test
    void anAllowedOriginReadsTheAnswer() {
        WebTest.test(api().cors(Cors.allowOrigin(ORIGIN)), client -> {
            HttpResponse<String> response = get(client, "/api/decks", ORIGIN);

            assertThat(header(response, "Access-Control-Allow-Origin")).isEqualTo(ORIGIN);
            assertThat(header(response, "Vary")).contains("Origin");
        });
    }

    /**
     * A same-origin request gains no access, but its answer still varies by
     * {@code Origin}: a shared cache that stored it without saying so would hand
     * an allowed origin an answer with no {@code Access-Control-Allow-Origin}.
     */
    @Test
    void aSameOriginRequestGainsNoAccessButStillVaries() {
        WebTest.test(api().cors(Cors.allowOrigin(ORIGIN)), client -> {
            HttpResponse<String> response = client.get("/api/decks");

            assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
            assertThat(header(response, "Vary")).isEqualTo("Origin");
        });
    }

    /** The same holds for an origin that is not allowed. */
    @Test
    void anUnknownOriginGainsNoAccessButStillVaries() {
        WebTest.test(api().gzip().cors(Cors.allowOrigin(ORIGIN)), client -> {
            HttpResponse<String> response = get(client, "/api/decks", "https://evil.example.com");

            assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
            assertThat(header(response, "Vary"))
                    .as("the field Gzip adds is kept beside it")
                    .isEqualTo("Origin, Accept-Encoding");
        });
    }

    /**
     * The reason this is not an after-filter: no filter runs for a 404, and a
     * browser that cannot read the answer reports a CORS failure rather than the
     * 404 that actually happened.
     */
    @Test
    void anErrorResponseCarriesTheHeadersToo() {
        WebTest.test(api().cors(Cors.allowOrigin(ORIGIN)), client -> {
            HttpResponse<String> response = get(client, "/api/nope", ORIGIN);

            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(header(response, "Access-Control-Allow-Origin")).isEqualTo(ORIGIN);
        });
    }

    @Test
    void anyOriginEchoesTheWildcardAndDoesNotVary() {
        WebTest.test(api().cors(Cors.anyOrigin()), client -> {
            HttpResponse<String> response = get(client, "/api/decks", "https://anywhere.example");

            assertThat(header(response, "Access-Control-Allow-Origin")).isEqualTo("*");
            assertThat(response.headers().firstValue("Vary")).isEmpty();
        });
    }

    @Test
    void credentialsAndExposedHeadersGoOutWhenAskedFor() {
        Cors cors = Cors.allowOrigin(ORIGIN).allowCredentials().exposeHeaders("X-Total-Count");

        WebTest.test(api().cors(cors), client -> {
            HttpResponse<String> response = get(client, "/api/decks", ORIGIN);

            assertThat(header(response, "Access-Control-Allow-Credentials")).isEqualTo("true");
            assertThat(header(response, "Access-Control-Expose-Headers")).isEqualTo("X-Total-Count");
        });
    }

    @Test
    void aPathOutsideTheConfiguredOneIsUntouched() {
        App app = api()
                .get("/admin", req -> WebResponse.text("admin"))
                .cors(Cors.allowOrigin(ORIGIN).forPath("/api/*"));

        WebTest.test(app, client -> {
            assertThat(header(get(client, "/api/decks", ORIGIN), "Access-Control-Allow-Origin"))
                    .isEqualTo(ORIGIN);
            assertThat(get(client, "/admin", ORIGIN).headers()
                    .firstValue("Access-Control-Allow-Origin")).isEmpty();
            assertThat(get(client, "/admin", ORIGIN).headers().firstValue("Vary")).isEmpty();
        });
    }

    // ---- Configuration that cannot mean anything ----

    @Test
    void credentialsCannotBeSentToAnyOrigin() {
        assertThatThrownBy(() -> Cors.anyOrigin().allowCredentials())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cors.allowOrigin");
    }

    @Test
    void theWildcardIsSpelledAnyOrigin() {
        assertThatThrownBy(() -> Cors.allowOrigin("*"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cors.anyOrigin()");
    }

    @Test
    void atLeastOneOriginIsRequired() {
        assertThatThrownBy(Cors::allowOrigin).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A response that fails while it is written, before anything is sent, is
     * reset to a 500. The reset used to take the CORS and security headers
     * with it, and a browser then reported a CORS failure instead of the 500.
     */
    @Test
    void theFiveHundredForAFailedWriteCarriesTheCorsAndSecurityHeaders() {
        App app = new App()
                .cors(Cors.allowOrigin(ORIGIN))
                .securityHeaders()
                .get("/export", req -> WebResponse.stream("text/csv", out -> {
                    throw new IOException("export failed");
                }))
                .get("/cookie", req -> WebResponse.raw((request, response) -> {
                    throw new IllegalStateException("raw writer failed");
                }));

        WebTest.test(app, client -> {
            for (String path : List.of("/export", "/cookie")) {
                HttpResponse<String> response = get(client, path, ORIGIN);

                assertThat(response.statusCode()).isEqualTo(500);
                assertThat(response.body()).isEqualTo("Internal Server Error");
                assertThat(header(response, "Access-Control-Allow-Origin")).isEqualTo(ORIGIN);
                assertThat(header(response, "Vary")).contains("Origin");
                assertThat(header(response, "X-Content-Type-Options")).isEqualTo("nosniff");
            }
        });
    }

    // ---- Helpers ----

    private static HttpResponse<String> preflight(TestClient client, String path, String method) {
        return preflight(client, path, method, ORIGIN);
    }

    private static HttpResponse<String> preflight(TestClient client, String path, String method,
            String origin) {
        return client.send(request -> request.uri(URI.create(client.url(path)))
                .header("Origin", origin)
                .header("Access-Control-Request-Method", method)
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()));
    }

    private static HttpResponse<String> get(TestClient client, String path, String origin) {
        return client.send(request -> request.uri(URI.create(client.url(path)))
                .header("Origin", origin)
                .GET());
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElseThrow(
                () -> new AssertionError("No " + name + " header: " + response.headers().map()));
    }
}
