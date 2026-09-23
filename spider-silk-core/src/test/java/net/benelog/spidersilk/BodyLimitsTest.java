package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.json.JsonReader;
import net.benelog.spidersilk.test.TestClient;
import net.benelog.spidersilk.test.TestRequest;
import net.benelog.spidersilk.test.WebTest;

/**
 * {@code body()} and {@code bodyNdjson(...)} bound what they hold in memory,
 * counted in bytes as they arrive, and refuse with 413 what does not fit.
 */
class BodyLimitsTest {

    private static final JsonReader<String> NAME = json -> json.asObject().getString("name");

    /** The body's length in characters, so a test can tell bytes from characters. */
    private static App echoLength(BodyLimits limits) {
        return new App().bodyLimits(limits)
                .post("/body", req -> WebResponse.text(req.body().length() + ""))
                .post("/ndjson", req -> WebResponse.text(req.bodyNdjson(NAME).count() + ""));
    }

    // ---- body() ----

    @Test
    void aBodyExactlyAtTheLimitIsReadAndOneByteOverIsA413() {
        WebTest.test(echoLength(BodyLimits.defaults().maxBytes(16)), client -> {
            var atLimit = client.post("/body", "a".repeat(16));
            var overLimit = client.post("/body", "a".repeat(17));

            assertThat(atLimit.statusCode()).isEqualTo(200);
            assertThat(atLimit.body()).isEqualTo("16");
            assertThat(overLimit.statusCode()).isEqualTo(413);
            assertThat(overLimit.body()).contains("16 bytes");
        });
    }

    /** No Content-Length to check up front: the bytes are counted as they arrive. */
    @Test
    void aChunkedBodyIsCountedAsItArrives() {
        WebTest.test(echoLength(BodyLimits.defaults().maxBytes(16)), client -> {
            assertThat(postChunked(client, "/body", "a".repeat(16)).body()).isEqualTo("16");
            assertThat(postChunked(client, "/body", "a".repeat(17)).statusCode()).isEqualTo(413);
        });
    }

    /** "é" is two bytes in UTF-8: the limit counts bytes, not characters. */
    @Test
    void theLimitCountsBytesNotCharacters() {
        WebTest.test(echoLength(BodyLimits.defaults().maxBytes(4)), client -> {
            var fourBytes = client.post("/body", "éé");
            var fiveBytes = postChunked(client, "/body", "ééa");

            assertThat(fourBytes.statusCode()).isEqualTo(200);
            assertThat(fourBytes.body()).isEqualTo("2");
            assertThat(fiveBytes.statusCode()).isEqualTo(413);
        });
    }

    @Test
    void bodyJsonIsBoundByTheSameLimit() {
        App app = new App().bodyLimits(BodyLimits.defaults().maxBytes(8))
                .post("/decks", req -> WebResponse.text(req.bodyJson(NAME)));

        WebTest.test(app, client -> {
            assertThat(client.postJson("/decks", "{\"name\":\"a\"}").statusCode()).isEqualTo(413);
            assertThat(client.postJson("/decks", "{\"name\"}").statusCode()).isEqualTo(400);
        });
    }

