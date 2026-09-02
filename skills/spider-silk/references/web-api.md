# The web API: routing, request, response, filters, errors

Contents: [Routing](#routing) · [Handlers](#handler-shapes) · [Request](#webrequest) · [Response](#webresponse) · [Filters and errors](#filters-and-errors) · [Sessions and flash](#sessions-and-flash) · [CORS, gzip, security headers](#response-wide-concerns) · [Request logging](#request-logging) · [Introspection](#route-introspection)

## Routing

Routes register on `App` (or a group) as one statement: `get`, `post`, `put`, `patch`, `delete`, `head`, `options`, each also as `(path, description, handler)`.

```java
app.get("/decks", decks::list);                     // this path, exactly
app.get("/decks/{deckId}", decks::show);            // {deckId} matches one segment, never a slash
app.before("/admin/*", req -> requireAdmin(req));   // /admin and everything under it
app.get("/api/decks", "List every deck", api::listDecks);   // description, kept as data
```

- Patterns compare segment by segment; there are no regular expressions.
- A trailing `*` (only allowed last) matches the rest including nothing, so `/admin/*` covers `/admin` itself; the remainder is not captured — read `req.path()`.
- A trailing slash on the request is ignored.
- Registration order breaks ties, so register a literal route (`/study/today`) before a variable one (`/study/{mode}`).
- A second route matching the same requests (same path, or same shape with a variable renamed) throws `IllegalStateException` at registration.
- No route: 404; wrong method: 405 with an `Allow` header; for GET/HEAD, static files are consulted before the 404.
- HEAD and OPTIONS are answered automatically (HEAD runs the GET and drops the body; OPTIONS answers with `Allow`); register `app.head`/`app.options` only to override, e.g. a CORS preflight.

Groups share a prefix; the group is a lambda argument, not ambient state, and prefixes resolve at registration:

```java
app.path("/api/decks", decks -> {
    decks.before(req -> requireApiKey(req));    // guards the whole group
    decks.get("", api::listDecks);              // GET /api/decks ("" or "/" is the prefix itself)
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
req.method(); req.path(); req.header(name); req.raw();      // raw() = the servlet request

long id = req.pathParamLong("deckId");                      // non-numeric -> 400
String name = req.pathParam("name");
Direction d = req.pathParamEnum("direction", Direction.class);

String q = req.param("q");                                  // missing -> 400
long page = req.paramLong("page", 1);                       // default covers absence, not garbage
boolean archived = req.paramBoolean("archived", false);     // "true"/"false" only; "yes" -> 400
List<String> tags = req.params("tag");                      // repeated values; empty list when absent

String p = req.queryParam("page");                          // query string only, null when absent
String n = req.formParam("name");                           // form body only
List<String> t = req.formParams("tag");

String theme = req.cookie("theme");                         // null when absent
Map<String, String> all = req.cookies();

String body = req.body();
Json.JsonValue json = req.bodyJson();                       // unparseable -> 400
NewDeck deck = req.bodyJson(NEW_DECK_READER);               // reader rejection -> 400
Stream<Card> cards = req.bodyNdjson(CARD_READER);           // lazy; a bad line -> 400 naming it
InputStream in = req.bodyStream();                          // unread bytes, for another library's parser
BufferedReader r = req.bodyReader();                        // unread characters; bytes or characters, never both
UploadedFile file = req.file("file");                       // missing part or not multipart -> 400
// UploadedFile: fileName(), contentType(), size(), bytes(), asText()
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

```java
// Bodies
WebResponse.html(page); WebResponse.text(s); WebResponse.bytes("application/pdf", pdf);
WebResponse.json(rawString); WebResponse.json(jsonValue); WebResponse.json(value, writer);
WebResponse.jsonArray(sink -> ...); WebResponse.ndjson(sink -> ...);   // written a value at a time, see content.md
WebResponse.template("deck");                       // name carries no extension
WebResponse.template("deck", Map.of("deck", deck));
WebResponse.stream("text/csv", out -> exporter.write(out));
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

// Reading back (assertions, after-filters)
response.status(); response.header(name); response.headers(); response.cookies(); response.body();
```

`body()` is a sealed `WebResponse.Body` (`Empty`, `Text`, `Bytes`, `Template`, `Stream`, `Sse`, `Raw`), so a `switch` needs no default and tests assert without a servlet response.

## Filters and errors

```java
app.before("/admin/*", req -> req.sessionAttr("user") == null
        ? WebResponse.redirect("/login")    // answers here; the route never runs
        : null);                            // null = carry on

app.after((req, res) -> res.header("X-Request-Id", requestId()));   // null = leave alone

app.exception(NoSuchDeckException.class,
        (req, e) -> WebResponse.text(e.getMessage()).status(HttpStatus.NOT_FOUND));
app.exception(Json.JsonException.class,       // most specific type wins, whatever the order
        (req, e) -> WebResponse.text(e.getMessage()).status(HttpStatus.BAD_REQUEST));

app.error(HttpStatus.NOT_FOUND, req -> WebResponse.template("not-found", Map.of("path", req.path())));
```

- `exception(Type, handler)` runs the handler for the most specific registered type the exception is an instance of, in any registration order.
- `Json.JsonException` is an `IllegalArgumentException`, so map it separately when `IllegalArgumentException` means 404.
- Register everything before `app.start(...)`: a route, filter, or setting added to a running app throws `IllegalStateException`.
- `throw new HttpException(HttpStatus.UNAUTHORIZED, "...")` rejects from anywhere and lets `error(status, ...)` render the body.
- `error(status, handler)` fills the body for any response that ended on that status with no body (router 404s, `HttpException`, `WebResponse.empty(status)`); a response that already carries a body is left alone.
- Inside an error handler, `req.errorMessage()` is the plain-text message the framework would have used.

## Sessions and flash

```java
req.sessionAttr("user", user);          // writing creates the session on demand
User user = req.sessionAttr("user");    // reading never creates one; null when absent
req.removeSessionAttr("user");

// Post/Redirect/Get: a flash value is visible exactly once, on the request after the redirect
req.flash("message", "Imported 12 cards.");
return WebResponse.redirect("/decks/" + id, HttpStatus.SEE_OTHER);
// ...then in the GET handler:
String message = req.flashed("message");    // null for a key nobody flashed
```

Sessions are the servlet container's, on by default (`JettyServer` can turn them off with `sessions(false)`; Tomcat and Undertow cannot).

## Response-wide concerns

CORS, compression, and security headers are named on `App`, not registered as filters, because they must also reach answers no filter sees (preflights, 404s, static files, error pages).
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
```

CORS is a browser rule about what a script may read; authentication stays a before-filter's job.

## Request logging

```java
app.requestLogger((req, res, millis) -> logger.info("{} {} -> {} ({}ms)",
        req.method(), req.path(), res.status().code(), millis));
```

Runs once per request after the response is complete; the status it sees is the one actually sent.
Core carries no logging framework — which logger and format is the application's call.

## Route introspection

`app.routes()` is an immutable snapshot of `record Route(String method, String path, String description)`, in registration order, with group prefixes resolved; `{name}` segments are OpenAPI path-template syntax verbatim.
`app.guards()` lists filters and error handlers the same way, as a sealed `Guard` (`Before(path)`, `After(path)`, `Error(status)`).
A route registered without a description reports `""`, not null.
The automatic HEAD/OPTIONS answers and `exception(...)` handlers are not listed.
Build on the list directly (a `/_routes` page, audits) or hand it to `spider-silk-openapi` — see content.md.
