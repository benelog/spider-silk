package net.benelog.spidersilk;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.Part;

import org.jspecify.annotations.Nullable;

import net.benelog.spidersilk.json.Json;
import net.benelog.spidersilk.json.JsonReader;
import net.benelog.spidersilk.json.JsonValue;

/**
 * The request side of a handler: what was asked for, and the session it was
 * asked in. The answer is the {@link WebResponse} the handler returns.
 *
 * <p>Reading is the whole of it, with one deliberate exception: the session.
 * {@link #session()} and {@link #flash(String, String)} write,
 * because a session outlives the response and cannot be a value returned from
 * one. Cookies are the other half of that split — the ones the client sent are
 * read here, the ones the server sets belong to {@link WebResponse}.
 *
 * <p>A name says what an absent value does. A value the handler cannot do
 * without is read by its plain name — {@link #param(String)},
 * {@link #pathParam(String)}, {@link #file(String)} — and its absence is a 400.
 * A value that may be missing is read by the same name with {@code OrNull} —
 * {@link #paramOrNull(String)}, {@link #fileOrNull(String)} — or with a default
 * as the last argument. {@link #header(String)}, {@link #cookie(String)}, and
 * {@link WebSession#get(String)} are the exception, and answer null under the
 * plain name: a request that sent no such header, carried no such cookie, or
 * has no such attribute is the usual case for each of them, not a bad request.
 * {@code JsonObject} follows the same rule.
 *
 * <p>Type conversion happens only through explicit methods. The request-wide
 * {@link #param(String)} and the path variables have named forms for the
 * common types — {@link #paramLong}, {@link #paramBoolean}, {@link #paramEnum},
 * {@link #pathParamLong}, {@link #pathParamEnum}. Every source, the query string
 * and the form body included, reads any other type through a parser such as
 * {@code Integer::parseInt}: {@link #queryParam(String, java.util.function.Function)}
 * is the form those two have. No reflection.
 */
public final class WebRequest {

    static final String FLASH_ATTRIBUTE = "net.benelog.spidersilk.flash";
    static final String NEGOTIATED_ATTRIBUTE = "net.benelog.spidersilk.negotiated";
    /** Set by {@link AppServlet} when the servlet has no multipart configuration. */
    static final String NO_MULTIPART_ATTRIBUTE = "net.benelog.spidersilk.noMultipart";

    /**
     * How the body has been read so far: the text {@link #body()} read, or a
     * {@link BodyMarker} once it went out unread or was refused. A request
     * attribute rather than a field, because a before-filter, the handler, and
     * the request logger each hold a {@code WebRequest} of their own over the
     * one servlet request.
     */
    static final String BODY_ATTRIBUTE = "net.benelog.spidersilk.body";

    /** What {@link #BODY_ATTRIBUTE} holds when it holds no text. */
    private enum BodyMarker {
        /** The body went out through {@link #bodyStream()}. */
        STREAM("bodyStream()"),
        /** The body went out through {@link #bodyReader()}. */
        READER("bodyReader()"),
        /**
         * The body went out through {@link #bodyNdjson}, whose reader holds
         * a chunk of it that no one else can see.
         */
        NDJSON("bodyNdjson()"),
        /**
         * A read was refused for size. Kept so that a handler catching the 413
         * cannot go on to read the rest of a body whose start was consumed.
         */
        TOO_LARGE(""),
        /**
         * The body ended before it should have, or the connection failed while
         * it was read. Kept for the same reason as {@link #TOO_LARGE}.
         */
        UNREADABLE("");

        /** The method that took the body, for a marker that records a hand-over. */
        private final String method;

        BodyMarker(String method) {
            this.method = method;
        }
    }

    /** How much {@link #body()} reads at a time, and its buffer's floor. */
    private static final int BODY_CHUNK = 8192;

    /** The largest buffer a Content-Length header may reserve up front: 1MB. */
    private static final int MAX_BODY_CAPACITY = 1024 * 1024;

    /** The media type {@code getPart} and {@code getParts} parse. */
    private static final String MULTIPART_FORM_DATA = "multipart/form-data";
    private static final String FORM_URLENCODED = "application/x-www-form-urlencoded";
    private static final Set<String> FORM_METHODS = Set.of("POST", "PUT", "PATCH");

    /** The limits a request built outside {@link AppServlet} reads under; never handed out. */
    private static final BodyLimits DEFAULT_LIMITS = BodyLimits.defaults();

    private final HttpServletRequest req;
    private final Map<String, String> pathParams;
    private final BodyLimits limits;
    private final @Nullable Route route;

