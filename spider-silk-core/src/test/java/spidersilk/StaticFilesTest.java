package spidersilk;

import static org.assertj.core.api.Assertions.assertThat;

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

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(CSS);
        });
    }

    @Test
    void servesAFileWithLengthAndValidators() {
        WebTest.test(new App().staticFiles("/public"), client -> {
            HttpResponse<String> response = client.get("/style.css");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(CSS);
            assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                    .isEqualTo("text/css; charset=UTF-8");
            assertThat(response.headers().firstValue("Content-Length").orElseThrow())
                    .isEqualTo(String.valueOf(CSS.length()));
            assertThat(response.headers().firstValue("Cache-Control").orElseThrow())
                    .isEqualTo("no-cache");
            assertThat(response.headers().firstValue("ETag")).isPresent();
            assertThat(response.headers().firstValue("Last-Modified")).isPresent();
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

            assertThat(response.statusCode()).isEqualTo(304);
            assertThat(response.body()).isEmpty();
        });
    }

    @Test
    void aStaleETagGetsTheFileAgain() {
        WebTest.test(new App().staticFiles("/public"), client -> {
            HttpResponse<String> response = client.send(request -> request
                    .uri(URI.create(client.url("/style.css")))
                    .header("If-None-Match", "\"something-else\"")
                    .GET());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(CSS);
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

            assertThat(response.statusCode()).isEqualTo(304);
        });
    }

    @Test
    void anOlderIfModifiedSinceGetsTheFileAgain() {
        WebTest.test(new App().staticFiles("/public"), client -> {
            HttpResponse<String> response = client.send(request -> request
                    .uri(URI.create(client.url("/style.css")))
                    .header("If-Modified-Since", "Tue, 01 Jan 2019 00:00:00 GMT")
                    .GET());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(CSS);
        });
    }

    @Test
    void maxAgeReplacesTheRevalidationDefault() {
        App app = new App().staticFiles(
                new StaticFiles("/public").maxAge(Duration.ofDays(365)));

        WebTest.test(app, client ->
                assertThat(client.get("/style.css").headers().firstValue("Cache-Control").orElseThrow())
                        .isEqualTo("public, max-age=31536000"));
    }

    @Test
    void hostedPathMovesTheFilesAndNothingElseServesThem() {
        App app = new App().staticFiles(
                new StaticFiles("/public").hostedPath("/assets"));

        WebTest.test(app, client -> {
            assertThat(client.get("/assets/style.css").statusCode()).isEqualTo(200);
            assertThat(client.get("/assets/style.css").body()).isEqualTo(CSS);
            assertThat(client.get("/style.css").statusCode()).isEqualTo(404);
        });
    }

    @Test
    void nestedFilesAreServed() {
        WebTest.test(new App().staticFiles("/public"),
                client -> assertThat(client.get("/sub/nested.txt").body()).isEqualTo("nested\n"));
    }

    @Test
    void directoriesAreNotServed() {
        WebTest.test(new App().staticFiles("/public"), client -> {
            assertThat(client.get("/sub").statusCode()).isEqualTo(404);
            assertThat(client.get("/sub/").statusCode()).isEqualTo(404);
        });
    }

    /** Jetty rejects a traversal with a 400 before the servlet sees it; the ".." guard is the second line. */
    @Test
    void traversalNeverReachesAFile() {
        WebTest.test(new App().staticFiles("/public"), client -> {
            HttpResponse<String> response = client.get("/../style.css");

            assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
            assertThat(response.body()).doesNotContain("#2b303b");
        });
    }

    @Test
    void routesWinOverFiles() {
        App app = new App()
                .staticFiles("/public")
                .get("/style.css", req -> WebResponse.text("from the route"));

        WebTest.test(app, client ->
                assertThat(client.get("/style.css").body()).isEqualTo("from the route"));
    }

    @Test
    void aRootWithNothingInItStillRoutes() {
        App app = new App().staticFiles("/nowhere").get("/", req -> WebResponse.text("ok"));

        WebTest.test(app, client -> {
            assertThat(client.get("/").body()).isEqualTo("ok");
            assertThat(client.get("/style.css").statusCode()).isEqualTo(404);
        });
    }

    @Test
    void headGetsTheHeadersWithoutTheBody() {
        WebTest.test(new App().staticFiles("/public"), client -> {
            HttpResponse<String> response = client.send(request -> request
                    .uri(URI.create(client.url("/style.css")))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()));

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEmpty();
            assertThat(response.headers().firstValue("ETag")).isNotEmpty();
        });
    }
}
