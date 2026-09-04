package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.net.URI;
import java.net.http.HttpRequest;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.test.TestRequest;
import net.benelog.spidersilk.test.WebTest;

/** A session attribute read under a named type, and a session ended. */
class SessionAttributesTest {

    record User(String name) {
    }

    @Test
    void aNamedTypeReadsBackAsThatType() {
        WebRequest request = TestRequest.get("/me").sessionAttr("user", new User("Ada")).build();

        assertThat(request.sessionAttr("user", User.class)).isEqualTo(new User("Ada"));
    }

    /** Absence is null, not a failure, whichever form asked. */
    @Test
    void anAbsentAttributeIsNull() {
        assertThat(TestRequest.get("/me").build().sessionAttr("user", User.class)).isNull();
        assertThat(TestRequest.get("/me").sessionAttr("other", "x").build()
                .sessionAttr("user", User.class)).isNull();
    }

    /** The failure lands on the read, and names the key and both types. */
    @Test
    void aValueOfAnotherTypeFailsWhereItIsRead() {
        WebRequest request = TestRequest.get("/me").sessionAttr("user", "Ada").build();

        assertThatIllegalStateException()
                .isThrownBy(() -> request.sessionAttr("user", User.class))
                .withMessageContaining("user")
                .withMessageContaining("java.lang.String")
                .withMessageContaining(User.class.getName());
    }

    /** A wrong type is the application's own mistake, so it answers 500 rather than 400. */
    @Test
    void aWrongTypeIsAServerError() {
        App app = new App()
                .before(req -> {
                    req.sessionAttr("user", "Ada");
                    return null;
                })
                .get("/me", req -> WebResponse.text(req.sessionAttr("user", User.class).name()));

        WebTest.test(app, client -> assertThat(client.get("/me").statusCode()).isEqualTo(500));
    }

    @Test
    void invalidatingASessionRemovesWhatWasInIt() {
        WebRequest request = TestRequest.get("/logout").sessionAttr("user", new User("Ada")).build();

        request.invalidateSession();

        assertThat(request.sessionAttr("user", User.class)).isNull();
    }

    /** Logging out twice is not an error: there is simply no session to end. */
    @Test
    void invalidatingWithoutASessionDoesNothing() {
        WebRequest request = TestRequest.get("/logout").build();

        request.invalidateSession();

        assertThat(request.sessionAttr("user", User.class)).isNull();
    }

    /** The visitor's session cookie stops working: the next request signs in again. */
    @Test
    void aLoggedOutVisitorStartsANewSession() {
        App app = new App()
                .post("/login", req -> {
                    req.sessionAttr("user", new User("Ada"));
                    return WebResponse.noContent();
                })
                .get("/me", req -> {
                    User user = req.sessionAttr("user", User.class);
                    return WebResponse.text(user == null ? "nobody" : user.name());
                })
                .post("/logout", req -> {
                    req.invalidateSession();
                    return WebResponse.noContent();
                });

        WebTest.test(app, client -> {
            String cookie = client.post("/login").headers().firstValue("Set-Cookie").orElseThrow();

            assertThat(as(client, cookie, "/me").body()).isEqualTo("Ada");

            as(client, cookie, "/logout", "POST");

            assertThat(as(client, cookie, "/me").body()).isEqualTo("nobody");
        });
    }

    private static java.net.http.HttpResponse<String> as(
            net.benelog.spidersilk.test.TestClient client, String cookie, String path) {
        return as(client, cookie, path, "GET");
    }

    private static java.net.http.HttpResponse<String> as(
            net.benelog.spidersilk.test.TestClient client, String cookie, String path, String method) {
        return client.send(request -> request.uri(URI.create(client.url(path)))
                .header("Cookie", cookie.split(";", 2)[0])
                .method(method, HttpRequest.BodyPublishers.noBody()));
    }
}
