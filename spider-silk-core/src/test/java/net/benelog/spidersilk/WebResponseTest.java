package net.benelog.spidersilk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.test.WebTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The envelope itself: what a {@code with}-style method does to the one it replaces. */
class WebResponseTest {

    @Test
    void aPlainAsciiFileNameIsQuotedAsItIs() {
        assertThat(WebResponse.empty().attachment("deck.csv").header("Content-Disposition"))
                .isEqualTo("attachment; filename=\"deck.csv\"");
    }

    /**
     * A name a quoted string cannot carry as it is gets an ASCII fallback and
     * RFC 6266's {@code filename*}, which every browser in use reads first.
     */
    @Test
    void aFileNameOutsidePlainAsciiAlsoGoesOutAsFilenameStar() {
        assertThat(WebResponse.empty().attachment("덱 \"final\".csv").header("Content-Disposition"))
                .isEqualTo("attachment; filename=\"_ \\\"final\\\".csv\";"
                        + " filename*=UTF-8''%EB%8D%B1%20%22final%22.csv");
        assertThat(WebResponse.empty().attachment("a\\b.txt").header("Content-Disposition"))
                .isEqualTo("attachment; filename=\"a\\\\b.txt\"; filename*=UTF-8''a%5Cb.txt");
        assertThat(WebResponse.empty().attachment("😀.png").header("Content-Disposition"))
                .as("one character outside the BMP is one fallback character")
                .isEqualTo("attachment; filename=\"_.png\"; filename*=UTF-8''%F0%9F%98%80.png");
    }

    @Test
    void aControlCharacterInAFileNameIsRefused() {
        assertThatThrownBy(() -> WebResponse.empty().attachment("deck.csv\r\nSet-Cookie: x=1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebResponse.empty().attachment("a\tb.csv"))
                .isInstanceOf(IllegalArgumentException.class);
    }

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

    /** Field names are case-insensitive in HTTP, and so is the map that holds them. */
    @Test
    void aHeaderIsReadBackWhateverSpellingIsAskedFor() {
        WebResponse response = WebResponse.text("ok").header("X-Trace-Id", "abc");

        assertThat(response.header("x-trace-id")).isEqualTo("abc");
        assertThat(response.header("X-TRACE-ID")).isEqualTo("abc");
        assertThat(response.headers().get("content-type")).isEqualTo("text/plain; charset=UTF-8");
        assertThat(response.headers().containsKey("CONTENT-TYPE")).isTrue();
        assertThat(response.headers()).hasSize(2);
    }

    /** One field has one value, and the spelling it arrived under is the one that stays. */
    @Test
    void aSecondSpellingReplacesTheValueAndKeepsTheFirstNameAndPlace() {
        WebResponse response = WebResponse.text("ok")
                .header("X-One", "1")
                .header("content-type", "application/json")
                .header("x-one", "again");

        assertThat(response.header("Content-Type")).isEqualTo("application/json");
        assertThat(response.header("X-One")).isEqualTo("again");
        assertThat(List.copyOf(response.headers().keySet()))
                .isEqualTo(List.of("Content-Type", "X-One"));
    }

    /** A filter that renames the spelling still sets the field the framework set. */
    @Test
    void overMatchesTheBaseHeaderWhateverItsSpelling() {
        WebResponse base = WebResponse.empty(HttpStatus.METHOD_NOT_ALLOWED)
                .header("Allow", "GET, POST");

        WebResponse answer = WebResponse.text("Method Not Allowed").header("allow", "GET").over(base);

        assertThat(answer.header("Allow")).isEqualTo("GET");
        assertThat(List.copyOf(answer.headers().keySet()))
                .isEqualTo(List.of("Allow", "Content-Type"));
    }

    /** The whole point of the map: one field, one line on the wire. */
    @Test
    void aFieldSetTwiceUnderTwoSpellingsIsSentOnce() {
        App app = new App().get("/", req -> WebResponse.text("ok")
                .header("content-type", "text/plain; charset=UTF-8")
                .header("X-Trace-Id", "abc")
                .header("x-trace-id", "def"));

        WebTest.test(app, client -> {
            var response = client.get("/");

            assertThat(response.headers().allValues("Content-Type")).hasSize(1);
            assertThat(response.headers().allValues("X-Trace-Id")).isEqualTo(List.of("def"));
        });
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

    // ---- What a caller still holds cannot change a response already made ----

    @Test
    void aCookieChangedAfterItWasAddedDoesNotChangeTheResponse() {
        Cookie cookie = new Cookie("theme", "dark");
        cookie.setSecure(true);
        cookie.setAttribute("SameSite", "None");
        WebResponse response = WebResponse.empty().cookie(cookie);

        cookie.setValue("light");
        cookie.setAttribute("SameSite", "Strict");

        Cookie sent = response.cookies().get(0);
        assertThat(sent.getValue()).isEqualTo("dark");
        assertThat(sent.getSecure()).isTrue();
        assertThat(sent.getAttribute("SameSite")).isEqualTo("None");
    }

    @Test
    void aCookieTakenFromTheResponseIsACopy() {
        WebResponse response = WebResponse.empty().cookie("theme", "dark");

        Cookie taken = response.cookies().get(0);
        taken.setValue("light");
        taken.setAttribute("SameSite", "None");

        Cookie again = response.cookies().get(0);
        assertThat(again.getValue()).isEqualTo("dark");
        assertThat(again.getPath()).isEqualTo("/");
        assertThat(again.isHttpOnly()).isTrue();
        assertThat(again.getAttribute("SameSite")).isEqualTo("Lax");
    }

    /** What reaches the wire is the copy, so a cookie changed afterwards is not what the browser gets. */
    @Test
    void theCookieSentIsTheOneAddedNotTheOneChangedLater() {
        Cookie cookie = new Cookie("theme", "dark");
        WebResponse answer = WebResponse.text("ok").cookie(cookie);
        cookie.setValue("light");
        App app = new App().get("/", req -> answer);

        WebTest.test(app, client -> assertThat(client.get("/").headers().firstValue("Set-Cookie"))
                .hasValueSatisfying(header -> assertThat(header).startsWith("theme=dark")));
    }

    @Test
    void aModelChangedAfterTheTemplateWasBuiltDoesNotChangeTheTemplate() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("title", "Decks");
        WebResponse response = WebResponse.template("decks", model);

        model.put("title", "Changed");
        model.put("extra", "added");

        WebResponse.Template template = (WebResponse.Template) response.body();
        assertThat(template.model()).containsExactly(Map.entry("title", "Decks"));
    }

    /** The copy is read-only, keeps a null value, and keeps the order the model iterated in. */
    @Test
    void theModelCopyIsReadOnlyAndKeepsNullsAndOrder() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("b", 2);
        model.put("message", null);
        model.put("a", 1);

        WebResponse.Template template = new WebResponse.Template("decks", model);

        assertThat(List.copyOf(template.model().keySet())).containsExactly("b", "message", "a");
        assertThat(template.model()).containsEntry("message", null);
        assertThatThrownBy(() -> template.model().put("c", 3))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