    /**
     * A handler that catches the 413 does not get to read on: the start of the
     * body is gone, and what is left would be a truncated body.
     */
    @Test
    void aRefusedBodyStaysRefused() {
        App app = new App().bodyLimits(BodyLimits.defaults().maxBytes(4))
                .post("/retry", req -> {
                    try {
                        req.body();
                    } catch (HttpException refused) {
                        // A handler that ignores the refusal and tries again.
                    }
                    return WebResponse.text("read " + req.body());
                })
                .post("/stream", req -> {
                    try {
                        req.body();
                    } catch (HttpException refused) {
                        // The same, reaching for the bytes this time.
                    }
                    try (InputStream in = req.bodyStream()) {
                        return WebResponse.text("read " + new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                });

        WebTest.test(app, client -> {
            var retried = postChunked(client, "/retry", "abcdefgh");
            var streamed = postChunked(client, "/stream", "abcdefgh");

            assertThat(retried.statusCode()).isEqualTo(413);
            assertThat(retried.body()).doesNotContain("read");
            assertThat(streamed.statusCode()).isEqualTo(413);
            assertThat(streamed.body()).doesNotContain("read");
        });
    }

    /** A request that claims fewer bytes than it sends is still stopped at the limit. */
    @Test
    void aContentLengthThatUnderstatesTheBodyIsNotTrusted() {
        HttpServletRequest raw = new BodyOf(TestRequest.post("/x").build().raw(),
                new ByteArrayInputStream("a".repeat(100).getBytes(StandardCharsets.UTF_8)), 3);
        WebRequest request = new WebRequest(raw, Map.of(), BodyLimits.defaults().maxBytes(10));

        assertThatThrownBy(request::body)
                .isInstanceOfSatisfying(HttpException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE));
    }

    /** A Content-Length over the limit is refused before a byte is read. */
    @Test
    void aDeclaredLengthOverTheLimitIsRefusedUnread() {
        CountingStream body = new CountingStream(100, "a".getBytes(StandardCharsets.UTF_8));
        WebRequest request = new WebRequest(new BodyOf(TestRequest.post("/x").build().raw(), body, 100),
                Map.of(), BodyLimits.defaults().maxBytes(10));

        assertThatThrownBy(request::body).isInstanceOf(HttpException.class);
        assertThat(body.read).isZero();
    }

    /** The default is 1MB, and applies to a request built outside a server too. */
    @Test
    void theDefaultIsOneMegabyte() {
        String oneMegabyte = "a".repeat(BodyLimits.DEFAULT_MAX_BYTES);

        assertThat(TestRequest.post("/x").body(oneMegabyte).build().body()).hasSize(BodyLimits.DEFAULT_MAX_BYTES);
        assertThatThrownBy(() -> TestRequest.post("/x").body(oneMegabyte + "a").build().body())
                .isInstanceOfSatisfying(HttpException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE));
    }

    @Test
    void unlimitedLiftsTheDefault() {
        String twoMegabytes = "a".repeat(2 * BodyLimits.DEFAULT_MAX_BYTES);
        HttpServletRequest raw = TestRequest.post("/x").body(twoMegabytes).build().raw();

        assertThat(new WebRequest(raw, Map.of(), BodyLimits.unlimited()).body()).hasSize(twoMegabytes.length());
    }

    @Test
    void aLimitMustBePositive() {
        assertThatIllegalArgumentException().isThrownBy(() -> BodyLimits.defaults().maxBytes(0));
        assertThatIllegalArgumentException().isThrownBy(() -> BodyLimits.defaults().maxNdjsonLineBytes(-1));
    }

    /** The limits are copied when registered, as every setting object is. */
    @Test
    void changingTheLimitsAfterRegisteringChangesNothing() {
        BodyLimits limits = BodyLimits.defaults().maxBytes(4);
        App app = echoLength(limits);
        limits.maxBytes(1000);

        WebTest.test(app, client -> assertThat(client.post("/body", "abcde").statusCode()).isEqualTo(413));
    }

    // ---- bodyNdjson(...) ----

    @Test
    void anNdjsonLineExactlyAtTheLimitIsReadAndOneByteOverIsA413NamingTheLine() {
        String sixteen = "{\"name\":\"abcde\"}";
        assertThat(sixteen).hasSize(16);

        WebTest.test(echoLength(BodyLimits.defaults().maxNdjsonLineBytes(16)), client -> {
            var atLimit = client.post("/ndjson", sixteen + "\r\n" + sixteen + "\n" + sixteen);
            var overLimit = postChunked(client, "/ndjson", sixteen + "\n{\"name\":\"abcdef\"}\n");

            assertThat(atLimit.statusCode()).isEqualTo(200);
            assertThat(atLimit.body()).isEqualTo("3");
            assertThat(overLimit.statusCode()).isEqualTo(413);
            assertThat(overLimit.body()).contains("Line 2");
        });
    }

    /** The line limit counts bytes too: fifteen characters here are nineteen bytes. */
    @Test
    void theNdjsonLineLimitCountsBytes() {
        String line = "{\"name\":\"éééé\"}";
        assertThat(line).hasSize(15);
        assertThat(line.getBytes(StandardCharsets.UTF_8)).hasSize(19);

        WebTest.test(echoLength(BodyLimits.defaults().maxNdjsonLineBytes(18)), client ->
                assertThat(client.post("/ndjson", line + "\n").statusCode()).isEqualTo(413));
        WebTest.test(echoLength(BodyLimits.defaults().maxNdjsonLineBytes(19)), client ->
                assertThat(client.post("/ndjson", line + "\n").body()).isEqualTo("1"));
    }

