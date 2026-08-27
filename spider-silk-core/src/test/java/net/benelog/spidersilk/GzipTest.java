package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/** Compression: what gets compressed, what does not, and what caching has to be told. */
class GzipTest {

    /** Long enough to be worth compressing, and repetitive enough to compress well. */
    private static final String PAGE = "<p>a deck of cards</p>\n".repeat(200);

    @Test
    void aClientThatAsksForGzipGetsIt() {
        App app = new App().gzip().get("/page", req -> WebResponse.html(PAGE));

        WebTest.test(app, client -> {
            HttpResponse<byte[]> response = gzipped(client, "/page");

            assertThat(header(response, "Content-Encoding")).isEqualTo("gzip");
            assertThat(header(response, "Vary")).contains("Accept-Encoding");
            assertThat(response.body().length).isLessThan(PAGE.length());
            assertThat(inflate(response.body())).isEqualTo(PAGE);
        });
    }

    /** The length announced is the length sent, since the body is compressed up front. */
    @Test
    void theAnnouncedLengthIsTheCompressedLength() {
        App app = new App().gzip().get("/page", req -> WebResponse.html(PAGE));

        WebTest.test(app, client -> {
            HttpResponse<byte[]> response = gzipped(client, "/page");

            assertThat(header(response, "Content-Length"))
                    .isEqualTo(String.valueOf(response.body().length));
        });
    }

    /**
     * The answer differs by Accept-Encoding whether or not this one was
     * compressed, so a shared cache has to be told either way.
     */
    @Test
    void aClientThatDoesNotAskGetsThePlainBodyAndAVaryHeader() {
        App app = new App().gzip().get("/page", req -> WebResponse.html(PAGE));

        WebTest.test(app, client -> {
            HttpResponse<String> response = client.get("/page");

            assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
            assertThat(response.body()).isEqualTo(PAGE);
            assertThat(header(response, "Vary")).contains("Accept-Encoding");
        });
    }

    @Test
    void gzipWithAZeroQualityIsAClientSayingNo() {
        App app = new App().gzip().get("/page", req -> WebResponse.html(PAGE));

        WebTest.test(app, client -> {
            HttpResponse<byte[]> response = client.send(request -> request
                    .uri(URI.create(client.url("/page")))
                    .header("Accept-Encoding", "gzip;q=0, deflate")
                    .GET(), HttpResponse.BodyHandlers.ofByteArray());

            assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
        });
    }

    @Test
    void aBodyBelowTheThresholdIsLeftAlone() {
        App app = new App().gzip().get("/small", req -> WebResponse.text("hello"));

        WebTest.test(app, client -> {
            HttpResponse<byte[]> response = gzipped(client, "/small");

            assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
            assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("hello");
        });
    }

    @Test
    void theThresholdIsConfigurable() {
        App app = new App().gzip(Gzip.defaults().minBytes(0))
                .get("/small", req -> WebResponse.text("hello hello hello hello hello hello"));

        WebTest.test(app, client ->
                assertThat(header(gzipped(client, "/small"), "Content-Encoding")).isEqualTo("gzip"));
    }

    /** A JPEG is already compressed; deflating it spends CPU to make it larger. */
    @Test
    void anAlreadyCompressedTypeIsNotTouched() {
        byte[] image = new byte[4096];
        App app = new App().gzip().get("/photo", req -> WebResponse.bytes(image, "image/jpeg"));

        WebTest.test(app, client -> {
            HttpResponse<byte[]> response = gzipped(client, "/photo");

            assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
            assertThat(response.headers().firstValue("Vary")).isEmpty();
            assertThat(response.body()).hasSize(image.length);
        });
    }

    @Test
    void aResponseThatIsAlreadyEncodedIsLeftAlone() {
        App app = new App().gzip().get("/page", req -> WebResponse.html(PAGE)
                .header("Content-Encoding", "br"));

        WebTest.test(app, client ->
                assertThat(header(gzipped(client, "/page"), "Content-Encoding")).isEqualTo("br"));
    }

    // ---- Static files, which are streams and carry validators ----

    /**
     * The reason this is not an after-filter: the stylesheet is the biggest thing
     * most pages download, and a static file is answered before any filter runs.
     */
    @Test
    void aStaticFileIsCompressedAsItIsWritten() {
        WebTest.test(new App().gzip(), client -> {
            HttpResponse<byte[]> response = gzipped(client, "/style.css");

            assertThat(header(response, "Content-Encoding")).isEqualTo("gzip");
            assertThat(inflate(response.body())).isEqualTo("body { color: #2b303b; }\n");
            // The length the uncompressed file announced no longer describes this
            // one. What the container works out for itself is the sent length.
            response.headers().firstValue("Content-Length").ifPresent(length ->
                    assertThat(length).isEqualTo(String.valueOf(response.body().length)));
        });
    }

    /**
     * Compressed and uncompressed are the same file and not the same bytes,
     * which is what a weak validator means — and what {@code StaticFiles}
     * accepts back on the next request.
     */
    @Test
    void aCompressedFileCarriesAWeakETagThatStillRevalidates() {
        WebTest.test(new App().gzip(), client -> {
            String etag = header(gzipped(client, "/style.css"), "ETag");
            assertThat(etag).startsWith("W/\"");

            HttpResponse<byte[]> revalidated = client.send(request -> request
                    .uri(URI.create(client.url("/style.css")))
                    .header("Accept-Encoding", "gzip")
                    .header("If-None-Match", etag)
                    .GET(), HttpResponse.BodyHandlers.ofByteArray());

            assertThat(revalidated.statusCode()).isEqualTo(304);
            assertThat(revalidated.body()).isEmpty();
        });
    }

    @Test
    void anUncompressedFileKeepsItsStrongETag() {
        WebTest.test(new App().gzip(), client ->
                assertThat(header(client.get("/style.css"), "ETag")).startsWith("\""));
    }

    // ---- Configuration that cannot mean anything ----

    @Test
    void aNegativeThresholdIsRejected() {
        assertThatThrownBy(() -> Gzip.defaults().minBytes(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Helpers ----

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
