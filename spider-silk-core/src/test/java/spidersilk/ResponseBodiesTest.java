package spidersilk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import spidersilk.test.WebTest;

/**
 * The two body kinds that produce their bytes as they go: a
 * {@link WebResponse#stream} for content too big to hold, and a
 * {@link WebResponse#raw} for what this framework has no shape for.
 */
class ResponseBodiesTest {

    @Test
    void aStreamedBodyIsWrittenAsItGoes() {
        App app = new App().get("/export", req -> WebResponse
                .stream("text/csv; charset=UTF-8", out -> {
                    for (int row = 1; row <= 3; row++) {
                        out.write(("row," + row + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                })
                .attachment("deck.csv"));

        WebTest.test(app, client -> {
            var response = client.get("/export");

            assertEquals(200, response.statusCode());
            assertEquals("row,1\nrow,2\nrow,3\n", response.body());
            assertTrue(response.headers().firstValue("Content-Type").orElseThrow()
                    .startsWith("text/csv"));
            assertEquals("attachment; filename=\"deck.csv\"",
                    response.headers().firstValue("Content-Disposition").orElseThrow());
        });
    }

    /** Only running the writer says how long the body would have been. */
    @Test
    void aHeadOfAStreamedBodyReportsTheLengthWithoutSendingIt() {
        App app = new App().get("/export", req -> WebResponse.stream("text/csv; charset=UTF-8",
                out -> out.write("front,back\n".getBytes(StandardCharsets.UTF_8))));

        WebTest.test(app, client -> {
            var response = client.head("/export");

            assertEquals(200, response.statusCode());
            assertEquals("", response.body());
            assertEquals("11", response.headers().firstValue("Content-Length").orElseThrow());
        });
    }

    /** The escape hatch: the status and headers are applied, the body is yours. */
    @Test
    void aRawBodyWritesTheServletResponseByHand() {
        App app = new App().get("/hand-written", req -> WebResponse
                .raw((servletRequest, servletResponse) ->
                        servletResponse.getWriter().write("written by hand"))
                .status(HttpStatus.CREATED)
                .contentType("text/plain; charset=UTF-8")
                .header("X-Escape-Hatch", "open"));

        WebTest.test(app, client -> {
            var response = client.get("/hand-written");

            assertEquals(201, response.statusCode());
            assertEquals("written by hand", response.body());
            assertEquals("open", response.headers().firstValue("X-Escape-Hatch").orElseThrow());
        });
    }

    @Test
    void aHeadOfARawBodyCountsWhatItWouldHaveSent() {
        App app = new App().get("/hand-written", req -> WebResponse
                .raw((servletRequest, servletResponse) ->
                        servletResponse.getWriter().write("written by hand"))
                .contentType("text/plain; charset=UTF-8"));

        WebTest.test(app, client -> {
            var response = client.head("/hand-written");

            assertEquals("", response.body());
            assertEquals("15", response.headers().firstValue("Content-Length").orElseThrow());
        });
    }

    /** A sealed body means a switch over the kinds needs no default case. */
    @Test
    void theBodyKindsAreExhaustive() {
        assertEquals("no body", describe(WebResponse.noContent()));
        assertEquals("hello", describe(WebResponse.html("hello")));
        assertEquals("2 bytes", describe(WebResponse.bytes(new byte[2], "application/octet-stream")));
        assertEquals("template deck",
                describe(WebResponse.template("deck", java.util.Map.of())));
        assertEquals("a stream", describe(WebResponse.stream("text/csv", out -> { })));
        assertEquals("an event stream", describe(WebResponse.sse(stream -> { })));
        assertEquals("written by hand", describe(WebResponse.raw((req, res) -> { })));
    }

    private static String describe(WebResponse response) {
        return switch (response.body()) {
            case WebResponse.Empty ignored -> "no body";
            case WebResponse.Text text -> text.content();
            case WebResponse.Bytes bytes -> bytes.data().length + " bytes";
            case WebResponse.Template template -> "template " + template.name();
            case WebResponse.Stream ignored -> "a stream";
            case WebResponse.Sse ignored -> "an event stream";
            case WebResponse.Raw ignored -> "written by hand";
        };
    }
}
