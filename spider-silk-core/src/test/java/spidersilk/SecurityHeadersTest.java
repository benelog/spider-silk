package spidersilk;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import spidersilk.test.TestRequest;
import spidersilk.test.WebTest;

/** The opinionated header set: what goes out by default, and what it never overwrites. */
class SecurityHeadersTest {

    @Test
    void everyResponseCarriesTheDefaults() {
        App app = new App().securityHeaders().get("/page", req -> WebResponse.html("<p>hi</p>"));

        WebTest.test(app, client -> {
            HttpResponse<String> response = client.get("/page");

            assertThat(header(response, "X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(header(response, "X-Frame-Options")).isEqualTo("DENY");
            assertThat(header(response, "Referrer-Policy"))
                    .isEqualTo("strict-origin-when-cross-origin");
        });
    }

    /** The reason this is not an after-filter: no filter runs for a 404. */
    @Test
    void anErrorPageCarriesThemToo() {
        WebTest.test(new App().securityHeaders(), client -> {
            HttpResponse<String> response = client.get("/nope");

            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(header(response, "X-Frame-Options")).isEqualTo("DENY");
        });
    }

    /** Nor for a static file, which is answered before routing gets that far. */
    @Test
    void aStaticFileCarriesThemToo() {
        WebTest.test(new App().securityHeaders(), client ->
                assertThat(header(client.get("/style.css"), "X-Content-Type-Options"))
                        .isEqualTo("nosniff"));
    }

    @Test
    void aResponseThatSaysItForItselfKeepsItsOwnValue() {
        App app = new App().securityHeaders().get("/embeddable",
                req -> WebResponse.html("<p>frame me</p>").header("X-Frame-Options", "SAMEORIGIN"));

        WebTest.test(app, client ->
                assertThat(header(client.get("/embeddable"), "X-Frame-Options"))
                        .isEqualTo("SAMEORIGIN"));
    }

    @Test
    void theConfiguredValuesReplaceTheDefaults() {
        SecurityHeaders headers = SecurityHeaders.defaults()
                .frameOptions("SAMEORIGIN")
                .referrerPolicy("no-referrer")
                .contentSecurityPolicy("default-src 'self'")
                .permissionsPolicy("camera=()")
                .header("X-Robots-Tag", "noindex");

        WebTest.test(new App().securityHeaders(headers)
                .get("/page", req -> WebResponse.html("<p>hi</p>")), client -> {
            HttpResponse<String> response = client.get("/page");

            assertThat(header(response, "X-Frame-Options")).isEqualTo("SAMEORIGIN");
            assertThat(header(response, "Referrer-Policy")).isEqualTo("no-referrer");
            assertThat(header(response, "Content-Security-Policy")).isEqualTo("default-src 'self'");
            assertThat(header(response, "Permissions-Policy")).isEqualTo("camera=()");
            assertThat(header(response, "X-Robots-Tag")).isEqualTo("noindex");
        });
    }

    @Test
    void thereIsNoPolicyUntilOneIsGiven() {
        App app = new App().securityHeaders().get("/page", req -> WebResponse.html("<p>hi</p>"));

        WebTest.test(app, client -> {
            HttpResponse<String> response = client.get("/page");

            assertThat(response.headers().firstValue("Content-Security-Policy")).isEmpty();
            assertThat(response.headers().firstValue("Permissions-Policy")).isEmpty();
        });
    }

    // ---- HSTS, which depends on the request as well as the configuration ----

    /**
     * A browser that learns this over plain HTTP learned it from whoever was in
     * the way, and cannot unlearn it for as long as it was told.
     */
    @Test
    void hstsStaysOffOverPlainHttp() {
        SecurityHeaders headers = SecurityHeaders.defaults().hsts(Duration.ofDays(365));
        App app = new App().securityHeaders(headers)
                .get("/page", req -> WebResponse.html("<p>hi</p>"));

        WebTest.test(app, client ->
                assertThat(client.get("/page").headers()
                        .firstValue("Strict-Transport-Security")).isEmpty());
    }

    @Test
    void hstsGoesOutOverHttps() {
        SecurityHeaders headers = SecurityHeaders.defaults().hsts(Duration.ofDays(365));

        WebResponse response = headers.apply(WebResponse.html("<p>hi</p>"),
                TestRequest.get("/page").secure().build());

        assertThat(response.header("Strict-Transport-Security"))
                .isEqualTo("max-age=31536000; includeSubDomains");
    }

    @Test
    void subdomainsCanBeLeftOut() {
        SecurityHeaders headers = SecurityHeaders.defaults().hsts(Duration.ofDays(1), false);

        WebResponse response = headers.apply(WebResponse.html("<p>hi</p>"),
                TestRequest.get("/page").secure().build());

        assertThat(response.header("Strict-Transport-Security")).isEqualTo("max-age=86400");
    }

    @Test
    void nothingIsAddedWhenNothingIsConfigured() {
        WebTest.test(new App().get("/page", req -> WebResponse.html("<p>hi</p>")), client ->
                assertThat(client.get("/page").headers()
                        .firstValue("X-Content-Type-Options")).isEmpty());
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElseThrow(
                () -> new AssertionError("No " + name + " header: " + response.headers().map()));
    }
}
