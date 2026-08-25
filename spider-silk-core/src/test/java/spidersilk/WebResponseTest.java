package spidersilk;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The envelope itself: what a {@code with}-style method does to the one it replaces. */
class WebResponseTest {

    @Test
    void aTemplateWithNothingToPassInNeedsNoModel() {
        WebResponse response = WebResponse.template("about");

        WebResponse.Template template = (WebResponse.Template) response.body();
        assertThat(template.name()).isEqualTo("about");
        assertThat(template.model()).isEqualTo(Map.of());
    }

    @Test
    void headersKeepTheOrderTheyWereSetIn() {
        WebResponse response = WebResponse.text("ok")
                .header("X-One", "1")
                .header("X-Two", "2")
                .header("X-Three", "3");

        assertThat(List.copyOf(response.headers().keySet()))
                .isEqualTo(List.of("Content-Type", "X-One", "X-Two", "X-Three"));
    }

    @Test
    void settingAHeaderTwiceReplacesItInPlace() {
        WebResponse response = WebResponse.text("ok")
                .header("X-One", "1")
                .header("X-Two", "2")
                .header("X-One", "again");

        assertThat(response.header("X-One")).isEqualTo("again");
        assertThat(List.copyOf(response.headers().keySet()))
                .isEqualTo(List.of("Content-Type", "X-One", "X-Two"));
    }

    @Test
    void theHeaderMapHandedOutCannotBeChanged() {
        WebResponse response = WebResponse.text("ok").header("X-One", "1");

        assertThatThrownBy(() -> response.headers().put("X-Two", "2"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aHeaderIsSetOnACopyAndLeavesTheOriginalAlone() {
        WebResponse original = WebResponse.text("ok");

        WebResponse withHeader = original.header("X-One", "1");

        assertThat(withHeader.header("X-One")).isEqualTo("1");
        assertThat(original.header("X-One")).isEqualTo(null);
    }

    /** An error handler's answer keeps what the framework had already worked out. */
    @Test
    void overKeepsTheBaseHeadersAndLetsTheNewOnesWin() {
        WebResponse base = WebResponse.empty(HttpStatus.METHOD_NOT_ALLOWED)
                .header("Allow", "GET, POST")
                .header("X-Kept", "yes");

        WebResponse answer = WebResponse.text("Method Not Allowed")
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .header("X-Kept", "replaced")
                .over(base);

        assertThat(answer.header("Allow")).isEqualTo("GET, POST");
        assertThat(answer.header("X-Kept")).isEqualTo("replaced");
        assertThat(List.copyOf(answer.headers().keySet()))
                .isEqualTo(List.of("Allow", "X-Kept", "Content-Type"));
    }

    /** 302, not 301: the default has to be the one that can be taken back. */
    @Test
    void aRedirectDefaultsToFound() {
        WebResponse response = WebResponse.redirect("/decks/3");

        assertThat(response.status()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.header("Location")).isEqualTo("/decks/3");
    }

    @Test
    void aRedirectCanNameItsOwnStatus() {
        assertThat(WebResponse.redirect("/new", HttpStatus.MOVED_PERMANENTLY).status())
                .isEqualTo(HttpStatus.MOVED_PERMANENTLY);
        assertThat(WebResponse.redirect("/decks", HttpStatus.SEE_OTHER).status())
                .isEqualTo(HttpStatus.SEE_OTHER);
        assertThat(WebResponse.redirect("/new", HttpStatus.PERMANENT_REDIRECT).status())
                .isEqualTo(HttpStatus.PERMANENT_REDIRECT);
    }

    /** A Location header on a 200 is not a redirect, so the status is checked. */
    @Test
    void aRedirectAtANonRedirectStatusIsRejected() {
        assertThatThrownBy(() -> WebResponse.redirect("/decks", HttpStatus.OK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebResponse.redirect("/decks", HttpStatus.NOT_FOUND))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Two things keying the same cached answer on two different request headers. */
    @Test
    void varyCollectsFieldsRatherThanReplacingThem() {
        WebResponse response = WebResponse.html("<p>hi</p>")
                .vary("Accept-Encoding")
                .vary("Origin");

        assertThat(response.header("Vary")).isEqualTo("Accept-Encoding, Origin");
    }

    @Test
    void varyDoesNotRepeatAFieldItAlreadyLists() {
        WebResponse response = WebResponse.html("<p>hi</p>")
                .header("Vary", "accept-encoding")
                .vary("Accept-Encoding");

        assertThat(response.header("Vary")).isEqualTo("accept-encoding");
    }
}