    /**
     * The line limit bounds a line, and the body limit does not apply: a body
     * of many small records far longer than either limit is read to its end.
     */
    @Test
    void manySmallRecordsAreReadWhateverTheBodyLength() {
        String body = "{\"name\":\"a\"}\n".repeat(10_000);

        WebTest.test(echoLength(BodyLimits.defaults().maxBytes(64).maxNdjsonLineBytes(64)), client -> {
            assertThat(client.post("/ndjson", body).body()).isEqualTo("10000");
            assertThat(postChunked(client, "/ndjson", body).body()).isEqualTo("10000");
        });
    }

    /** Taking the first record reads one chunk of a long body, not the body. */
    @Test
    void recordsAreReadIncrementally() {
        CountingStream body = new CountingStream(Long.MAX_VALUE, "{\"name\":\"a\"}\n".getBytes(StandardCharsets.UTF_8));
        WebRequest request = new WebRequest(new BodyOf(TestRequest.post("/x").build().raw(), body, -1),
                Map.of(), BodyLimits.defaults().maxNdjsonLineBytes(64));

        Iterator<String> names = request.bodyNdjson(NAME).iterator();

        assertThat(names.next()).isEqualTo("a");
        assertThat(names.next()).isEqualTo("a");
        assertThat(body.read).isLessThanOrEqualTo(8192);
    }

    /**
     * A line that never ends is refused once it passes the limit, having read
     * no more than the limit and a chunk of a body that would never finish.
     */
    @Test
    void anOversizedLineIsRefusedBeforeItIsBufferedInFull() {
        CountingStream body = new CountingStream(Long.MAX_VALUE, "a".getBytes(StandardCharsets.UTF_8));
        WebRequest request = new WebRequest(new BodyOf(TestRequest.post("/x").build().raw(), body, -1),
                Map.of(), BodyLimits.defaults().maxNdjsonLineBytes(1000));

        Iterator<String> names = request.bodyNdjson(NAME).iterator();

        assertThatThrownBy(names::next)
                .isInstanceOfSatisfying(HttpException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
                    assertThat(e.getMessage()).contains("Line 1");
                });
        assertThat(body.read).isLessThanOrEqualTo(1000 + 8192);
    }

    /** After a refused line the stream keeps refusing, rather than reading on from its middle. */
    @Test
    void aRefusedLineStaysRefused() {
        String body = "a".repeat(100) + "\n{\"name\":\"b\"}\n";
        WebRequest request = new WebRequest(TestRequest.post("/x").body(body).build().raw(),
                Map.of(), BodyLimits.defaults().maxNdjsonLineBytes(50));

        Iterator<String> names = request.bodyNdjson(NAME).iterator();

        assertThatThrownBy(names::next).isInstanceOf(HttpException.class);
        assertThatThrownBy(names::next).isInstanceOf(HttpException.class);
        assertThatThrownBy(request::bodyStream).isInstanceOf(HttpException.class);
    }

    // ---- helpers ----

    /** Sent from a stream, so the request carries no Content-Length and goes chunked. */
    private static java.net.http.HttpResponse<String> postChunked(TestClient client, String path, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return client.send(builder -> builder
                .uri(URI.create(client.url(path)))
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))));
    }

    /** A request whose body and declared length are whatever the test says. */
    private static final class BodyOf extends HttpServletRequestWrapper {

        private final InputStream body;
        private final long declared;

        BodyOf(HttpServletRequest request, InputStream body, long declared) {
            super(request);
            this.body = body;
            this.declared = declared;
        }

        @Override
        public long getContentLengthLong() {
            return declared;
        }

        @Override
        public int getContentLength() {
            return (int) declared;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ServletInputStream() {
                @Override
                public int read() throws java.io.IOException {
                    return body.read();
                }

                @Override
                public int read(byte[] b, int off, int len) throws java.io.IOException {
                    return body.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return false;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }

    /** A pattern repeated up to a length, counting what was read. */
    private static final class CountingStream extends InputStream {

        private final long length;
        private final byte[] pattern;
        long read;

        CountingStream(long length, byte[] pattern) {
            this.length = length;
            this.pattern = pattern;
        }

        @Override
        public int read() {
            if (read >= length) {
                return -1;
            }
            return pattern[(int) (read++ % pattern.length)];
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (read >= length) {
                return -1;
            }
            int count = (int) Math.min(len, length - read);
            for (int i = 0; i < count; i++) {
                b[off + i] = pattern[(int) (read++ % pattern.length)];
            }
            return count;
        }
    }
}
