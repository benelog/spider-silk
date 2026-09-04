package net.benelog.spidersilk;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Stream;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.Part;

import net.benelog.spidersilk.json.Json;
import net.benelog.spidersilk.json.JsonReader;

/**
 * The request side of a handler: what was asked for, and the session it was
 * asked in. The answer is the {@link WebResponse} the handler returns.
 *
 * <p>Reading is the whole of it, with one deliberate exception: the session.
 * {@link #sessionAttr(String, Object)} and {@link #flash(String, String)} write,
 * because a session outlives the response and cannot be a value returned from
 * one. Cookies are the other half of that split — the ones the client sent are
 * read here, the ones the server sets belong to {@link WebResponse}.
 *
 * <p>Type conversion happens only through explicit methods
 * ({@link #pathParamLong} and the like). No reflection.
 */
public final class WebRequest {

    static final String FLASH_ATTRIBUTE = "net.benelog.spidersilk.flash";
    static final String NEGOTIATED_ATTRIBUTE = "net.benelog.spidersilk.negotiated";

    private final HttpServletRequest req;
    private final Map<String, String> pathParams;

    private Map<String, List<String>> parsedQuery;
    private String path;
    private String errorMessage;

    /**
     * Wraps a servlet request, with the path variables the router resolved.
     *
     * <p>Public because the caller is not always {@link AppServlet}: anything
     * that has a servlet request and knows what the path variables should be
     * can build the argument a handler takes, which is what
     * {@code TestRequest} in {@code spider-silk-test} does to call a handler
     * with no server underneath it.
     *
     * @param req        the request to read; every accessor delegates to it
     * @param pathParams the resolved path variables, empty when there are none
     */
    public WebRequest(HttpServletRequest req, Map<String, String> pathParams) {
        this.req = req;
        this.pathParams = pathParams;
    }

    // ---- Request info ----

    public String method() {
        return req.getMethod();
    }

    /**
     * The path the request was made to, with no query string on the end.
     *
     * <p>Worked out once and kept, the way the parsed query string is: routing,
     * CORS, the error path, and the request logger all ask, and the answer
     * cannot change while the request is being served.
     */
    public String path() {
        if (path == null) {
            String servletPath = req.getServletPath();
            String pathInfo = req.getPathInfo();
            String whole = pathInfo == null ? servletPath : servletPath + pathInfo;
            path = whole.isEmpty() ? "/" : whole;
        }
        return path;
    }

    public String header(String name) {
        return req.getHeader(name);
    }

    /**
     * Every value of a repeated header, in the order the request sent them —
     * {@code Accept-Encoding} listed twice, or a {@code Forwarded} chain. Empty
     * when the header is absent, and {@link #header(String)} returns the first of
     * these.
     */
    public List<String> headers(String name) {
        return List.copyOf(Collections.list(req.getHeaders(name)));
    }

    /**
     * Every header the request carried, by name, each with all of its values —
     * what a request logger or a signature over the headers reads. The names come
     * back as the request spelled them, and a lookup through them is therefore
     * case-sensitive where {@link #header(String)} is not.
     */
    public Map<String, List<String>> headers() {
        Map<String, List<String>> byName = new LinkedHashMap<>();
        for (String name : Collections.list(req.getHeaderNames())) {
            byName.putIfAbsent(name, headers(name));
        }
        // Read-only, and in the order the request sent them, which Map.copyOf
        // would lose.
        return Collections.unmodifiableMap(byName);
    }

    /** The declared media type of the body, or null when the request sent none. */
    public String contentType() {
        return req.getContentType();
    }

    /**
     * The query string as it arrived, undecoded and without the {@code ?}, or
     * null when the URL carried none. {@link #queryParam(String)} reads a single
     * value out of it; this is the whole of it, for a signature or a log line.
     */
    public String queryString() {
        return req.getQueryString();
    }

    /**
     * Whether the request arrived over TLS. What HSTS and a {@code Secure} cookie
     * ask about, and behind a TLS-terminating proxy the answer is only true once
     * the container has been told to trust {@code X-Forwarded-Proto}.
     */
    public boolean isSecure() {
        return req.isSecure();
    }

