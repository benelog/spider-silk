package net.benelog.spidersilk;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.json.Json;
import net.benelog.spidersilk.test.WebTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Which exception handler runs: the one for the most specific type, whatever the order. */
class ExceptionHandlersTest {

    @Test
    void theHandlerForTheMostSpecificTypeRunsWhenTheGeneralOneWasRegisteredFirst() {
        App app = new App()
                .get("/boom", req -> {
                    throw new IllegalStateException("specific");
                })
                .exception(Exception.class,
                        (req, e) -> WebResponse.text("general").status(HttpStatus.INTERNAL_SERVER_ERROR))
                .exception(IllegalStateException.class,
                        (req, e) -> WebResponse.text(e.getMessage()).status(HttpStatus.CONFLICT));

        WebTest.test(app, client -> {
            HttpResponse<String> response = client.get("/boom");

            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(response.body()).isEqualTo("specific");
        });
    }

    @Test
    void theHandlerForTheMostSpecificTypeRunsWhenItWasRegisteredFirst() {
        App app = new App()
                .get("/boom", req -> {
                    throw new IllegalStateException("specific");
                })
                .exception(IllegalStateException.class,
                        (req, e) -> WebResponse.text(e.getMessage()).status(HttpStatus.CONFLICT))
                .exception(Exception.class,
                        (req, e) -> WebResponse.text("general").status(HttpStatus.INTERNAL_SERVER_ERROR));

        WebTest.test(app, client -> {
            HttpResponse<String> response = client.get("/boom");

            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(response.body()).isEqualTo("specific");
        });
    }

    @Test
    void anExceptionNoHandlerCoversStillFallsToTheGeneralOne() {
        App app = new App()
                .get("/boom", req -> {
                    throw new UnsupportedOperationException("other");
                })
                .exception(IllegalStateException.class,
                        (req, e) -> WebResponse.text("wrong").status(HttpStatus.CONFLICT))
                .exception(RuntimeException.class,
                        (req, e) -> WebResponse.text(e.getMessage()).status(HttpStatus.BAD_GATEWAY));

        WebTest.test(app, client -> {
            HttpResponse<String> response = client.get("/boom");

            assertThat(response.statusCode()).isEqualTo(502);
            assertThat(response.body()).isEqualTo("other");
        });
    }

    /**
     * The trap this exists to close: an application that maps
     * IllegalArgumentException to 404 used to answer 404 for a body that failed
     * to parse, because Json threw the same type.
     */
    @Test
    void aBodyThatFailsToParseIsToldApartFromAnIllegalArgument() {
        App app = new App()
                .post("/decks", req -> WebResponse.text(
                        req.bodyJson().asObject().getString("name")))
                .get("/decks/{id}", req -> {
                    throw new IllegalArgumentException("Deck not found");
                })
                .exception(IllegalArgumentException.class,
                        (req, e) -> WebResponse.text(e.getMessage()).status(HttpStatus.NOT_FOUND))
                .exception(Json.JsonException.class,
                        (req, e) -> WebResponse.text(e.getMessage()).status(HttpStatus.BAD_REQUEST));

        WebTest.test(app, client -> {
            assertThat(client.postJson("/decks", "{}").statusCode()).isEqualTo(400);
            assertThat(client.postJson("/decks", "not json").statusCode()).isEqualTo(400);
            assertThat(client.postJson("/decks", "{\"name\":\"ok\"}").body()).isEqualTo("ok");
            assertThat(client.get("/decks/7").statusCode()).isEqualTo(404);
        });
    }
}
