package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.benelog.spidersilk.test.WebTest;

/** A file a handler chose, answered with the type its name implies and its size. */
class FileResponseTest {

    @TempDir
    Path dir;

    @Test
    void aFileIsAnsweredWithItsTypeAndItsLength() throws Exception {
        Path export = Files.writeString(dir.resolve("deck.csv"), "front,back\nein,one\n");
        App app = new App().get("/export", req -> WebResponse.file(export).attachment("deck.csv"));

        WebTest.test(app, client -> {
            var response = client.get("/export");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("front,back\nein,one\n");
            assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                    .startsWith("text/csv");
            assertThat(response.headers().firstValue("Content-Length").orElseThrow())
                    .isEqualTo("19");
            assertThat(response.headers().firstValue("Content-Disposition").orElseThrow())
                    .isEqualTo("attachment; filename=\"deck.csv\"");
        });
    }

    /** Bytes go out as they are: a file is copied, not decoded and re-encoded. */
    @Test
    void aBinaryFileIsAnsweredByteForByte() throws Exception {
        byte[] pixel = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 1, 2, 3};
        Path image = Files.write(dir.resolve("pixel.png"), pixel);
        App app = new App().get("/pixel.png", req -> WebResponse.file(image));

        WebTest.test(app, client -> {
            var response = client.send(request -> request.uri(java.net.URI.create(client.url("/pixel.png"))),
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray());

            assertThat(response.body()).isEqualTo(pixel);
            assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                    .isEqualTo("image/png");
        });
    }

    @Test
    void anExtensionTheTableDoesNotKnowIsOctetStream() throws Exception {
        Path blob = Files.writeString(dir.resolve("deck.bak"), "whatever");
        App app = new App().get("/blob", req -> WebResponse.file(blob));

        WebTest.test(app, client -> assertThat(
                client.get("/blob").headers().firstValue("Content-Type").orElseThrow())
                .isEqualTo("application/octet-stream"));
    }

    @Test
    void aFileWithNoExtensionIsOctetStreamToo() throws Exception {
        Path blob = Files.writeString(dir.resolve("LICENSE"), "whatever");
        App app = new App().get("/license", req -> WebResponse.file(blob));

        WebTest.test(app, client -> assertThat(
                client.get("/license").headers().firstValue("Content-Type").orElseThrow())
                .isEqualTo("application/octet-stream"));
    }

    /** The name is a guess, so a handler that knows better says so. */
    @Test
    void contentTypeOverridesWhatTheNameImplied() throws Exception {
        Path data = Files.writeString(dir.resolve("report.txt"), "{}");
        App app = new App().get("/report", req -> WebResponse.file(data).contentType("application/json"));

        WebTest.test(app, client -> assertThat(
                client.get("/report").headers().firstValue("Content-Type").orElseThrow())
                .isEqualTo("application/json"));
    }

    /** A HEAD reports the length the GET would have sent, without copying the file. */
    @Test
    void aHeadReportsTheLengthWithNoBody() throws Exception {
        Path export = Files.writeString(dir.resolve("deck.csv"), "front,back\n");
        App app = new App().get("/export", req -> WebResponse.file(export));

        WebTest.test(app, client -> {
            var response = client.head("/export");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEmpty();
            assertThat(response.headers().firstValue("Content-Length").orElseThrow())
                    .isEqualTo("11");
        });
    }

    /**
     * A missing file is not a 404: the framework cannot tell a cleaned-up export from a
     * path built wrong, so it throws and the handler decides which one it was.
     */
    @Test
    void aMissingFileThrowsRatherThanAnswering404() {
        Path missing = dir.resolve("gone.csv");
        App app = new App()
                .exception(UncheckedIOException.class,
                        (req, e) -> WebResponse.text("no export").status(HttpStatus.GONE))
                .get("/export", req -> WebResponse.file(missing));

        WebTest.test(app, client -> {
            var response = client.get("/export");

            assertThat(response.statusCode()).isEqualTo(410);
            assertThat(response.body()).isEqualTo("no export");
        });
    }

    /** The check runs in the factory, so a directory fails before any header is committed. */
    @Test
    void aDirectoryThrowsBeforeTheResponseIsCommitted() throws Exception {
        Path sub = Files.createDirectory(dir.resolve("exports"));
        App app = new App()
                .exception(UncheckedIOException.class,
                        (req, e) -> WebResponse.text("not a file").status(HttpStatus.BAD_REQUEST))
                .get("/export", req -> WebResponse.file(sub));

        WebTest.test(app, client -> {
            var response = client.get("/export");

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).isEqualTo("not a file");
        });
    }

    /** A handler that wants a 404 for a missing file writes the one line that says so. */
    @Test
    void aHandlerThatWantsA404ChecksForItself() {
        Path missing = dir.resolve("gone.csv");
        App app = new App().get("/export", req -> {
            if (!Files.isRegularFile(missing)) {
                throw new HttpException(HttpStatus.NOT_FOUND, "No export for that deck");
            }
            return WebResponse.file(missing);
        });

        WebTest.test(app, client -> assertThat(client.get("/export").statusCode()).isEqualTo(404));
    }

    /** A file body is a streamed body, so gzip compresses it as it is written. */
    @Test
    void aFileIsCompressedLikeAnyOtherStreamedBody() throws Exception {
        String html = "<p>" + "x".repeat(2000) + "</p>";
        Path page = Files.writeString(dir.resolve("page.html"), html);
        App app = new App().gzip().get("/page.html", req -> WebResponse.file(page));

        WebTest.test(app, client -> {
            var response = client.send(request -> request
                            .uri(java.net.URI.create(client.url("/page.html")))
                            .header("Accept-Encoding", "gzip"),
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray());

            assertThat(response.headers().firstValue("Content-Encoding")).contains("gzip");
            assertThat(response.body().length).isLessThan(html.length());
            assertThat(inflate(response.body())).isEqualTo(html);
        });
    }

    private static String inflate(byte[] compressed) {
        try (var in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(compressed))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
