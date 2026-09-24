package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.benelog.spidersilk.test.TestClient;
import net.benelog.spidersilk.test.WebTest;

/**
 * A static file holds nothing open unless its body is written: a response a
 * filter replaces, one a filter throws over, a HEAD, and a 304 each leave
 * nothing behind, and the body that is written closes what it opened.
 */
class StaticFilesReleaseTest {

    private static final String CSS = "body { color: #2b303b; }\n";

    private static final int REQUESTS = 30;

    // ---- a connection that opens a stream to answer for its metadata ----

    @Test
    void aReplacedFileLeavesNothingOpen() {
        CountingResources resources = new CountingResources(Map.of("/public/style.css", CSS));
        App app = new App()
                .staticFiles(new StaticFiles("/public", resources::lookup))
                .responseFilter((req, res) -> WebResponse.text("replacement"));

        WebTest.test(app, client -> {
            for (int i = 0; i < REQUESTS; i++) {
                assertThat(client.get("/style.css").body()).isEqualTo("replacement");
            }
        });

        assertThat(resources.connected()).isGreaterThanOrEqualTo(REQUESTS);
        assertThat(resources.stillOpen()).isZero();
    }

    @Test
    void aThrowingResponseFilterLeavesNothingOpen() {
        CountingResources resources = new CountingResources(Map.of("/public/style.css", CSS));
        App app = new App()
                .staticFiles(new StaticFiles("/public", resources::lookup))
                .responseFilter((req, res) -> {
                    throw new IllegalStateException("the filter failed");
                });

        WebTest.test(app, client -> {
            for (int i = 0; i < REQUESTS; i++) {
                assertThat(client.get("/style.css").statusCode()).isEqualTo(500);
            }
        });

        assertThat(resources.connected()).isGreaterThanOrEqualTo(REQUESTS);
        assertThat(resources.stillOpen()).isZero();
    }

