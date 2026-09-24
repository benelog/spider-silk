package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

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

    /**
     * A declined body leaves as the {@code Text} it entered as, so the request
     * logger sees the same body with gzip on as with it off. Both ways of
     * declining are covered: too small to try, and no smaller once tried, which
     * twelve bytes under a twenty-byte gzip header always are.
     */
    @Test
    void aDeclinedTextBodyIsStillTextToTheRequestLogger() {
        List<WebResponse.Body> logged = new CopyOnWriteArrayList<>();
        App app = new App().gzip(Gzip.defaults().minBytes(8))
                .requestLogger((req, completion) -> logged.add(completion.response().body()))
                .get("/small", req -> WebResponse.text("hello"))
                .get("/no-smaller", req -> WebResponse.text("hello, world"));

        WebTest.test(app, client -> {
            assertThat(gzipped(client, "/small").headers().firstValue("Content-Encoding")).isEmpty();
            assertThat(gzipped(client, "/no-smaller").headers().firstValue("Content-Encoding")).isEmpty();
        });

        assertThat(logged).containsExactly(
                new WebResponse.Text("hello"), new WebResponse.Text("hello, world"));
    }

    /** The threshold is in bytes as sent, and Korean text is three bytes a character. */
    @Test
    void theThresholdCountsEncodedBytesNotCharacters() {
        App app = new App().gzip(Gzip.defaults().minBytes(300))
                .get("/below", req -> WebResponse.text("가".repeat(99) + "ab"))
                .get("/at", req -> WebResponse.text("가".repeat(99) + "abc"));

        WebTest.test(app, client -> {
            // 101 chars and 299 bytes: under the threshold, although not by its length alone.
            HttpResponse<byte[]> below = gzipped(client, "/below");
            assertThat(below.headers().firstValue("Content-Encoding")).isEmpty();
            assertThat(new String(below.body(), StandardCharsets.UTF_8)).isEqualTo("가".repeat(99) + "ab");

            // 102 chars and 300 bytes: exactly at it.
            HttpResponse<byte[]> at = gzipped(client, "/at");
            assertThat(header(at, "Content-Encoding")).isEqualTo("gzip");
            assertThat(inflate(at.body())).isEqualTo("가".repeat(99) + "abc");
        });
    }

    @Test
    void theByteCountMatchesWhatTheWriterEncodes() {
        List<String> samples = List.of(
                "",
                "hello",
                "가".repeat(40),
                "😀".repeat(30),
                "é".repeat(50) + "가나다" + "😀",
                "a\uD83Db",                            // an unpaired high surrogate
                "\uDE00".repeat(20),                   // unpaired low surrogates
                "\uD83D".repeat(3) + "😀");  // unpaired highs, then a pair
        for (String sample : samples) {
            int encoded = sample.getBytes(StandardCharsets.UTF_8).length;
            for (int threshold = 0; threshold <= encoded + 4; threshold++) {
                assertThat(Gzip.reachesUtf8Bytes(sample, threshold))
                        .as("%s against %d (encodes to %d bytes)", sample, threshold, encoded)
                        .isEqualTo(encoded >= threshold);
            }
        }
    }

    /** A JPEG is already compressed; deflating it spends CPU to make it larger. */
    @Test
    void anAlreadyCompressedTypeIsNotTouched() {
        byte[] image = new byte[4096];
        App app = new App().gzip().get("/photo", req -> WebResponse.bytes("image/jpeg", image));

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

    /**
     * A streamed body is deflated as it is written, so the trailer has to be
     * written when the writer returns and not when the deflater is collected.
     * Reading it back is what says the trailer is there.
     */
    @Test
    void aStreamedBodyArrivesWholeAndItsLengthIsNotAnnounced() {
        App app = new App().gzip().get("/report", req ->
                WebResponse.stream("text/plain; charset=UTF-8", out -> {
                    for (int i = 0; i < 200; i++) {
                        out.write("<p>a deck of cards</p>\n".getBytes(StandardCharsets.UTF_8));
                    }
                }));

        WebTest.test(app, client -> {
            HttpResponse<byte[]> response = gzipped(client, "/report");

            assertThat(header(response, "Content-Encoding")).isEqualTo("gzip");
            assertThat(inflate(response.body())).isEqualTo(PAGE);
            assertThat(response.body().length).isLessThan(PAGE.length());
        });
    }

    // ---- A streamed body that fails ----

    /**
     * A writer that fails before writing anything leaves nothing sent. Ending
     * the gzip stream then would write a trailer and commit a whole, empty body
     * as a 200; not ending it leaves the servlet free to answer 500.
     */
    @Test
    void aWriterThatFailsBeforeWritingIsA500NotAnEmptyGzipBody() {
        List<RequestCompletion> logged = new CopyOnWriteArrayList<>();
        IOException failure = new IOException("planned writer failure");
        App app = new App().gzip().requestLogger((req, completion) -> logged.add(completion))
                .get("/broken", req -> WebResponse.stream("text/plain", out -> {
                    throw failure;
                }));

        WebTest.test(app, client -> {
            HttpResponse<byte[]> response = gzipped(client, "/broken");

            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
            assertThat(new String(response.body(), StandardCharsets.UTF_8))
                    .isEqualTo("Internal Server Error");
        });
        assertThat(logged).singleElement().satisfies(completion -> {
            assertThat(completion.statusCode()).isEqualTo(500);
            assertThat(completion.writeFailure()).isSameAs(failure);
        });
    }

    /**
     * A writer that fails after the response is committed cannot turn it into a
     * 500, so the transfer is aborted instead: the client sees a body that never
     * ended, not a truncated one that inflates cleanly.
     */
    @Test
    void aWriterThatFailsAfterCommittingAbortsTheTransfer() {
        List<RequestCompletion> logged = new CopyOnWriteArrayList<>();
        IOException failure = new IOException("planned committed failure");
        App app = new App().gzip().requestLogger((req, completion) -> logged.add(completion))
                .get("/partial", req -> WebResponse.stream("text/plain", out -> {
                    out.write("partial".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    throw failure;
                }));

        WebTest.test(app, client ->
                assertThatThrownBy(() -> gzipped(client, "/partial"))
                        .isInstanceOf(UncheckedIOException.class));
        assertThat(logged).singleElement().satisfies(completion -> {
            assertThat(completion.statusCode()).isEqualTo(200);
            assertThat(completion.writeFailure()).isSameAs(failure);
        });
    }

    /**
     * A failed writer still releases the Deflater's native memory rather than
     * leaving it to the Cleaner. The stream the writer was handed is ended, so
     * writing to it afterwards has no Deflater left to write through.
     */
    @Test
    void aWriterThatFailsStillEndsTheDeflater() {
        AtomicReference<OutputStream> handed = new AtomicReference<>();
        App app = new App().gzip()
                .get("/broken", req -> WebResponse.stream("text/plain", out -> {
                    handed.set(out);
                    throw new IOException("planned writer failure");
                }));

        WebTest.test(app, client ->
                assertThat(gzipped(client, "/broken").statusCode()).isEqualTo(500));
        assertThat(handed.get()).isInstanceOf(GZIPOutputStream.class);
        assertThatThrownBy(() -> handed.get().write("late".getBytes(StandardCharsets.UTF_8)))
                // Which unchecked type says so depends on the JDK; the message does not.
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Deflater has been closed");
    }

    // ---- Static files, which are streams and carry validators ----

    /**
     * The reason this is not an after-filter: the stylesheet is the biggest thing
     * most pages download, and a static file bypasses route filters.
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

    // ---- Configuration ----

    /** A media type is case-insensitive, so a configured one is folded, not compared as typed. */
    @Test
    void aConfiguredTypeMatchesWhateverCaseItWasWrittenIn() {
        App app = new App().gzip(Gzip.defaults().types("Text/HTML"))
                .get("/page", req -> WebResponse.html(PAGE));

        WebTest.test(app, client ->
                assertThat(header(gzipped(client, "/page"), "Content-Encoding")).isEqualTo("gzip"));
    }

    // ---- Configuration that cannot mean anything ----

    @Test
    void aNegativeThresholdIsRejected() {
        assertThatThrownBy(() -> Gzip.defaults().minBytes(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Helpers ----

    /**
     * The compressed length is known only by compressing the whole body, which
     * for a static file meant opening and reading it for every HEAD. A HEAD
     * now carries the GET's headers without running the writer at all.
     */
    @Test
    void aHeadForACompressedStreamRunsNoWriter() {
        AtomicInteger runs = new AtomicInteger();
        App app = new App().gzip().get("/big.css", req -> WebResponse.stream("text/css", out -> {
            runs.incrementAndGet();
            out.write("body{color:red}".repeat(300).getBytes(StandardCharsets.UTF_8));
        }));

        WebTest.test(app, client -> {
            HttpResponse<byte[]> head = client.send(request -> request
                    .uri(URI.create(client.url("/big.css")))
                    .header("Accept-Encoding", "gzip")
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()), HttpResponse.BodyHandlers.ofByteArray());

            assertThat(head.statusCode()).isEqualTo(200);
            assertThat(header(head, "Content-Encoding")).isEqualTo("gzip");
            assertThat(header(head, "Vary")).contains("Accept-Encoding");
            assertThat(head.headers().firstValue("Content-Length")).isEmpty();
            assertThat(runs.get()).as("a HEAD opens nothing").isZero();

            assertThat(inflate(gzipped(client, "/big.css").body())).isEqualTo("body{color:red}".repeat(300));
            assertThat(runs.get()).isEqualTo(1);
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
