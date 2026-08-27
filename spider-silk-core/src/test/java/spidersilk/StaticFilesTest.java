package spidersilk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import spidersilk.test.TestClient;
import spidersilk.test.WebTest;

/** Static file serving: content, validators, and conditional requests. */
class StaticFilesTest {

    private static final String CSS = "body { color: #2b303b; }\n";

    /** The asset the {@code /public} fixtures put a {@code .br} and a {@code .gz} beside. */
    private static final String APP_CSS = "body { font: 1rem/1.5 system-ui; }\n";

    /**
     * The siblings hold text rather than real brotli and gzip streams, which is
     * the point: what comes back proves the bytes were sent on untouched.
     */
    private static final String BROTLI = "not really brotli, but these are the bytes that go out\n";
    private static final String GZIP = "not really gzip, but these are the bytes that go out\n";

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

    // ---- a directory on disk ----

    @Test
    void aFileInADirectoryIsServedWithTheSameHeaders(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("style.css"), CSS);
        App app = new App().staticFiles(StaticFiles.directory(root));

        WebTest.test(app, client -> {
            HttpResponse<String> response = client.get("/style.css");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(CSS);
            assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                    .isEqualTo("text/css; charset=UTF-8");
            assertThat(response.headers().firstValue("Content-Length").orElseThrow())
                    .isEqualTo(String.valueOf(CSS.length()));
            assertThat(response.headers().firstValue("Cache-Control").orElseThrow())
                    .isEqualTo("no-cache");
            assertThat(response.headers().firstValue("Last-Modified")).isPresent();
        });
    }

    @Test
    void aDirectoryFileRevalidatesToNotModified(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("style.css"), CSS);
        App app = new App().staticFiles(StaticFiles.directory(root).maxAge(Duration.ofDays(365)));

        WebTest.test(app, client -> {
            String etag = client.get("/style.css").headers().firstValue("ETag").orElseThrow();
            assertThat(client.get("/style.css").headers().firstValue("Cache-Control").orElseThrow())
                    .isEqualTo("public, max-age=31536000");

            HttpResponse<String> response = client.send(request -> request
                    .uri(URI.create(client.url("/style.css")))
                    .header("If-None-Match", etag)
                    .GET());

            assertThat(response.statusCode()).isEqualTo(304);
            assertThat(response.body()).isEmpty();
        });
    }

    @Test
    void nestedFilesInADirectoryAreServedAndTheDirectoryItselfIsNot(@TempDir Path root)
            throws IOException {
        Files.createDirectory(root.resolve("sub"));
        Files.writeString(root.resolve("sub/nested.txt"), "nested\n");
        App app = new App().staticFiles(StaticFiles.directory(root));

        WebTest.test(app, client -> {
            assertThat(client.get("/sub/nested.txt").body()).isEqualTo("nested\n");
            assertThat(client.get("/sub").statusCode()).isEqualTo(404);
            assertThat(client.get("/sub/").statusCode()).isEqualTo(404);
            assertThat(client.get("/missing.txt").statusCode()).isEqualTo(404);
        });
    }

    /**
     * The guard a classpath lookup does not need: the file the request resolves
     * to, links followed, has to still be under the root.
     */
    @Test
    void aSymbolicLinkOutOfTheDirectoryIsNotServed(@TempDir Path parent) throws IOException {
        Path root = Files.createDirectory(parent.resolve("public"));
        Path secret = Files.writeString(parent.resolve("secret.txt"), "not yours\n");
        try {
            Files.createSymbolicLink(root.resolve("secret.txt"), secret);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("this file system does not do symbolic links");
        }
        App app = new App().staticFiles(StaticFiles.directory(root));

        WebTest.test(app, client -> {
            HttpResponse<String> response = client.get("/secret.txt");

            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.body()).doesNotContain("not yours");
        });
    }

    @Test
    void traversalOutOfADirectoryNeverReachesAFile(@TempDir Path parent) throws IOException {
        Path root = Files.createDirectory(parent.resolve("public"));
        Files.writeString(parent.resolve("secret.txt"), "not yours\n");
        App app = new App().staticFiles(StaticFiles.directory(root));

        WebTest.test(app, client -> {
            HttpResponse<String> response = client.get("/../secret.txt");

            assertThat(response.statusCode()).isGreaterThanOrEqualTo(400);
            assertThat(response.body()).doesNotContain("not yours");
        });
    }

    /** A volume that is not mounted yet is a 404, not a failure to start. */
    @Test
    void aDirectoryThatDoesNotExistStillRoutes(@TempDir Path parent) {
        App app = new App()
                .staticFiles(StaticFiles.directory(parent.resolve("never-mounted")))
                .get("/", req -> WebResponse.text("ok"));

        WebTest.test(app, client -> {
            assertThat(client.get("/").body()).isEqualTo("ok");
            assertThat(client.get("/style.css").statusCode()).isEqualTo(404);
        });
    }

    @Test
    void severalRootsAreReadInTheOrderGiven(@TempDir Path uploads) throws IOException {
        Files.writeString(uploads.resolve("avatar.txt"), "an upload\n");
        Files.writeString(uploads.resolve("style.css"), "shadowed\n");
        App app = new App().staticFiles(
                new StaticFiles("/public"),
                StaticFiles.directory(uploads));

        WebTest.test(app, client -> {
            assertThat(client.get("/style.css").body()).isEqualTo(CSS);
            assertThat(client.get("/avatar.txt").body()).isEqualTo("an upload\n");
        });
    }

    @Test
    void aHostedPathKeepsTheUploadsOffTheAssets(@TempDir Path uploads) throws IOException {
        Files.writeString(uploads.resolve("avatar.txt"), "an upload\n");
        App app = new App().staticFiles(
                new StaticFiles("/public"),
                StaticFiles.directory(uploads).hostedPath("/uploads"));

        WebTest.test(app, client -> {
            assertThat(client.get("/uploads/avatar.txt").body()).isEqualTo("an upload\n");
            assertThat(client.get("/avatar.txt").statusCode()).isEqualTo(404);
            assertThat(client.get("/style.css").body()).isEqualTo(CSS);
        });
    }

    @Test
    void noRootAtAllLeavesEveryPathToRouting() {
        App app = new App().staticFiles().get("/", req -> WebResponse.text("ok"));

        WebTest.test(app, client -> {
            assertThat(client.get("/").body()).isEqualTo("ok");
            assertThat(client.get("/style.css").statusCode()).isEqualTo(404);
        });
    }

    // ---- pre-compressed siblings ----

    @Test
    void aBrotliSiblingWinsWhereTheClientTakesBoth() {
        freshenSiblings();

        WebTest.test(new App().staticFiles(precompressed()), client -> {
            HttpResponse<String> response = encoded(client, "/app.css", "br, gzip");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Encoding")).hasValue("br");
            assertThat(response.body()).isEqualTo(BROTLI);
            assertThat(response.headers().firstValue("Content-Length").orElseThrow())
                    .isEqualTo(String.valueOf(BROTLI.length()));
        });
    }

    /** The extension is the encoding, so the type still comes from the original name. */
    @Test
    void aSiblingIsAnnouncedAsTheOriginalsContentType() {
        freshenSiblings();

        WebTest.test(new App().staticFiles(precompressed()), client ->
                assertThat(encoded(client, "/app.css", "br").headers()
                        .firstValue("Content-Type").orElseThrow())
                        .isEqualTo("text/css; charset=UTF-8"));
    }

    @Test
    void aClientThatCannotReadBrotliGetsTheGzipSibling() {
        freshenSiblings();

        WebTest.test(new App().staticFiles(precompressed()), client -> {
            HttpResponse<String> response = encoded(client, "/app.css", "gzip");

            assertThat(response.headers().firstValue("Content-Encoding")).hasValue("gzip");
            assertThat(response.body()).isEqualTo(GZIP);
        });
    }

    @Test
    void aClientThatTakesNeitherGetsTheFileItself() {
        freshenSiblings();

        WebTest.test(new App().staticFiles(precompressed()), client -> {
            HttpResponse<String> response = client.get("/app.css");

            assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
            assertThat(response.body()).isEqualTo(APP_CSS);
        });
    }

    /** Nothing is on until it is named, a sibling sitting there included. */
    @Test
    void aSiblingIsNotLookedForUntilPrecompressedIsNamed() {
        WebTest.test(new App().staticFiles("/public"), client -> {
            HttpResponse<String> response = encoded(client, "/app.css", "br, gzip");

            assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
            assertThat(response.headers().firstValue("Vary")).isEmpty();
            assertThat(response.body()).isEqualTo(APP_CSS);
        });
    }

    /** A shared cache must not hand encoded bytes to a client that cannot read them. */
    @Test
    void everyAnswerVariesOnAcceptEncodingWhetherOrNotASiblingWasFound() {
        freshenSiblings();

        WebTest.test(new App().staticFiles(precompressed()), client -> {
            assertThat(encoded(client, "/app.css", "br").headers().firstValue("Vary"))
                    .hasValue("Accept-Encoding");
            assertThat(client.get("/style.css").headers().firstValue("Vary"))
                    .hasValue("Accept-Encoding");
        });
    }

    /**
     * The two encodings are one resource, so the validator comes from the file
     * whichever body is sent — weak, because the bytes are not the same bytes.
     */
    @Test
    void aSiblingCarriesTheOriginalsValidatorMarkedWeak() {
        freshenSiblings();

        WebTest.test(new App().staticFiles(precompressed()), client -> {
            String plain = client.get("/app.css").headers().firstValue("ETag").orElseThrow();
            String weak = encoded(client, "/app.css", "br").headers()
                    .firstValue("ETag").orElseThrow();
            assertThat(weak).isEqualTo("W/" + plain);

            HttpResponse<String> revalidated = client.send(request -> request
                    .uri(URI.create(client.url("/app.css")))
                    .header("Accept-Encoding", "br")
                    .header("If-None-Match", weak)
                    .GET());

            assertThat(revalidated.statusCode()).isEqualTo(304);
            assertThat(revalidated.body()).isEmpty();
            assertThat(revalidated.headers().firstValue("Vary")).hasValue("Accept-Encoding");
        });
    }

    /** Deflating a body that is already a brotli stream is the failure to avoid. */
    @Test
    void gzipLeavesASiblingAlone() {
        freshenSiblings();

        WebTest.test(new App().gzip().staticFiles(precompressed()), client -> {
            HttpResponse<String> response = encoded(client, "/app.css", "gzip");

            assertThat(response.headers().allValues("Content-Encoding"))
                    .containsExactly("gzip");
            assertThat(response.body()).isEqualTo(GZIP);
        });
    }

    /** No sibling is the case gzip() was already answering, and it still answers it. */
    @Test
    void anAssetWithNoSiblingFallsBackToDeflatingOnTheFly() {
        WebTest.test(new App().gzip().staticFiles(precompressed()), client -> {
            HttpResponse<byte[]> response = client.send(request -> request
                    .uri(URI.create(client.url("/style.css")))
                    .header("Accept-Encoding", "gzip")
                    .GET(), HttpResponse.BodyHandlers.ofByteArray());

            assertThat(response.headers().firstValue("Content-Encoding")).hasValue("gzip");
            assertThat(inflate(response.body())).isEqualTo(CSS);
            assertThat(response.headers().firstValue("Vary")).hasValue("Accept-Encoding");
        });
    }

    @Test
    void aSiblingInADirectoryIsServedTheSameWay(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("app.css"), APP_CSS);
        Files.writeString(root.resolve("app.css.br"), BROTLI);

        App app = new App().staticFiles(StaticFiles.directory(root).precompressed());
        WebTest.test(app, client -> {
            HttpResponse<String> response = encoded(client, "/app.css", "br");

            assertThat(response.headers().firstValue("Content-Encoding")).hasValue("br");
            assertThat(response.body()).isEqualTo(BROTLI);
        });
    }

    /** A sibling older than what it sits next to is a build that did not rerun. */
    @Test
    void aStaleSiblingIsPassedOver(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("app.css"), APP_CSS);
        Path stale = Files.writeString(root.resolve("app.css.br"), BROTLI);
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minusSeconds(3600)));

        App app = new App().staticFiles(StaticFiles.directory(root).precompressed());
        WebTest.test(app, client -> {
            HttpResponse<String> response = encoded(client, "/app.css", "br");

            assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
            assertThat(response.body()).isEqualTo(APP_CSS);
        });
    }

    /** It is a sibling of the file being served, not a file of its own to fall back on. */
    @Test
    void aSiblingWithNothingBesideItIsNotServed(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("app.css.br"), BROTLI);

        App app = new App().staticFiles(StaticFiles.directory(root).precompressed());
        WebTest.test(app, client ->
                assertThat(encoded(client, "/app.css", "br").statusCode()).isEqualTo(404));
    }

    // ---- helpers ----

    private static StaticFiles precompressed() {
        return new StaticFiles("/public").precompressed();
    }

    private static HttpResponse<String> encoded(TestClient client, String path, String encodings) {
        return client.send(request -> request
                .uri(URI.create(client.url(path)))
                .header("Accept-Encoding", encodings)
                .GET());
    }

    /**
     * The build copies the checkout's timestamps across, and which of two files
     * a checkout wrote first is not this test's to decide — so the siblings are
     * made no older than the asset before the freshness rule is asked about them.
     */
    private static void freshenSiblings() {
        for (String name : new String[] {"/public/app.css.br", "/public/app.css.gz"}) {
            URL url = StaticFilesTest.class.getResource(name);
            Assumptions.assumeTrue(url != null && "file".equals(url.getProtocol()),
                    "The fixtures are not on a filesystem this can touch: " + name);
            try {
                Path path = Path.of(url.toURI());
                Files.setLastModifiedTime(path, FileTime.from(Instant.now()));
            } catch (IOException | URISyntaxException e) {
                throw new IllegalStateException("Cannot freshen " + name, e);
            }
        }
    }

    private static String inflate(byte[] compressed) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