    @Test
    void deliveryHeadAndNotModifiedKeepTheirAnswersAndLeaveNothingOpen() {
        CountingResources resources = new CountingResources(Map.of("/public/style.css", CSS));
        App app = new App().gzip().staticFiles(new StaticFiles("/public", resources::lookup));

        WebTest.test(app, client -> {
            HttpResponse<String> get = client.get("/style.css");
            assertThat(get.statusCode()).isEqualTo(200);
            assertThat(get.body()).isEqualTo(CSS);
            assertThat(get.headers().firstValue("Content-Length"))
                    .hasValue(String.valueOf(CSS.length()));
            String etag = get.headers().firstValue("ETag").orElseThrow();
            assertThat(etag).isEqualTo("\"" + Long.toHexString(CountingResources.MODIFIED)
                    + "-" + Long.toHexString(CSS.length()) + "\"");

            HttpResponse<String> head = client.head("/style.css");
            assertThat(head.statusCode()).isEqualTo(200);
            assertThat(head.body()).isEmpty();
            assertThat(head.headers().firstValue("Content-Length"))
                    .hasValue(String.valueOf(CSS.length()));

            HttpResponse<String> unchanged = notModified(client, "/style.css", etag);
            assertThat(unchanged.statusCode()).isEqualTo(304);
            assertThat(unchanged.body()).isEmpty();

            HttpResponse<byte[]> gzipped = client.send(request -> request
                    .uri(URI.create(client.url("/style.css")))
                    .header("Accept-Encoding", "gzip")
                    .GET(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(gzipped.headers().firstValue("Content-Encoding")).hasValue("gzip");
            assertThat(inflate(gzipped.body())).isEqualTo(CSS);
        });

        assertThat(resources.stillOpen()).isZero();
    }

    // ---- the real classpath, counted by the operating system ----

    /** The reproduction the fix answers: an exploded classpath, on Linux. */
    @Test
    void replacedAndFailedFilesOnTheClasspathHoldNoDescriptors() {
        URL style = StaticFilesReleaseTest.class.getResource("/public/style.css");
        Assumptions.assumeTrue(style != null && "file".equals(style.getProtocol()),
                "the fixtures are not an exploded classpath");
        String suffix = "/public/style.css";

        App replaced = new App().responseFilter((req, res) -> WebResponse.text("replacement"));
        WebTest.test(replaced, client -> {
            long before = descriptorsEndingWith(suffix);
            for (int i = 0; i < REQUESTS; i++) {
                assertThat(client.get("/style.css").body()).isEqualTo("replacement");
            }
            assertThat(descriptorsEndingWith(suffix)).isEqualTo(before);
        });

        App failing = new App().responseFilter((req, res) -> {
            throw new IllegalStateException("the filter failed");
        });
        WebTest.test(failing, client -> {
            long before = descriptorsEndingWith(suffix);
            for (int i = 0; i < REQUESTS; i++) {
                assertThat(client.get("/style.css").statusCode()).isEqualTo(500);
            }
            assertThat(descriptorsEndingWith(suffix)).isEqualTo(before);
        });
    }

    // ---- a packaged application ----

    /**
     * A packaged application reads its files out of a jar, and gets the answers
     * an exploded classpath gives: the same headers, a validator taken off the
     * jar file's own time, and no answer for a directory entry.
     */
    @Test
    void aPackagedFileIsServedLikeAnExplodedOne(@TempDir Path dir) throws IOException {
        Path jar = packagedAssets(dir);
        String expectedEtag = "\"" + Long.toHexString(Files.getLastModifiedTime(jar).toMillis())
                + "-" + Long.toHexString(CSS.length()) + "\"";

        try (URLClassLoader loader = loaderOf(jar)) {
            WebTest.test(new App().gzip().staticFiles(packaged(loader)), client -> {
                HttpResponse<String> get = client.get("/packaged.css");
                assertThat(get.statusCode()).isEqualTo(200);
                assertThat(get.body()).isEqualTo(CSS);
                assertThat(get.headers().firstValue("Content-Type"))
                        .hasValue("text/css; charset=UTF-8");
                assertThat(get.headers().firstValue("Content-Length"))
                        .hasValue(String.valueOf(CSS.length()));
                assertThat(get.headers().firstValue("ETag")).hasValue(expectedEtag);

                HttpResponse<String> head = client.head("/packaged.css");
                assertThat(head.body()).isEmpty();
                assertThat(head.headers().firstValue("Content-Length"))
                        .hasValue(String.valueOf(CSS.length()));

                assertThat(notModified(client, "/packaged.css", expectedEtag).statusCode())
                        .isEqualTo(304);

                HttpResponse<byte[]> gzipped = client.send(request -> request
                        .uri(URI.create(client.url("/packaged.css")))
                        .header("Accept-Encoding", "gzip")
                        .GET(), HttpResponse.BodyHandlers.ofByteArray());
                assertThat(gzipped.headers().firstValue("Content-Encoding")).hasValue("gzip");
                assertThat(inflate(gzipped.body())).isEqualTo(CSS);

                assertThat(client.get("/sub").statusCode()).isEqualTo(404);
                assertThat(client.get("/nothing.css").statusCode()).isEqualTo(404);
            });
        }
    }

    /**
     * A jar in an image carries the image's stamp, so a packaged file is tagged
     * by the CRC-32 the jar already holds for the entry, and the jar is not read
     * again for it.
     */
    @Test
    void aStampedJarTagsAFileByTheEntrysChecksum(@TempDir Path dir) throws IOException {
        Path jar = packagedAssets(dir);
        Files.setLastModifiedTime(jar, FileTime.fromMillis(1_000));
        CRC32 crc = new CRC32();
        crc.update(CSS.getBytes(StandardCharsets.UTF_8));
        String expectedEtag = "\"" + Long.toHexString(crc.getValue())
                + "-" + Long.toHexString(CSS.length()) + "\"";

        try (URLClassLoader loader = loaderOf(jar)) {
            WebTest.test(new App().staticFiles(packaged(loader)), client -> {
                HttpResponse<String> get = client.get("/packaged.css");
                assertThat(get.body()).isEqualTo(CSS);
                assertThat(get.headers().firstValue("ETag")).hasValue(expectedEtag);
                assertThat(get.headers().firstValue("Last-Modified")).isEmpty();
                assertThat(notModified(client, "/packaged.css", expectedEtag).statusCode())
                        .isEqualTo(304);
            });
        }
    }

    /**
     * Reading a jar entry's modification time off its connection's headers
     * opens the jar file once more per request, and nothing closes it. Every
     * kind of answer is counted here — delivered, HEAD, 304, replaced, and
     * failed — and none of them may leave a descriptor on the jar behind.
     */
    @Test
    void aPackagedFileHoldsNoDescriptorWhateverTheAnswer(@TempDir Path dir) throws IOException {
        Path jar = packagedAssets(dir);
        String suffix = "/" + jar.getFileName();

        try (URLClassLoader loader = loaderOf(jar)) {
            App delivered = new App().staticFiles(packaged(loader));
            WebTest.test(delivered, client -> {
                String etag = client.get("/packaged.css").headers().firstValue("ETag").orElseThrow();
                long before = descriptorsEndingWith(suffix);
                for (int i = 0; i < REQUESTS; i++) {
                    assertThat(client.get("/packaged.css").body()).isEqualTo(CSS);
                    assertThat(client.head("/packaged.css").statusCode()).isEqualTo(200);
                    assertThat(notModified(client, "/packaged.css", etag).statusCode())
                            .isEqualTo(304);
                }
                assertThat(descriptorsEndingWith(suffix)).isEqualTo(before);
            });

            App replaced = new App()
                    .staticFiles(packaged(loader))
                    .responseFilter((req, res) -> WebResponse.text("replacement"));
            WebTest.test(replaced, client -> {
                client.get("/packaged.css");
                long before = descriptorsEndingWith(suffix);
                for (int i = 0; i < REQUESTS; i++) {
                    assertThat(client.get("/packaged.css").body()).isEqualTo("replacement");
                }
                assertThat(descriptorsEndingWith(suffix)).isEqualTo(before);
            });

            App failing = new App()
                    .staticFiles(packaged(loader))
                    .responseFilter((req, res) -> {
                        throw new IllegalStateException("the filter failed");
                    });
            WebTest.test(failing, client -> {
                client.get("/packaged.css");
                long before = descriptorsEndingWith(suffix);
                for (int i = 0; i < REQUESTS; i++) {
                    assertThat(client.get("/packaged.css").statusCode()).isEqualTo(500);
                }
                assertThat(descriptorsEndingWith(suffix)).isEqualTo(before);
            });
        }
    }

    // ---- helpers ----

    /** A jar holding {@code public/packaged.css} and a {@code public/sub/} directory entry. */
    private static Path packagedAssets(Path dir) throws IOException {
        Path jar = dir.resolve("assets.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("public/packaged.css"));
            out.write(CSS.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("public/sub/"));
            out.closeEntry();
        }
        return jar;
    }

    /** A loader that sees the jar and nothing else, not even the test's own classpath. */
    private static URLClassLoader loaderOf(Path jar) throws MalformedURLException {
        return new URLClassLoader(new URL[] {jar.toUri().toURL()}, null);
    }

    private static StaticFiles packaged(ClassLoader loader) {
        return new StaticFiles("/public", name -> loader.getResource(name.substring(1)));
    }

    private static HttpResponse<String> notModified(TestClient client, String path, String etag) {
        return client.send(request -> request
                .uri(URI.create(client.url(path)))
                .header("If-None-Match", etag)
                .GET());
    }

    /** How many of this process's descriptors point at a path ending so, where the system says. */
    private static long descriptorsEndingWith(String suffix) {
        Path fds = Path.of("/proc/self/fd");
        Assumptions.assumeTrue(Files.isDirectory(fds), "no /proc to count descriptors in");
        try (var entries = Files.list(fds)) {
            return entries.filter(fd -> {
                try {
                    return Files.readSymbolicLink(fd).toString().endsWith(suffix);
                } catch (IOException | UnsupportedOperationException e) {
                    return false; // closed between the listing and the read
                }
            }).count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String inflate(byte[] compressed) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Resources behind a URL scheme of their own, whose connections open a
     * stream the moment their metadata is asked for — as the JDK's own
     * {@code file:} connection does — and which count every stream still open.
     */
    private static final class CountingResources extends URLStreamHandler {

        static final long MODIFIED = 1_700_000_000_000L;

        private final Map<String, String> files;
        private final AtomicInteger connections = new AtomicInteger();
        private final AtomicInteger open = new AtomicInteger();

        CountingResources(Map<String, String> files) {
            this.files = files;
        }

        URL lookup(String name) {
            if (!files.containsKey(name)) {
                return null;
            }
            try {
                return URL.of(URI.create("counting:" + name), this);
            } catch (MalformedURLException e) {
                throw new IllegalStateException(e);
            }
        }

        int connected() {
            return connections.get();
        }

        int stillOpen() {
            return open.get();
        }

        @Override
        protected URLConnection openConnection(URL url) {
            String content = files.get(url.getPath());
            return new URLConnection(url) {

                private InputStream stream;

                @Override
                public void connect() throws IOException {
                    if (stream != null) {
                        return;
                    }
                    if (content == null) {
                        throw new FileNotFoundException(url.getPath());
                    }
                    connections.incrementAndGet();
                    open.incrementAndGet();
                    stream = new CountedStream(content.getBytes(StandardCharsets.UTF_8));
                    connected = true;
                }

                @Override
                public long getLastModified() {
                    return content == null ? 0 : connectFor(MODIFIED);
                }

                @Override
                public long getContentLengthLong() {
                    return content == null ? -1 : connectFor(content.length());
                }

                private long connectFor(long value) {
                    try {
                        connect();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return value;
                }

                @Override
                public InputStream getInputStream() throws IOException {
                    connect();
                    return stream;
                }
            };
        }

        /** A stream that counts itself closed once, however often it is closed. */
        private final class CountedStream extends ByteArrayInputStream {

            private boolean closed;

            CountedStream(byte[] bytes) {
                super(bytes);
            }

            @Override
            public void close() {
                if (!closed) {
                    closed = true;
                    open.decrementAndGet();
                }
            }
        }
    }
}
