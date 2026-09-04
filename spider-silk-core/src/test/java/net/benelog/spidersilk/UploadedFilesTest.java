package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.StringJoiner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.benelog.spidersilk.test.TestClient;
import net.benelog.spidersilk.test.WebTest;

/**
 * Uploads read as a stream, written to disk, and counted per field, against a
 * real container parsing a real multipart body.
 */
class UploadedFilesTest {

    private static final String BOUNDARY = "spidersilkboundary";

    @TempDir
    Path dir;

    /** The whole point of writeTo: the bytes reach a file without a byte[] in between. */
    @Test
    void anUploadIsWrittenToAFile() throws Exception {
        Path target = dir.resolve("saved.csv");
        App app = new App().post("/import", req -> {
            req.file("csv").writeTo(target);
            return WebResponse.text("saved");
        });

        WebTest.test(app, client -> {
            HttpResponse<String> response = post(client, "/import",
                    file("csv", "deck.csv", "text/csv", "front,back\nhola,hello\n"));

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(readString(target)).isEqualTo("front,back\nhola,hello\n");
        });
    }

    /** A relative target is resolved against the working directory, not the container's. */
    @Test
    void aRelativeTargetIsResolvedAgainstTheWorkingDirectory() throws Exception {
        Path target = dir.resolve("relative.csv");
        Path relative = Path.of("").toAbsolutePath().relativize(target);
        App app = new App().post("/import", req -> {
            req.file("csv").writeTo(relative);
            return WebResponse.text("saved");
        });

        WebTest.test(app, client -> {
            post(client, "/import", file("csv", "deck.csv", "text/csv", "front,back\n"));

            assertThat(readString(target)).isEqualTo("front,back\n");
        });
    }

    @Test
    void anUploadIsReadAsAStream() throws Exception {
        App app = new App().post("/import", req -> {
            try (InputStream in = req.file("csv").inputStream()) {
                return WebResponse.text(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        WebTest.test(app, client -> assertThat(
                post(client, "/import", file("csv", "deck.csv", "text/csv", "front,back\n")).body())
                .isEqualTo("front,back\n"));
    }

    /** One field, several files: what an {@code <input type="file" multiple>} sends. */
    @Test
    void aFieldCarriesSeveralFiles() throws Exception {
        App app = new App().post("/pages", req -> {
            StringJoiner names = new StringJoiner(",");
            for (UploadedFile page : req.files("pages")) {
                names.add(page.fileName() + ":" + page.asText());
            }
            return WebResponse.text(names.toString());
        });

        WebTest.test(app, client -> assertThat(post(client, "/pages",
                file("pages", "one.txt", "text/plain", "1"),
                file("pages", "two.txt", "text/plain", "2"),
                field("title", "Scans")).body())
                .isEqualTo("one.txt:1,two.txt:2"));
    }

    /** A text field of the same form is a part too, and is not a file. */
    @Test
    void aTextFieldIsNotCountedAsAFile() throws Exception {
        App app = new App().post("/pages", req ->
                WebResponse.text(req.files("title").size() + " " + req.param("title")));

        WebTest.test(app, client -> assertThat(post(client, "/pages",
                file("pages", "one.txt", "text/plain", "1"),
                field("title", "Scans")).body())
                .isEqualTo("0 Scans"));
    }

    @Test
    void anOptionalUploadIsNullWhenTheFieldIsAbsent() throws Exception {
        App app = new App().post("/avatar", req -> {
            UploadedFile avatar = req.fileOrNull("avatar");
            return WebResponse.text(avatar == null ? "none" : avatar.fileName());
        });

        WebTest.test(app, client -> {
            assertThat(post(client, "/avatar", file("other", "note.txt", "text/plain", "x")).body())
                    .isEqualTo("none");
            assertThat(post(client, "/avatar", file("avatar", "me.png", "image/png", "PNG")).body())
                    .isEqualTo("me.png");
        });
    }

    /** A request that is not multipart at all has no file either, and no 400. */
    @Test
    void anOptionalUploadIsNullWhenTheRequestIsNotMultipart() throws Exception {
        App app = new App().post("/avatar", req ->
                WebResponse.text(req.fileOrNull("avatar") == null ? "none" : "one"));

        WebTest.test(app, client -> {
            assertThat(client.postJson("/avatar", "{}").body()).isEqualTo("none");
            assertThat(client.post("/avatar", "").body()).isEqualTo("none");
        });
    }

    /**
     * A browser sends the part with an empty file name when a file input was
     * left alone, so an empty name is no file rather than an empty one.
     */
    @Test
    void aFileInputLeftEmptyIsNotAnUpload() throws Exception {
        App app = new App()
                .post("/avatar", req ->
                        WebResponse.text(req.fileOrNull("avatar") == null ? "none" : "one"))
                .post("/import", req -> WebResponse.text(req.file("csv").asText()));

        WebTest.test(app, client -> {
            assertThat(post(client, "/avatar", file("avatar", "", "application/octet-stream", ""))
                    .body()).isEqualTo("none");
            assertThat(post(client, "/import", file("csv", "", "application/octet-stream", ""))
                    .statusCode()).isEqualTo(400);
        });
    }

    // ---- Building a multipart body by hand, since the client has no form for one ----

    private static HttpResponse<String> post(TestClient client, String path, String... parts) {
        StringBuilder body = new StringBuilder();
        for (String part : parts) {
            body.append("--").append(BOUNDARY).append("\r\n").append(part);
        }
        body.append("--").append(BOUNDARY).append("--\r\n");
        return client.send(request -> request.uri(URI.create(client.url(path)))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
    }

    private static String file(String name, String fileName, String contentType, String content) {
        return "Content-Disposition: form-data; name=\"" + name
                + "\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n"
                + content + "\r\n";
    }

    private static String field(String name, String value) {
        return "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n";
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
