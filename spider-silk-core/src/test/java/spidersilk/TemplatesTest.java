package spidersilk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.net.http.HttpResponse;
import java.util.Map;

import org.junit.jupiter.api.Test;

import spidersilk.test.WebTest;

/** The default template engine, and the suffix a name is looked up under. */
class TemplatesTest {

    @Test
    void anAppRendersWithJteWithoutBeingConfigured() {
        App app = new App().get("/hello",
                req -> WebResponse.template("greeting", Map.of("name", "Silk")));

        WebTest.test(app, client -> {
            HttpResponse<String> response = client.get("/hello");

            assertEquals(200, response.statusCode());
            assertEquals("<p>Hello, Silk!</p>\n", response.body());
            assertEquals("text/html;charset=utf-8",
                    response.headers().firstValue("Content-Type").orElseThrow());
        });
    }

    @Test
    void theModelIsEscapedByTheDefaultEngine() {
        App app = new App().get("/hello",
                req -> WebResponse.template("greeting", Map.of("name", "<script>")));

        WebTest.test(app, client ->
                assertEquals("<p>Hello, &lt;script&gt;!</p>\n", client.get("/hello").body()));
    }

    @Test
    void aRootAndSuffixOfItsOwnReplaceTheDefaults() {
        App app = new App()
                .templates(new JteTemplates("templates").suffix(".html"))
                .get("/hello", req -> WebResponse.template("greeting", Map.of("name", "Silk")));

        WebTest.test(app, client ->
                assertEquals("<p>Howdy, Silk!</p>\n", client.get("/hello").body()));
    }

    @Test
    void theSuffixIsAppendedNotChecked() {
        StringWriter out = new StringWriter();

        assertTrue(assertThrowsRenderFailure(
                () -> new JteTemplates("jte").render("greeting.jte", Map.of("name", "Silk"), out))
                .contains("greeting.jte.jte"));
    }

    @Test
    void aTemplateThatThrowsReachesTheExceptionHandler() {
        App app = new App()
                .get("/hello", req -> WebResponse.template("nothing-here", Map.of()))
                .exception(Exception.class,
                        (e, req) -> WebResponse.text("caught").status(HttpStatus.BAD_REQUEST));

        WebTest.test(app, client -> {
            HttpResponse<String> response = client.get("/hello");

            assertEquals(400, response.statusCode());
            assertEquals("caught", response.body());
        });
    }

    private static String assertThrowsRenderFailure(Runnable render) {
        try {
            render.run();
            throw new AssertionError("Expected the missing template to fail the render");
        } catch (RuntimeException e) {
            return String.valueOf(e.getMessage());
        }
    }
}
