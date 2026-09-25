package net.benelog.spidersilk.tomcat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import jakarta.servlet.MultipartConfigElement;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.benelog.spidersilk.App;
import net.benelog.spidersilk.BodyLimits;
import net.benelog.spidersilk.Handler;
import net.benelog.spidersilk.HttpException;
import net.benelog.spidersilk.StaticFiles;
import net.benelog.spidersilk.WebResponse;
import net.benelog.spidersilk.json.JsonReader;

/**
 * The Jetty acceptance tests, run against Tomcat. What core promises has to
 * hold on either server, so this file deliberately mirrors {@code JettyServerTest}.
 */
class TomcatServerTest {

    private static final JsonReader<String> NAME = json -> json.asObject().getString("name");

    private final HttpClient client = HttpClient.newHttpClient();
    private App app;
    private TomcatServer server;

    @AfterEach
    void stopApp() {
        if (app != null) {
            app.stop();
            app = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private App startOnTomcat(App app) {
        this.app = app.server((a, port) -> new TomcatServer(a).port(port)).start(0);
        return this.app;
    }

    @Test
    void servesRoutesOverHttp() throws Exception {
        startOnTomcat(new App()
                .get("/hello/{name}", req -> WebResponse.text("Hello " + req.pathParam("name"))));

        HttpResponse<String> response = get("/hello/spider");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("Hello spider");
    }

    /** The tail of a wildcard route reaches the handler with its slashes intact. */
    @Test
    void aNamedTailCarriesTheRestOfThePath() throws Exception {
        startOnTomcat(new App()
                .get("/files/{path*}", req -> WebResponse.text("file " + req.pathParam("path"))));

        assertThat(get("/files/docs/2026/report.pdf").body())
                .isEqualTo("file docs/2026/report.pdf");
        assertThat(get("/files").body()).isEqualTo("file ");
    }

    @Test
    void pickedPortIsReadable() {
        startOnTomcat(new App());

        assertThat(app.port()).as("port 0 should be replaced by the bound port").isPositive();
    }

    @Test
    void unmatchedPathFallsBackToNotFound() throws Exception {
        startOnTomcat(new App());

        assertThat(get("/nope").statusCode()).isEqualTo(404);
    }

    /**
     * Compression is a transform over the {@link WebResponse}, not a feature of
     * the container, and the streamed body is where that claim is testable: it
     * wraps the servlet output stream this server handed over rather than
     * writing a byte array that was finished beforehand.
     */
    @Test
    void gzipCompressesAStreamedBodyOverHttp() throws Exception {
        String csv = "front,back\n".repeat(200);
        startOnTomcat(new App().gzip()
                .get("/export", req -> WebResponse.stream("text/csv; charset=UTF-8",
                        out -> out.write(csv.getBytes(StandardCharsets.UTF_8)))));

        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + app.port() + "/export"))
                .header("Accept-Encoding", "gzip")
                .build();
        HttpResponse<byte[]> response =
                client.send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.headers().firstValue("Content-Encoding")).hasValue("gzip");
        assertThat(response.body().length).isLessThan(csv.length());
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(csv);
        }
    }

    /**
     * A streamed body that fails before anything is sent is still a 500, with
     * or without compression: nothing about the failure reached the client, so
     * nothing about it has to be taken back.
     */
    @Test
    void aStreamThatFailsBeforeCommittingIsA500() throws Exception {
        startOnTomcat(failingStreams());

        for (String encoding : List.of("gzip", "identity")) {
            HttpResponse<String> response = client.send(HttpRequest
                    .newBuilder(URI.create("http://localhost:" + app.port() + "/broken"))
                    .header("Accept-Encoding", encoding)
                    .build(), HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).as(encoding).isEqualTo(500);
            assertThat(response.headers().firstValue("Content-Encoding")).as(encoding).isEmpty();
            assertThat(response.body()).as(encoding).isEqualTo("Internal Server Error");
        }
    }

    /**
     * A streamed body that fails after the status went out cannot become a 500,
     * so the container aborts the transfer: the client sees a chunked body with
     * no last chunk, or fewer bytes than {@code Content-Length} announced, and
     * never a truncated body that reads as complete.
     */
    @Test
    void aStreamThatFailsAfterCommittingAbortsTheTransfer() {
        startOnTomcat(failingStreams());

        for (String path : List.of("/partial", "/partial-with-length")) {
            for (String encoding : List.of("gzip", "identity")) {
                HttpRequest request = HttpRequest
                        .newBuilder(URI.create("http://localhost:" + app.port() + path))
                        .header("Accept-Encoding", encoding)
                        .build();

                assertThatThrownBy(() -> client.send(request, HttpResponse.BodyHandlers.ofByteArray()))
                        .as(path + " with " + encoding)
                        .isInstanceOf(IOException.class);
            }
        }
    }

    private static App failingStreams() {
        byte[] partial = "partial".getBytes(StandardCharsets.UTF_8);
        return new App().gzip()
                .get("/broken", req -> WebResponse.stream("text/plain", out -> {
                    throw new IOException("planned writer failure");
                }))
                .get("/partial", req -> WebResponse.stream("text/plain", out -> {
                    out.write(partial);
                    out.flush();
                    throw new IOException("planned committed failure");
                }))
                .get("/partial-with-length", req -> WebResponse.stream("text/plain", out -> {
                    out.write(partial);
                    out.flush();
                    throw new IOException("planned committed failure");
                }).header("Content-Length", "1000"));
    }

    /** Sessions come with Tomcat's context, so flash works with no configuration. */
    @Test
    void sessionsWork() throws Exception {
        startOnTomcat(new App().get("/flash", req -> {
            req.flash("message", "saved");
            return WebResponse.text("ok");
        }));

        assertThat(get("/flash").statusCode()).isEqualTo(200);
    }

    /** req.file(...) needs a MultipartConfig on the servlet; the default supplies one. */
    @Test
    void multipartUploadsWorkOutOfTheBox() throws Exception {
        startOnTomcat(new App()
                .post("/upload", req -> WebResponse.text(req.file("csv").asText())));

        String boundary = "spidersilkboundary";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"csv\"; filename=\"deck.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n"
                + "front,back\r\n"
                + "--" + boundary + "--\r\n";
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + app.port() + "/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("front,back");
    }

    /** writeTo and files(name) are core's promises about an upload, on this container. */
    @Test
    void anUploadIsWrittenToDiskAndOneFieldCarriesSeveral(@TempDir Path dir) throws Exception {
        Path saved = dir.resolve("saved.csv");
        startOnTomcat(new App()
                .post("/upload", req -> {
                    req.files("csv").get(1).writeTo(saved);
                    return WebResponse.text(
                            req.files("csv").size() + " " + req.file("csv").fileName());
                }));

        HttpResponse<String> response = client.send(upload(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.body()).isEqualTo("2 one.csv");
        assertThat(Files.readString(saved)).isEqualTo("hola,hello");
    }

    /**
     * An upload the container will not take is a failed upload, not a missing
     * file: a part over {@code maxFileSize} is a 413, and a body cut off before
     * its closing boundary is a 400, whichever of the three reads asks. Each
     * container reports the two in its own way, so this is the claim that core
     * reads them alike.
     */
    @Test
    void anUploadTheContainerRefusesIsNotAMissingFile() throws Exception {
        MultipartConfigElement tenBytes =
                new MultipartConfigElement(System.getProperty("java.io.tmpdir"), 10, 100_000, 0);
        app = new App()
                .post("/file", req -> WebResponse.text(req.file("csv").fileName()))
                .post("/fileOrNull", req -> WebResponse.text(req.fileOrNull("csv") == null ? "none" : "one"))
                .post("/files", req -> WebResponse.text(req.files("csv").size() + " files"))
                .server((a, port) -> new TomcatServer(a).port(port).multipart(tenBytes))
                .start(0);

        for (String path : List.of("/file", "/fileOrNull", "/files")) {
            assertThat(multipart(path, "front,back is more than ten bytes\r\n--spidersilkboundary--\r\n")
                    .statusCode())
                    .as("a part over maxFileSize, through " + path)
                    .isEqualTo(413);
            assertThat(multipart(path, "front,back").statusCode())
                    .as("a body cut off before its closing boundary, through " + path)
                    .isEqualTo(400);
        }
    }

    /** One file part named "csv", followed by whatever the test wants to end the body with. */
    private HttpResponse<String> multipart(String path, String rest) throws Exception {
        String body = "--spidersilkboundary\r\n"
                + "Content-Disposition: form-data; name=\"csv\"; filename=\"deck.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n"
                + rest;
        return sendOnItsOwnConnection(HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path))
                .header("Content-Type", "multipart/form-data; boundary=spidersilkboundary")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    /**
     * A request a refused body came before must not reuse that connection. The
     * container answers without reading the rest of the body and then closes the
     * connection, and a POST that the client's pool puts on it meanwhile fails
     * with an EOF, which the client does not retry for a POST.
     */
    private static HttpResponse<String> sendOnItsOwnConnection(HttpRequest request) throws Exception {
        try (HttpClient fresh = HttpClient.newHttpClient()) {
            return fresh.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    /** Two files under one field name, the shape {@code files("csv")} reads. */
    private HttpRequest upload() {
        String boundary = "spidersilkboundary";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"csv\"; filename=\"one.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n"
                + "front,back\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"csv\"; filename=\"two.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n"
                + "hola,hello\r\n"
                + "--" + boundary + "--\r\n";
        return HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    /**
     * A form body is read once, as fields or as bytes. The container parses the
     * form by reading the body, so the stream answers nothing after a parameter
     * read rather than throwing, and a body read as bytes first leaves no fields.
     * {@code TestRequest} answers both orders the same way.
     */
    @Test
    void aFormBodyIsReadAsFieldsOrAsBytesNotBoth() throws Exception {
        startOnTomcat(new App()
                .post("/fields-first", req -> WebResponse.text(
                        req.param("name") + "|" + bytesOf(req.bodyStream())))
                .post("/bytes-first", req -> WebResponse.text(
                        bytesOf(req.bodyStream()) + "|" + req.formParamOrNull("name"))));

        assertThat(postForm("/fields-first").body()).isEqualTo("English|");
        assertThat(postForm("/bytes-first").body()).isEqualTo("name=English|null");
    }

    /**
     * The body read as text is kept, so a filter that reads it leaves it for the
     * handler, and handing it over unread afterwards is refused rather than
     * answered with an empty stream. The container hands back one reader, already
     * at its end, so this is the claim that core keeps the text and not that the
     * container reads the body twice.
     */
    @Test
    void theBodyReadAsTextIsKeptForTheRestOfTheRequest() throws Exception {
        startOnTomcat(new App()
                .beforeRoute(req -> {
                    req.body();
                    return null;
                })
                .post("/text-twice", req -> WebResponse.text(req.body() + "|" + req.body()))
                .post("/text-then-bytes", req -> WebResponse.text(bytesOf(req.bodyStream()))));

        assertThat(postForm("/text-twice").body()).isEqualTo("name=English|name=English");
        assertThat(postForm("/text-then-bytes").statusCode()).isEqualTo(500);
    }

    /**
     * The body limits are the framework's, counted in bytes as the body arrives,
     * so they hold on Tomcat as they do on any server: a body at the limit is
     * read, one byte over is a 413 whether or not it declared its length, and a
     * refused body stays refused rather than handing on what is left of it.
     */
    @Test
    void theBodyLimitsHoldOnTomcat() throws Exception {
        startOnTomcat(new App()
                .bodyLimits(BodyLimits.defaults().maxBytes(4).maxNdjsonLineBytes(16))
                .post("/body", req -> WebResponse.text(req.body().length() + ""))
                .post("/retry", req -> {
                    try {
                        req.body();
                    } catch (HttpException refused) {
                        // A handler that ignores the refusal and reads again.
                    }
                    return WebResponse.text("read " + bytesOf(req.bodyStream()));
                })
                .post("/ndjson", req -> WebResponse.text(req.bodyNdjson(NAME).count() + "")));

        assertThat(postBody("/body", "éé", false).body()).isEqualTo("2");
        assertThat(postBody("/body", "éé", true).body()).isEqualTo("2");
        assertThat(postBody("/body", "ééa", false).statusCode()).isEqualTo(413);
        assertThat(postBody("/body", "ééa", true).statusCode()).isEqualTo(413);
        assertThat(postBody("/retry", "abcdefgh", true).statusCode()).isEqualTo(413);
    }

    /**
     * An NDJSON line over its limit is a 413 naming the line, and a body of many
     * small records, far longer than either limit, is read to its end.
     */
    @Test
    void theNdjsonLineLimitHoldsOnTomcat() throws Exception {
        startOnTomcat(new App()
                .bodyLimits(BodyLimits.defaults().maxBytes(4).maxNdjsonLineBytes(16))
                .post("/body", req -> WebResponse.text(req.body().length() + ""))
                .post("/retry", req -> {
                    try {
                        req.body();
                    } catch (HttpException refused) {
                        // A handler that ignores the refusal and reads again.
                    }
                    return WebResponse.text("read " + bytesOf(req.bodyStream()));
                })
                .post("/ndjson", req -> WebResponse.text(req.bodyNdjson(NAME).count() + "")));

        HttpResponse<String> tooLong = postBody("/ndjson", "{\"name\":\"a\"}\n{\"name\":\"abcdef\"}\n", true);
        assertThat(tooLong.statusCode()).isEqualTo(413);
        assertThat(tooLong.body()).contains("Line 2");
        String records = "{\"name\":\"abcde\"}\r\n".repeat(5_000);
        assertThat(postBody("/ndjson", records, false).body()).isEqualTo("5000");
        assertThat(postBody("/ndjson", records, true).body()).isEqualTo("5000");
    }

    /** A body sent from a stream carries no Content-Length and goes chunked. */
    private HttpResponse<String> postBody(String path, String body, boolean chunked)
            throws IOException, InterruptedException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + app.port() + path))
                .POST(chunked
                        ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))
                        : HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForm(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + app.port() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("name=English"))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String bytesOf(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * SSE is framing over the servlet response and nothing else, which is the
     * reason core writes it that way. This is that claim, checked on the other
     * server: one thread per open stream, flushed as it goes.
     */
    @Test
    void serverSentEventsStreamOverTomcat() throws Exception {
        startOnTomcat(new App().get("/events", req -> WebResponse.sse(stream -> {
            stream.send("tick", "1");
            stream.send("tick", "2");
            stream.close();
        })));

        HttpResponse<String> response = get("/events");

        assertThat(response.statusCode()).isEqualTo(200);
        // Tomcat spells the charset "UTF-8" where Jetty writes "utf-8".
        assertThat(response.headers().firstValue("Content-Type").orElse("").replace(" ", "").toLowerCase(Locale.ROOT))
                .isEqualTo("text/event-stream;charset=utf-8");
        assertThat(response.body()).contains("event: tick");
        assertThat(response.body()).contains("data: 1");
        assertThat(response.body()).contains("data: 2");
    }

    /** A HEAD that gzip would compress carries the GET's headers, and no length it cannot know. */
    @Test
    void aHeadForACompressedStreamCarriesNoLength() throws Exception {
        startOnTomcat(new App().gzip().get("/big.css", req -> WebResponse.stream("text/css",
                out -> out.write("body{color:red}".repeat(300).getBytes(StandardCharsets.UTF_8)))));

        HttpResponse<Void> head = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/big.css"))
                .header("Accept-Encoding", "gzip")
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());

        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.headers().firstValue("Content-Encoding")).hasValue("gzip");
        assertThat(head.headers().firstValue("Content-Length")).isEmpty();
    }

    /**
     * The same form limits on every server: a thousand parts, text fields
     * included, and 8KB of headers per part. Tomcat's own defaults were 50
     * parts and 512 bytes, and refused an ordinary form with a meaningless 413.
     */
    @Test
    void aFormOfManyFieldsOrALongFileNameIsTakenAsOnEveryServer() throws Exception {
        startOnTomcat(new App().post("/up", req ->
                WebResponse.text(req.params("f").size() + " " + req.files("file").size())));

        assertThat(multipart(fields(60) + file("a.txt")).body()).isEqualTo("60 1");
        assertThat(multipart(file("n".repeat(600) + ".txt")).body()).isEqualTo("0 1");
        assertThat(multipart(fields(1001)).statusCode()).isEqualTo(413);
    }

    private static String fields(int count) {
        return "--X\r\nContent-Disposition: form-data; name=\"f\"\r\n\r\nv\r\n".repeat(count);
    }

    private static String file(String name) {
        return "--X\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name
                + "\"\r\nContent-Type: text/plain\r\n\r\nhi\r\n";
    }

    private HttpResponse<String> multipart(String parts) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/up"))
                .header("Content-Type", "multipart/form-data; boundary=X")
                .POST(HttpRequest.BodyPublishers.ofString(parts + "--X--\r\n")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** A query string that is not UTF-8 is the same 400 from a query read and a param read. */
    @Test
    void aQueryStringThatIsNotUtf8IsA400() throws Exception {
        startOnTomcat(new App()
                .get("/query", req -> WebResponse.text(String.valueOf(req.queryParamOrNull("a"))))
                .get("/param", req -> WebResponse.text(String.valueOf(req.paramOrNull("a")))));

        for (String path : List.of("/query?a=%FF", "/param?a=%FF")) {
            HttpResponse<String> response = get(path);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).startsWith("Query string is not UTF-8");
        }
        assertThat(get("/param?a=%ED%95%9C").body()).isEqualTo("한");
    }

    /** A redirect to a path outside ASCII goes out percent-encoded, the same on every server. */
    @Test
    void aNonAsciiRedirectIsPercentEncoded() throws Exception {
        startOnTomcat(new App().get("/go", req -> WebResponse.redirect("/decks/한국어")));

        assertThat(get("/go").headers().firstValue("Location"))
                .hasValue("/decks/%ED%95%9C%EA%B5%AD%EC%96%B4");
    }

    /**
     * A body the client cut short is the request's fault: 400, not a 500 from
     * the read. Tomcat marks a request whose read failed as an error of its
     * own and answers with its own page, so only the status is core's here.
     */
    @Test
    void aBodyCutShortIsA400() throws Exception {
        startOnTomcat(new App().post("/echo", req -> WebResponse.text(req.body())));

        try (Socket socket = new Socket("localhost", app.port())) {
            socket.getOutputStream().write(("POST /echo HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Length: 100\r\nConnection: close\r\n\r\nhello").getBytes(StandardCharsets.UTF_8));
            socket.shutdownOutput();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            assertThat(response).startsWith("HTTP/1.1 400");
        }
    }

    /**
     * A client that stops reading leaves the handler blocked in a flush, and
     * the stop still ends within the stop timeout instead of waiting for the
     * connector to give up on the write.
     */
    @Test
    void aClientThatStopsReadingDelaysTheStopNoLongerThanTheStopTimeout() throws Exception {
        AtomicInteger sent = new AtomicInteger();
        String event = "x".repeat(64 * 1024);
        this.app = new App()
                .server((a, port) -> new TomcatServer(a).port(port).stopTimeout(Duration.ofSeconds(1)))
                .get("/events", req -> WebResponse.sse(stream -> {
                    while (stream.isOpen()) {
                        stream.send(event);
                        sent.incrementAndGet();
                    }
                }))
                .start(0);
        try (Socket socket = new Socket()) {
            socket.setReceiveBufferSize(4096);
            socket.connect(new InetSocketAddress("localhost", app.port()));
            socket.getOutputStream().write("GET /events HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    .getBytes(StandardCharsets.UTF_8));
            int last = -1;
            while (sent.get() == 0 || sent.get() != last) {
                last = sent.get();
                Thread.sleep(300);
            }

            long startedAt = System.nanoTime();
            app.stop();
            long millis = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(millis).as("the stop should end with its one-second timeout").isLessThan(3_000);
        }
    }

    /** Static files are read off the classpath by core, so they travel too. */
    @Test
    void staticFilesAreServed() throws Exception {
        startOnTomcat(new App());

        HttpResponse<String> response = get("/hello.txt");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body().strip()).isEqualTo("a static file");
    }

    /** A directory root travels too, and so does the guard that keeps it enclosed. */
    @Test
    void aDirectoryRootIsServedAndCannotBeWalkedOutOf(@TempDir Path parent) throws Exception {
        Path root = Files.createDirectory(parent.resolve("uploads"));
        Files.writeString(root.resolve("avatar.txt"), "an upload\n");
        Files.writeString(parent.resolve("secret.txt"), "not yours\n");
        startOnTomcat(new App().staticFiles(StaticFiles.directory(root)));

        assertThat(get("/avatar.txt").body().strip()).isEqualTo("an upload");
        assertThat(get("/missing.txt").statusCode()).isEqualTo(404);

        HttpResponse<String> traversal = get("/../secret.txt");
        assertThat(traversal.statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(traversal.body()).doesNotContain("not yours");
    }

    /**
     * A query string that will not decode is a 400 through the merged readers
     * too. Tomcat's own parser treats it differently, so this is the claim that
     * core decodes the query string before the container does.
     */
    @Test
    void aMalformedQueryStringIsA400ThroughParamAndParams() {
        startOnTomcat(new App()
                .get("/param", req -> WebResponse.text(req.param("q")))
                .get("/params", req -> WebResponse.text(req.params("q").toString()))
                .get("/paramOrNull", req -> WebResponse.text(String.valueOf(req.paramOrNull("q")))));

        for (String path : List.of("/param", "/params", "/paramOrNull")) {
            String response = rawGet(path + "?q=%zz");

            assertThat(response).as(path).startsWith("HTTP/1.1 400");
            assertThat(response).as(path).contains("Query string is not valid URL encoding");
        }
    }

    /** Decoding the query string first leaves a form body to the container, as before. */
    @Test
    void aFormBodyIsStillReadThroughParam() throws Exception {
        startOnTomcat(new App()
                .post("/submit", req -> WebResponse.text(req.param("name") + " " + req.params("tag"))));

        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + app.port() + "/submit?tag=a"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("name=Ada&tag=b"))
                .build();

        assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).body())
                .isEqualTo("Ada [a, b]");
    }

    /**
     * A body the container will not parse is the client's fault, whichever
     * parameter read reaches it. A multipart body is refused as it is for
     * {@code file(name)}: 413 for a part over {@code maxFileSize}, 400 for one
     * cut off before its closing boundary. A form body is refused with a 400.
     */
    @Test
    void aBodyTheContainerCannotParseIsA4xxThroughTheParameterReads() throws Exception {
        MultipartConfigElement tenBytes =
                new MultipartConfigElement(System.getProperty("java.io.tmpdir"), 10, 100_000, 0);
        app = new App()
                .post("/param", req -> WebResponse.text(req.param("a")))
                .post("/params", req -> WebResponse.text(req.params("a").toString()))
                .post("/formParam", req -> WebResponse.text(req.formParam("a")))
                .server((a, port) -> new TomcatServer(a).port(port).multipart(tenBytes))
                .start(0);

        for (String path : List.of("/param", "/params", "/formParam")) {
            assertThat(form(path, "a=%zz").statusCode())
                    .as("a form field whose escape will not decode, through " + path)
                    .isEqualTo(400);
            assertThat(multipart(path, "front,back is more than ten bytes\r\n--spidersilkboundary--\r\n")
                    .statusCode())
                    .as("a part over maxFileSize, through " + path)
                    .isEqualTo(413);
            assertThat(multipart(path, "front,back").statusCode())
                    .as("a multipart body cut off before its closing boundary, through " + path)
                    .isEqualTo(400);
        }

        // Tomcat's maxPostSize is 2MB, and it names no size refusal the servlet API defines.
        assertThat(form("/param", "a=" + "x".repeat(3_000_000)).statusCode())
                .as("a form body over the container's limit")
                .isEqualTo(400);
    }

    private HttpResponse<String> form(String path, String body) throws Exception {
        return sendOnItsOwnConnection(HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    /**
     * Sends a GET the java.net.http client cannot build, since {@link URI}
     * refuses a percent-escape that is not two hex digits. The whole response
     * is returned as text.
     */
    private String rawGet(String target) {
        String request = "GET " + target + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
        try (Socket socket = new Socket("localhost", app.port())) {
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void aContextPathMountsTheAppUnderIt() throws Exception {
        server = new TomcatServer(new App().get("/", req -> WebResponse.text("ok")))
                .port(0)
                .contextPath("/app");
        server.start();

        assertThat(getFrom(server.port(), "/app/").statusCode()).isEqualTo(200);
        assertThat(getFrom(server.port(), "/").statusCode()).isEqualTo(404);
    }

    @Test
    void customizersReachTheRealTomcatObjects() throws Exception {
        AtomicReference<Tomcat> customizedTomcat = new AtomicReference<>();
        AtomicReference<Context> customizedContext = new AtomicReference<>();
        server = new TomcatServer(new App().get("/", req -> WebResponse.text("ok")))
                .port(0)
                .customizeConnector(connector -> connector.setProperty("server", "silk"))
                .customizeContext(customizedContext::set)
                .customizeTomcat(customizedTomcat::set);
        server.start();

        HttpResponse<String> response = getFrom(server.port(), "/");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Server").orElse(null))
                .as("the Connector customizer should have set the Server header")
                .isEqualTo("silk");
        assertThat(customizedTomcat.get())
                .as("the Tomcat customizer should have run against the real Tomcat")
                .isSameAs(server.tomcat());
        assertThat(customizedContext.get().getPath())
                .as("the Context customizer should have run against the mounted context")
                .isEqualTo("");
    }

    /**
     * Graceful shutdown: a request already running is finished, not dropped.
     * It runs longer than Tomcat's own two-second {@code unloadDelay}, which
     * would finish a shorter request whether the drain waited or not.
     */
    @Test
    void stopWaitsForARequestInFlight() throws Exception {
        CountDownLatch handlerEntered = new CountDownLatch(1);
        startOnTomcat(new App().get("/slow", req -> {
            handlerEntered.countDown();
            Thread.sleep(2_500);
            return WebResponse.text("finished");
        }));

        String url = "http://localhost:" + app.port() + "/slow";
        CompletableFuture<HttpResponse<String>> inFlight = client.sendAsync(
                HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(handlerEntered.await(2, TimeUnit.SECONDS))
                .as("the handler never started")
                .isTrue();

        app.stop();
        app = null;

        HttpResponse<String> response = inFlight.get(5, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("finished");
    }

    /** An idle keep-alive connection must not hold the stop timeout hostage. */
    @Test
    void anIdleConnectionDoesNotDelayTheStop() throws Exception {
        startOnTomcat(new App().get("/", req -> WebResponse.text("ok")));
        assertThat(get("/").statusCode()).isEqualTo(200);   // leaves a keep-alive connection behind

        long startedAt = System.nanoTime();
        app.stop();
        app = null;

        long millis = (System.nanoTime() - startedAt) / 1_000_000;
        assertThat(millis)
                .as("stopping with only an idle connection open")
                .isLessThan(2_000);
    }

    /** A suite that starts a server per test would rather not wait for the drain. */
    @Test
    void aZeroStopTimeoutStopsImmediately() throws Exception {
        server = new TomcatServer(new App().get("/", req -> WebResponse.text("ok")))
                .port(0)
                .stopTimeout(Duration.ZERO);
        server.start();
        assertThat(getFrom(server.port(), "/").statusCode()).isEqualTo(200);

        long startedAt = System.nanoTime();
        server.stop();
        server = null;

        long millis = (System.nanoTime() - startedAt) / 1_000_000;
        assertThat(millis).isLessThan(2_000);
    }

    /** Ctrl-C stops the server, and the hook goes away again with it. */
    @Test
    void theShutdownHookIsRemovedOnStop() {
        server = new TomcatServer(new App()).port(0);
        server.start();
        Thread registered = server.shutdownHookThread();
        assertThat(registered).as("a shutdown hook should be registered by default").isNotNull();

        server.stop();
        server = null;

        assertThat(Runtime.getRuntime().removeShutdownHook(registered))
                .as("starting a server per test must not accumulate shutdown hooks")
                .isFalse();
    }

    @Test
    void theShutdownHookCanBeTurnedOff() {
        server = new TomcatServer(new App()).port(0).shutdownHook(false);
        server.start();

        assertThat(server.shutdownHookThread()).isNull();
    }

    /** The default base directory is temporary, so nothing lands next to the build. */
    @Test
    void nothingIsWrittenToTheWorkingDirectory() {
        server = new TomcatServer(new App()).port(0);
        server.start();

        String base = server.tomcat().getServer().getCatalinaBase().getAbsolutePath();
        assertThat(base)
                .as("the base directory should be a temporary one, but was " + base)
                .startsWith(System.getProperty("java.io.tmpdir"));
    }

    /**
     * An executor the application passed in is its own: the stop waits for
     * its requests without shutting it down, so the server starts again on it,
     * and whatever else the application runs on it keeps running.
     */
    @Test
    void stopLeavesAnExecutorItWasGivenRunning() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        try {
            CountDownLatch handlerEntered = new CountDownLatch(1);
            this.app = new App()
                    .get("/", req -> WebResponse.text("ok"))
                    .get("/slow", req -> {
                        handlerEntered.countDown();
                        Thread.sleep(500);
                        return WebResponse.text("finished");
                    })
                    .server((a, port) -> new TomcatServer(a).port(port).executor(pool))
                    .start(0);
            assertThat(get("/").statusCode()).isEqualTo(200);

            String url = "http://localhost:" + app.port() + "/slow";
            CompletableFuture<HttpResponse<String>> inFlight = client.sendAsync(
                    HttpRequest.newBuilder(URI.create(url)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(handlerEntered.await(2, TimeUnit.SECONDS)).as("the handler never started").isTrue();
            app.stop();

            assertThat(inFlight.get(5, TimeUnit.SECONDS).body())
                    .as("the stop should have waited for the request on the pool")
                    .isEqualTo("finished");
            assertThat(pool.isShutdown()).as("the pool is the application's").isFalse();

            app.start(0);
            assertThat(get("/").statusCode()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
    }

    /** The virtual-thread recipe: an executor, no API of our own. */
    @Test
    void handlersCanRunOnVirtualThreads() throws Exception {
        this.app = new App()
                .get("/",
                        req -> WebResponse.text(
                                Thread.currentThread().isVirtual() ? "virtual" : "platform"))
                .server((a, port) -> new TomcatServer(a)
                        .port(port)
                        .executor(Executors.newVirtualThreadPerTaskExecutor()))
                .start(0);

        assertThat(get("/").body()).isEqualTo("virtual");
    }

    @Test
    void theRunningTomcatIsReachable() {
        server = new TomcatServer(new App()).port(0);
        server.start();

        assertThat(server.tomcat()).as("tomcat() should hand out the running server").isNotNull();
    }

    /**
     * A start that fails leaves nothing behind, so a third server takes the
     * port the second one could not. No customizer is needed to see the clash:
     * {@code TomcatServer} turns the connector's {@code throwOnFailure} on
     * itself, so the bind failure is thrown rather than written to the log.
     */
    @Test
    void aFailedStartDoesNotHoldThePort() throws Exception {
        server = new TomcatServer(new App().get("/", req -> WebResponse.text("first"))).port(0);
        server.start();
        int taken = server.port();

        App refused = new App();
        TomcatServer second = new TomcatServer(refused).port(taken);
        assertThatThrownBy(second::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to start Tomcat on port " + taken);
        assertThatCode(() -> refused.get("/", req -> WebResponse.text("second")))
                .as("a start that failed never served, so registration stays open")
                .doesNotThrowAnyException();

        server.stop();
        server = null;

        TomcatServer third =
                new TomcatServer(new App().get("/", req -> WebResponse.text("third"))).port(taken);
        third.start();
        try {
            assertThat(getFrom(third.port(), "/").body()).isEqualTo("third");
        } finally {
            third.stop();
        }
    }

    /**
     * The servlet is initialized while Tomcat starts, and that is when it takes
     * the routes: registration closes then, whoever started the server, and
     * reopens once stopping has destroyed the servlet.
     */
    @Test
    void registrationClosesWhileTheServerServesTheApp() throws Exception {
        App served = new App().get("/", req -> WebResponse.text("ok"));
        server = new TomcatServer(served).port(0);
        server.start();

        assertThatIllegalStateException()
                .isThrownBy(() -> served.get("/late", req -> WebResponse.text("late")));
        assertThat(getFrom(server.port(), "/").body()).isEqualTo("ok");

        server.stop();
        server = null;
        assertThatCode(() -> served.get("/late", req -> WebResponse.text("late")))
                .doesNotThrowAnyException();
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return getFrom(app.port(), path);
    }

    private HttpResponse<String> getFrom(int port, String path)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + path))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Two frames of the framework stand between {@code HttpServlet.service} and a
     * handler, on this container as on Jetty. {@code CallStackDepthTest} in core
     * names them; here the point is that the container does not change the count.
     */
    @Test
    void aHandlerStandsOnTwoFramesOfTheFramework() throws Exception {
        AtomicReference<List<StackWalker.StackFrame>> captured = new AtomicReference<>();
        startOnTomcat(new App().get("/stack", req -> {
            captured.set(StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                    .walk(frames -> frames.toList()));
            return WebResponse.text("ok");
        }));

        get("/stack");

        assertThat(captured.get().stream()
                .takeWhile(frame -> !frame.getClassName().equals("jakarta.servlet.http.HttpServlet"))
                .filter(frame -> frame.getClassName().startsWith("net.benelog.spidersilk."))
                .filter(frame -> !frame.getClassName().startsWith(TomcatServerTest.class.getName()))
                .map(frame -> frame.getClassName() + "." + frame.getMethodName()))
                .containsExactly("net.benelog.spidersilk.AppServlet.dispatch",
                        "net.benelog.spidersilk.AppServlet.service");
    }

    /** Sends a POST as written, with the headers given, and answers the whole response as text. */
    private String rawPost(String path, String contentType, String body) {
        return raw("POST " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Type: "
                + contentType + "\r\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length
                + "\r\n\r\n" + body);
    }

    /** Sends a request exactly as written, which must close the connection, and answers the whole response as text. */
    private String raw(String request) {
        try (Socket socket = new Socket("localhost", app.port())) {
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A charset this JVM cannot decode, the empty one among them, is a 415 from
     * every read of the body, parameters and files included, and never a 500
     * or the container's own page. The header itself still reads.
     */
    @Test
    void aCharsetTheJvmCannotDecodeIsA415FromEveryRead() throws Exception {
        startOnTomcat(new App()
                .post("/contentType", req -> WebResponse.text(String.valueOf(req.contentType())))
                .post("/param", req -> WebResponse.text(String.valueOf(req.paramOrNull("a"))))
                .post("/params", req -> WebResponse.text(req.params("a").toString()))
                .post("/formParam", req -> WebResponse.text(String.valueOf(req.formParamOrNull("a"))))
                .post("/file", req -> WebResponse.text(String.valueOf(req.fileOrNull("f") != null)))
                .post("/files", req -> WebResponse.text(String.valueOf(req.files("f").size())))
                .post("/body", req -> WebResponse.text(req.body())));

        String form = "application/x-www-form-urlencoded; charset=nope";
        assertThat(rawPost("/contentType", form, "a=1")).startsWith("HTTP/1.1 200").endsWith(form);
        for (String path : List.of("/param", "/params", "/formParam", "/body")) {
            assertThat(rawPost(path, form, "a=1")).as(path).startsWith("HTTP/1.1 415")
                    .contains("Unsupported charset in Content-Type: nope");
            assertThat(rawPost(path, "application/x-www-form-urlencoded; charset=", "a=1")).as(path)
                    .startsWith("HTTP/1.1 415");
        }
        for (String path : List.of("/param", "/file", "/files")) {
            assertThat(rawPost(path, "multipart/form-data; boundary=X; charset=nope", "--X--\r\n")).as(path)
                    .startsWith("HTTP/1.1 415");
        }
        assertThat(rawPost("/body", "text/plain; charset=", "hi")).startsWith("HTTP/1.1 415")
                .contains("Unsupported charset in Content-Type");
        assertThat(rawPost("/body", "text/plain; charset=\"ISO-8859-1\"", "hi")).startsWith("HTTP/1.1 200");
    }

    /**
     * A query string this parser takes answers the parameter reads alike on
     * every server. Tomcat parsed it again for them, refused the empty name,
     * and blamed a form body on a request that had none.
     */
    @Test
    void aQueryStringWithAnEmptyNameReadsAlikeThroughEveryParameterRead() throws Exception {
        startOnTomcat(new App()
                .get("/query", req -> WebResponse.text("q=" + req.queryParams("a") + " p=" + req.params("a")
                        + " one=" + req.paramOrNull("a") + " form=" + req.formParams("a")))
                .post("/query", req -> WebResponse.text("q=" + req.queryParams("a") + " p=" + req.params("a"))));

        assertThat(get("/query?=a&a=b=c").body()).isEqualTo("q=[b=c] p=[b=c] one=b=c form=[]");
        String posted = rawPost("/query?=a&a=b=c", "application/x-www-form-urlencoded", "a=d");
        assertThat(posted).doesNotContain("Form body");
    }

    /**
     * A form body reads on POST, PUT, and PATCH, form-encoded or multipart, on
     * every server, and a form on DELETE is left unread on every server. Each
     * container gated the form by a set of methods of its own.
     */
    @Test
    void aFormBodyReadsOnPostPutAndPatchAlike() throws Exception {
        Handler echo = req -> WebResponse.text("a=" + req.paramOrNull("a") + " form=" + req.formParamOrNull("a"));
        startOnTomcat(new App().post("/form", echo).put("/form", echo).patch("/form", echo).delete("/form", echo));

        for (String method : List.of("POST", "PUT", "PATCH")) {
            assertThat(rawForm(method, "application/x-www-form-urlencoded", "a=1")).as(method)
                    .startsWith("HTTP/1.1 200").endsWith("a=1 form=1");
            assertThat(rawForm(method, "multipart/form-data; boundary=X",
                    "--X\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n1\r\n--X--\r\n")).as(method)
                    .startsWith("HTTP/1.1 200").endsWith("a=1 form=1");
        }
        assertThat(rawForm("DELETE", "application/x-www-form-urlencoded", "a=1"))
                .startsWith("HTTP/1.1 200").endsWith("a=null form=null");
    }

    private String rawForm(String method, String contentType, String body) {
        return raw(method + " /form HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Type: "
                + contentType + "\r\nContent-Length: " + body.length() + "\r\n\r\n" + body);
    }

    /**
     * A multipart part whose Content-Disposition names no field is skipped by
     * the file reads. Undertow answers a null name for it, which was a 500.
     */
    @Test
    void aPartWithNoNameIsSkippedByTheFileReads() throws Exception {
        startOnTomcat(new App()
                .post("/files", req -> WebResponse.text("files=" + req.files("f").size()))
                .post("/fileOrNull", req -> WebResponse.text("file=" + (req.fileOrNull("f") != null)))
                .post("/file", req -> WebResponse.text("file=" + req.file("f").fileName())));

        String parts = "--B\r\nContent-Disposition: form-data\r\n\r\nnoname\r\n"
                + "--B\r\nContent-Disposition: form-data; name=\"t\"\r\n\r\nv\r\n";
        String type = "multipart/form-data; boundary=B";
        String withFile = parts + "--B\r\nContent-Disposition: form-data; name=\"f\"; filename=\"a.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\nhi\r\n--B--\r\n";

        assertThat(rawPost("/files", type, parts + "--B--\r\n")).startsWith("HTTP/1.1 200").endsWith("files=0");
        assertThat(rawPost("/fileOrNull", type, parts + "--B--\r\n")).startsWith("HTTP/1.1 200").endsWith("file=false");
        assertThat(rawPost("/file", type, parts + "--B--\r\n")).startsWith("HTTP/1.1 400");
        assertThat(rawPost("/files", type, withFile)).startsWith("HTTP/1.1 200").endsWith("files=1");
        assertThat(rawPost("/file", type, withFile)).startsWith("HTTP/1.1 200").endsWith("file=a.txt");
    }

    /**
     * An empty, {@code .}, or {@code ..} segment in a request path, literal or
     * percent-encoded, is a 400 on every server and never reaches a variable.
     * Undertow bound {@code /a//b} to {@code ""} and {@code /files/../secret} to
     * a tail of {@code ../secret}, and Tomcat collapsed and resolved them.
     */
    @Test
    void anEmptyOrDotSegmentInThePathIsA400() throws Exception {
        startOnTomcat(new App()
                .get("/files/{path*}", req -> WebResponse.text("tail=[" + req.pathParam("path") + "]"))
                .get("/a/{x}/b", req -> WebResponse.text("x=[" + req.pathParam("x") + "]"))
                .get("/decks/{id}", req -> WebResponse.text("deck=[" + req.pathParam("id") + "]")));

        for (String path : List.of("/files//etc/passwd", "/a//b", "/files/../secret", "/files/%2e%2e/secret",
                "/a/./b", "/decks/.", "//decks/1", "/files/a/..")) {
            assertThat(rawGet(path)).as(path).startsWith("HTTP/1.1 400");
        }
        assertThat(rawGet("/decks/1/")).endsWith("deck=[1]");
        assertThat(rawGet("/files/a/b.txt")).endsWith("tail=[a/b.txt]");
        assertThat(rawGet("/files/..a/.b")).endsWith("tail=[..a/.b]");
    }

    /** A path escape whose bytes are not UTF-8 is a 400 on every server, and a UTF-8 one still decodes. */
    @Test
    void aPathEscapeThatIsNotUtf8IsA400() throws Exception {
        startOnTomcat(new App().get("/hello/{name}", req -> WebResponse.text("Hello " + req.pathParam("name"))));

        assertThat(rawGet("/hello/a%FF")).startsWith("HTTP/1.1 400");
        assertThat(rawGet("/hello/a%C3")).startsWith("HTTP/1.1 400");
        assertThat(get("/hello/sp%C3%A4der").body()).isEqualTo("Hello späder");
    }
}
