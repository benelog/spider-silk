package net.benelog.spidersilk;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.servlet.http.Cookie;

import net.benelog.spidersilk.json.Json;
import net.benelog.spidersilk.json.JsonWriter;

/**
 * The answer a {@link Handler} returns: a status, headers, cookies, and a body.
 *
 * <pre>{@code
 * app.get("/decks/{deckId}", req ->
 *         WebResponse.template("deck", Map.of("deck", service.deck(req.pathParamLong("deckId")))));
 *
 * app.post("/decks", req ->
 *         WebResponse.redirect("/decks/" + service.create(req.param("name")).id()));
 *
 * app.get("/api/decks", req -> WebResponse.json(service.decks(), Codecs::writeDecks));
 * }</pre>
 *
 * <p>A response is an immutable value. Every {@code with}-style method —
 * {@link #status(HttpStatus)}, {@link #header(String, String)}, {@link #cookie(String, String)} —
 * returns a new response rather than changing this one, which is what lets an
 * {@link AfterFilter} take a response and hand back a different one.
 *
 * <p>The envelope is always the same; what differs between an HTML page, a JSON
 * document, a file download, and an SSE stream is the {@link Body}. That is a
 * sealed type, so {@link AppServlet} answers each kind exhaustively and an
 * application can pattern-match over one without a default case:
 *
 * <pre>{@code
 * String describe(WebResponse response) {
 *     return switch (response.body()) {
 *         case WebResponse.Empty ignored -> "no body";
 *         case WebResponse.Text text -> text.content();
 *         case WebResponse.Bytes bytes -> bytes.data().length + " bytes";
 *         case WebResponse.Template template -> "template " + template.name();
 *         case WebResponse.Stream ignored -> "a stream";
 *         case WebResponse.Sse ignored -> "an event stream";
 *         case WebResponse.Raw ignored -> "written by hand";
 *     };
 * }
 * }</pre>
 */
public final class WebResponse {

    /** The kinds of body a response can carry. Sealed, so the list is the whole list. */
    public sealed interface Body {
    }

    /** No body at all: a redirect, a 204, an OPTIONS answer, an error not yet filled in. */
    public record Empty() implements Body {
    }

    /** Text written as UTF-8. HTML, plain text, and JSON all arrive here. */
    public record Text(String content) implements Body {
    }

    /**
     * Bytes written as they are. The array is held, not copied — a download is
     * exactly the case where a second copy is what you were avoiding — so it
     * belongs to the response once handed over.
     */
    public record Bytes(byte[] data) implements Body {
    }

    /**
     * A template and its model, rendered by the engine set through
     * {@link App#templates(TemplateRenderer)}. Rendering happens while the
     * handler's exception handling still applies, so a template that throws is
     * routed to {@link App#exception} like any other failure.
     *
     * <p>The model is held as it was given, not copied: a template model takes
     * null values, which the unmodifiable copies would refuse. Hand over a map
     * nothing else still writes to.
     */
    public record Template(String name, Map<String, Object> model) implements Body {
    }

    /** A body written straight to the output stream, for content too big to hold. */
    public record Stream(StreamWriter writer) implements Body {
    }

    /** A Server-Sent Events stream, filled for as long as it should last. */
    public record Sse(SseWriter writer) implements Body {
    }

    /** The escape hatch: the raw servlet request and response, written by hand. */
    public record Raw(ServletWriter writer) implements Body {
    }

    private static final Empty EMPTY_BODY = new Empty();

    /** Null means "not set", which answers 200 and lets {@link App#error} fill in a status. */
    private final HttpStatus status;
    private final Map<String, String> headers;
    private final List<Cookie> cookies;
    private final Body body;

    private WebResponse(HttpStatus status, Map<String, String> headers, List<Cookie> cookies,
            Body body) {
        this.status = status;
        this.headers = headers;
        this.cookies = cookies;
        this.body = body;
    }

    // ---- Bodies ----

    /** An HTML page. */
    public static WebResponse html(String content) {
        return of(new Text(content)).contentType("text/html; charset=UTF-8");
    }