    /** The address the request came from, as text. The proxy's, when there is one in front. */
    public String remoteAddress() {
        return req.getRemoteAddr();
    }

    /** {@code "http"} or {@code "https"}, the other half of {@link #isSecure()}. */
    public String scheme() {
        return req.getScheme();
    }

    /**
     * The host the request was addressed to, with the port when it is not the
     * default for the scheme, so that {@code scheme() + "://" + host() + path()}
     * is the absolute URL of this request.
     *
     * <p>The port is the one part of this that is not a bare delegate, and it is
     * there because leaving it out is wrong exactly where an absolute URL is
     * built by hand — a development server on 8080, a second instance on 8081.
     * The container answers both halves, so a proxy's {@code X-Forwarded-Host} is
     * applied here on the same terms as {@link #scheme()}.
     */
    public String host() {
        String name = req.getServerName();
        int port = req.getServerPort();
        return port == defaultPort(req.getScheme()) ? name : name + ":" + port;
    }

    private static int defaultPort(String scheme) {
        return "https".equals(scheme) ? 443 : 80;
    }

    /**
     * The media type to answer with, out of the ones this handler can produce.
     * The question a handler actually asks — "HTML or JSON here?" — answered
     * against the {@code Accept} header, quality values and specificity applied:
     *
     * <pre>{@code
     * app.get("/decks", req -> switch (req.accepts("text/html", "application/json")) {
     *     case "application/json" -> WebResponse.json(service.decks(), Codecs::writeDecks);
     *     default -> WebResponse.template("decks", Map.of("decks", service.decks()));
     * });
     * }</pre>
     *
     * <p>The answer is one of the strings passed in, never null: a caller that
     * will take none of them is a 406, the same contract as {@link #param(String)}
     * answering 400 rather than null. A caller that sent no {@code Accept} at all
     * gets the first candidate, so the list is written in the order the handler
     * prefers.
     *
     * <p>Asking makes the answer depend on the request header, so the response
     * carries {@code Vary: Accept} without the handler saying so — the same
     * bookkeeping {@link App#gzip()} does for {@code Accept-Encoding}, and for
     * the same reason: a shared cache must not hand JSON to the next browser.
     */
    public String accepts(String... candidates) {
        if (candidates.length == 0) {
            throw new IllegalArgumentException("accepts() needs at least one media type to offer");
        }
        negotiated();
        String best = AcceptHeader.best(header("Accept"), List.of(candidates));
        if (best == null) {
            throw new HttpException(HttpStatus.NOT_ACCEPTABLE,
                    "Not Acceptable: this endpoint answers " + String.join(", ", candidates));
        }
        return best;
    }

    /**
     * The media types the caller asked for, in the order it prefers them, with
     * the ones it refused dropped — the parsed view behind {@link #accepts},
     * for a handler that has to decide something {@code accepts} cannot phrase.
     * Empty when the request sent no {@code Accept}, which is a caller that will
     * take anything rather than one that will take nothing.
     */
    public List<String> acceptedTypes() {
        negotiated();
        return AcceptHeader.preferences(header("Accept"));
    }

    /** Records that this answer depends on {@code Accept}, for {@link AppServlet} to declare. */
    private void negotiated() {
        req.setAttribute(NEGOTIATED_ATTRIBUTE, true);
    }

    /**
     * The servlet request underneath, for the things this class does not wrap:
     * an async context, a client certificate, a container-specific attribute.
     *
     * <p>An escape hatch and not a shortcut. What is read through it is read
     * behind the framework's back — {@link #accepts} records that the answer
     * varies by {@code Accept} and reading the header here does not, a body
     * consumed here is a body {@link #body()} can no longer read — and a
     * handler that uses it is a handler tied to the servlet API rather than to
     * this one. {@link WebResponse#raw(ServletWriter)} is the same hatch on the
     * way out.
     */
    public HttpServletRequest raw() {
        return req;
    }

