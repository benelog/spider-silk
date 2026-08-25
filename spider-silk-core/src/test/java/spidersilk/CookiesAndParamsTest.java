package spidersilk;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;

import spidersilk.test.WebTest;

/** Cookies and repeated parameters, end to end. */
class CookiesAndParamsTest {

    // ---- Cookies ----

    @Test
    void aCookieSetOnOneRequestComesBackOnTheNext() {
        App app = new App()
                .get("/set", req -> WebResponse.text("set").cookie("theme", "dark"))
                .get("/read", req -> WebResponse.text(String.valueOf(req.cookie("theme"))));

        WebTest.test(app, client -> {
            client.get("/set");
            assertThat(client.get("/read").body()).isEqualTo("dark");
        });
    }

    @Test
    void anAbsentCookieIsNull() {
        App app = new App().get("/read", req -> WebResponse.text(String.valueOf(req.cookie("nope"))));

        WebTest.test(app, client -> assertThat(client.get("/read").body()).isEqualTo("null"));
    }

    @Test
    void everyCookieIsReadable() {
        App app = new App().get("/read", req -> WebResponse.text(req.cookies().toString()));

        WebTest.test(app, client -> {
            HttpResponse<String> response = client.send(request -> request
                    .uri(URI.create(client.url("/read")))
                    .header("Cookie", "a=1; b=2")
                    .GET());

            assertThat(response.body()).isEqualTo("{a=1, b=2}");
        });
    }

    @Test
    void defaultsAreScopedToTheSiteAndHiddenFromScripts() {
        App app = new App().get("/set", req -> WebResponse.text("set").cookie("theme", "dark"));

        WebTest.test(app, client -> {
            String setCookie = client.get("/set").headers()
                    .firstValue("Set-Cookie").orElseThrow();

            assertThat(setCookie).startsWith("theme=dark");
            assertThat(setCookie).contains("Path=/");
            assertThat(setCookie).contains("HttpOnly");
            assertThat(setCookie).contains("SameSite=Lax");
        });
    }

    @Test
    void aMaxAgeMakesTheCookieOutliveTheSession() {
        App app = new App().get("/set",
                req -> WebResponse.text("set").cookie("token", "abc", Duration.ofDays(7)));

        WebTest.test(app, client -> {
            String setCookie = client.get("/set").headers()
                    .firstValue("Set-Cookie").orElseThrow();

            assertThat(setCookie).contains("Max-Age=604800");
        });
    }

    @Test
    void removingACookieExpiresIt() {
        App app = new App().get("/logout", req -> WebResponse.text("bye").removeCookie("token"));

        WebTest.test(app, client -> {
            String setCookie = client.get("/logout").headers()
                    .firstValue("Set-Cookie").orElseThrow();

            assertThat(setCookie).startsWith("token=;");
            // Both are RFC 6265 deletions, and which one a container writes is its own
            // business: Jetty 12.1 switched from Max-Age=0 to the epoch Expires.
            assertThat(setCookie).satisfiesAnyOf(
                    header -> assertThat(header).contains("Max-Age=0"),
                    header -> assertThat(header).contains("Expires=Thu, 01 Jan 1970"));
        });
    }

    /** The hand-built escape hatch reaches attributes the defaults do not set. */
    @Test
    void aHandBuiltCookieIsSentAsIs() {
        App app = new App().get("/set", req -> {
            Cookie cookie = new Cookie("cross", "site");
            cookie.setPath("/api");
            cookie.setSecure(true);
            cookie.setAttribute("SameSite", "None");
            return WebResponse.text("set").cookie(cookie);
        });

        WebTest.test(app, client -> {
            String setCookie = client.get("/set").headers()
                    .firstValue("Set-Cookie").orElseThrow();

            assertThat(setCookie).contains("Path=/api");
            assertThat(setCookie).contains("Secure");
            assertThat(setCookie).contains("SameSite=None");
        });
    }

    // ---- Repeated parameters ----

    @Test
    void repeatedQueryParametersAreAllReadable() {
        App app = new App().get("/search", req -> WebResponse.text(req.params("tag").toString()));

        WebTest.test(app, client ->
                assertThat(client.get("/search?tag=java&tag=web&tag=jvm").body()).isEqualTo("[java, web, jvm]"));
    }

    @Test
    void repeatedFormFieldsAreAllReadable() {
        App app = new App().post("/cards", req -> WebResponse.text(req.params("tag").toString()));

        WebTest.test(app, client -> {
            HttpResponse<String> response = client.send(request -> request
                    .uri(URI.create(client.url("/cards")))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("tag=a&tag=b")));

            assertThat(response.body()).isEqualTo("[a, b]");
        });
    }

    @Test
    void anAbsentParameterIsAnEmptyListRatherThanAnError() {
        App app = new App().get("/search", req -> WebResponse.text(req.params("tag").toString()));

        WebTest.test(app, client -> assertThat(client.get("/search").body()).isEqualTo("[]"));
    }

    @Test
    void theSingularAccessorStillReturnsTheFirstValue() {
        App app = new App().get("/search", req ->
                WebResponse.text(req.param("tag") + " of " + req.params("tag").size()));

        WebTest.test(app, client ->
                assertThat(client.get("/search?tag=java&tag=web").body()).isEqualTo("java of 2"));
    }

    @Test
    void aFormPostedThroughTheTestClientRoundTrips() {
        App app = new App().post("/decks", req -> WebResponse.text(req.param("name")));

        WebTest.test(app, client ->
                assertThat(client.postForm("/decks", Map.of("name", "English")).body())
                        .isEqualTo("English"));
    }

    @Test
    void theListIsImmutable() {
        App app = new App().get("/search", req -> {
            List<String> tags = req.params("tag");
            try {
                tags.add("sneaky");
                return WebResponse.text("mutable");
            } catch (UnsupportedOperationException e) {
                return WebResponse.text("immutable");
            }
        });

        WebTest.test(app, client ->
                assertThat(client.get("/search?tag=a").body()).isEqualTo("immutable"));
    }
}
