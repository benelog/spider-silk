package net.benelog.spidersilk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/** An App is registered before it starts: the routing table is read without a lock. */
class RegistrationAfterStartTest {

    private final App app = new App().get("/", req -> WebResponse.text("ok"));

    @AfterEach
    void stop() {
        app.stop();
    }

    @Test
    void registeringOnARunningAppIsRefused() {
        app.start(0);

        assertThatIllegalStateException()
                .isThrownBy(() -> app.get("/late", req -> WebResponse.text("late")))
                .withMessageContaining("register before start()");
        assertThatIllegalStateException()
                .isThrownBy(() -> app.path("/api", api -> api.post("/x", req -> WebResponse.empty())));
        assertThatIllegalStateException()
                .isThrownBy(() -> app.before(req -> null));
        assertThatIllegalStateException()
                .isThrownBy(() -> app.after((req, res) -> null));
        assertThatIllegalStateException()
                .isThrownBy(() -> app.exception(Exception.class, (e, req) -> WebResponse.empty()));
        assertThatIllegalStateException()
                .isThrownBy(() -> app.error(HttpStatus.NOT_FOUND, req -> WebResponse.text("gone")));
        assertThatIllegalStateException()
                .isThrownBy(() -> app.gzip());
        assertThatIllegalStateException()
                .isThrownBy(() -> app.staticFiles("/assets"));
    }

    @Test
    void stoppingOpensRegistrationAgain() {
        app.start(0);
        app.stop();

        assertThatCode(() -> app.get("/late", req -> WebResponse.text("late")))
                .doesNotThrowAnyException();
    }
}
