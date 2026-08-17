package spidersilk.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import spidersilk.App;

class JettyServerTest {

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
                .get("/hello/{name}", ctx -> ctx.text("Hello " + ctx.pathParam("name")))
                .start(0);

        HttpResponse<String> response = get("/hello/spider");

        assertEquals(200, response.statusCode());
        assertEquals("Hello spider", response.body());
    }

    @Test
    void pickedPortIsReadable() {
        app = new App().start(0);

        assertTrue(app.port() > 0, "port 0 should be replaced by the bound port");
    }

    @Test
    void unmatchedPathFallsBackToNotFound() throws Exception {
        app = new App().start(0);

        assertEquals(404, get("/nope").statusCode());
    }

    /** Sessions are on by default, so flash works without any extra configuration. */
    @Test
    void sessionsAreEnabledByDefault() throws Exception {
        app = new App()
                .get("/flash", ctx -> {
                    ctx.flash("message", "saved");
                    ctx.text("ok");
                })
                .start(0);

        assertEquals(200, get("/flash").statusCode());
    }

    /** ctx.file(...) needs a MultipartConfig on the servlet; the default supplies one. */
    @Test
    void multipartUploadsWorkOutOfTheBox() throws Exception {
        app = new App()
                .post("/upload", ctx -> ctx.text(ctx.file("csv").asText()))
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

        assertEquals(200, response.statusCode());
        assertEquals("front,back", response.body());
    }

    @Test
    void customizersReachTheRealJettyObjects() throws Exception {
        JettyServer jetty = new JettyServer(new App().get("/", ctx -> ctx.text("ok")))
                .port(0)
                .customizeHttpConfiguration(http -> http.setSendServerVersion(false))
                .customizeServer(server -> server.setAttribute("customized", true));
        jetty.start();
        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + jetty.port() + "/"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Server").isEmpty(),
                    "Server header should be suppressed by the HttpConfiguration customizer");
            assertEquals(true, jetty.jetty().getAttribute("customized"),
                    "the Server customizer should have run against the real Server");
        } finally {
            jetty.stop();
        }
    }

    @Test
    void anotherServerCanBePluggedIn() {
        RecordingServer recording = new RecordingServer();
        app = new App().server((a, port) -> recording).start(1234);

        assertTrue(recording.started);
        assertEquals(1234, app.port());
    }

    @Test
    void portBeforeStartIsRejected() {
        assertThrows(IllegalStateException.class, () -> new App().port());
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
