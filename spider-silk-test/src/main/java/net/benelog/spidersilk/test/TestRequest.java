package net.benelog.spidersilk.test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.Part;

import net.benelog.spidersilk.WebRequest;
import net.benelog.spidersilk.WebResponse;
import net.benelog.spidersilk.json.Json;
import net.benelog.spidersilk.json.JsonWriter;

/**
 * Builds a {@link WebRequest} so a handler can be called directly, with no
 * server and no servlet container.
 *
 * <pre>{@code
 * @Test
 * void createDeckRespondsWith201() {
 *     WebResponse response = controller.createDeck(TestRequest.post("/api/decks")
 *             .jsonBody(Json.obj().put("name", "Spanish"))
 *             .build());
 *
 *     assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
 * }
 * }</pre>
 *
 * <p>This is the cheaper half of the two testing styles: a handler is a function
 * from a request to a response, so calling it and asserting on what comes back
 * needs neither a port nor a parsed HTTP message. {@link WebTest} is the other
 * half, for when routing, filters, and status handling are what is under test.
 *
 * <p>Path variables are supplied rather than matched — no route is involved, so
 * {@code pathParam("deckId", "3")} states what the router would have resolved.
 *
 * <p>What the request answers is what a container would answer, in the places
 * that a handler can tell apart: query and form parameters stay distinguishable
 * ({@link #queryParam} versus {@link #formParam}), a header lookup ignores case,
 * and asking for a file on a request that has none fails the way a real
 * non-multipart request fails.
 */
public final class TestRequest {

    private final String method;
    private final String path;

    private final Map<String, List<String>> headers =
            new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, List<String>> queryParams = new LinkedHashMap<>();
    private final Map<String, List<String>> formParams = new LinkedHashMap<>();
    private final Map<String, String> pathParams = new LinkedHashMap<>();
    private final List<Cookie> cookies = new ArrayList<>();
    private final List<Part> parts = new ArrayList<>();

    private String body = "";
    private StubServletRequest.StubSession session;
    private boolean secure;
    private String remoteAddress = "127.0.0.1";

    private TestRequest(String method, String path) {
        this.method = method;
        this.path = path;
    }

    // ---- Starting one ----

    /** A GET, the request most handlers are tested with. */
    public static TestRequest get(String path) {
        return method("GET", path);
    }

    /** A POST; the body comes from {@link #formParam}, {@link #body}, or {@link #jsonBody}. */
    public static TestRequest post(String path) {
        return method("POST", path);
    }

    /** A PUT, with the same body options as {@link #post}. */
    public static TestRequest put(String path) {
        return method("PUT", path);
    }

    /** A PATCH, with the same body options as {@link #post}. */
    public static TestRequest patch(String path) {
        return method("PATCH", path);
    }

    /** A DELETE. */
    public static TestRequest delete(String path) {
        return method("DELETE", path);
    }

