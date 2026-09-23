package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.test.TestRequest;
import net.benelog.spidersilk.test.WebTest;

/** A session attribute read under a named type, a session ended, and flash delivered once. */
class SessionAttributesTest {

    record User(String name) {
    }

    @Test
    void aNamedTypeReadsBackAsThatType() {
        WebRequest request = TestRequest.get("/me").session("user", new User("Ada")).build();

        assertThat(request.session().get("user", User.class)).isEqualTo(new User("Ada"));
    }

    /** Absence is null, not a failure, whichever form asked. */
    @Test
    void anAbsentAttributeIsNull() {
        assertThat(TestRequest.get("/me").build().session().get("user", User.class)).isNull();
        assertThat(TestRequest.get("/me").session("other", "x").build()
                .session().get("user", User.class)).isNull();
    }

    /** The failure lands on the read, and names the key and both types. */
    @Test
    void aValueOfAnotherTypeFailsWhereItIsRead() {
        WebRequest request = TestRequest.get("/me").session("user", "Ada").build();

        assertThatIllegalStateException()
                .isThrownBy(() -> request.session().get("user", User.class))
                .withMessageContaining("user")
                .withMessageContaining("java.lang.String")
                .withMessageContaining(User.class.getName());
    }

    /** A wrong type is the application's own mistake, so it answers 500 rather than 400. */
    @Test
    void aWrongTypeIsAServerError() {
        App app = new App()
                .beforeRoute(req -> {
                    req.session().set("user", "Ada");
                    return null;
                })
                .get("/me", req -> WebResponse.text(req.session().get("user", User.class).name()));

        WebTest.test(app, client -> assertThat(client.get("/me").statusCode()).isEqualTo(500));
    }

    /** A {@code Class} is a value like any other once the write has its own name. */
    @Test
    void aClassIsStoredWithoutACast() {
        WebRequest request = TestRequest.get("/me").build();

        request.session().set("kind", User.class);

        assertThat(request.session().get("kind", Class.class)).isEqualTo(User.class);
    }

    /** A null type is not a removal in disguise: it fails, and leaves the attribute where it was. */
    @Test
    void aNullTypeFailsAndNamesTheRemoval() {
        WebRequest request = TestRequest.get("/me").session("user", new User("Ada")).build();

        assertThatNullPointerException()
                .isThrownBy(() -> request.session().get("user", null))
                .withMessageContaining("remove(key)");
        assertThat(request.session().get("user", User.class)).isEqualTo(new User("Ada"));
    }

    /** Asking for the session and reading from it start none: only a write does. */
    @Test
    void readingWithoutASessionStartsNone() {
        App app = new App().get("/me", req -> {
            Object user = req.session().get("user");
            return WebResponse.text(String.valueOf(user));
        });

        WebTest.test(app, client -> {
            var response = client.get("/me");
            assertThat(response.body()).isEqualTo("null");
            assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        });
    }

    @Test
    void removingAnAttributeWithoutASessionStartsNone() {
        App app = new App().post("/forget", req -> {
            req.session().remove("user");
            return WebResponse.noContent();
        });

        WebTest.test(app, client -> assertThat(client.post("/forget").headers().firstValue("Set-Cookie")).isEmpty());
    }

    @Test
    void invalidatingASessionRemovesWhatWasInIt() {
        WebRequest request = TestRequest.get("/logout").session("user", new User("Ada")).build();

        request.session().invalidate();

        assertThat(request.session().get("user", User.class)).isNull();
    }

    /** Logging out twice is not an error: there is simply no session to end. */
    @Test
    void invalidatingWithoutASessionDoesNothing() {
        WebRequest request = TestRequest.get("/logout").build();

        request.session().invalidate();

        assertThat(request.session().get("user", User.class)).isNull();
    }

    /** The visitor's session cookie stops working: the next request signs in again. */
    @Test
    void aLoggedOutVisitorStartsANewSession() {
        App app = new App()
                .post("/login", req -> {
                    req.session().set("user", new User("Ada"));
                    return WebResponse.noContent();
                })
                .get("/me", req -> {
                    User user = req.session().get("user", User.class);
                    return WebResponse.text(user == null ? "nobody" : user.name());
                })
                .post("/logout", req -> {
                    req.session().invalidate();
                    return WebResponse.noContent();
                });

        WebTest.test(app, client -> {
            String cookie = client.post("/login").headers().firstValue("Set-Cookie").orElseThrow();

            assertThat(as(client, cookie, "/me").body()).isEqualTo("Ada");

            as(client, cookie, "/logout", "POST");

            assertThat(as(client, cookie, "/me").body()).isEqualTo("nobody");
        });
    }