    private @Nullable Map<String, List<String>> parsedQuery;
    private @Nullable String path;
    private @Nullable String errorMessage;
    private @Nullable Throwable thrown;

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
        this(req, pathParams, DEFAULT_LIMITS, null);
    }

    /** The request {@link AppServlet} builds, under the limits the application set. */
    WebRequest(HttpServletRequest req, Map<String, String> pathParams, BodyLimits limits) {
        this(req, pathParams, limits, null);
    }

    private WebRequest(HttpServletRequest req, Map<String, String> pathParams, BodyLimits limits,
            @Nullable Route route) {
        this.req = req;
        this.pathParams = pathParams;
        this.limits = limits;
        this.route = route;
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

    public @Nullable String header(String name) {
        return req.getHeader(name);
    }

    /**
     * Every value of a repeated header, in the order the request sent them —
     * {@code Accept-Encoding} listed twice, or a {@code Forwarded} chain. Empty
     * when the header is absent, and {@link #header(String)} returns the first of
     * these.
     */
    public List<String> headers(String name) {
        // Collections.list already drains the enumeration into a list of its
        // own, which nothing else can reach, so wrapping it is enough:
        // List.copyOf on top of that copied the same values a second time.
        return Collections.unmodifiableList(Collections.list(req.getHeaders(name)));
    }

    /**
     * A list field, such as {@code Accept}, as one value: its lines joined
     * with commas, which is how RFC 9110 reads a list field sent on several
     * lines. {@link #header(String)} answers the first line alone, and a
     * negotiation that read it would miss what the others offer. Null when the
     * request did not send the field.
     */
    @Nullable String listHeader(String name) {
        return listHeader(req, name);
    }

    static @Nullable String listHeader(HttpServletRequest req, String name) {
        Enumeration<String> lines = req.getHeaders(name);
        if (lines == null || !lines.hasMoreElements()) {
            return null;
        }
        String first = lines.nextElement();
        if (!lines.hasMoreElements()) {
            return first;
        }
        StringJoiner joined = new StringJoiner(", ").add(first);
        while (lines.hasMoreElements()) {
            joined.add(lines.nextElement());
        }
        return joined.toString();
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
            // computeIfAbsent, so a name the container enumerates twice reads
            // its values once: putIfAbsent had to have them before it could
            // find out it was going to discard them.
            byName.computeIfAbsent(name, this::headers);
        }
        // Read-only, and in the order the request sent them, which Map.copyOf
        // would lose.
        return Collections.unmodifiableMap(byName);
    }

    /**
     * The declared media type of the body, or null when the request sent none.
     * This is the header as sent: Jetty's {@code getContentType()} resolves the
     * charset parameter on the way, and throws for one this JVM does not know.
     */
    public @Nullable String contentType() {
        return req.getHeader("Content-Type");
    }

    /**
     * The query string as it arrived, undecoded and without the {@code ?}, or
     * null when the URL carried none. {@link #queryParam(String)} reads a single
     * value out of it; this is the whole of it, for a signature or a log line.
     */
    public @Nullable String queryString() {
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
     *
     * <p>An IPv6 literal keeps its brackets, {@code [::1]:8080}: Undertow
     * answers the server name without them, which a port would run into.
     */
    public String host() {
        String serverName = req.getServerName();
        String name = serverName.indexOf(':') >= 0 && !serverName.startsWith("[")
                ? "[" + serverName + "]"
                : serverName;
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
        String best = AcceptHeader.best(listHeader("Accept"), List.of(candidates));
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
        return AcceptHeader.preferences(listHeader("Accept"));
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
     * varies by {@code Accept} and reading the header here does not, and a body
     * consumed here is one {@link #body()} neither keeps nor knows is gone — and a
     * handler that uses it is a handler tied to the servlet API rather than to
     * this one. {@link WebResponse#raw(ServletWriter)} is the same hatch on the
     * way out.
     */
    public HttpServletRequest raw() {
        return req;
    }

    // ---- The matched route ----

    /**
     * The route that answered this request, as {@link App#routes()} reports it:
     * the method, the path pattern with group prefixes resolved, and the
     * description. {@code "/api/decks/{deckId}"} is the pattern, where
     * {@link #path()} is the {@code "/api/decks/7"} the caller asked for, so
     * this is the name a request log, a metric, or a tracing span groups by.
     *
     * <p>Null before routing and where no route matched: in a
     * {@link App#beforeRequest} filter, and for a static file, a 404, a 405, and
     * the automatic OPTIONS answer. It is set from {@link App#beforeRoute} on,
     * through the handler, the after-filters, the exception and error handlers,
     * the response filters, and the request logger. A HEAD answered by a GET
     * route reports that GET route.
     */
    public @Nullable Route route() {
        return route;
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
     *         that name, or if no route has matched yet — in a
     *         {@link App#beforeRequest} filter, or for a 404. That is a mismatch
     *         between the code and where it runs, not bad input, so it is not the
     *         {@code IllegalArgumentException} an application maps to a status.
     */
    public String pathParam(String name) {
        String value = pathParams.get(name);
        if (value == null) {
            if (route == null && pathParams.isEmpty()) {
                throw new IllegalStateException(("No route has matched this request, so there is no path"
                        + " variable {%s}. A beforeRequest filter runs before routing; beforeRoute"
                        + " runs after it and reads path variables.").formatted(name));
            }
            String pattern = route == null ? "Path pattern" : "Path pattern " + route.path();
            throw new IllegalStateException(pattern + " has no such variable: {" + name + "}");
        }
        return value;
    }

    public long pathParamLong(String name) {
        String value = pathParam(name);
        try {
            return asciiLong(value);
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

    /**
     * A required parameter. Responds with 400 if missing, and with 400 when the
     * query string will not decode, the same answer {@link #queryParam(String)}
     * gives.
     */
    public String param(String name) {
        String value = parameter(name);
        if (value == null) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Missing required parameter: " + name);
        }
        return value;
    }

    /**
     * An optional parameter: the value sent, or the default when it is absent.
     * A default of null is {@link #paramOrNull(String)}, which says so by name —
     * a literal {@code null} here matches the parser overload as well, and does
     * not compile.
     */
    public String param(String name, String defaultValue) {
        String value = parameter(name);
        return value != null ? value : defaultValue;
    }

    /**
     * An optional parameter, or null when the request did not send it — the
     * shape {@link #queryParamOrNull(String)}, {@link #cookie(String)}, and
     * {@link #fileOrNull(String)} already have for a value whose absence is an
     * answer rather than an error.
     *
     * <pre>{@code
     * String search = req.paramOrNull("q");
     * List<Deck> decks = search == null ? service.decks() : service.search(search);
     * }</pre>
     *
     * <p>It reads the same merged view as {@link #param(String)}, and a query
     * string that will not decode still answers 400.
     */
    public @Nullable String paramOrNull(String name) {
        return parameter(name);
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
        String value = parameter(name);
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
        String value = parameter(name);
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
        String value = parameter(name);
        return value == null ? defaultValue : parse(name, value, parser);
    }

    /**
     * The container's merged view of one parameter, once the query string has
     * been through {@link #parsedQuery()}. A query string that will not decode
     * therefore answers 400 here, as it does for {@link #queryParam(String)},
     * rather than reaching the container's parser, which fails on it in a way
     * of its own and differently on each server. Only the query string is read
     * first: the body is still read by the container, when and as it was, and
     * {@link #formFields} turns what it throws into a 4xx.
     *
     * <p>A request that carries no form answers from the query string alone.
     * The container would read the same values, but Tomcat parses the query
     * string again for it and refuses one this parser takes, such as an empty
     * name in {@code ?=a}, as a form body that could not be read.
     */
    private @Nullable String parameter(String name) {
        List<String> fromQuery = queryParams(name);
        if (!carriesForm()) {
            return fromQuery.isEmpty() ? null : fromQuery.get(0);
        }
        return formFields(() -> req.getParameter(name));
    }

    private static long parseLong(String name, String value) {
        try {
            return asciiLong(value);
        } catch (NumberFormatException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    "Parameter %s is not a number: %s".formatted(name, value));
        }
    }

    /**
     * An optional sign and ASCII digits as a {@code long}. {@link Long#parseLong}
     * takes every digit {@link Character#digit} knows, so {@code ?n=１２} in
     * full-width digits read as 12 and {@code /decks/١} in Arabic-Indic as deck
     * 1: two spellings of one resource that nothing else in the URL agrees on.
     */
    private static long asciiLong(String value) {
        int start = !value.isEmpty() && (value.charAt(0) == '-' || value.charAt(0) == '+') ? 1 : 0;
        if (start == value.length()) {
            throw new NumberFormatException(value);
        }
        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                throw new NumberFormatException(value);
            }
        }
        return Long.parseLong(value);
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
        // The query string is checked first, and alone without a form, for the
        // reasons parameter(name) gives.
        List<String> fromQuery = queryParams(name);
        if (!carriesForm()) {
            return fromQuery;
        }
        String[] values = formFields(() -> req.getParameterValues(name));
        return values == null ? List.of() : List.of(values);
    }

    /**
     * A read of the container's parameters, with a body it cannot parse
     * answered as the client's fault rather than as a 500.
     *
     * <p>A multipart form goes through {@code getParts} first, so its failures
     * answer as they do for {@link #file(String)}: 413 for a size limit, 400
     * for a body that will not parse. Tomcat and Undertow report both through
     * {@code getParameter} as the same {@link IllegalStateException}, and only
     * {@code getParts} tells them apart. A servlet with no multipart
     * configuration is left to {@code getParameter}, which then reads no field
     * out of the body, as it did before. Jetty reports that case as it reports
     * an upload over its limits, so {@code JettyServer} says so through
     * {@link AppServlet#NO_MULTIPART_PARAMETER}, and {@code getParts} is not
     * asked at all.
     *
     * <p>A form whose charset this JVM does not know answers 415 before the
     * container is asked, as {@link #body()} does.
     *
     * <p>A form-encoded body the container will not parse — an escape that
     * will not decode, more fields or more bytes than it takes — is a 400
     * carrying the container's reason. Jetty throws its {@code BadMessageException},
     * Tomcat an {@code IllegalStateException}, and Undertow either ignores the
     * field or refuses the body itself, and none of them names a size refusal
     * in a way the servlet API defines, so the status does not try to.
     */
    private <T> T formFields(Supplier<T> read) {
        // The container decodes the fields in the declared charset, and Jetty
        // throws for one it does not know where Tomcat and Undertow fall back
        // to their own: 415 on each, as body() answers.
        bodyCharset();
        if (isMultipart() && req.getAttribute(NO_MULTIPART_ATTRIBUTE) == null) {
            try {
                req.getParts();
            } catch (IOException | ServletException | RuntimeException e) {
                RuntimeException refused = refusedMultipart(e);
                if (refused instanceof HttpException) {
                    throw refused;
                }
            }
        }
        try {
            return read.get();
        } catch (RuntimeException e) {
            // Tomcat parses the query string again with the body and refuses
            // either through the same exception, so with a query string the
            // message cannot say which of the two it was.
            String what = req.getQueryString() == null ? "Form body" : "Parameters";
            throw new HttpException(HttpStatus.BAD_REQUEST,
                    what + " could not be read: " + deepestMessage(e));
        }
    }

    /**
     * A required parameter from the query string only. The servlet API merges
     * the query string with a form body, so an {@code id} in the URL and an
     * {@code id} in the form both answer to {@link #param(String)}; this is the
     * way to say which one you meant.
     */
    public String queryParam(String name) {
        return required(name, queryParamOrNull(name), "query parameter");
    }

    /** A query-string parameter, or null when absent. */
    public @Nullable String queryParamOrNull(String name) {
        List<String> values = queryParams(name);
        return values.isEmpty() ? null : values.get(0);
    }

    /** Every value of a repeated query-string parameter. */
    public List<String> queryParams(String name) {
        return parsedQuery().getOrDefault(name, List.of());
    }

    /**
     * A required query-string parameter read through a parser of your own —
     * {@link #param(String, Function)}, with the source named.
     *
     * <pre>{@code
     * LocalDate since = req.queryParam("since", LocalDate::parse);
     * }</pre>
     *
     * <p>The contract is the one {@code param(name, parser)} has. A parameter the
     * query string does not carry answers 400, even when the form body carries
     * one of that name, and so does a parser that throws
     * {@link IllegalArgumentException} or {@link DateTimeException}. Anything
     * else the parser throws stays a 500.
     *
     * <p>{@link #queryParamOrNull(String)} is the optional string, and answers null.
     * The optional typed form takes a default:
     * {@link #queryParam(String, Function, Object)}.
     */
    public <T> T queryParam(String name, Function<String, T> parser) {
        return parse(name, queryParam(name), parser);
    }

    /**
     * An optional query-string parameter read through a parser: the default when
     * the query string does not carry it, still a 400 when the parser rejects
     * what it does carry.
     *
     * <pre>{@code
     * int page = req.queryParam("page", Integer::parseInt, 1);
     * }</pre>
     */
    public <T> T queryParam(String name, Function<String, T> parser, T defaultValue) {
        String value = queryParamOrNull(name);
        return value == null ? defaultValue : parse(name, value, parser);
    }

    /**
     * A required parameter from the form body only — the counterpart to
     * {@link #queryParam(String)}. Only present for a form-encoded body the
     * container parsed; a JSON body is read with {@link #body()}.
     */
    public String formParam(String name) {
        return required(name, formParamOrNull(name), "form field");
    }

    /** A form field, or null when absent. */
    public @Nullable String formParamOrNull(String name) {
        List<String> values = formParams(name);
        return values.isEmpty() ? null : values.get(0);
    }

    /** Every value of a repeated form field. */
    public List<String> formParams(String name) {
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

    /**
     * A required form field read through a parser of your own — the counterpart
     * to {@link #queryParam(String, Function)}.
     *
     * <pre>{@code
     * LocalDate due = req.formParam("due", LocalDate::parse);
     * }</pre>
     *
     * <p>A field the form body does not carry answers 400, even when the query
     * string carries a parameter of that name, and so does a parser that rejects
     * the text. {@link #formParamOrNull(String)} is the optional string, and answers
     * null.
     */
    public <T> T formParam(String name, Function<String, T> parser) {
        return parse(name, formParam(name), parser);
    }

    /**
     * An optional form field read through a parser: the default when the form
     * body does not carry it, still a 400 when the parser rejects what it does
     * carry.
     */
    public <T> T formParam(String name, Function<String, T> parser, T defaultValue) {
        String value = formParamOrNull(name);
        return value == null ? defaultValue : parse(name, value, parser);
    }

    /** The value a source-specific required read found, or the 400 that names the source. */
    private static String required(String name, @Nullable String value, String source) {
        if (value == null) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Missing required " + source + ": " + name);
        }
        return value;
    }

    private Map<String, List<String>> parsedQuery() {
        if (parsedQuery == null) {
            parsedQuery = parseQueryString(req.getQueryString());
        }
        return parsedQuery;
    }

    private static Map<String, List<String>> parseQueryString(@Nullable String query) {
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
        } catch (CharacterCodingException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Query string is not UTF-8: " + query);
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

    /**
     * One name or value of the query string, decoded as UTF-8. Bytes that are
     * not UTF-8, such as {@code %FF}, are refused rather than replaced with
     * U+FFFD, which {@link java.net.URLDecoder} does without a word: a value
     * the caller never sent would reach the handler, while {@code param} read
     * through the container answered 400 on Jetty and Tomcat and U+FFFD on
     * Undertow.
     *
     * @throws IllegalArgumentException for a {@code %} not followed by two hex digits
     * @throws CharacterCodingException for bytes that are not UTF-8
     */
    private static String decode(String value) throws CharacterCodingException {
        if (value.indexOf('%') < 0 && value.indexOf('+') < 0) {
            return value;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '+') {
                bytes.write(' ');
            } else if (c == '%') {
                int high = i + 2 < value.length() ? hexDigit(value.charAt(i + 1)) : -1;
                int low = high < 0 ? -1 : hexDigit(value.charAt(i + 2));
                if (low < 0) {
                    throw new IllegalArgumentException("Not a percent-escape at " + i + ": " + value);
                }
                bytes.write(high << 4 | low);
                i += 2;
            } else {
                bytes.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
            }
        }
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString();
    }

    /** An ASCII hex digit's value, or -1: {@link Character#digit} takes full-width digits too. */
    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    // ---- Body ----

    /**
     * The body as text, exactly as it arrived: line endings are not rewritten
     * and a trailing newline is kept, so a signature over the raw body still
     * verifies.
     *
     * <p>The text is read once and kept for the rest of the request, so every
     * call answers the whole body. A before-filter that reads it to check a
     * signature leaves it in place for the handler's {@link #bodyJson()}.
     *
     * <p>The body goes out one way or the other: as this text, or unread through
     * {@link #bodyStream()}, {@link #bodyReader()}, or {@link #bodyNdjson}.
     * Asking for the text once it went out unread throws
     * {@link IllegalStateException} rather than answering the part nobody read.
     *
     * <p>A body over {@link BodyLimits#maxBytes(int)} — 1MB unless
     * {@link App#bodyLimits(BodyLimits)} says otherwise — answers 413. The limit
     * counts bytes as they arrive, so a body that declares no length, or less
     * than it sends, is refused at the first byte past it. Every later read of
     * the body answers 413 as well, rather than the part nobody checked.
     *
     * <p>A {@code Content-Type} naming a charset this JVM cannot decode answers
     * 415 before a byte is read, and so do {@link #bodyJson()},
     * {@link #bodyReader()}, and {@link #bodyNdjson}.
     *
     * <p>A form-encoded POST is spent by its first {@link #param} read, because
     * the container parses the form by reading the body to its end, and this
     * then answers {@code ""}. The reverse order leaves the form with nothing to
     * parse. What is read through {@link #raw()} is read behind this method's
     * back, and is not tracked.
     */
    public String body() {
        Object read = req.getAttribute(BODY_ATTRIBUTE);
        if (read instanceof String text) {
            return text;
        }
        if (read == BodyMarker.TOO_LARGE) {
            throw bodyTooLarge();
        }
        if (read == BodyMarker.UNREADABLE) {
            throw bodyUnreadable("");
        }
        if (read != null) {
            // Anything but the text is the marker handOver(...) left.
            throw new IllegalStateException("The body was already handed over unread, through"
                    + " bodyStream(), bodyReader(), or bodyNdjson(), so body() cannot read it as text");
        }
        String text = readBody();
        req.setAttribute(BODY_ATTRIBUTE, text);
        return text;
    }

    /**
     * Reads the bytes up to the limit and decodes them once, with the charset
     * the reader would have used. Counting bytes rather than characters is what
     * bounds memory for every charset alike, and is what Content-Length counts.
     * At most one byte past the limit is read, which is how a body one byte over
     * is told apart from one exactly at it. The charset is settled before any
     * of it, so a body in one this JVM cannot decode is refused unread.
     */
    private String readBody() {
        int max = limits.maxBytes();
        if (req.getContentLengthLong() > max) {
            // Refused on the header alone: nothing is read that would be thrown away.
            throw refuseBody();
        }
        Charset charset = bodyCharset();
        try {
            InputStream in = req.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(bodyCapacity(max));
            byte[] chunk = new byte[BODY_CHUNK];
            long total = 0;
            while (true) {
                int read = in.read(chunk, 0, (int) Math.min(chunk.length, max - total + 1));
                if (read < 0) {
                    break;
                }
                total += read;
                if (total > max) {
                    throw refuseBody();
                }
                bytes.write(chunk, 0, read);
            }
            return bytes.toString(charset);
        } catch (IOException e) {
            throw refuseUnreadable(e);
        }
    }

    /**
     * The charset the request declared. {@link AppServlet} sets UTF-8 on a
     * request that declared none, so the fallback here is for a request built
     * outside it.
     *
     * <p>A charset this JVM does not know, or a name that is not a charset name
     * at all, is the client's to fix, so it answers 415 rather than the 500 an
     * {@link UnsupportedEncodingException} would become.
     */
    private Charset bodyCharset() {
        String declared = charsetParameter(contentType());
        if (declared == null) {
            declared = containerCharset();
        }
        if (declared == null) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(declared);
        } catch (IllegalArgumentException e) {
            throw unsupportedCharset(declared);
        }
    }

    /**
     * The {@code charset} parameter of a Content-Type, unquoted, or null when it
     * names none. Read here rather than through {@code getCharacterEncoding()},
     * since each container parses it its own way: for {@code charset=} Jetty
     * answers no charset, Tomcat the empty name, and Undertow throws.
     */
    private static @Nullable String charsetParameter(@Nullable String contentType) {
        if (contentType == null) {
            return null;
        }
        for (String parameter : contentType.split(";", -1)) {
            String trimmed = parameter.trim();
            if (trimmed.regionMatches(true, 0, "charset=", 0, 8)) {
                String value = trimmed.substring(8).trim();
                return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                        ? value.substring(1, value.length() - 1)
                        : value;
            }
        }
        return null;
    }

    /** The charset the container holds for a request whose header named none: UTF-8, as {@link AppServlet} sets it. */
    private @Nullable String containerCharset() {
        try {
            return req.getCharacterEncoding();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static HttpException unsupportedCharset(String declared) {
        return new HttpException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Unsupported charset in Content-Type: " + declared);
    }

    /** Marks the body refused for size, so no later read answers the rest of it, and says so. */
    private HttpException refuseBody() {
        req.setAttribute(BODY_ATTRIBUTE, BodyMarker.TOO_LARGE);
        return bodyTooLarge();
    }

    /**
     * A body the container could not finish reading: the client sent less than
     * its {@code Content-Length}, or the connection failed partway. The fault
     * is on the request's side, so it is a 400, as decision 63 has it, and the
     * request logger does not report it as a failure of the application.
     */
    private HttpException refuseUnreadable(IOException failure) {
        req.setAttribute(BODY_ATTRIBUTE, BodyMarker.UNREADABLE);
        return bodyUnreadable(": " + deepestMessage(failure));
    }

    private static HttpException bodyUnreadable(String detail) {
        return new HttpException(HttpStatus.BAD_REQUEST, "Request body could not be read" + detail);
    }

    private HttpException bodyTooLarge() {
        return new HttpException(HttpStatus.CONTENT_TOO_LARGE,
                "Request body is larger than the limit of %d bytes".formatted(limits.maxBytes()));
    }

    /**
     * Records that the body goes out unread, after checking that nobody read it
     * as text first. The container enforces the stream-or-reader rule on its
     * own; this adds the rule the cached text needs.
     */
    private void handOver(BodyMarker taker) {
        String asked = taker.method;
        Object read = req.getAttribute(BODY_ATTRIBUTE);
        if (read == BodyMarker.TOO_LARGE) {
            // Part of the body is already gone: handing on the rest would be a truncated body.
            throw bodyTooLarge();
        }
        if (read == BodyMarker.UNREADABLE) {
            throw bodyUnreadable("");
        }
        if (read instanceof String) {
            throw new IllegalStateException("The body was already read as text by body() or bodyJson(),"
                    + " so " + asked + " has nothing left to hand over. Read the text again with body()");
        }
        if (read instanceof BodyMarker taken && !(taken == taker && taker != BodyMarker.NDJSON)) {
            // The stream and the reader are the container's, so asking for the
            // same one again answers it where the first caller left it. Anything
            // else would read on from a body another reader has part of, and the
            // NDJSON reader holds a chunk of it that no one else can see.
            throw new IllegalStateException("The body already went out through " + taken.method
                    + ", so " + asked + " cannot read it as well");
        }
        req.setAttribute(BODY_ATTRIBUTE, taker);
    }

    /**
     * How much room to give the buffer {@link #body()} fills: what
     * Content-Length declares, so that it need not grow at all. It is capped
     * because the header is the caller's claim rather than a measurement, and a
     * request that declares the whole limit and sends nothing must not reserve it.
     */
    private int bodyCapacity(int max) {
        long declared = req.getContentLengthLong();
        if (declared <= 0) {
            return BODY_CHUNK;
        }
        return (int) Math.min(declared, Math.min(max, MAX_BODY_CAPACITY));
    }

    /**
     * Parses the body as JSON. Responds with 400 on invalid syntax.
     *
     * <p>It parses the text {@link #body()} keeps, so a filter that read the
     * body first does not leave this with nothing to parse.
     */
    public JsonValue bodyJson() {
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
     * Reader rejection means IllegalArgumentException or DateTimeException,
     * matching the parameter parser contract. Other exceptions remain server errors.
     */
    public <T> T bodyJson(JsonReader<T> reader) {
        JsonValue json = bodyJson();
        try {
            return reader.read(json);
        } catch (IllegalArgumentException | DateTimeException e) {
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
     * <p>No {@link BodyLimits} applies here: the caller reads the stream and
     * decides how much of it to keep, which is what makes a large upload
     * possible. A body {@link #body()} already refused for size answers 413
     * rather than a stream that starts partway through.
     *
     * <p>The body goes out once, one way: a request whose text
     * {@link #body()} or {@link #bodyJson()} already read throws
     * {@link IllegalStateException} here, and so does one whose body went out
     * through {@link #bodyReader()} or {@link #bodyNdjson}. {@code body()} after
     * this throws as well. The stream itself is the container's, so a second
     * call answers the same stream, at wherever the first reader left it.
     *
     * <p>A form-encoded POST is spent by its first {@link #param} read as well,
     * because the container parses the form by reading the body to its end. The
     * stream is then already at its end and answers no bytes, which Jetty,
     * Tomcat, and Undertow all do instead of throwing. The reverse order leaves
     * {@link #formParam} with nothing to parse once the body has been read here.
     */
    public InputStream bodyStream() {
        handOver(BodyMarker.STREAM);
        try {
            return req.getInputStream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The body as characters, unread, decoded with the charset the request
     * declared. The counterpart of {@link #bodyStream()} for a library that
     * reads text, subject to the same one-way rule, and likewise not limited.
     * A charset this JVM cannot decode answers 415, as it does for
     * {@link #body()}.
     */
    public BufferedReader bodyReader() {
        handOver(BodyMarker.READER);
        // Settled here rather than left to getReader(), which each container
        // fails on in its own way.
        bodyCharset();
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
     *     return WebResponse.json(Json.object().put("imported", imported));
     * });
     * }</pre>
     *
     * <p>Blank lines are skipped, and a line that is not valid JSON or that the
     * reader rejects answers 400 naming the line — which is the reason to read
     * NDJSON rather than one big array when the body is large: the report says
     * where the body went wrong, not just that it did.
     *
     * <p>A line over {@link BodyLimits#maxNdjsonLineBytes(int)} — 1MB unless
     * {@link App#bodyLimits(BodyLimits)} says otherwise — answers 413 naming the
     * line, as soon as it outgrows the limit rather than once it has arrived.
     * The number of lines is not limited, so memory holds one line at a time
     * however long the body runs. Lines are split on the bytes, so the body is
     * read in a charset that encodes a newline as one byte, which UTF-8 does.
     *
     * <p>The body goes out unread, as it does through {@link #bodyReader()}, so
     * {@link #body()} cannot read it afterwards and a body already read as text
     * throws {@link IllegalStateException} here.
     *
     * <p>The stream is lazy, so those failures happen where it is consumed. Do
     * that before returning the response: inside a
     * {@link WebResponse#stream(String, StreamWriter)} writer handler exception
     * handling has finished, so rejection cannot become a 400 response.
     */
    public <T> Stream<T> bodyNdjson(JsonReader<T> reader) {
        handOver(BodyMarker.NDJSON);
        NdjsonLines lines;
        try {
            lines = new NdjsonLines(req.getInputStream(), bodyCharset(), limits.maxNdjsonLineBytes(),
                    this::refuseLine, this::refuseUnreadable);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        AtomicLong line = new AtomicLong();
        return StreamSupport.stream(lines, false).<T>mapMulti((text, values) -> {
            long number = line.incrementAndGet();
            if (!isJsonBlank(text)) {
                values.accept(readLine(text, number, reader));
            }
        });
    }

    /**
     * Whether a line holds only the whitespace JSON allows: space, tab, CR, and
     * LF. {@link String#isBlank()} is Java's whitespace, so a line of a form
     * feed or an em space was skipped where the parser refuses the character.
     */
    private static boolean isJsonBlank(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
                return false;
            }
        }
        return true;
    }

    private HttpException refuseLine(long number) {
        req.setAttribute(BODY_ATTRIBUTE, BodyMarker.TOO_LARGE);
        return new HttpException(HttpStatus.CONTENT_TOO_LARGE,
                "Line %d of the NDJSON body is larger than the limit of %d bytes"
                        .formatted(number, limits.maxNdjsonLineBytes()));
    }

    private static <T> T readLine(String text, long number, JsonReader<T> reader) {
        try {
            return reader.read(Json.parse(text));
        } catch (IllegalArgumentException | DateTimeException e) {
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
     *
     * <p>A multipart body the container will not take is not a missing file.
     * One refused for its size — a part over {@code maxFileSize}, a body over
     * {@code maxRequestSize} — answers 413, and one that will not parse, such as
     * a body cut off before its closing boundary, answers 400.
     */
    public UploadedFile file(String name) {
        if (!isMultipart()) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Not a multipart request");
        }
        UploadedFile file = upload(name);
        if (file == null) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "Missing uploaded file: " + name);
        }
        return file;
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
     *
     * <p>Null never stands for an upload that failed. A multipart body the
     * container refuses for its size answers 413, and one it cannot parse
     * answers 400, as they do for {@link #file(String)}: a handler that carried
     * on as if nothing had been chosen would lose the upload without a word.
     */
    public @Nullable UploadedFile fileOrNull(String name) {
        return isMultipart() ? upload(name) : null;
    }

    /**
     * Every file sent under one field name, in the order the request sent them —
     * the shape a {@code <input type="file" multiple>} arrives in. Empty when
     * the field carried none, since "nothing chosen" is an answer, not an error,
     * and {@link #params(String)} answers a repeated parameter the same way.
     *
     * <pre>{@code
     * for (UploadedFile page : req.files("pages")) {
     *     page.writeTo(scans.resolve(UUID.randomUUID() + ".pdf"));
     * }
     * }</pre>
     *
     * <p>Only the parts that carry a file are counted. The text fields of the
     * same form are parts too, and they are read with {@link #param(String)}.
     *
     * <p>An empty list never stands for an upload that failed: a multipart body
     * the container refuses for its size answers 413, and one it cannot parse
     * answers 400, as they do for {@link #file(String)}.
     */
    public List<UploadedFile> files(String name) {
        if (!isMultipart()) {
            return List.of();
        }
        bodyCharset();
        Collection<Part> parts;
        try {
            parts = req.getParts();
        } catch (IOException | ServletException | RuntimeException e) {
            throw refusedMultipart(e);
        }
        List<UploadedFile> files = new ArrayList<>();
        for (Part part : parts) {
            // Undertow answers a null name for a part whose Content-Disposition
            // carries none. It is no field's part, and Tomcat skips it too.
            if (name.equals(part.getName()) && isFile(part)) {
                files.add(new UploadedFile(part));
            }
        }
        return List.copyOf(files);
    }

    /**
     * Whether the request declares a multipart form. Decided by the header, not
     * by what the container throws: the servlet API throws the same
     * {@code ServletException} for a request that is not multipart as Jetty
     * does for one that is and will not parse, and telling those two apart is
     * the difference between "no file" and a failed upload.
     */
    private boolean isMultipart() {
        return MULTIPART_FORM_DATA.equalsIgnoreCase(mediaType());
    }

    /**
     * Whether the container reads parameters out of this request's body: a
     * form-encoded or multipart one on POST, PUT, or PATCH. Each container
     * gates the form by method, and every server is set to these three.
     */
    private boolean carriesForm() {
        return FORM_METHODS.contains(req.getMethod()) && (isMultipart() || isFormEncoded());
    }

    /** Whether the request declares a form-encoded body, the other kind a container parses into fields. */
    private boolean isFormEncoded() {
        return FORM_URLENCODED.equalsIgnoreCase(mediaType());
    }

    /** The Content-Type without its parameters, or null when the request sent none. */
    private @Nullable String mediaType() {
        String type = contentType();
        if (type == null) {
            return null;
        }
        int semicolon = type.indexOf(';');
        return (semicolon < 0 ? type : type.substring(0, semicolon)).trim();
    }

    /**
     * The first file under that name, or null when the multipart form carries
     * none: the one {@link #files(String)} puts first. {@code getPart(name)}
     * answers the first part of the name whatever it holds, and a browser sends
     * an untouched file input as an empty part, so a form whose first input of
     * the name was left empty read as carrying no file at all.
     */
    private @Nullable UploadedFile upload(String name) {
        List<UploadedFile> files = files(name);
        return files.isEmpty() ? null : files.get(0);
    }

    /**
     * What a multipart body the container would not hand over answers. The
     * servlet API names {@link IllegalStateException} for a part or a body over
     * its limit, and each of the three containers puts one on the cause chain
     * when a limit refused the upload: Tomcat's and Undertow's carry the size
     * exception as their cause, and Jetty wraps its own in a
     * {@link ServletException}. That is 413. Anything else — Jetty's
     * {@code ServletException} over an EOF, Tomcat's and Undertow's
     * {@link IOException} for a body cut short — is a body that will not parse,
     * and 400.
     *
     * <p>Undertow refuses a form of more than 1000 parts with a
     * {@code RuntimeException} around its own checked exception, which names
     * no size either, so that is a 400 as well: a request refused, not a 500.
     *
     * <p>The API names the same exception for a servlet with no multipart
     * configuration at all. Tomcat and Undertow throw that one bare, with no
     * cause, before reading anything, and it is the deployment's fault rather
     * than the request's, so it goes on as the 500 it was. Jetty wraps that
     * case like the others, so there it answers 413 with Jetty's own message.
     */
    private RuntimeException refusedMultipart(Exception failure) {
        if (failure instanceof IllegalStateException bare && bare.getCause() == null) {
            return bare;
        }
        boolean tooLarge = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            tooLarge |= cause instanceof IllegalStateException;
        }
        String detail = deepestMessage(failure);
        return tooLarge
                ? new HttpException(HttpStatus.CONTENT_TOO_LARGE, "Multipart upload refused: " + detail)
                : new HttpException(HttpStatus.BAD_REQUEST, "Multipart body could not be read: " + detail);
    }

    /**
     * The message nearest the root of a container's failure, which is the one
     * that says what was wrong with the body; the wrappers above it repeat it
     * or name only themselves. Empty when nothing on the chain has one.
     */
    private static String deepestMessage(Throwable failure) {
        String detail = "";
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null) {
                detail = cause.getMessage();
            }
        }
        return detail;
    }

    /**
     * Whether a part is an uploaded file at all. A part with no submitted file
     * name is a text field of the same form, or a file input the browser sent
     * empty because nothing was chosen; neither is a file a handler can read.
     */
    private static boolean isFile(@Nullable Part part) {
        return part != null && part.getSubmittedFileName() != null
                && !part.getSubmittedFileName().isEmpty();
    }

    // ---- Cookies the client sent ----

    /** A cookie the client sent, or null. Setting one is {@link WebResponse#cookie}. */
    public @Nullable String cookie(String name) {
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
     * The session's attributes, read and written in one place.
     *
     * <pre>{@code
     * req.session().set("user", user);
     * User user = req.session().get("user", User.class);
     * }</pre>
     *
     * <p>Asking for it starts no session: only {@link WebSession#set} does.
     */
    public WebSession session() {
        return new WebSession(req);
    }

    // ---- Flash (visible exactly once, on the request after a redirect) ----

    /**
     * Leaves a message for the request after the redirect, which reads it with
     * {@link #flashed(String)} and is the only request that can.
     *
     * <p>A null value removes the key, as it does for
     * {@link WebSession#set(String, Object)}: a handler withdraws a flash it set
     * earlier in the same request by flashing null under that key. It withdraws
     * only what waits for the next request; a value this request received is
     * still what {@link #flashed(String)} answers. Withdrawing never creates a
     * session.
     *
     * <p>Two requests in one session can be on two container threads at once —
     * a form posted in one tab while another is still loading. Finding the map
     * and creating it are therefore done under the session's own monitor, which
     * {@code AppServlet} takes for the other half of the pair, and the map is
     * concurrent so that two writers do not corrupt it once they both hold it.
     */
    public void flash(String key, @Nullable String value) {
        if (value == null) {
            Map<String, String> pending = pendingFlash(false);
            if (pending != null) {
                pending.remove(key);
            }
            return;
        }
        Objects.requireNonNull(pendingFlash(true)).put(key, value);
    }

    /**
     * The map of flash waiting in the session for the next request. With
     * {@code create} false, null when there is no session or nothing waiting,
     * so that withdrawing a flash never starts a session.
     */
    private @Nullable Map<String, String> pendingFlash(boolean create) {
        HttpSession session = req.getSession(create);
        if (session == null) {
            return null;
        }
        // The container answers one HttpSession object per session id, so this
        // is the lock the promoting side takes too.
        synchronized (session) {
            @SuppressWarnings("unchecked")
            Map<String, String> existing = (Map<String, String>) session.getAttribute(FLASH_ATTRIBUTE);
            if (existing == null && create) {
                existing = new ConcurrentHashMap<>();
                session.setAttribute(FLASH_ATTRIBUTE, existing);
            }
            return existing;
        }
    }

    /** A flash value left by the previous request, or null. */
    public @Nullable String flashed(String key) {
        Object attribute = req.getAttribute(FLASH_ATTRIBUTE);
        if (attribute instanceof Map<?, ?> flash) {
            Object value = flash.get(key);
            return value == null ? null : value.toString();
        }
        return null;
    }

    // ---- Errors ----

    /**
     * Inside an {@link App#statusPage(HttpStatus, Handler)} handler, the plain-text message
     * the framework would have answered with. Null when the status came from a
     * handler rather than from the router or an {@link HttpException}.
     */
    public @Nullable String errorMessage() {
        return errorMessage;
    }

    void errorMessage(@Nullable String errorMessage) {
        this.errorMessage = errorMessage;
    }

    /**
     * Inside the request logger, the exception the request was answered for:
     * what a handler, a filter, or a template threw, whether an exception
     * handler answered it or the framework's 500 did. Null when nothing threw,
     * and for an {@link HttpException}, which is a status rather than a failure.
     */
    @Nullable Throwable thrown() {
        return thrown;
    }

    void thrown(@Nullable Throwable thrown) {
        this.thrown = thrown;
    }

    /** The same request, with the route that matched and the path variables it resolved. */
    WebRequest withRoute(Route matched, Map<String, String> resolved) {
        WebRequest copy = new WebRequest(req, resolved, limits, matched);
        // The same servlet request, so the same path: the copy is made after
        // routing has already asked for it.
        copy.path = path;
        copy.parsedQuery = parsedQuery;
        copy.errorMessage = errorMessage;
        copy.thrown = thrown;
        return copy;
    }
}
