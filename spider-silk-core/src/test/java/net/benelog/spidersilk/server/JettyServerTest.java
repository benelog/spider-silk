package net.benelog.spidersilk.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import jakarta.servlet.MultipartConfigElement;

import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.benelog.spidersilk.App;
import net.benelog.spidersilk.BodyLimits;
import net.benelog.spidersilk.HttpException;
import net.benelog.spidersilk.WebResponse;
import net.benelog.spidersilk.json.JsonReader;

class JettyServerTest {

    private static final JsonReader<String> NAME = json -> json.asObject().getString("name");

    private final HttpClient client = HttpClient.newHttpClient();
    private App app;

    @AfterEach
    void stopApp() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void servesRoutesOverHttp() throws Exception {
        app = new App()
                .get("/hello/{name}", req -> WebResponse.text("Hello " + req.pathParam("name")))
                .start(0);

        HttpResponse<String> response = get("/hello/spider");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("Hello spider");
    }

    /**
     * The same form limits on every server: a thousand parts, text fields
     * included, and 8KB of headers per part. Tomcat's own defaults were 50
     * parts and 512 bytes, and refused an ordinary form with a meaningless 413.
     */
    @Test
    void aFormOfManyFieldsOrALongFileNameIsTakenAsOnEveryServer() throws Exception {
        start(new App().post("/up", req ->
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

    /**
     * With multipart turned off, a parameter read of a multipart request goes
     * to getParameter, as on Tomcat and Undertow. Jetty reported the missing
     * configuration the way it reports an upload over its limits, a 413.
     */
    @Test
    void withMultipartOffAQueryParameterOfAMultipartRequestStillReads() throws Exception {
        app = new App()
                .server((a, port) -> new JettyServer(a).port(port).multipart(null))
                .post("/up", req -> WebResponse.text(req.param("page")))
                .start(0);

        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + app.port() + "/up?page=2"))
                .header("Content-Type", "multipart/form-data; boundary=X")
                .POST(HttpRequest.BodyPublishers.ofString(fields(1) + "--X--\r\n")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("2");
    }

    private void start(App configured) {
        app = configured.start(0);
    }

    /** The tail of a wildcard route reaches the handler with its slashes intact. */
    @Test
    void aNamedTailCarriesTheRestOfThePath() throws Exception {
        app = new App()
                .get("/files/{path*}", req -> WebResponse.text("file " + req.pathParam("path")))
                .start(0);

        assertThat(get("/files/docs/2026/report.pdf").body())
                .isEqualTo("file docs/2026/report.pdf");
        assertThat(get("/files").body()).isEqualTo("file ");
    }

    @Test
    void pickedPortIsReadable() {
        app = new App().start(0);

        assertThat(app.port()).as("port 0 should be replaced by the bound port").isPositive();
    }

    @Test
    void unmatchedPathFallsBackToNotFound() throws Exception {
        app = new App().start(0);

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
        app = new App().gzip()
                .get("/export", req -> WebResponse.stream("text/csv; charset=UTF-8",
                        out -> out.write(csv.getBytes(StandardCharsets.UTF_8))))
                .start(0);

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
        app = failingStreams().start(0);

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
        app = failingStreams().start(0);

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

    /** Sessions are on by default, so flash works without any extra configuration. */
    @Test
    void sessionsAreEnabledByDefault() throws Exception {
        app = new App()
                .get("/flash", req -> {
                    req.flash("message", "saved");
                    return WebResponse.text("ok");
                })
                .start(0);

        assertThat(get("/flash").statusCode()).isEqualTo(200);
    }

    /** req.file(...) needs a MultipartConfig on the servlet; the default supplies one. */
    @Test
    void multipartUploadsWorkOutOfTheBox() throws Exception {
        app = new App()
                .post("/upload", req -> WebResponse.text(req.file("csv").asText()))
                .start(0);

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
        app = new App()
                .post("/upload", req -> {
                    req.files("csv").get(1).writeTo(saved);
                    return WebResponse.text(
                            req.files("csv").size() + " " + req.file("csv").fileName());
                })
                .start(0);

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
                .server((a, port) -> new JettyServer(a).port(port).multipart(tenBytes))
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
     * A query string that will not decode is a 400 through the merged readers
     * too. Jetty's own parser fails on it differently, so this is the claim
     * that core decodes the query string before the container does.
     */
    @Test
    void aMalformedQueryStringIsA400ThroughParamAndParams() {
        app = new App()
                .get("/param", req -> WebResponse.text(req.param("q")))
                .get("/params", req -> WebResponse.text(req.params("q").toString()))
                .get("/paramOrNull", req -> WebResponse.text(String.valueOf(req.paramOrNull("q"))))
                .start(0);

        for (String path : List.of("/param", "/params", "/paramOrNull")) {
            String response = rawGet(path + "?q=%zz");

            assertThat(response).as(path).startsWith("HTTP/1.1 400");
            assertThat(response).as(path).contains("Query string is not valid URL encoding");
        }
    }

    /** Decoding the query string first leaves a form body to the container, as before. */
    @Test
    void aFormBodyIsStillReadThroughParam() throws Exception {
        app = new App()
                .post("/submit", req -> WebResponse.text(req.param("name") + " " + req.params("tag")))
                .start(0);

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
                .server((a, port) -> new JettyServer(a).port(port).multipart(tenBytes))
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

        // Jetty takes 200,000 bytes of form, and names no size refusal the servlet API defines.
        assertThat(form("/param", "a=" + "x".repeat(300_000)).statusCode())
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

    /**
     * A form body is read once, as fields or as bytes. The container parses the
     * form by reading the body, so the stream answers nothing after a parameter
     * read rather than throwing, and a body read as bytes first leaves no fields.
     * {@code TestRequest} answers both orders the same way.
     */
    @Test
    void aFormBodyIsReadAsFieldsOrAsBytesNotBoth() throws Exception {
        app = new App()
                .post("/fields-first", req -> WebResponse.text(
                        req.param("name") + "|" + bytesOf(req.bodyStream())))
                .post("/bytes-first", req -> WebResponse.text(
                        bytesOf(req.bodyStream()) + "|" + req.formParamOrNull("name")))
                .start(0);

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
        app = new App()
                .beforeRoute(req -> {
                    req.body();
                    return null;
                })
                .post("/text-twice", req -> WebResponse.text(req.body() + "|" + req.body()))
                .post("/text-then-bytes", req -> WebResponse.text(bytesOf(req.bodyStream())))
                .start(0);

        assertThat(postForm("/text-twice").body()).isEqualTo("name=English|name=English");
        assertThat(postForm("/text-then-bytes").statusCode()).isEqualTo(500);
    }

    /**
     * The body limits are the framework's, counted in bytes as the body arrives,
     * so they hold on Jetty as they do on any server: a body at the limit is
     * read, one byte over is a 413 whether or not it declared its length, and a
     * refused body stays refused rather than handing on what is left of it.
     */
    @Test
    void theBodyLimitsHoldOnJetty() throws Exception {
        app = new App()
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
                .post("/ndjson", req -> WebResponse.text(req.bodyNdjson(NAME).count() + "")).start(0);

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
    void theNdjsonLineLimitHoldsOnJetty() throws Exception {
        app = new App()
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
                .post("/ndjson", req -> WebResponse.text(req.bodyNdjson(NAME).count() + "")).start(0);

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

    @Test
    void customizersReachTheRealJettyObjects() throws Exception {
        JettyServer jetty = new JettyServer(new App().get("/", req -> WebResponse.text("ok")))
                .port(0)
                .customizeHttpConfiguration(http -> http.setSendServerVersion(false))
                .customizeServer(server -> server.setAttribute("customized", true));
        jetty.start();
        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + jetty.port() + "/"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Server"))
                    .as("Server header should be suppressed by the HttpConfiguration customizer")
                    .isEmpty();
            assertThat(jetty.jetty().getAttribute("customized"))
                    .as("the Server customizer should have run against the real Server")
                    .isEqualTo(true);
        } finally {
            jetty.stop();
        }
    }

    /** Graceful shutdown: a request already running is finished, not dropped. */
    @Test
    void stopWaitsForARequestInFlight() throws Exception {
        CountDownLatch handlerEntered = new CountDownLatch(1);
        app = new App().get("/slow", req -> {
            handlerEntered.countDown();
            Thread.sleep(300);
            return WebResponse.text("finished");
        }).start(0);

        String url = "http://localhost:" + app.port() + "/slow";
        CompletableFuture<HttpResponse<String>> inFlight = client.sendAsync(
                HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(handlerEntered.await(2, TimeUnit.SECONDS))
                .as("the handler never started")
                .isTrue();

        app.stop();

        HttpResponse<String> response = inFlight.get(2, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("finished");
    }

    @Test
    void gracefulShutdownIsOnByDefault() {
        JettyServer jetty = new JettyServer(new App()).port(0);
        jetty.start();
        try {
            assertThat(jetty.jetty().getStopTimeout())
                    .isEqualTo(JettyServer.DEFAULT_STOP_TIMEOUT.toMillis());
        } finally {
            jetty.stop();
        }
    }

    /** A suite that starts a server per test would rather not wait for the drain. */
    @Test
    void aZeroStopTimeoutTurnsGracefulShutdownOff() {
        JettyServer jetty = new JettyServer(new App()).port(0).stopTimeout(Duration.ZERO);
        jetty.start();
        try {
            assertThat(jetty.jetty().getStopTimeout()).isEqualTo(0);
        } finally {
            jetty.stop();
        }
    }

    /** Customizers run last, so they can still overrule a setting method. */
    @Test
    void customizeServerOverrulesTheStopTimeout() {
        JettyServer jetty = new JettyServer(new App())
                .port(0)
                .stopTimeout(Duration.ofSeconds(2))
                .customizeServer(server -> server.setStopTimeout(7_000));
        jetty.start();
        try {
            assertThat(jetty.jetty().getStopTimeout()).isEqualTo(7_000);
        } finally {
            jetty.stop();
        }
    }

    /** Ctrl-C stops the server: Jetty's own hook, one per JVM rather than one per server. */
    @Test
    void aShutdownHookIsRegisteredByDefault() {
        JettyServer jetty = new JettyServer(new App()).port(0);
        jetty.start();
        try {
            assertThat(jetty.jetty().getStopAtShutdown()).isTrue();
        } finally {
            jetty.stop();
        }
    }

    @Test
    void theShutdownHookCanBeTurnedOff() {
        JettyServer jetty = new JettyServer(new App()).port(0).shutdownHook(false);
        jetty.start();
        try {
            assertThat(jetty.jetty().getStopAtShutdown()).isFalse();
        } finally {
            jetty.stop();
        }
    }

    /** An idle keep-alive connection must not hold the stop timeout hostage. */
    @Test
    void anIdleConnectionDoesNotDelayTheStop() throws Exception {
        app = new App().get("/", req -> WebResponse.text("ok")).start(0);
        assertThat(get("/").statusCode()).isEqualTo(200);   // leaves a keep-alive connection behind

        long startedAt = System.nanoTime();
        app.stop();
        app = null;

        long millis = (System.nanoTime() - startedAt) / 1_000_000;
        assertThat(millis)
                .as("stopping with only an idle connection open")
                .isLessThan(1_000);
    }

    /**
     * The virtual-thread recipe: platform threads keep running the selectors,
     * handlers run on virtual ones. No API of our own — just a thread pool.
     */
    @Test
    void handlersCanRunOnVirtualThreads() throws Exception {
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setVirtualThreadsExecutor(VirtualThreads.getDefaultVirtualThreadsExecutor());
        app = new App()
                .get("/",
                        req -> WebResponse.text(
                                Thread.currentThread().isVirtual() ? "virtual" : "platform"))
                .server((a, port) -> new JettyServer(a).port(port).threadPool(threadPool))
                .start(0);

        assertThat(get("/").body()).isEqualTo("virtual");
    }

    @Test
    void anotherServerCanBePluggedIn() {
        RecordingServer recording = new RecordingServer();
        app = new App().server((a, port) -> recording).start(1234);

        assertThat(recording.started).isTrue();
        assertThat(app.port()).isEqualTo(1234);
    }

    @Test
    void portBeforeStartIsRejected() {
        assertThatThrownBy(() -> new App().port()).isInstanceOf(IllegalStateException.class);
    }

    /**
     * A start that fails leaves nothing behind: no bound port, no threads. Were
     * the half-started server left as it is, the port would still be held when
     * the retry came and its pool threads would keep the JVM alive.
     */
    @Test
    void aFailedStartHoldsNeitherThePortNorItsThreads() throws Exception {
        JettyServer first = new JettyServer(new App().get("/", req -> WebResponse.text("first")))
                .port(0);
        first.start();
        int taken = first.port();

        QueuedThreadPool pool = new QueuedThreadPool();
        pool.setName("failed-start");
        JettyServer second = new JettyServer(new App()).port(taken).threadPool(pool);
        assertThatThrownBy(second::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to start Jetty on port " + taken);
        assertThat(threadNamesStartingWith("failed-start"))
                .as("the pool of a start that failed should be stopped, not left running")
                .isEmpty();

        first.stop();

        JettyServer third = new JettyServer(new App().get("/", req -> WebResponse.text("third")))
                .port(taken);
        third.start();
        try {
            assertThat(getFrom(third.port(), "/").body()).isEqualTo("third");
        } finally {
            third.stop();
        }
    }

    private static List<String> threadNamesStartingWith(String prefix) {
        return Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(name -> name.startsWith(prefix))
                .toList();
    }

    private HttpResponse<String> getFrom(int port, String path)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + path))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + app.port() + path))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static final class RecordingServer implements WebServer {

        boolean started;

        @Override
        public void start() {
            started = true;
        }

        @Override
        public void stop() {
            started = false;
        }

        @Override
        public void join() {
        }

        @Override
        public int port() {
            return 1234;
        }
    }
}
