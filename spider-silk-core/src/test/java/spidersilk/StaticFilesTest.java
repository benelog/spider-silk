package spidersilk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import spidersilk.test.WebTest;

/** Static file serving: content, validators, and conditional requests. */
class StaticFilesTest {

    private static final String CSS = "body { color: #2b303b; }\n";

    @Test
    void classpathPublicIsServedWithoutBeingConfigured() {
        WebTest.test(new App(), client -> {
            HttpResponse<String> response = client.get("/style.css");

            assertEquals(200, response.statusCode());
            assertEquals(CSS, response.body());
        });
    }

    @Test
    void servesAFileWithLengthAndValidators() {
        WebTest.test(new App().staticFiles("/public"), client -> {
            HttpResponse<String> response = client.get("/style.css");

            assertEquals(200, response.statusCode());
            assertEquals(CSS, response.body());
            assertEquals("text/css; charset=UTF-8",
                    response.headers().firstValue("Content-Type").orElseThrow());
            assertEquals(String.valueOf(CSS.length()),
                    response.headers().firstValue("Content-Length").orElseThrow());
            assertEquals("no-cache",
                    response.headers().firstValue("Cache-Control").orElseThrow());
            assertTrue(response.headers().firstValue("ETag").isPresent());
            assertTrue(response.headers().firstValue("Last-Modified").isPresent());
        });
    }

    @Test
    void aMatchingETagGetsABodylessNotModified() {
        WebTest.test(new App().staticFiles("/public"), client -> {
            String etag = client.get("/style.css").headers().firstValue("ETag").orElseThrow();

            HttpResponse<String> response = client.send(request -> request
                    .uri(URI.create(client.url("/style.css")))
                    .header("If-None-Match", etag)
                    .GET());

            assertEquals(304, response.statusCode());
            assertEquals("", response.body());
        });
    }

    @Test
    void aStaleETagGetsTheFileAgain() {
        WebTest.test(new App().staticFiles("/public"), client -> {
            HttpResponse<String> response = client.send(request -> request
                    .uri(URI.create(client.url("/style.css")))
                    .header("If-None-Match", "\"something-else\"")
                    .GET());

            assertEquals(200, response.statusCode());
            assertEquals(CSS, response.body());
        });
    }

    @Test
    void anIfModifiedSinceAtOrAfterTheFileGetsNotModified() {
        WebTest.test(new App().staticFiles("/public"), client -> {
            String lastModified =
                    client.get("/style.css").headers().firstValue("Last-Modified").orElseThrow();

            HttpResponse<String> response = client.send(request -> request
                    .uri(URI.create(client.url("/style.css")))
                    .header("If-Modified-Since", lastModified)
                    .GET());

            assertEquals(304, response.statusCode());
        });
    }

    @Test
    void anOlderIfModifiedSinceGetsTheFileAgain() {
        WebTest.test(new App().staticFiles("/public"), client -> {
            HttpResponse<String> response = client.send(request -> request
                    .uri(URI.create(client.url("/style.css")))
                    .header("If-Modified-Since", "Tue, 01 Jan 2019 00:00:00 GMT")
                    .GET());

            assertEquals(200, response.statusCode());
            assertEquals(CSS, response.body());
        });
    }

    @Test
    void maxAgeReplacesTheRevalidationDefault() {
        App app = new App().staticFiles(
                new StaticFiles("/public").maxAge(Duration.ofDays(365)));

        WebTest.test(app, client -> assertEquals("public, max-age=31536000",
                client.get("/style.css").headers().firstValue("Cache-Control").orElseThrow()));
    }

    @Test
    void hostedPathMovesTheFilesAndNothingElseServesThem() {
        App app = new App().staticFiles(
                new StaticFiles("/public").hostedPath("/assets"));

        WebTest.test(app, client -> {
            assertEquals(200, client.get("/assets/style.css").statusCode());
            assertEquals(CSS, client.get("/assets/style.css").body());
            assertEquals(404, client.get("/style.css").statusCode());
        });
    }

    @Test
    void nestedFilesAreServed() {
        WebTest.test(new App().staticFiles("/public"),
                client -> assertEquals("nested\n", client.get("/sub/nested.txt").body()));
    }

    @Test
    void directoriesAreNotServed() {
        WebTest.test(new App().staticFiles("/public"), client -> {
            assertEquals(404, client.get("/sub").statusCode());
            assertEquals(404, client.get("/sub/").statusCode());
        });
    }

    /** Jetty rejects a traversal with a 400 before the servlet sees it; the ".." guard is the second line. */
    @Test
    void traversalNeverReachesAFile() {
        WebTest.test(new App().staticFiles("/public"), client -> {
            HttpResponse<String> response = client.get("/../style.css");

            assertTrue(response.statusCode() >= 400, "got: " + response.statusCode());
            assertFalse(response.body().contains("#2b303b"));
        });
    }

    @Test
    void routesWinOverFiles() {
        App app = new App()
                .staticFiles("/public")
                .get("/style.css", req -> WebResponse.text("from the route"));

        WebTest.test(app, client -> assertEquals("from the route", client.get("/style.css").body()));
    }

    @Test
    void aRootWithNothingInItStillRoutes() {
        App app = new App().staticFiles("/nowhere").get("/", req -> WebResponse.text("ok"));

        WebTest.test(app, client -> {
            assertEquals("ok", client.get("/").body());
            assertEquals(404, client.get("/style.css").statusCode());
        });
    }

    @Test
    void headGetsTheHeadersWithoutTheBody() {
        WebTest.test(new App().staticFiles("/public"), client -> {
            HttpResponse<String> response = client.send(request -> request
                    .uri(URI.create(client.url("/style.css")))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()));

            assertEquals(200, response.statusCode());
            assertEquals("", response.body());
            assertFalse(response.headers().firstValue("ETag").isEmpty());
        });
    }
}
