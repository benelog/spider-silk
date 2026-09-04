package net.benelog.spidersilk;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.servlet.http.Cookie;

import net.benelog.spidersilk.json.Json;
import net.benelog.spidersilk.json.JsonSink;
import net.benelog.spidersilk.json.JsonStreamWriter;
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
     * belongs to the response once handed over. Two bodies are equal only when
     * they hold the same array, which is the identity a held-not-copied array
     * has; nothing compares bodies by content.
     */
    @SuppressWarnings("ArrayRecordComponent")
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
    private final Headers headers;
    private final List<Cookie> cookies;
    private final Body body;

    private WebResponse(HttpStatus status, Headers headers, List<Cookie> cookies, Body body) {
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

    /**
     * A JSON array written one element at a time, for an answer too big to hold
     * as a tree. The brackets and the commas are this method's; the writer says
     * only what the next element is.
     *
     * <pre>{@code
     * app.get("/api/decks/{deckId}/cards", req -> {
     *     long deckId = req.pathParamLong("deckId");
     *     return WebResponse.jsonArray(sink -> service.eachCard(deckId,
     *             card -> sink.write(card, Codecs.CARD)));
     * });
     * }</pre>
     *
     * <p>What this buys is memory: {@code json(list, JsonWriter.list(w))} builds
     * every element as a tree and then one string holding all of them, so a
     * large answer is held twice before a byte of it is sent. Here the largest
     * thing alive is one element.
     *
     * <p>It is a {@link #stream(String, StreamWriter)} body underneath, so the
     * same rule applies: the headers are committed before the writer runs, and a
     * failure partway through can no longer change the status into an error the
     * client would understand. It also means a HEAD runs the writer to find the
     * length, and that gzip covers it like any other JSON.
     */
    public static WebResponse jsonArray(JsonStreamWriter writer) {
        Objects.requireNonNull(writer, "writer");
        return stream("application/json", out -> {
            Writer text = textWriter(out);
            ArraySink sink = new ArraySink(text);
            writer.write(sink);
            sink.close();
            text.flush();
        });
    }

    /**
     * Newline-delimited JSON: one complete JSON value per line,
     * {@code application/x-ndjson}. A reader can act on each line as it lands
     * without waiting for the end, and can stop reading without having parsed a
     * document that was never closed — which a truncated JSON array cannot
     * offer.
     *
     * <pre>{@code
     * app.get("/api/decks/{deckId}/cards.ndjson", req -> {
     *     long deckId = req.pathParamLong("deckId");
     *     return WebResponse.ndjson(sink -> service.eachCard(deckId,
     *             card -> sink.write(card, Codecs.CARD)));
     * });
     * }</pre>
     *
     * <p>This is bulk transfer, not live events: it ends when the data ends, and
     * nothing flushes between lines beyond what the container's buffer decides.
     * A stream that stays open because more will happen later is
     * {@link #sse(SseWriter)}, which flushes per event and reconnects.
     *
     * <p>A value written here must not contain a newline of its own, and none
     * can: {@code Json} escapes one inside a string as {@code \n} and puts no
     * whitespace between members.
     */
    public static WebResponse ndjson(JsonStreamWriter writer) {
        Objects.requireNonNull(writer, "writer");
        return stream("application/x-ndjson", out -> {
            Writer text = textWriter(out);
            writer.write(new LinesSink(text));
            text.flush();
        });
    }

    /**
     * Bytes already in memory — a generated PDF, an image, an export small
     * enough to build before answering. The content type comes first, as it
     * does on {@link #stream(String, StreamWriter)}.
     *
     * <pre>{@code
     * app.get("/decks/{deckId}/sheet.pdf", req -> WebResponse
     *         .bytes("application/pdf", service.sheet(req.pathParamLong("deckId")))
     *         .attachment("deck.pdf"));
     * }</pre>
     *
     * <p>A body that is written as it goes, rather than held whole, is
     * {@link #stream(String, StreamWriter)}.
     */
    public static WebResponse bytes(String contentType, byte[] data) {
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
     * A file on disk, answered with the content type its name implies, its size
     * as {@code Content-Length}, and a {@link Stream} body.
     *
     * <pre>{@code
     * app.get("/decks/{deckId}/export", req ->
     *         WebResponse.file(service.writeExport(req.pathParamLong("deckId")))
     *                 .attachment("deck.csv"));
     * }</pre>
     *
     * <p>The type comes from the extension, and a name whose extension is not in
     * the table answers {@code application/octet-stream}. A file whose name says
     * nothing useful about its content is answered with
     * {@code .contentType(...)}, which overrides what was worked out here.
     *
     * <p>A path that is not a readable regular file throws
     * {@link UncheckedIOException} rather than answering 404. The framework
     * cannot tell an export that was cleaned up from a path the handler built
     * wrong, and the handler can: it checks the file is there and answers 404
     * itself when that is what a missing one means. The check happens here
     * rather than while the body is written, so the failure is still inside the
     * handler's {@code try} and an {@link App#exception} handler can see it.
     *
     * <p>Validators and conditional requests are {@link StaticFiles}' business,
     * not this one's. A file a handler chose is not a static file, and only the
     * handler knows whether it can change.
     */
    public static WebResponse file(Path path) {
        Objects.requireNonNull(path, "path");
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read file: " + path, e);
        }
        if (!attributes.isRegularFile()) {
            throw new UncheckedIOException(
                    new IOException("Not a regular file: " + path));
        }
        return of(new Stream(out -> Files.copy(path, out)))
                .contentType(ContentTypes.byPath(path.getFileName().toString()))
                .header("Content-Length", Long.toString(attributes.size()));
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
        return new WebResponse(null, Headers.EMPTY, List.of(), body);
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

    /**
     * The value of a header, whatever spelling it was set under. Field names are
     * case-insensitive in HTTP, so {@code header("content-type")} answers what
     * {@link #contentType(String)} set. Null when nothing set it.
     */
    public String header(String name) {
        return headers.get(name);
    }

    /**
     * Every header set on this response, read-only and in the order they were
     * set. A response is built by replacement — {@link #header(String, String)}
     * returns a new one — so there is nothing here for a caller to alter.
     *
     * <p>The keys are case-insensitive, the way HTTP compares field names, and
     * each holds the spelling it was first set under. One field therefore has one
     * value here however many spellings set it, and a repeated header — several
     * {@code Link} lines in one answer — is not something this can carry.
     * Cookies have {@link #cookies()} of their own, and anything else that has to
     * be sent twice is written through {@link #raw(ServletWriter)}.
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

    /**
     * Sets a header, replacing whatever spelling of that name was there before.
     * The field keeps the name and the position it was first set under, so
     * {@code header("content-type", ...)} over a {@code Content-Type} changes the
     * value and nothing else.
     */
    public WebResponse header(String name, String value) {
        return new WebResponse(status, headers.with(name, value), cookies, body);
    }

    public WebResponse contentType(String contentType) {
        return header("Content-Type", contentType);
    }

    /**
     * Sets several headers at once, in that map's order. CORS and the security
     * headers each set three or four constants on every answer, and a
     * {@link #header(String, String)} apiece would copy the header map and build
     * a response for each one of them.
     */
    WebResponse withHeaders(Map<String, String> fields) {
        return carrying(headers.withAll(fields));
    }

    /** The same, for the fields this response does not already say for itself. */
    WebResponse withHeadersIfAbsent(Map<String, String> fields) {
        return carrying(headers.withAllAbsent(fields));
    }

    /**
     * This response with those headers, and this response itself when they are
     * the ones it already had. Identity is what is asked here rather than
     * equality: {@link Headers} answers with itself when a change left it as it
     * was, and that is the one case worth not building a response for.
     */
    @SuppressWarnings("ReferenceEquality")
    private WebResponse carrying(Headers updated) {
        return updated == headers ? this : new WebResponse(status, updated, cookies, body);
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
        String value = varyValue(header("Vary"), field);
        return value == null ? this : header("Vary", value);
    }

    /**
     * What {@code Vary} reads once that field is added to it, or null when it is
     * already listed. Separate from {@link #vary(String)} so that a decorator
     * setting several headers at once can put {@code Vary} among them rather
     * than spend a copy of the response on it.
     */
    static String varyValue(String existing, String field) {
        if (existing == null || existing.isBlank()) {
            return field;
        }
        for (String listed : existing.split(",", -1)) {
            if (listed.trim().equalsIgnoreCase(field)) {
                return null;
            }
        }
        return existing + ", " + field;
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
     * This response without that header, whatever its spelling — what
     * compressing a stream has to do to the {@code Content-Length} the
     * uncompressed body had worked out.
     */
    WebResponse withoutHeader(String name) {
        return headers.containsKey(name)
                ? new WebResponse(status, headers.without(name), cookies, body)
                : this;
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
        List<Cookie> mergedCookies = new ArrayList<>(base.cookies);
        mergedCookies.addAll(cookies);
        return new WebResponse(status, headers.over(base.headers), List.copyOf(mergedCookies),
                body);
    }

    /**
     * The response body is bytes and JSON is text, and the elements arrive one
     * at a time — so the encoder is buffered rather than encoding each element
     * on its own.
     */
    private static Writer textWriter(OutputStream out) {
        return new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 8192);
    }

    /** Framing for {@link #jsonArray}: the brackets, and a comma between elements. */
    private static final class ArraySink implements JsonSink {

        private final Writer out;
        private boolean started;

        ArraySink(Writer out) {
            this.out = out;
        }

        @Override
        public void write(Json.JsonValue value) {
            try {
                out.write(started ? ',' : '[');
                started = true;
                out.write(value.toJson());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** An array nothing was written to is still an array: {@code []}. */
        void close() throws IOException {
            out.write(started ? "]" : "[]");
        }
    }

    /** Framing for {@link #ndjson}: a newline after every value, including the last. */
    private static final class LinesSink implements JsonSink {

        private final Writer out;

        LinesSink(Writer out) {
            this.out = out;
        }

        @Override
        public void write(Json.JsonValue value) {
            try {
                out.write(value.toJson());
                out.write('\n');
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static Cookie defaultCookie(String name, String value) {
        Cookie cookie = new Cookie(name, value);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setAttribute("SameSite", "Lax");
        return cookie;
    }
}
