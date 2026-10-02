package net.benelog.spidersilk.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.UnaryOperator;

import net.benelog.spidersilk.json.JsonValue;

/**
 * An HTTP client aimed at the app under test.
 *
 * <p>Every method returns the raw {@link HttpResponse} rather than an assertion
 * DSL — the assertions belong to whichever test library the project already
 * uses. {@link #send(UnaryOperator)} is the way out for anything these
 * shorthands do not cover.
 */
public final class TestClient {

    private final HttpClient client;
    private final String baseUrl;

    TestClient(HttpClient client, String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl;
    }

    /** The absolute URL a path resolves to, e.g. "http://localhost:34567/decks". */
    public String url(String path) {
        return baseUrl + path;
    }

    /** Sends a GET. */
    public HttpResponse<String> get(String path) {
        return send(request -> request.uri(URI.create(url(path))).GET());
    }

    /** Sends a HEAD, which the app answers with the GET route's headers and no body. */
    public HttpResponse<String> head(String path) {
        return send(request -> request.uri(URI.create(url(path)))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()));
    }

    /** Sends an OPTIONS, answered from the routes registered for the path. */
    public HttpResponse<String> options(String path) {
        return send(request -> request.uri(URI.create(url(path)))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()));
    }

    /** Sends a DELETE. */
    public HttpResponse<String> delete(String path) {
        return send(request -> request.uri(URI.create(url(path))).DELETE());
    }

    /** Posts an empty body, for a route that acts on the path alone. */
    public HttpResponse<String> post(String path) {
        return post(path, "");
    }

    /** Posts a raw body. Pair it with {@code header("Content-Type", ...)} via {@link #send}. */
    public HttpResponse<String> post(String path, String body) {
        return send(request -> request.uri(URI.create(url(path)))
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    /** Puts a raw body, with the same Content-Type advice as {@link #post(String, String)}. */
    public HttpResponse<String> put(String path, String body) {
        return send(request -> request.uri(URI.create(url(path)))
                .PUT(HttpRequest.BodyPublishers.ofString(body)));
    }

    /** Patches with a raw body, with the same Content-Type advice as {@link #post(String, String)}. */
    public HttpResponse<String> patch(String path, String body) {
        return send(request -> request.uri(URI.create(url(path)))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body)));
    }

    /** Posts a URL-encoded form, the shape an HTML {@code <form>} submits. */
    public HttpResponse<String> postForm(String path, Map<String, String> form) {
        return send(request -> request.uri(URI.create(url(path)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encode(form))));
    }

    /** Posts a JSON body, sent as {@code Content-Type: application/json}. */
    public HttpResponse<String> postJson(String path, String json) {
        return send(request -> request.uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    /**
     * Posts a JSON body given as a tree, so a test states the body in the same
     * builder the handler reads it with rather than in escaped quotes:
     *
     * <pre>{@code
     * client.postJson("/api/decks", Json.object().put("name", "Spanish"))
     * }</pre>
     *
     * <p>A body the application writes through its own {@code JsonWriter} is
     * {@code client.postJson(path, NEW_DECK.write(deck))}.
     */
    public HttpResponse<String> postJson(String path, JsonValue json) {
        return postJson(path, json.toJson());
    }

    /** Puts a JSON body, sent as {@code Content-Type: application/json}. */
    public HttpResponse<String> putJson(String path, String json) {
        return send(request -> request.uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json)));
    }

    /** Puts a JSON body given as a tree, as {@link #postJson(String, JsonValue)} posts one. */
    public HttpResponse<String> putJson(String path, JsonValue json) {
        return putJson(path, json.toJson());
    }

    /** Patches with a JSON body, sent as {@code Content-Type: application/json}. */
    public HttpResponse<String> patchJson(String path, String json) {
        return send(request -> request.uri(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json)));
    }

    /** Patches with a JSON body given as a tree, as {@link #postJson(String, JsonValue)} posts one. */
    public HttpResponse<String> patchJson(String path, JsonValue json) {
        return patchJson(path, json.toJson());
    }

    /** Builds and sends any request. Redirects are not followed, so 302s stay visible. */
    public HttpResponse<String> send(UnaryOperator<HttpRequest.Builder> build) {
        return send(build, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The same, reading the body some other way — as bytes, when the response is
     * compressed or is a file and decoding it as text would destroy it.
     */
    public <T> HttpResponse<T> send(UnaryOperator<HttpRequest.Builder> build,
            HttpResponse.BodyHandler<T> bodyHandler) {
        try {
            return client.send(build.apply(HttpRequest.newBuilder()).build(), bodyHandler);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a response", e);
        }
    }

    private static String encode(Map<String, String> form) {
        StringJoiner encoded = new StringJoiner("&");
        form.forEach((name, value) -> encoded.add(URLEncoder.encode(name, StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return encoded.toString();
    }
}
