# The web API: routing, request, response, filters, errors

Contents: [Routing](#routing) · [Handlers](#handler-shapes) · [Request](#webrequest) · [Response](#webresponse) · [Filters and errors](#filters-and-errors) · [Sessions and flash](#sessions-and-flash) · [CORS, gzip, security headers](#response-wide-concerns) · [Request logging](#request-logging) · [Introspection](#route-introspection)

## Routing

Routes register on `App` (or a group) as one statement: `get`, `post`, `put`, `patch`, `delete`, `head`, `options`, each also as `(path, description, handler)`.

```java
app.get("/decks", decks::list);                           // this path, exactly
app.get("/decks/{deckId}", decks::show);                  // {deckId} matches one segment, never a slash
app.get("/files/{path*}", files::serve);                  // {path*} matches the rest: req.pathParam("path")
app.beforeRoute("/admin/*", req -> requireAdmin(req));    // matched routes under /admin
app.get("/api/decks", "List every deck", api::listDecks);   // description, kept as data
```

- Patterns compare segment by segment; there are no regular expressions.
- A trailing `*` (only allowed last) matches the rest including nothing, so `/admin/*` covers `/admin` itself, and it captures nothing.
- A trailing `{name*}` matches the same paths and binds them: `req.pathParam("path")` is `docs/a.txt` under `/files/docs/a.txt`, and `""` under `/files`. `/files/*` and `/files/{path*}` are the same shape, so only one of them registers.
- A trailing slash on the request is ignored.
- Registration order breaks ties, so register a literal route (`/study/today`) before a variable one (`/study/{mode}`).
- A second route matching the same requests (same path, or same shape with a variable renamed) throws `IllegalStateException` at registration.
- No route: 404; wrong method: 405 with an `Allow` header; for GET/HEAD, static files are consulted before the 404.
- HEAD and OPTIONS are answered automatically (HEAD runs the GET and drops the body; OPTIONS answers with `Allow`); register `app.head`/`app.options` only to override, e.g. a CORS preflight.

Groups share a prefix; the group is a lambda argument, not ambient state, and prefixes resolve at registration:

```java
app.path("/api/decks", decks -> {
    decks.beforeRoute(req -> requireApiKey(req));    // guards matched routes in the group
    decks.get("", api::listDecks);                   // GET /api/decks ("" or "/" is the prefix itself)
    decks.get("/{deckId}", api::showDeck);
    decks.path("/{deckId}/cards", cards -> cards.get("", api::listCards));  // groups nest
});
```

## Handler shapes

`Handler` has one method, so all three shapes below are idiomatic; pick per route:

```java
app.get("/ping", req -> WebResponse.text("pong"));          // lambda: no state worth a class

public class StatsAction implements Handler {               // one class, one route ("Action")
    private final StatsService stats;
    public StatsAction(StatsService stats) { this.stats = stats; }
    @Override public WebResponse handle(WebRequest req) {
        return WebResponse.template("stats", Map.of("stats", stats.overview()));
    }
}
app.get("/stats", context.statsAction());

public class DeckController {                               // one class, several routes
    public WebResponse showDeck(WebRequest req) { ... }     // public: registered from outside
    public WebResponse renameDeck(WebRequest req) { ... }
}
app.get("/decks/{deckId}", decks::showDeck);
app.post("/decks/{deckId}/rename", decks::renameDeck);
```

Never invent a `Controller` interface or a `register(App)` method: the whole routing table stays one visible list.

## WebRequest

```java
req.method(); req.path(); req.header(name);                 // header lookup ignores case
List<String> tags = req.headers("X-Tag");                   // every value of a repeated header
Map<String, List<String>> all = req.headers();              // every header, all values
req.contentType(); req.queryString();                       // null when the request carried none
req.isSecure(); req.scheme(); req.host(); req.remoteAddress();
// scheme() + "://" + host() + path() = the absolute URL; host() carries a non-default port
req.raw();                                                  // the servlet request; escape hatch only

long id = req.pathParamLong("deckId");                      // non-numeric -> 400
String name = req.pathParam("name");
Direction d = req.pathParamEnum("direction", Direction.class);

String q = req.param("q");                                  // missing -> 400
String search = req.paramOrNull("q");                       // missing -> null; param(name, null) does not compile
long page = req.paramLong("page", 1);                       // default covers absence, not garbage
boolean archived = req.paramBoolean("archived", false);     // "true"/"false" only
LocalDate since = req.param("since", LocalDate::parse);     // any other type: a parser
UUID owner = req.param("owner", UUID::fromString);          // 400 on IllegalArgumentException
int page = req.param("page", Integer::parseInt, 1);         //   or DateTimeException
int deckId = req.pathParam("deckId", Integer::parseInt);    // path variables take one too; "yes" -> 400
List<String> tags = req.params("tag");                      // repeated values; empty list when absent

String p = req.queryParamOrNull("page");                    // query string only, null when absent
String n = req.formParam("name");                           // form body only, missing -> 400
List<String> t = req.formParams("tag");
LocalDate due = req.formParam("due", LocalDate::parse);     // one source, a parser: absent -> 400
int pg = req.queryParam("page", Integer::parseInt, 1);      // ...or the default; the other source never counts

String theme = req.cookie("theme");                         // null when absent
Map<String, String> all = req.cookies();

String body = req.body();                                   // read once, kept: a filter's read leaves it for the handler; over 1MB -> 413
JsonValue json = req.bodyJson();                       // unparseable -> 400
NewDeck deck = req.bodyJson(NEW_DECK_READER);               // reader rejection -> 400
Stream<Card> cards = req.bodyNdjson(CARD_READER);           // lazy; a bad line -> 400 naming it, a line over 1MB -> 413
InputStream in = req.bodyStream();                          // unread bytes, for another library's parser; no size limit
BufferedReader r = req.bodyReader();                        // unread characters; never mixed with body() or each other
UploadedFile file = req.file("file");                       // missing part or not multipart -> 400
UploadedFile avatar = req.fileOrNull("avatar");             // optional upload; null when absent
List<UploadedFile> pages = req.files("pages");              // one field, several files; empty when none
// UploadedFile: fileName(), contentType(), size(), bytes(), asText(),
//               inputStream(), writeTo(path)               // the last two hold nothing in memory
```

Content negotiation reads `Accept` for you and answers one of the offered strings (406 when the caller takes none, first candidate when no `Accept` was sent), adding `Vary: Accept` automatically:

```java
app.get("/decks", req -> switch (req.accepts("text/html", "application/json")) {
    case "application/json" -> WebResponse.json(deckService.decks(), Codecs::writeDecks);
    default -> WebResponse.template("decks", Map.of("decks", deckService.decks()));
});
```

## WebResponse

Immutable value: every method returns a new response, so chains compose and after-filters can rewrite.
Cookies are copied on the way in and on the way out of `cookies()`, and a template model is copied into a read-only map (nulls kept); `bytes` arrays and stream writers are handed over, not copied.

```java
// Bodies
WebResponse.html(page); WebResponse.text(s); WebResponse.bytes("application/pdf", pdf);
WebResponse.rawJson(text); WebResponse.json(jsonValue); WebResponse.json(value, writer);   // rawJson: text that is JSON already
WebResponse.jsonArray(sink -> ...); WebResponse.ndjson(sink -> ...);   // written a value at a time, see content.md
WebResponse.template("deck");                       // name carries no extension
WebResponse.template("deck", Map.of("deck", deck));
WebResponse.stream("text/csv", out -> exporter.write(out));
WebResponse.file(path);                       // type from the name, length from the file
                                              //   not a readable file -> UncheckedIOException,
                                              //   never a 404: that is the handler's line
WebResponse.sse(stream -> ...);                     // see content.md
WebResponse.raw((servletReq, servletRes) -> ...);   // full servlet control, rare

// Statuses and redirects
WebResponse.empty(); WebResponse.empty(HttpStatus.FORBIDDEN); WebResponse.noContent();
WebResponse.redirect("/decks/" + id);                       // 302
WebResponse.redirect("/decks", HttpStatus.SEE_OTHER);       // 303, after a POST
WebResponse.redirect("/new-home", HttpStatus.MOVED_PERMANENTLY);   // non-3xx status rejected

// Building on
response.status(HttpStatus.CREATED).header("Location", "/api/decks/1")
        .contentType("text/plain").vary("Accept-Language").attachment("export.csv")
        .cookie("theme", "dark")                            // Path=/, HttpOnly, SameSite=Lax
        .cookie("token", value, Duration.ofDays(7))
        .removeCookie("stale");
// cookie(jakarta.servlet.http.Cookie) for Secure, Domain, SameSite=None

// Reading back (assertions, filters); cookies() hands out copies
response.status(); response.header(name); response.headers(); response.cookies(); response.body();
response.header("content-type");            // names compare without regard to case
```

`body(replacement)` preserves headers.
Remove or update old body metadata (`Content-Length`, `ETag`, `Last-Modified`, `Content-Encoding`) with `withoutHeader(name)` when the content changes.
Set the new content type explicitly when needed.

Header names are case-insensitive both ways: `header("content-type", ...)` over a `Content-Type` replaces the value and keeps the first name and its position, so `headers()` is one value per field, in the order they were set.
A header that has to be sent more than once — two `Link` lines in one answer — is out of scope: cookies have `cookie(...)` / `cookies()`, and everything else repeated is written through `WebResponse.raw((req, res) -> res.addHeader(...))`.

`body()` is a sealed `WebResponse.Body` (`Empty`, `Text`, `Bytes`, `Template`, `Streamed`, `Sse`, `Raw`), so a `switch` needs no default and tests assert without a servlet response.

## Filters and errors

```java
app.beforeRoute("/admin/*", req -> req.session().get("user") == null
        ? WebResponse.redirect("/login")    // answers here; the route never runs
        : null);                            // null = carry on

app.afterRoute("/api/*", (req, res) -> res.header("Cache-Control", "no-store"));   // a route that completed; return res to leave alone

app.responseFilter((req, res) -> res.header("X-Request-Id", requestId()));  // every answer: early, error, 404, static file

app.exception(NoSuchDeckException.class,
        (req, e) -> WebResponse.text(e.getMessage()).status(HttpStatus.NOT_FOUND));
app.exception(JsonException.class,       // most specific type wins, whatever the order
        (req, e) -> WebResponse.text(e.getMessage()).status(HttpStatus.BAD_REQUEST));

app.statusPage(HttpStatus.NOT_FOUND, req -> WebResponse.template("not-found", Map.of("path", req.path())));
```

- `afterRoute` sees only a route that returned normally; `responseFilter` sees every response (before-filter answers, exception answers, 404/405, OPTIONS, static files), runs before CORS/security headers/gzip, and a filter that throws goes to `exception`/`error` without the filters running again. Authorization uses `beforeRequest` for every matching request, including static files and missing routes, or `beforeRoute` for a matched route with path variables. `afterRoute` and `responseFilter` reject null results as programming errors.
- `beforeRequest(filter)` / `beforeRequest(path, filter)` runs before routing, including automatic OPTIONS, and has no path variables. Return null to continue or a response to stop. Its errors and early responses use the normal response pipeline. Route groups support the same two forms.
- `exception(Type, handler)` runs the handler for the most specific registered type the exception is an instance of, in any registration order.
- `JsonException` is an `IllegalArgumentException`, so map it separately when `IllegalArgumentException` means 404.
- Register everything before `app.start(...)`: a route, filter, or setting added while an `AppServlet` serves the app (embedded, a server started directly, or an external container) throws `IllegalStateException`; `stop()` reopens it.
- `app.cors(...)`, `app.gzip(...)`, `app.securityHeaders(...)`, `app.staticFiles(...)`, and `app.bodyLimits(...)` copy the value they are given, so changing it afterwards does nothing.
- `throw new HttpException(HttpStatus.UNAUTHORIZED, "...")` rejects from anywhere and lets `statusPage(status, ...)` render the body. It passes a handler for a broader type (`RuntimeException`, `Exception`) by; only `exception(HttpException.class, ...)` or a handler for a subtype catches it.
- `statusPage(status, handler)` fills the body for any response that ended on that status with no body (router 404s, `HttpException`, `WebResponse.empty(status)`); a response that already carries a body is left alone.
- Inside a status page's handler, `req.errorMessage()` is the plain-text message the framework would have used.

## Sessions and flash

```java
req.session().set("user", user);                    // writing creates the session on demand
User user = req.session().get("user", User.class);  // reading never creates one; null when absent
                                                    //   wrong type -> IllegalStateException here (500)
User same = req.session().get("user");              // caller's cast; wrong type fails on the assignment
req.session().remove("user");                       // session().set(key, null) does the same
req.session().invalidate();                         // logging out; no session = nothing to do

// Post/Redirect/Get: a flash value is visible exactly once, on the request after the redirect
req.flash("message", "Imported 12 cards.");
return WebResponse.redirect("/decks/" + id, HttpStatus.SEE_OTHER);
// ...then in the GET handler:
String message = req.flashed("message");    // null for a key nobody flashed
```

Sessions are the servlet container's, on by default (`JettyServer` can turn them off with `sessions(false)`; Tomcat and Undertow cannot).

## Response-wide concerns

CORS, compression, and security headers are named on `App`, not registered as filters, because they must also reach answers no route-scoped filter sees (preflights, 404s, static files, error pages).
They run after `responseFilter`, so a response filter cannot undo them, and each value is copied when registered.
Nothing is on until it is named.

```java
app.cors(Cors.allowOrigin("https://app.example.com", "https://admin.example.com")
        .forPath("/api/*")                  // default "/*"
        .allowCredentials()                 // incompatible with anyOrigin(): throws
        .exposeHeaders("X-Total-Count")
        .maxAge(Duration.ofHours(1)));
// Cors.anyOrigin() answers *, right for a public read-only API
// Preflights are answered automatically, methods taken from the routing table

app.gzip();                                 // or app.gzip(Gzip.defaults().minBytes(4096).types("text/", "application/json"))
// SSE and raw are never compressed; streams compress on the fly; Vary: Accept-Encoding handled

app.securityHeaders();                      // nosniff, X-Frame-Options: DENY, Referrer-Policy
app.securityHeaders(SecurityHeaders.defaults()
        .frameOptions("SAMEORIGIN")
        .hsts(Duration.ofDays(365))         // only sent over HTTPS; start short
        .contentSecurityPolicy("default-src 'self'")
        .permissionsPolicy("camera=()")
        .header("X-Robots-Tag", "noindex"));
// A response that set one of these headers itself keeps its own value

app.bodyLimits(BodyLimits.defaults()        // on without the call: 1MB body, 1MB per NDJSON line
        .maxBytes(8 * 1024 * 1024)          // body() and bodyJson(), in bytes; over it -> 413
        .maxNdjsonLineBytes(64 * 1024));    // one bodyNdjson line; the number of lines is free
// BodyLimits.unlimited() lifts both; bodyStream()/bodyReader(), forms, and multipart are never counted
```

CORS is a browser rule about what a script may read; authentication stays a before-filter's job.

## Request logging

```java
app.requestLogger((req, completion) -> logger.info("{} {} -> {} ({}ms)",
        req.method(), req.path(), completion.statusCode(), completion.took().toMillis()));
```

`RequestCompletion.statusCode()` reports the servlet status after writing.
`response()` is the response definition and may differ from a raw writer's output.
`took()` is the elapsed Duration, and `writeFailure()` / `writeFailed()` record an exception during decoration or writing independently of status.
A write failure can leave a 200 after commitment or cause a 500 before commitment.
`thrown()` / `threw()` report what a handler, a filter, or a template threw, whether an exception handler answered it or the framework's 500 did; null for an `HttpException`, which is a status rather than a failure.
Read it with `statusCode()`: a 400 is the caller's mistake, a 500 the application's.
`req.route()` there is the route that answered (null for a static file, 404, 405, OPTIONS), so a metric or a span groups by `req.route().path()`.
`req.body()` there answers the text a filter or handler already read.
Core carries no logging framework — which logger and format is the application's call.

## Route introspection

`app.routes()` is an immutable snapshot of `record Route(String method, String path, String description)`, in registration order, with group prefixes resolved; `{name}` segments are OpenAPI path-template syntax verbatim.
`app.hooks()` lists filters, status pages, and response filters the same way, as a sealed `Hook` (`BeforeRequest(path)`, `BeforeRoute(path)`, `AfterRoute(path)`, `StatusPage(status)`, `EveryResponse()`); an exhaustive `switch` needs all five cases.
A route registered without a description reports `""`, not null.
The automatic HEAD/OPTIONS answers and `exception(...)` handlers are not listed.
`req.route()` reports the entry that answered the current request, set from `beforeRoute` through the request logger and null before routing or where nothing matched; a HEAD answered by a GET route reports the GET route.
Build on the list directly (a `/_routes` page, audits) or hand it to `spider-silk-openapi` — see content.md.
