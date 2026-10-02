package net.benelog.spidersilk.test;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;

import net.benelog.spidersilk.App;
import net.benelog.spidersilk.WebRequest;
import net.benelog.spidersilk.WebResponse;
import net.benelog.spidersilk.json.Json;
import net.benelog.spidersilk.json.JsonValue;

import static org.assertj.core.api.Assertions.assertThat;

/** What the client's JSON shorthands put on the wire. */
class TestClientTest {

    private static WebResponse echo(WebRequest req) {
        return WebResponse.text(req.method() + " " + req.contentType() + " "
                + req.bodyJson().asObject().getString("name"));
    }

    private static App echoApp() {
        return new App()
                .post("/decks", TestClientTest::echo)
                .put("/decks", TestClientTest::echo)
                .patch("/decks", TestClientTest::echo);
    }

    @Test
    void everyJsonSendCarriesTheJsonContentType() {
        WebTest.test(echoApp(), client -> {
            List<BiFunction<String, String, HttpResponse<String>>> textSends =
                    List.of(client::postJson, client::putJson, client::patchJson);
            List<String> methods = List.of("POST", "PUT", "PATCH");

            for (int i = 0; i < textSends.size(); i++) {
                assertThat(textSends.get(i).apply("/decks", "{\"name\":\"Spanish\"}").body())
                        .isEqualTo(methods.get(i) + " application/json Spanish");
            }
        });
    }

    @Test
    void everyJsonSendTakesATree() {
        WebTest.test(echoApp(), client -> {
            List<BiFunction<String, JsonValue, HttpResponse<String>>> treeSends =
                    List.of(client::postJson, client::putJson, client::patchJson);
            List<String> methods = List.of("POST", "PUT", "PATCH");

            for (int i = 0; i < treeSends.size(); i++) {
                assertThat(treeSends.get(i).apply("/decks", Json.object().put("name", "Spanish")).body())
                        .isEqualTo(methods.get(i) + " application/json Spanish");
            }
        });
    }
}