    /** Any other method — HEAD, OPTIONS, or one of your own. */
    public static TestRequest method(String method, String path) {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("A path starts with '/': " + path);
        }
        if (path.indexOf('?') >= 0) {
            throw new IllegalArgumentException(
                    "Put the query string in queryParam(name, value), not in the path: " + path);
        }
        return new TestRequest(method, path);
    }

    // ---- Parameters ----

    /**
     * A query-string parameter. Call it twice with one name for a repeated one,
     * which is what {@code req.params(name)} and {@code req.queryParams(name)}
     * read back.
     */
    public TestRequest queryParam(String name, String value) {
        queryParams.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
        return this;
    }

    /**
     * A form field, as a parsed form body — the shape an HTML {@code <form>}
     * arrives in. Kept apart from {@link #queryParam} so that
     * {@code req.formParam} and {@code req.queryParam} answer differently, the
     * way they do behind a real container.
     */
    public TestRequest formParam(String name, String value) {
        formParams.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
        return this;
    }

    /** A path variable the router would have resolved from the pattern. */
    public TestRequest pathParam(String name, String value) {
        pathParams.put(name, value);
        return this;
    }

    /** A request header. Call it twice with one name for a repeated one. */
    public TestRequest header(String name, String value) {
        headers.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
        return this;
    }

    /**
     * Marks the request as having arrived over HTTPS, which is what
     * {@code isSecure()} reports and what HSTS and a Secure cookie ask about.
     */
    public TestRequest secure() {
        this.secure = true;
        return this;
    }

    /**
     * The address the request came from, which {@code remoteAddress()} reports —
     * what a rate limiter or an allow-list branches on. {@code 127.0.0.1} unless
     * a test says otherwise.
     *
     * <p>The host is stated as a header instead: {@code header("Host",
     * "shop.example.com")} is what {@code host()} and {@code scheme()} read,
     * the way a real request supplies them.
     */
    public TestRequest remoteAddress(String remoteAddress) {
        this.remoteAddress = remoteAddress;
        return this;
    }

    /** A cookie the client sent. Setting one is {@link WebResponse#cookie}. */
    public TestRequest cookie(String name, String value) {
        cookies.add(new Cookie(name, value));
        return this;
    }

    // ---- Body ----

    /** A raw body, read back by {@code req.body()}. */
    public TestRequest body(String body) {
        this.body = body;
        return this;
    }

    /**
     * A JSON body as raw text, with the content type that goes with it. This is
     * the form for a body no builder would produce — malformed JSON, or a field
     * the writer does not emit.
     */
    public TestRequest jsonBody(String json) {
        return body(json).header("Content-Type", "application/json");
    }

    /**
     * A JSON body as a tree, so a test states the body in the same builder the
     * handler reads it with rather than in escaped quotes:
     *
     * <pre>{@code
     * TestRequest.post("/api/decks").jsonBody(Json.obj().put("name", "Spanish"))
     * }</pre>
     */
    public TestRequest jsonBody(Json.JsonValue json) {
        return jsonBody(json.toJson());
    }

    /**
     * A JSON body written through the application's own writer, so the test and
     * the handler agree on the wire format by construction:
     *
     * <pre>{@code
     * static final JsonWriter<NewDeck> NEW_DECK = deck -> Json.obj().put("name", deck.name());
     *
     * TestRequest.post("/api/decks").jsonBody(new NewDeck("Spanish"), NEW_DECK)
     * }</pre>
     */
    public <T> TestRequest jsonBody(T value, JsonWriter<T> writer) {
        return jsonBody(writer.write(value));
    }

    /**
     * An uploaded file, read back by {@code req.file(name)}. A request with no
     * file at all is not a multipart request, so asking for one answers 400 —
     * the same as behind a container.
     *
     * <p>Call it twice with one name for a field that carries several files,
     * which is what {@code req.files(name)} reads back. An empty file name is
     * the file input a browser sent with nothing chosen, so
     * {@code req.fileOrNull(name)} answers null for it.
     */
    public TestRequest file(String name, String fileName, String contentType, byte[] content) {
        parts.add(new StubPart(name, fileName, contentType, content));
        return this;
    }

    /** A text file upload, encoded as UTF-8 — a CSV import and the like. */
    public TestRequest file(String name, String fileName, String content) {
        return file(name, fileName, "text/plain; charset=UTF-8",
                content.getBytes(StandardCharsets.UTF_8));
    }

    // ---- Session ----

    /**
     * A session attribute already in place, as if an earlier request had put it
     * there. Reading one back is {@code req.sessionAttr(key)}; a request built
     * without any of these has no session until the handler asks for one.
     */
    public TestRequest sessionAttr(String key, Object value) {
        if (session == null) {
            session = new StubServletRequest.StubSession();
        }
        session.setAttribute(key, value);
        return this;
    }

    // ---- Finishing ----

    /**
     * The request to hand a handler. What the handler leaves in the session is
     * readable afterwards through {@code sessionAttr(key)} on the request this
     * returns.
     */
    public WebRequest build() {
        Map<String, List<String>> headerCopy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headerCopy.putAll(copyOf(headers));
        StubServletRequest raw = new StubServletRequest(method, path, headerCopy,
                copyOf(queryParams), copyOf(formParams), List.copyOf(cookies), List.copyOf(parts),
                !parts.isEmpty(), body, session);
        raw.secure(secure);
        raw.remoteAddress(remoteAddress);
        return new WebRequest(raw, Map.copyOf(pathParams));
    }

    /** Parameter names are case-sensitive; only the header map above is not. */
    private static Map<String, List<String>> copyOf(Map<String, List<String>> values) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        values.forEach((name, list) -> copy.put(name, List.copyOf(list)));
        return copy;
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** One uploaded file, held in memory. Its bytes are the caller's, so equality is identity. */
    @SuppressWarnings("ArrayRecordComponent")
    private record StubPart(String name, String fileName, String contentType, byte[] content)
            implements Part {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getSubmittedFileName() {
            return fileName;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public String getHeader(String headerName) {
            return "Content-Type".equalsIgnoreCase(headerName) ? contentType : null;
        }

        @Override
        public Collection<String> getHeaders(String headerName) {
            String value = getHeader(headerName);
            return value == null ? List.of() : List.of(value);
        }

        @Override
        public Collection<String> getHeaderNames() {
            return List.of("Content-Type");
        }

        /**
         * Writes the bytes out, so {@code UploadedFile.writeTo(path)} works here
         * as it does behind a container. There is no multipart location to
         * resolve a relative name against, so the name is taken as it is.
         */
        @Override
        public void write(String target) throws IOException {
            Files.write(Path.of(target), content);
        }

        @Override
        public void delete() {
        }
    }
}