    // ---- Path variables ----

    /**
     * A path variable the matched route declared.
     *
     * <p>A {@code {name*}} tail comes back as the rest of the path with its
     * slashes intact, and as {@code ""} when it matched nothing at all: a route
     * on {@code "/files/{path*}"} reads {@code "a/b.txt"} out of
     * {@code /files/a/b.txt} and {@code ""} out of {@code /files}.
     *
     * @throws IllegalStateException if the route's pattern has no variable of
     *         that name. That is a mismatch between the pattern and the handler
     *         reading it, not bad input, so it is not the
     *         {@code IllegalArgumentException} an application maps to a status.
     */
    public String pathParam(String name) {
        String value = pathParams.get(name);
        if (value == null) {
            throw new IllegalStateException(
                    "Path pattern has no such variable: {" + name + "}");
        }
        return value;
    }

    public long pathParamLong(String name) {
        String value = pathParam(name);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "Path variable {%s} is not a number: %s".formatted(name, value));
        }
    }

    public <E extends Enum<E>> E pathParamEnum(String name, Class<E> type) {
        return pathParam(name, value -> Enum.valueOf(type, value));
    }

    /**
     * A path variable read through a parser of your own, for the types that have
     * no named form.
     *
     * <pre>{@code
     * app.get("/decks/{deckId}", req -> deckPage(req.pathParam("deckId", UUID::fromString)));
     * }</pre>
     *
     * <p>A parser that rejects the text answers 400 naming the variable, the same
     * contract as {@link #pathParamLong}. Rejecting means throwing
     * {@link IllegalArgumentException} or {@link DateTimeException}, which is
     * what {@code UUID::fromString}, {@code Integer::parseInt}, and
     * {@code LocalDate::parse} already do. Any other exception is a fault in the
     * parser rather than in the request, and stays a 500.
     */
    public <T> T pathParam(String name, Function<String, T> parser) {
        String value = pathParam(name);
        try {
            return parser.apply(value);
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "Invalid value for path variable {%s}: %s".formatted(name, value));
        }
    }

    // ---- Query string and form parameters ----

    /** A required parameter. Responds with 400 if missing. */
    public String param(String name) {
        String value = req.getParameter(name);
        if (value == null) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Missing required parameter: " + name);
        }
        return value;
    }

    public String param(String name, String defaultValue) {
        String value = req.getParameter(name);
        return value != null ? value : defaultValue;
    }

    public long paramLong(String name) {
        return parseLong(name, param(name));
    }

    /**
     * An optional numeric parameter — {@code ?page=} pagination, typically.
     * The default answers for an absent parameter; a value that is present but
     * not a number is still a 400, the same contract as {@link #paramLong(String)}.
     */
    public long paramLong(String name, long defaultValue) {
        String value = req.getParameter(name);
        return value == null ? defaultValue : parseLong(name, value);
    }

    /**
     * A required boolean parameter: {@code true} or {@code false}, in any
     * case. Anything else is a 400, the same contract as
     * {@link #paramLong(String)}. A checkbox therefore wants
     * {@code value="true"}, or {@link #params(String)} where its presence is the
     * answer.
     */
    public boolean paramBoolean(String name) {
        return parseBoolean(name, param(name));
    }

    /**
     * An optional boolean parameter: the default when absent, still a 400 when
     * the value is neither {@code true} nor {@code false}.
     */
    public boolean paramBoolean(String name, boolean defaultValue) {
        String value = req.getParameter(name);
        return value == null ? defaultValue : parseBoolean(name, value);
    }

    public <E extends Enum<E>> E paramEnum(String name, Class<E> type) {
        return param(name, value -> Enum.valueOf(type, value));
    }

    /**
     * An optional enum parameter: the default when absent, still a 400 when the
     * value names no constant.
     */
    public <E extends Enum<E>> E paramEnum(String name, Class<E> type, E defaultValue) {
        return param(name, value -> Enum.valueOf(type, value), defaultValue);
    }

    /**
     * A required parameter read through a parser of your own, for the types that
     * have no named form.
     *
     * <pre>{@code
     * LocalDate since = req.param("since", LocalDate::parse);
     * UUID owner = req.param("owner", UUID::fromString);
     * }</pre>
     *
     * <p>A missing parameter answers 400, as {@link #param(String)} does, and so
     * does a parser that rejects the text. Rejecting means throwing
     * {@link IllegalArgumentException} or {@link DateTimeException}, which is
     * what {@code UUID::fromString}, {@code Integer::parseInt}, and
     * {@code LocalDate::parse} already do. Any other exception is a fault in the
     * parser rather than in the request, and stays a 500.
     *
     * <p>The named forms stay the short spelling for the types that have one:
     * {@link #paramLong(String)}, {@link #paramBoolean(String)}, and
     * {@link #paramEnum(String, Class)}.
     */
    public <T> T param(String name, Function<String, T> parser) {
        return parse(name, param(name), parser);
    }

    /**
     * An optional parameter read through a parser of your own: the default when
     * absent, still a 400 when the parser rejects what was sent.
     *
     * <pre>{@code
     * int page = req.param("page", Integer::parseInt, 1);
     * }</pre>
     */
    public <T> T param(String name, Function<String, T> parser, T defaultValue) {
        String value = req.getParameter(name);
        return value == null ? defaultValue : parse(name, value, parser);
    }

    private static long parseLong(String name, String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "Parameter %s is not a number: %s".formatted(name, value));
        }
    }

    private static boolean parseBoolean(String name, String value) {
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw new HttpException(HttpStatus.BAD_REQUEST,
                "Parameter %s is not a boolean: %s".formatted(name, value));
    }

    private static <T> T parse(String name, String value, Function<String, T> parser) {
        try {
            return parser.apply(value);
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "Invalid value for parameter %s: %s".formatted(name, value));
        }
    }

    /**
     * Every value of a repeated parameter, in the order the request sent them —
     * the shape a group of checkboxes or a multi-select arrives in. Empty when
     * the parameter is absent, since "none checked" is an answer, not an error.
     * {@link #param(String)} returns the first of these.
     */
    public List<String> params(String name) {
        String[] values = req.getParameterValues(name);
        return values == null ? List.of() : List.of(values);
    }

    /**
     * A parameter from the query string only, or null. The servlet API merges
     * the query string with a form body, so an {@code id} in the URL and an
     * {@code id} in the form both answer to {@link #param(String)}; this is the
     * way to say which one you meant.
     */
    public String queryParam(String name) {
        List<String> values = queryParams(name);
        return values.isEmpty() ? null : values.get(0);
    }

    /** Every value of a repeated query-string parameter. */
    public List<String> queryParams(String name) {
        return parsedQuery().getOrDefault(name, List.of());
    }

    /**
     * A parameter from the form body only, or null — the counterpart to
     * {@link #queryParam(String)}. Only present for a form-encoded body the
     * container parsed; a JSON body is read with {@link #body()}.
     */
    public String formParam(String name) {
        List<String> values = formParams(name);
        return values.isEmpty() ? null : values.get(0);
    }

    /** Every value of a repeated form field. */
    public List<String> formParams(String name) {
        // The query string is read first so that a malformed one answers 400
        // here too, rather than reaching the container's own parser as a 500.
        List<String> fromQuery = queryParams(name);
        List<String> merged = params(name);
        if (fromQuery.isEmpty()) {
            return merged;
        }
        // The container hands back query and form values in one list. Take out
        // one entry per query value: what is left came from the body.
        List<String> remaining = new ArrayList<>(merged);
        for (String value : fromQuery) {
            remaining.remove(value);
        }
        return List.copyOf(remaining);
    }

    private Map<String, List<String>> parsedQuery() {
        if (parsedQuery == null) {
            parsedQuery = parseQueryString(req.getQueryString());
        }
        return parsedQuery;
    }

    private static Map<String, List<String>> parseQueryString(String query) {
        if (query == null || query.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> parsed = new LinkedHashMap<>();
        try {
            for (String pair : query.split("&", -1)) {
                if (pair.isEmpty()) {
                    continue;
                }
                int equals = pair.indexOf('=');
                String name = decode(equals < 0 ? pair : pair.substring(0, equals));
                String value = equals < 0 ? "" : decode(pair.substring(equals + 1));
                parsed.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
            }
        } catch (IllegalArgumentException e) {
            // A stray %, or one not followed by two hex digits. That is the URL
            // the caller sent, so it is a 400 like every other bad input here,
            // and it is answered for the query string as a whole because a
            // percent-escape that will not decode makes none of it trustworthy.
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "Query string is not valid URL encoding: " + query);
        }
        // The lists are handed out by queryParams(name) and cached for the rest
        // of the request, so they are frozen before either can happen.
        parsed.replaceAll((name, values) -> List.copyOf(values));
        return Collections.unmodifiableMap(parsed);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    // ---- Body ----

    /**
     * The body as text, exactly as it arrived: line endings are not rewritten
     * and a trailing newline is kept, so a signature over the raw body still
     * verifies.
     */
    public String body() {
        try {
            StringWriter text = new StringWriter();
            req.getReader().transferTo(text);
            return text.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Parses the body as JSON. Responds with 400 on invalid syntax. */
    public Json.JsonValue bodyJson() {
        try {
            return Json.parse(body());
        } catch (IllegalArgumentException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Request body is not valid JSON: " + e.getMessage());
        }
    }

    /**
     * Parses the body as JSON and hands it to a hand-written reader. Responds
     * with 400 both on invalid syntax and on a body the reader rejects — a
     * missing key or a value of the wrong type — so a handler receives a whole
     * value or nothing at all, the same contract as {@link #pathParamLong}.
     */
    public <T> T bodyJson(JsonReader<T> reader) {
        Json.JsonValue json = bodyJson();
        try {
            return reader.read(json);
        } catch (IllegalArgumentException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Request body was rejected: " + e.getMessage());
        }
    }

    /**
     * The body as bytes, unread — what a streaming parser from another library
     * wants, so a large upload is never held as one String first.
     *
     * <pre>{@code
     * try (JsonParser parser = jackson.createParser(req.bodyStream())) {
     *     ...
     * }
     * }</pre>
     *
     * <p>The servlet API allows one or the other, not both: a request that has
     * already gone through {@link #body()}, {@link #bodyJson()},
     * {@link #bodyNdjson}, or {@link #param} — all of which read characters —
     * throws {@link IllegalStateException} here, and the reverse holds too.
     * Whichever a handler picks, it picks once.
     */
    public InputStream bodyStream() {
        try {
            return req.getInputStream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The body as characters, unread, decoded with the charset the request
     * declared. The counterpart of {@link #bodyStream()} for a library that
     * reads text, and subject to the same one-or-the-other rule.
     */
    public BufferedReader bodyReader() {
        try {
            return req.getReader();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Newline-delimited JSON: one value per line, read lazily, so a body of a
     * million records is never in memory at once.
     *
     * <pre>{@code
     * app.post("/api/decks/{deckId}/cards", req -> {
     *     long deckId = req.pathParamLong("deckId");
     *     int imported = cardService.addAll(deckId, req.bodyNdjson(Codecs.NEW_CARD).toList());
     *     return WebResponse.json(Json.obj().put("imported", imported));
     * });
     * }</pre>
     *
     * <p>Blank lines are skipped, and a line that is not valid JSON or that the
     * reader rejects answers 400 naming the line — which is the reason to read
     * NDJSON rather than one big array when the body is large: the report says
     * where the body went wrong, not just that it did.
     *
     * <p>The stream is lazy, so those failures happen where it is consumed. Do
     * that before returning the response: inside a
     * {@link WebResponse#stream(String, StreamWriter)} writer the headers are
     * already committed and a 400 can no longer be sent.
     */
    public <T> Stream<T> bodyNdjson(JsonReader<T> reader) {
        AtomicLong line = new AtomicLong();
        return bodyReader().lines().<T>mapMulti((text, values) -> {
            long number = line.incrementAndGet();
            if (!text.isBlank()) {
                values.accept(readLine(text, number, reader));
            }
        });
    }

    private static <T> T readLine(String text, long number, JsonReader<T> reader) {
        try {
            return reader.read(Json.parse(text));
        } catch (IllegalArgumentException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "Line %d of the NDJSON body was rejected: %s".formatted(number, e.getMessage()));
        }
    }

    /**
     * A multipart upload. Responds with 400 if missing.
     *
     * <p>Missing is any of the three ways it can be: no part of that name, a
     * part carrying no file name, and a request that is not multipart at all.
     * {@link #fileOrNull(String)} answers null for the same three, for an upload
     * a handler does not require.
     */
    public UploadedFile file(String name) {
        Part part;
        try {
            part = req.getPart(name);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ServletException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Not a multipart request");
        }
        if (!isFile(part)) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Missing uploaded file: " + name);
        }
        return new UploadedFile(part);
    }

    /**
     * An upload the form need not carry, or null — the shape {@link #cookie} and
     * {@link #queryParam} already have for a value whose absence is an answer
     * rather than an error.
     *
     * <pre>{@code
     * UploadedFile avatar = req.fileOrNull("avatar");
     * if (avatar != null) {
     *     avatar.writeTo(avatars.resolve(userId + ".png"));
     * }
     * }</pre>
     *
     * <p>Null covers every way the file can be absent: no part of that name, a
     * file input the browser sent empty because nothing was chosen, and a
     * request that is not multipart at all. A handler that declared the upload
     * optional has already said what to do about all three, which is why none
     * of them is the 400 {@link #file(String)} answers.
     */
    public UploadedFile fileOrNull(String name) {
        Part part;
        try {
            part = req.getPart(name);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ServletException e) {
            return null;
        }
        return isFile(part) ? new UploadedFile(part) : null;
    }

    /**
     * Every file sent under one field name, in the order the request sent them —
     * the shape a {@code <input type="file" multiple>} arrives in. Empty when
     * the field carried none, since "nothing chosen" is an answer, not an error,
     * and {@link #params(String)} answers a repeated parameter the same way.
     *
     * <pre>{@code
     * for (UploadedFile page : req.files("pages")) {
     *     page.writeTo(scans.resolve(page.fileName()));
     * }
     * }</pre>
     *
     * <p>Only the parts that carry a file are counted. The text fields of the
     * same form are parts too, and they are read with {@link #param(String)}.
     */
    public List<UploadedFile> files(String name) {
        Collection<Part> parts;
        try {
            parts = req.getParts();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ServletException e) {
            return List.of();
        }
        List<UploadedFile> files = new ArrayList<>();
        for (Part part : parts) {
            if (part.getName().equals(name) && isFile(part)) {
                files.add(new UploadedFile(part));
            }
        }
        return List.copyOf(files);
    }

    /**
     * Whether a part is an uploaded file at all. A part with no submitted file
     * name is a text field of the same form, or a file input the browser sent
     * empty because nothing was chosen; neither is a file a handler can read.
     */
    private static boolean isFile(Part part) {
        return part != null && part.getSubmittedFileName() != null
                && !part.getSubmittedFileName().isEmpty();
    }

    // ---- Cookies the client sent ----

    /** A cookie the client sent, or null. Setting one is {@link WebResponse#cookie}. */
    public String cookie(String name) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookie.getName().equals(name)) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /** Every cookie the client sent. A repeated name keeps the first value. */
    public Map<String, String> cookies() {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) {
            return Map.of();
        }
        Map<String, String> byName = new LinkedHashMap<>();
        for (Cookie cookie : cookies) {
            byName.putIfAbsent(cookie.getName(), cookie.getValue());
        }
        // Read-only, and in the order the request sent them, which Map.copyOf
        // would lose.
        return Collections.unmodifiableMap(byName);
    }

    // ---- Session ----

    /**
     * A session attribute, or null when there is no session or no such
     * attribute. The type parameter is the caller's cast, so that
     * {@code User user = req.sessionAttr("user")} reads as one line; a value of
     * another type fails at that assignment, as it would with the cast written out.
     *
     * <p>{@link #sessionAttr(String, Class)} names the type instead, and fails at
     * the read rather than at the assignment.
     */
    @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
    public <T> T sessionAttr(String key) {
        HttpSession session = req.getSession(false);
        return session == null ? null : (T) session.getAttribute(key);
    }

    /**
     * A session attribute of a named type, or null when there is no session or no
     * such attribute.
     *
     * <pre>{@code
     * User user = req.sessionAttr("user", User.class);
     * }</pre>
     *
     * <p>The cast is {@link Class#cast}, so a value of another type fails on this
     * line, naming the key and both types, rather than on the assignment several
     * lines away that {@link #sessionAttr(String)} fails on.
     * {@code paramEnum(name, type)} takes a class for the same reason, and neither
     * is reflection in the sense this framework avoids: the type is written at the
     * call site.
     *
     * <p>A value of the wrong type is a mismatch between the line that wrote the
     * session and the line that reads it, both of them the application's own, so
     * it is an {@link IllegalStateException} and a 500 — the same answer
     * {@link #pathParam(String)} gives an undeclared variable, and not the 400
     * that a caller's bad input earns.
     *
     * <p>Storing a {@code Class} is the one case this overload gets in the way of:
     * {@code sessionAttr(key, User.class)} reads, so writing that value says
     * {@code sessionAttr(key, (Object) User.class)}.
     */
    public <T> T sessionAttr(String key, Class<T> type) {
        HttpSession session = req.getSession(false);
        Object value = session == null ? null : session.getAttribute(key);
        if (value != null && !type.isInstance(value)) {
            throw new IllegalStateException("Session attribute %s is a %s, not a %s"
                    .formatted(key, value.getClass().getName(), type.getName()));
        }
        return type.cast(value);
    }

    public void sessionAttr(String key, Object value) {
        req.getSession(true).setAttribute(key, value);
    }

    public void removeSessionAttr(String key) {
        HttpSession session = req.getSession(false);
        if (session != null) {
            session.removeAttribute(key);
        }
    }

    /**
     * Ends the session, so that everything in it is gone and the next request
     * starts a new one. What logging out is.
     *
     * <p>Does nothing when there is no session, since a visitor who was never
     * signed in has nothing to end. The request keeps working afterwards, but
     * reading a session attribute through it answers null and writing one starts
     * a session again, so a handler invalidates and then returns.
     */
    public void invalidateSession() {
        HttpSession session = req.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    // ---- Flash (visible exactly once, on the request after a redirect) ----

    public void flash(String key, String value) {
        HttpSession session = req.getSession(true);
        @SuppressWarnings("unchecked")
        Map<String, String> flash = (Map<String, String>) session.getAttribute(FLASH_ATTRIBUTE);
        if (flash == null) {
            flash = new HashMap<>();
            session.setAttribute(FLASH_ATTRIBUTE, flash);
        }
        flash.put(key, value);
    }

    /** A flash value left by the previous request, or null. */
    public String flashed(String key) {
        Object attribute = req.getAttribute(FLASH_ATTRIBUTE);
        if (attribute instanceof Map<?, ?> flash) {
            Object value = flash.get(key);
            return value == null ? null : value.toString();
        }
        return null;
    }

    // ---- Errors ----

    /**
     * Inside an {@link App#error(HttpStatus, Handler)} handler, the plain-text message
     * the framework would have answered with. Null when the status came from a
     * handler rather than from the router or an {@link HttpException}.
     */
    public String errorMessage() {
        return errorMessage;
    }

    void errorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    /** The same request, with the path variables a matched route resolved. */
    WebRequest withPathParams(Map<String, String> resolved) {
        WebRequest copy = new WebRequest(req, resolved);
        // The same servlet request, so the same path: the copy is made after
        // routing has already asked for it.
        copy.path = path;
        copy.errorMessage = errorMessage;
        return copy;
    }
}
