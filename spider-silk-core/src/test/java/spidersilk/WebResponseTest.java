package spidersilk;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The envelope itself: what a {@code with}-style method does to the one it replaces. */
class WebResponseTest {

    @Test
    void aTemplateWithNothingToPassInNeedsNoModel() {
        WebResponse response = WebResponse.template("about");

        WebResponse.Template template = (WebResponse.Template) response.body();
        assertEquals("about", template.name());
        assertEquals(Map.of(), template.model());
    }

    @Test
    void headersKeepTheOrderTheyWereSetIn() {
        WebResponse response = WebResponse.text("ok")
                .header("X-One", "1")
                .header("X-Two", "2")
                .header("X-Three", "3");

        assertEquals(List.of("Content-Type", "X-One", "X-Two", "X-Three"),
                List.copyOf(response.headers().keySet()));
    }

    @Test
    void settingAHeaderTwiceReplacesItInPlace() {
        WebResponse response = WebResponse.text("ok")
                .header("X-One", "1")
                .header("X-Two", "2")
                .header("X-One", "again");

        assertEquals("again", response.header("X-One"));
        assertEquals(List.of("Content-Type", "X-One", "X-Two"),
                List.copyOf(response.headers().keySet()));
    }

    @Test
    void theHeaderMapHandedOutCannotBeChanged() {
        WebResponse response = WebResponse.text("ok").header("X-One", "1");

        assertThrows(UnsupportedOperationException.class,
                () -> response.headers().put("X-Two", "2"));
    }

    @Test
    void aHeaderIsSetOnACopyAndLeavesTheOriginalAlone() {
        WebResponse original = WebResponse.text("ok");

        WebResponse withHeader = original.header("X-One", "1");

        assertEquals("1", withHeader.header("X-One"));
        assertEquals(null, original.header("X-One"));
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

        assertEquals("GET, POST", answer.header("Allow"));
        assertEquals("replaced", answer.header("X-Kept"));
        assertEquals(List.of("Allow", "X-Kept", "Content-Type"),
                List.copyOf(answer.headers().keySet()));
    }

    /** 302, not 301: the default has to be the one that can be taken back. */
    @Test
    void aRedirectDefaultsToFound() {
        WebResponse response = WebResponse.redirect("/decks/3");

        assertEquals(HttpStatus.FOUND, response.status());
        assertEquals("/decks/3", response.header("Location"));
    }

    @Test
    void aRedirectCanNameItsOwnStatus() {
        assertEquals(HttpStatus.MOVED_PERMANENTLY,
                WebResponse.redirect("/new", HttpStatus.MOVED_PERMANENTLY).status());
        assertEquals(HttpStatus.SEE_OTHER,
                WebResponse.redirect("/decks", HttpStatus.SEE_OTHER).status());
        assertEquals(HttpStatus.PERMANENT_REDIRECT,
                WebResponse.redirect("/new", HttpStatus.PERMANENT_REDIRECT).status());
    }

    /** A Location header on a 200 is not a redirect, so the status is checked. */
    @Test
    void aRedirectAtANonRedirectStatusIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> WebResponse.redirect("/decks", HttpStatus.OK));
        assertThrows(IllegalArgumentException.class,
                () -> WebResponse.redirect("/decks", HttpStatus.NOT_FOUND));
    }
}
