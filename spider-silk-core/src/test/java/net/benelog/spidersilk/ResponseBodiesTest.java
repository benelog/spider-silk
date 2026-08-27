package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.test.WebTest;

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

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("row,1\nrow,2\nrow,3\n");
            assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                    .startsWith("text/csv");
            assertThat(response.headers().firstValue("Content-Disposition").orElseThrow())
                    .isEqualTo("attachment; filename=\"deck.csv\"");
        });
    }

    /** Only running the writer says how long the body would have been. */
    @Test
    void aHeadOfAStreamedBodyReportsTheLengthWithoutSendingIt() {
        App app = new App().get("/export", req -> WebResponse.stream("text/csv; charset=UTF-8",
                out -> out.write("front,back\n".getBytes(StandardCharsets.UTF_8))));

        WebTest.test(app, client -> {
            var response = client.head("/export");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEmpty();
            assertThat(response.headers().firstValue("Content-Length").orElseThrow())
                    .isEqualTo("11");
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

            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(response.body()).isEqualTo("written by hand");
            assertThat(response.headers().firstValue("X-Escape-Hatch").orElseThrow())
                    .isEqualTo("open");
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

            assertThat(response.body()).isEmpty();
            assertThat(response.headers().firstValue("Content-Length").orElseThrow())
                    .isEqualTo("15");
        });
    }

    /** A sealed body means a switch over the kinds needs no default case. */
    @Test
    void theBodyKindsAreExhaustive() {
        assertThat(describe(WebResponse.noContent())).isEqualTo("no body");
        assertThat(describe(WebResponse.html("hello"))).isEqualTo("hello");
        assertThat(describe(WebResponse.bytes(new byte[2], "application/octet-stream")))
                .isEqualTo("2 bytes");
        assertThat(describe(WebResponse.template("deck", java.util.Map.of())))
                .isEqualTo("template deck");
        assertThat(describe(WebResponse.stream("text/csv", out -> { }))).isEqualTo("a stream");
        assertThat(describe(WebResponse.sse(stream -> { }))).isEqualTo("an event stream");
        assertThat(describe(WebResponse.raw((req, res) -> { }))).isEqualTo("written by hand");
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
