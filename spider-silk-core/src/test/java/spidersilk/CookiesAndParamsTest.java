package spidersilk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            assertEquals("dark", client.get("/read").body());
        });
    }

    @Test
    void anAbsentCookieIsNull() {
        App app = new App().get("/read", req -> WebResponse.text(String.valueOf(req.cookie("nope"))));

        WebTest.test(app, client -> assertEquals("null", client.get("/read").body()));
    }

    @Test
    void everyCookieIsReadable() {
        App app = new App().get("/read", req -> WebResponse.text(req.cookies().toString()));

        WebTest.test(app, client -> {
            HttpResponse<String> response = client.send(request -> request
                    .uri(URI.create(client.url("/read")))
                    .header("Cookie", "a=1; b=2")
                    .GET());

            assertEquals("{a=1, b=2}", response.body());
        });
    }

    @Test
    void defaultsAreScopedToTheSiteAndHiddenFromScripts() {
        App app = new App().get("/set", req -> WebResponse.text("set").cookie("theme", "dark"));

        WebTest.test(app, client -> {
            String setCookie = client.get("/set").headers()
                    .firstValue("Set-Cookie").orElseThrow();

            assertTrue(setCookie.startsWith("theme=dark"), setCookie);
            assertTrue(setCookie.contains("Path=/"), setCookie);
            assertTrue(setCookie.contains("HttpOnly"), setCookie);
            assertTrue(setCookie.contains("SameSite=Lax"), setCookie);
        });
    }

    @Test
    void aMaxAgeMakesTheCookieOutliveTheSession() {
        App app = new App().get("/set",
                req -> WebResponse.text("set").cookie("token", "abc", Duration.ofDays(7)));

        WebTest.test(app, client -> {
            String setCookie = client.get("/set").headers()
                    .firstValue("Set-Cookie").orElseThrow();

            assertTrue(setCookie.contains("Max-Age=604800"), setCookie);
        });
    }

    @Test
    void removingACookieExpiresIt() {
        App app = new App().get("/logout", req -> WebResponse.text("bye").removeCookie("token"));

        WebTest.test(app, client -> {
            String setCookie = client.get("/logout").headers()
                    .firstValue("Set-Cookie").orElseThrow();

            assertTrue(setCookie.contains("Max-Age=0"), setCookie);
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

            assertTrue(setCookie.contains("Path=/api"), setCookie);
            assertTrue(setCookie.contains("Secure"), setCookie);
            assertTrue(setCookie.contains("SameSite=None"), setCookie);
        });
    }

    // ---- Repeated parameters ----

    @Test
    void repeatedQueryParametersAreAllReadable() {
        App app = new App().get("/search", req -> WebResponse.text(req.params("tag").toString()));

        WebTest.test(app, client ->
                assertEquals("[java, web, jvm]", client.get("/search?tag=java&tag=web&tag=jvm").body()));
    }

    @Test
    void repeatedFormFieldsAreAllReadable() {
        App app = new App().post("/cards", req -> WebResponse.text(req.params("tag").toString()));

        WebTest.test(app, client -> {
            HttpResponse<String> response = client.send(request -> request
                    .uri(URI.create(client.url("/cards")))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("tag=a&tag=b")));

            assertEquals("[a, b]", response.body());
        });
    }

    @Test
    void anAbsentParameterIsAnEmptyListRatherThanAnError() {
        App app = new App().get("/search", req -> WebResponse.text(req.params("tag").toString()));

        WebTest.test(app, client -> assertEquals("[]", client.get("/search").body()));
    }

    @Test
    void theSingularAccessorStillReturnsTheFirstValue() {
        App app = new App().get("/search", req ->
                WebResponse.text(req.param("tag") + " of " + req.params("tag").size()));

        WebTest.test(app, client ->
                assertEquals("java of 2", client.get("/search?tag=java&tag=web").body()));
    }

    @Test
    void aFormPostedThroughTheTestClientRoundTrips() {
        App app = new App().post("/decks", req -> WebResponse.text(req.param("name")));

        WebTest.test(app, client -> assertEquals("English",
                client.postForm("/decks", Map.of("name", "English")).body()));
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

        WebTest.test(app, client -> assertEquals("immutable", client.get("/search?tag=a").body()));
    }
}