    /** Plain text. */
    public static WebResponse text(String content) {
        return of(new Text(content)).contentType("text/plain; charset=UTF-8");
    }

    /** A JSON document that is already serialized. */
    public static WebResponse json(String rawJson) {
        return of(new Text(rawJson)).contentType("application/json");
    }

    public static WebResponse json(Json.JsonValue value) {
        return json(value.toJson());
    }

    /** A value written as JSON through a hand-written writer. */
    public static <T> WebResponse json(T value, JsonWriter<T> writer) {
        return json(writer.write(value));
    }

    public static WebResponse bytes(byte[] data, String contentType) {
        return of(new Bytes(data)).contentType(contentType);
    }

    /** A template with nothing to pass in. */
    public static WebResponse template(String template) {
        return template(template, Map.of());
    }

    /**
     * A template rendered with the engine set via
     * {@link App#templates(TemplateRenderer)} — by default jte over
     * {@code classpath:/jte}, which turns {@code "deck"} into
     * {@code classpath:/jte/deck.jte}. The name carries no extension: the
     * engine appends its own, so a switch of engine does not rewrite every
     * handler.
     */
    public static WebResponse template(String template, Map<String, Object> model) {
        return of(new Template(Objects.requireNonNull(template, "template"),
                Objects.requireNonNull(model, "model")))
                .contentType("text/html; charset=UTF-8");
    }

    /**
     * A body written straight to the output stream — a large download, or
     * anything generated as it goes rather than held in memory first.
     *
     * <pre>{@code
     * app.get("/decks/{deckId}/export", req -> WebResponse
     *         .stream("text/csv; charset=UTF-8", out -> service.writeCsv(deckId, out))
     *         .attachment("deck.csv"));
     * }</pre>
     *
     * <p>The stream is written after the response headers are committed, so a
     * failure partway through can no longer change the status. Anything that can
     * fail in a way the client should hear about belongs before the response is
     * returned, not inside the writer.
     */
    public static WebResponse stream(String contentType, StreamWriter writer) {
        return of(new Stream(Objects.requireNonNull(writer, "writer"))).contentType(contentType);
    }

    /**
     * Answers with a Server-Sent Events stream: {@code text/event-stream}, one
     * event per {@link SseStream#send} call, flushed as it goes.
     *
     * <pre>{@code
     * app.get("/decks/{deckId}/events", req -> {
     *     long deckId = req.pathParamLong("deckId");
     *     return WebResponse.sse(stream -> {
     *         while (stream.isOpen()) {
     *             stream.send("due", Json.obj().put("count", service.due(deckId)).toJson());
     *             Thread.sleep(1000);
     *         }
     *     });
     * });
     * }</pre>
     *
     * <p>This is an ordinary route answering in a different shape — not a
     * registration of its own — so {@link App#routes()} lists it, filters cover
     * it, and {@link App#requestLogger} reports it when the stream ends.
     *
     * <p>The request occupies its thread for the life of the stream, which is
     * what keeps SSE working through a plain servlet container. Many concurrent
     * streams are what the virtual-thread executor on the Jetty thread pool is
     * for.
     *
     * <p>The writer returning closes the stream, and so does {@link App#stop()}.
     * A client that disconnects is not an error: the write that discovers it
     * throws {@link SseStream.Closed}, which ends the writer here rather than at
     * an exception handler.
     *
     * <p>A HEAD of an SSE route answers with the headers and never runs the
     * writer — a stream with the body thrown away would never end.
     */
    public static WebResponse sse(SseWriter writer) {
        return of(new Sse(Objects.requireNonNull(writer, "writer")))
                .contentType("text/event-stream; charset=UTF-8")
                .header("Cache-Control", "no-cache");
    }

    /**
     * The escape hatch: write the raw servlet response by hand. Nothing in this
     * framework inspects what comes out, so the status and headers set on this
     * response are applied first and everything after that is yours.
     */
    public static WebResponse raw(ServletWriter writer) {
        return of(new Raw(Objects.requireNonNull(writer, "writer")));
    }