    /**
     * A null value removes the attribute, which is the container's rule for
     * setAttribute. The null is a literal: the write has a name of its own, so
     * nothing resolves it to a read.
     */
    @Test
    void writingNullRemovesASessionAttribute() {
        App app = new App()
                .post("/login", req -> {
                    req.session().set("user", new User("Ada"));
                    return WebResponse.noContent();
                })
                .post("/forget", req -> {
                    req.session().set("user", null);
                    return WebResponse.noContent();
                })
                .get("/me", req -> WebResponse.text(String.valueOf(req.session().get("user", User.class))));

        WebTest.test(app, client -> {
            client.post("/login");
            client.post("/forget");

            assertThat(client.get("/me").body()).isEqualTo("null");
        });
    }

    /** Flashing null follows session().set: it withdraws a flash set earlier in the same request. */
    @Test
    void flashingNullWithdrawsAFlashSetEarlier() {
        App app = new App()
                .post("/save", req -> {
                    req.flash("message", "saved");
                    req.flash("other", "kept");
                    req.flash("message", null);
                    return WebResponse.noContent();
                })
                .get("/home", req -> WebResponse.text(req.flashed("message") + " " + req.flashed("other")));

        WebTest.test(app, client -> {
            client.post("/save");

            assertThat(client.get("/home").body()).isEqualTo("null kept");
        });
    }

    /** Withdrawing touches only what waits for the next request, not what this one received. */
    @Test
    void flashingNullLeavesWhatThisRequestReceived() {
        App app = new App()
                .post("/save", req -> {
                    req.flash("message", "saved");
                    return WebResponse.noContent();
                })
                .get("/home", req -> {
                    req.flash("message", null);
                    return WebResponse.text(String.valueOf(req.flashed("message")));
                });

        WebTest.test(app, client -> {
            client.post("/save");

            assertThat(client.get("/home").body()).isEqualTo("saved");
            assertThat(client.get("/home").body()).isEqualTo("null");
        });
    }

    /** There is nothing to withdraw without a session, so flashing null does not start one. */
    @Test
    void flashingNullWithoutASessionStartsNone() {
        App app = new App().post("/clear", req -> {
            req.flash("message", null);
            return WebResponse.noContent();
        });

        WebTest.test(app, client -> assertThat(client.post("/clear").headers().firstValue("Set-Cookie")).isEmpty());
    }

    /**
     * Flash is delivered exactly once, and a session read by two requests at the
     * same time is where "once" is hard to keep. A browser makes that case by
     * prefetching the redirect target, or by having a second tab open on the
     * same site.
     *
     * <p>The window between taking the flash and removing it is a few
     * instructions wide, so one round of the race finds an unsynchronized
     * promotion only now and then. The round is therefore repeated: the answer
     * is exact every time when the promotion holds the session's monitor, and
     * over this many attempts it is not when it does not.
     */
    @Test
    void aFlashedMessageReachesOnlyOneOfTwoSimultaneousRequests() {
        App app = new App()
                .post("/save", req -> {
                    req.flash("message", "saved");
                    return WebResponse.noContent();
                })
                .get("/home", req -> WebResponse.text(String.valueOf(req.flashed("message"))));

        WebTest.test(app, client -> {
            for (int round = 0; round < 50; round++) {
                // The client keeps cookies, so both reads below are one session.
                client.post("/save");

                List<String> answers = together(2, () -> client.get("/home").body());

                assertThat(answers).containsExactlyInAnyOrder("saved", "null");
            }
        });
    }

    /** Runs a call on that many threads, released together, and collects the answers. */
    private static List<String> together(int threads, Supplier<String> call) {
        CyclicBarrier start = new CyclicBarrier(threads);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<String>> pending = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                pending.add(pool.submit(() -> {
                    start.await();
                    return call.get();
                }));
            }
            List<String> answers = new ArrayList<>();
            for (Future<String> answer : pending) {
                answers.add(answer.get());
            }
            return answers;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the answers", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("A request failed", e.getCause());
        }
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
