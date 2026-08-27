package net.benelog.spidersilk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import net.benelog.spidersilk.test.WebTest;

/**
 * The README blocks that need {@code spider-silk-test}, which the example
 * module does not depend on. See {@code flashcard.ReadmeSnippets} for the rest.
 *
 * <p>Not a test: it is compiled, never run, so that a README snippet naming a
 * method the harness no longer has fails the build.
 */
@SuppressWarnings("unused")
class ReadmeSnippets {

    void createsADeck(App app) {
        WebTest.test(app, client -> {
            var created = client.postForm("/decks", Map.of("name", "English"));
            assertThat(created.statusCode()).isEqualTo(302);   // redirects are not followed
            assertThat(client.get("/api/decks").body()).contains("English");
        });
    }

    /** The `get`/`post`/... list the README claims TestClient offers. */
    void everyClientMethod(App app) {
        WebTest.test(app, client -> {
            client.get("/");
            client.post("/");
            client.put("/", "body");
            client.patch("/", "body");
            client.delete("/");
            client.head("/");
            client.options("/");
            client.postForm("/", Map.of("name", "English"));
            client.postJson("/", "{}");
            client.send(request -> request.uri(java.net.URI.create(client.url("/"))).GET());
        });
    }
}