    /**
     * A redirect, by default a 302. The {@code Location} header goes out as
     * given: a path like {@code "/decks/3"} is what an application normally
     * wants, and is what HTTP allows.
     *
     * <p>302 rather than 301 because a redirect is reversible only while it is
     * temporary. A 301 is cached by browsers and intermediaries — often
     * indefinitely — so a wrong one keeps sending visitors to the wrong place
     * long after the code is fixed, and there is no way to call it back. It is
     * also what the servlet API's own {@code sendRedirect} sends, and what
     * Javalin, Spark, Spring MVC, Express, Rails, and Django all default to; a
     * framework that defaulted to 301 would be surprising in the one direction
     * that cannot be undone.
     *
     * <p>Say {@link #redirect(String, HttpStatus)} when the answer is something
     * else: {@code MOVED_PERMANENTLY} once a URL really has moved for good,
     * {@code SEE_OTHER} after a POST — the status 303 exists for exactly that
     * and is what 302 is doing in practice — or {@code TEMPORARY_REDIRECT} /
     * {@code PERMANENT_REDIRECT} when the method must survive the redirect.
     */
    public static WebResponse redirect(String location) {
        return redirect(location, HttpStatus.FOUND);
    }

    /**
     * A redirect at the status you name.
     *
     * @throws IllegalArgumentException if the status is not a 3xx, since a
     *         {@code Location} header on anything else is not a redirect
     */
    public static WebResponse redirect(String location, HttpStatus status) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(status, "status");
        if (status.code() < 300 || status.code() > 399) {
            throw new IllegalArgumentException("A redirect needs a 3xx status, not " + status);
        }
        return empty(status).header("Location", location);
    }

    /** No body, and no status of its own — 200 unless something sets one. */
    public static WebResponse empty() {
        return of(EMPTY_BODY);
    }

    /** No body, at this status. */
    public static WebResponse empty(HttpStatus status) {
        return empty().status(status);
    }

    /** 204 No Content. */
    public static WebResponse noContent() {
        return empty(HttpStatus.NO_CONTENT);
    }

    private static WebResponse of(Body body) {
        return new WebResponse(null, Map.of(), List.of(), body);
    }

    // ---- Reading ----

    /** The status this answers with. {@code OK} when nothing set one. */
    public HttpStatus status() {
        return status == null ? HttpStatus.OK : status;
    }

    /** Whether a status was set explicitly, which {@link App#error} needs to know. */
    boolean hasStatus() {
        return status != null;
    }

    public String header(String name) {
        return headers.get(name);
    }

    /**
     * Every header set on this response, read-only and in the order they were
     * set. A response is built by replacement — {@link #header(String, String)}
     * returns a new one — so there is nothing here for a caller to alter.
     */
    public Map<String, String> headers() {
        return headers;
    }

    /**
     * The cookies this response sets, read-only and in the order they were
     * added. The list is; a {@link Cookie} is not, because the servlet API's
     * cookie is a mutable object and this hands back the ones it was given
     * rather than copies. Setting one is {@link #cookie(Cookie)}, which returns
     * a new response; altering one taken from here changes what this response
     * sends.
     */
    public List<Cookie> cookies() {
        return cookies;
    }

    public Body body() {
        return body;
    }

    // ---- Building on ----

    public WebResponse status(HttpStatus status) {
        return new WebResponse(Objects.requireNonNull(status, "status"), headers, cookies, body);
    }

    public WebResponse header(String name, String value) {
        Map<String, String> copy = new LinkedHashMap<>(headers);
        copy.put(Objects.requireNonNull(name, "name"), value);
        return new WebResponse(status, unmodifiable(copy), cookies, body);
    }

    public WebResponse contentType(String contentType) {
        return header("Content-Type", contentType);
    }

    /**
     * Adds a field name to {@code Vary}, keeping the ones already there.
     * Compression and CORS each make the answer depend on a request header —
     * {@code Accept-Encoding} for one, {@code Origin} for the other — and a
     * plain {@link #header(String, String)} would have the second erase the
     * first, leaving a shared cache free to hand the wrong body to the next
     * client. A field already listed is not repeated.
     */
    public WebResponse vary(String field) {
        Objects.requireNonNull(field, "field");
        String existing = header("Vary");
        if (existing == null || existing.isBlank()) {
            return header("Vary", field);
        }
        for (String listed : existing.split(",")) {
            if (listed.trim().equalsIgnoreCase(field)) {
                return this;
            }
        }
        return header("Vary", existing + ", " + field);
    }

    /** Sets Content-Disposition so the response downloads as a file. */
    public WebResponse attachment(String filename) {
        return header("Content-Disposition", "attachment; filename=\"%s\"".formatted(filename));
    }

    /** The same response carrying a different body, which is what a filter rewrites. */
    public WebResponse body(Body body) {
        return new WebResponse(status, headers, cookies, Objects.requireNonNull(body, "body"));
    }

    /**
     * The value of a header whatever its spelling. Field names are
     * case-insensitive in HTTP, and {@link #header(String)} reads the map by
     * the exact key it was set under, which is enough for a caller that set it
     * and not enough for one asking what somebody else already set.
     */
    String headerIgnoringCase(String name) {
        String exact = headers.get(name);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getKey().equalsIgnoreCase(name)) {
                return header.getValue();
            }
        }
        return null;
    }

    /**
     * This response without that header, whatever its spelling — what
     * compressing a stream has to do to the {@code Content-Length} the
     * uncompressed body had worked out.
     */
    WebResponse withoutHeader(String name) {
        Map<String, String> copy = new LinkedHashMap<>(headers);
        copy.keySet().removeIf(key -> key.equalsIgnoreCase(name));
        return copy.size() == headers.size()
                ? this
                : new WebResponse(status, unmodifiable(copy), cookies, body);
    }

    /**
     * Sets a cookie that lasts until the browser closes, scoped to the whole
     * site, HttpOnly, and SameSite=Lax. Those defaults are what a session-ish
     * cookie should be; {@link #cookie(Cookie)} is there for the rest.
     */
    public WebResponse cookie(String name, String value) {
        return cookie(defaultCookie(name, value));
    }

    /** Sets a cookie that outlives the browser session, with the same defaults. */
    public WebResponse cookie(String name, String value, Duration maxAge) {
        Cookie cookie = defaultCookie(name, value);
        cookie.setMaxAge((int) Math.min(maxAge.toSeconds(), Integer.MAX_VALUE));
        return cookie(cookie);
    }

    /** Sets a cookie built by hand — the way to Secure, a Domain, or SameSite=None. */
    public WebResponse cookie(Cookie cookie) {
        List<Cookie> copy = new ArrayList<>(cookies);
        copy.add(Objects.requireNonNull(cookie, "cookie"));
        return new WebResponse(status, headers, List.copyOf(copy), body);
    }

    /** Expires a cookie that was set with the defaults. */
    public WebResponse removeCookie(String name) {
        Cookie cookie = defaultCookie(name, "");
        cookie.setMaxAge(0);
        return cookie(cookie);
    }

    /**
     * This response, keeping the headers and cookies of the one it replaces. An
     * {@link App#error(HttpStatus, Handler)} handler answering a 405 gets the
     * {@code Allow} header the framework had already worked out, without having
     * to know about it.
     */
    WebResponse over(WebResponse base) {
        Map<String, String> mergedHeaders = new LinkedHashMap<>(base.headers);
        mergedHeaders.putAll(headers);
        List<Cookie> mergedCookies = new ArrayList<>(base.cookies);
        mergedCookies.addAll(cookies);
        return new WebResponse(status, unmodifiable(mergedHeaders), List.copyOf(mergedCookies),
                body);
    }

    /**
     * The map is freshly built here and its only reference goes into the wrapper,
     * so a read-only view of it is as safe as a copy — and one allocation less.
     * A {@code LinkedHashMap} behind the view is also what keeps the headers in
     * the order they were set; {@link Map#copyOf} does not promise an order.
     */
    private static Map<String, String> unmodifiable(Map<String, String> fresh) {
        return Collections.unmodifiableMap(fresh);
    }

    private static Cookie defaultCookie(String name, String value) {
        Cookie cookie = new Cookie(name, value);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setAttribute("SameSite", "Lax");
        return cookie;
    }
}
