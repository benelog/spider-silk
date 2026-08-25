package spidersilk.test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.Part;

import spidersilk.WebRequest;
import spidersilk.WebResponse;

/**
 * Builds a {@link WebRequest} so a handler can be called directly, with no
 * server and no servlet container.
 *
 * <pre>{@code
 * @Test
 * void createDeckRespondsWith201() {
 *     WebResponse response = controller.createDeck(TestRequest.post("/api/decks")
 *             .jsonBody("{\"name\": \"Spanish\"}")
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
    private final Map<String, Part> parts = new LinkedHashMap<>();

    private String body = "";
    private StubServletRequest.StubSession session;
    private boolean secure;

    private TestRequest(String method, String path) {
        this.method = method;
        this.path = path;
    }

    // ---- Starting one ----

    public static TestRequest get(String path) {
        return method("GET", path);
    }

    public static TestRequest post(String path) {
        return method("POST", path);
    }

    public static TestRequest put(String path) {
        return method("PUT", path);
    }

    public static TestRequest patch(String path) {
        return method("PATCH", path);
    }

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

    public TestRequest header(String name, String value) {
        headers.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
        return this;
    }

    /** A cookie the client sent. Setting one is {@link WebResponse#cookie}. */
    /**
     * Marks the request as having arrived over HTTPS, which is what
     * {@code isSecure()} reports and what HSTS and a Secure cookie ask about.
     */
    public TestRequest secure() {
        this.secure = true;
        return this;
    }

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

    /** A JSON body, with the content type that goes with it. */
    public TestRequest jsonBody(String json) {
        return body(json).header("Content-Type", "application/json");
    }

    /**
     * An uploaded file, read back by {@code req.file(name)}. A request with no
     * file at all is not a multipart request, so asking for one answers 400 —
     * the same as behind a container.
     */
    public TestRequest file(String name, String fileName, String contentType, byte[] content) {
        parts.put(name, new StubPart(name, fileName, contentType, content));
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
                copyOf(queryParams), copyOf(formParams), List.copyOf(cookies), Map.copyOf(parts),
                !parts.isEmpty(), body, session);
        raw.secure(secure);
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

    /** One uploaded file, held in memory. */
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

        @Override
        public void write(String target) {
            throw new UnsupportedOperationException(
                    "A TestRequest upload is held in memory; read it with bytes() or asText()");
        }

        @Override
        public void delete() {
        }
    }
}
