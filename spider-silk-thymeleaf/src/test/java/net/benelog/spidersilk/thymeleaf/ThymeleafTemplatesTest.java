package net.benelog.spidersilk.thymeleaf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringWriter;
import java.net.http.HttpResponse;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.App;
import net.benelog.spidersilk.HttpStatus;
import net.benelog.spidersilk.WebResponse;
import net.benelog.spidersilk.test.WebTest;

/** What {@code app.templates(new ThymeleafTemplates(...))} promises. */
class ThymeleafTemplatesTest {

    @Test
    void aTemplateIsRenderedAsHtml() {
        App app = new App()
                .templates(new ThymeleafTemplates("thymeleaf"))
                .get("/hello", req -> WebResponse.template("greeting", Map.of("name", "Silk")));

        WebTest.test(app, client -> {
            HttpResponse<String> response = client.get("/hello");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("<p>Hello, Silk!</p>\n");
            assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                    .isEqualTo("text/html;charset=utf-8");
        });
    }

    @Test
    void thTextEscapesTheModel() {
        App app = new App()
                .templates(new ThymeleafTemplates("thymeleaf"))
                .get("/hello", req -> WebResponse.template("greeting", Map.of("name", "<script>")));

        WebTest.test(app, client ->
                assertThat(client.get("/hello").body()).isEqualTo("<p>Hello, &lt;script&gt;!</p>\n"));
    }

    @Test
    void aRootAndSuffixOfItsOwnReplaceTheDefaults() {
        App app = new App()
                .templates(new ThymeleafTemplates("templates").suffix(".th.html"))
                .get("/hello", req -> WebResponse.template("greeting", Map.of("name", "Silk")));

        WebTest.test(app, client ->
                assertThat(client.get("/hello").body()).isEqualTo("<p>Howdy, Silk!</p>\n"));
    }

    @Test
    void theSuffixIsAppendedNotChecked() {
        StringWriter out = new StringWriter();

        assertThatThrownBy(() -> new ThymeleafTemplates("thymeleaf")
                .render("greeting.html", Map.of("name", "Silk"), out))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("greeting.html.html");
    }

    @Test
    void aTemplateThatThrowsReachesTheExceptionHandler() {
        App app = new App()
                .templates(new ThymeleafTemplates("thymeleaf"))
                .get("/hello", req -> WebResponse.template("nothing-here", Map.of()))
                .exception(Exception.class,
                        (e, req) -> WebResponse.text("caught").status(HttpStatus.BAD_REQUEST));

        WebTest.test(app, client -> {
            HttpResponse<String> response = client.get("/hello");

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).isEqualTo("caught");
        });
    }
}
