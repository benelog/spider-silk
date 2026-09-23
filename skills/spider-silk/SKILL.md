---
name: spider-silk
description: >-
  Build web applications and HTTP APIs with Spider Silk, a no-reflection Java web framework on the Jakarta Servlet API
  (group net.benelog.spidersilk, embedded Jetty by default).
  Use this skill whenever the user mentions Spider Silk or spider-silk-core, whenever a build file depends on
  net.benelog.spidersilk, and for any task in such a project even when the framework is not named:
  setting up the dependency (Maven Central), adding routes, handlers, filters, error handling, JSON endpoints,
  templates (jte, FreeMarker, Handlebars, Thymeleaf), static files, SSE, WebSocket, sessions, CORS, compression,
  tests (WebTest, TestRequest), server tuning (Jetty, Tomcat, Undertow), or deployment (Jib, Docker, GraalVM native image).
license: Apache-2.0
metadata:
  version: "1.1.0"
  homepage: https://spider-silk.benelog.net
---

# Spider Silk

A thin Java web framework on top of the Jakarta Servlet API.
Two stack frames stand between `HttpServlet.service` and a handler: `AppServlet.service` and `AppServlet.dispatch`.
Requires Java 21 or later.
Releases are published to Maven Central, so `mavenCentral()` is the only repository a build needs.
The Gradle plugin is on Central too, not on the Gradle Plugin Portal — see [First-run setup](#first-run-setup) before touching the build file.
The full manual is at <https://spider-silk.benelog.net>; the reference files beside this one are distilled from it.

**Version.** This skill writes `1.1.0` throughout, which is the release it was written against and the only version string in it.
Before putting it in a build file, prefer whatever the project already declares.
For a project starting fresh, check <https://central.sonatype.com/artifact/net.benelog.spidersilk/spider-silk-core> for the current version, since this skill's copy ages with each release.
<https://github.com/benelog/spider-silk/blob/main/CHANGELOG.md> lists what changed between releases.

## Principles the code you write must respect

Spider Silk is built around explicitness, and code that fights this reads as wrong to its users.

1. **No reflection.**
   There is no annotation scanning, no proxies, no automatic binding.
   Never introduce annotation-based routing or classpath scanning: those fight the framework itself, and there is no seam for them.
   JSON mapping is hand-written with the framework's `Json` API — that is a feature, not a gap to fill, so do not reach for a binding library unasked.
   A library the *application* adds is a different question, and the answer to it is not "no".
   When the user asks for Jackson, Gson, or avaje-jsonb, wire it through the seam that exists for exactly that: `WebResponse.rawJson(String)` on the way out, `req.bodyStream()` or `bodyReader()` on the way in.
   [references/content.md](references/content.md) has the shape.
2. **A handler is a function from a request to a response**: `WebResponse handle(WebRequest req)`.
   Handlers return a `WebResponse` value; they never write to a servlet response directly (except a deliberate `WebResponse.raw(...)`).
3. **No DI container.**
   The object graph is assembled by calling constructors directly, conventionally in one `...Context` class.
   Do not add Spring, Guice, or any container.
4. **Routes are an explicit list.**
   Every route is one registration statement; there is no `Controller` interface and no self-registering class.
   Handlers arrive as a lambda, a single-route `...Action` class implementing `Handler`, or a public method reference (`decks::showDeck`).
5. **The framework covers the web tier only.**
   Persistence, transactions, and scheduling are the application's own code: plain `NamedParameterJdbcTemplate`, a hand-rolled `Transactions` wrapper around `TransactionTemplate`, a hand-wired context class.
   The worked example is <https://github.com/benelog/spider-silk/tree/main/example-flashcard>.

## First-run setup

The artifacts resolve from Maven Central with no credentials.

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'net.benelog.spidersilk:spider-silk-core:1.1.0'
    testImplementation 'net.benelog.spidersilk:spider-silk-test:1.1.0'
}
```

The most common first-run failure is `Plugin [id: 'net.benelog.spidersilk'] was not found`: Gradle searches the Plugin Portal alone for a plugin id unless `settings.gradle` adds `mavenCentral()` to `pluginManagement.repositories`.
Modules come in four groups: core (`spider-silk-core`, `spider-silk-test`), optional extensions (Tomcat/Undertow servers, FreeMarker/Handlebars/Thymeleaf templates, Jetty WebSocket, OpenAPI), optional build conventions (Gradle plugin, Maven parent), and the example app.
Add an extension only when the user asks for what it provides.
For Maven, the module tables, exclusions, the Gradle plugin, and the Maven parent, read [references/setup.md](references/setup.md).

## Hello, world

```java
import net.benelog.spidersilk.App;
import net.benelog.spidersilk.WebResponse;

public class Main {
    public static void main(String[] args) {
        App app = new App();
        app.get("/hello/{name}", req -> WebResponse.text("Hello, " + req.pathParam("name")));
        app.start(8080);
    }
}
```

Embedded Jetty ships with core, so nothing else is needed to serve a request.
`start` returns once the port is bound and the server's threads keep the JVM alive; `stop()` shuts down gracefully.

## The core API at a glance

```java
App app = new App();    // jte over classpath:/jte, classpath:/public served at /

// Server-side rendering: template name carries no extension
app.get("/decks/{deckId}", req -> {
    long deckId = req.pathParamLong("deckId");   // non-numeric input becomes a 400
    return WebResponse.template("deck", Map.of("deck", service.deck(deckId)));
});

// JSON API: you state in code what goes out (no automatic serialization)
app.get("/api/decks", req -> WebResponse.json(
        Json.array().add(Json.object().put("id", 1L).put("name", "English"))));

app.post("/api/decks", req -> {
    String name = req.bodyJson().asObject().getString("name");   // missing key -> 400
    return WebResponse.json(Json.object().put("name", name)).status(HttpStatus.CREATED);
});

// Routes sharing a prefix: the group is an argument, not ambient state
app.path("/api/decks", group -> {
    group.beforeRoute(req -> requireApiKey(req));    // guards matched routes under /api/decks
    group.get("", api::listDecks);                   // GET  /api/decks
    group.get("/{deckId}", api::showDeck);           // GET  /api/decks/{deckId}
});

// Exception-to-response mapping, and a styled error page for any 404
app.exception(IllegalArgumentException.class,
        (req, e) -> WebResponse.text(e.getMessage()).status(HttpStatus.NOT_FOUND));
app.exception(JsonException.class,        // the more specific type wins, whatever the order
        (req, e) -> WebResponse.text(e.getMessage()).status(HttpStatus.BAD_REQUEST));
app.statusPage(HttpStatus.NOT_FOUND, req -> WebResponse.template("not-found", Map.of("path", req.path())));

// What almost every deployed app turns on (off until named)
app.cors(Cors.allowOrigin("https://app.example.com").forPath("/api/*"))
   .gzip()
   .securityHeaders();

app.start(8080);
```

Key packages: `net.benelog.spidersilk` (App, WebRequest, WebResponse, HttpStatus, Handler, HttpException), `net.benelog.spidersilk.json` (Json, JsonWriter, JsonReader, JsonCodec), `net.benelog.spidersilk.server` (JettyServer, WebServer).

## Contracts that hold everywhere

- Nullness is in the signatures: every published package is JSpecify `@NullMarked`, so only a `@Nullable` parameter or return takes or answers null (`paramOrNull`, `header`, `cookie`, `session().get`, `getObjectOrNull`, a `BeforeFilter` result). Everything else is non-null, and a default passed to `param(name, default)` or `getString(key, default)` is never null. The names follow one rule: the plain name requires the value, a default as the last argument makes it optional, and `OrNull` answers null.
- Typed extraction fails as a 400, not a null: `pathParamLong`, `paramLong`, `paramEnum`, `bodyJson(reader)`, `file(name)` all answer the request with 400 on bad or missing input, so handlers have no null branches to write.
- A type with no named form takes a parser: `req.param("since", LocalDate::parse)`, `req.param("page", Integer::parseInt, 1)`, `req.pathParam("deckId", UUID::fromString)`. A parser that throws `IllegalArgumentException` or `DateTimeException` answers 400 naming the parameter. Do not write `Long.parseLong(req.param(...))` by hand: that is a 500 on bad input.
- One source only, with a parser: `req.queryParam("page", Integer::parseInt, 1)`, `req.formParam("due", LocalDate::parse)`. Same contract as `param(name, parser)`; a value in the other source does not count. The one-argument `queryParam(name)` / `formParam(name)` require a value; `queryParamOrNull(name)` / `formParamOrNull(name)` answer null when absent.
- `param(name, default)` forms cover absence only; a present-but-unparseable value is still a 400. An optional string with no default is `req.paramOrNull(name)` — `param(name, null)` does not compile.
- `req.body()` reads the text once and keeps it: a before-filter that reads it (a signature check) leaves it for the handler's `bodyJson()`. `bodyStream()`, `bodyReader()`, and `bodyNdjson()` hand the body over unread instead, and mixing the two throws `IllegalStateException` whichever comes second. A form POST is still spent by its first `param()` read.
- Ordinary reads about the request have a method, so do not reach through `raw()` for them: `isSecure()`, `scheme()`, `host()`, `remoteAddress()`, `contentType()`, `queryString()`, `headers(name)`, `headers()`. `raw()` is for an async context, a client certificate, or a container-specific attribute.
- A large upload never becomes a `byte[]`: `req.file(name).writeTo(path)` writes it to disk and `inputStream()` hands it to a parser. An optional upload is `req.fileOrNull(name)` (null when absent, including a file input left empty), and a field carrying several files is `req.files(name)`.
- `WebResponse` is immutable: every builder method returns a new value, so chains work and filters can rewrite responses. `cookie(Cookie)` keeps a copy and `cookies()` hands out copies; a template model is copied into a read-only map (null values kept). A `bytes(...)` array and a stream writer are handed over, not copied.
- Response header names compare without regard to case, and one field holds one value: `res.header("content-type")` reads what `.contentType(...)` set, and setting it again under another spelling replaces the value in place. A header that has to be sent twice (two `Link` lines) is not something `headers()` can carry — write it through `WebResponse.raw`; cookies have `cookie(...)` / `cookies()` of their own.
- Statuses are `HttpStatus` constants, never raw ints; `HttpStatus.of(int)` when the number arrives at runtime.
- A header every response must carry (a request id) is `app.responseFilter((req, res) -> ...)`, not `app.afterRoute(...)`: an after-filter never sees a before-filter's answer, an exception handler's or status page's response, a 404/405, or a static file. Neither one authorizes; guards stay before-filters.
- `beforeRequest(filter)` or `beforeRequest(path, filter)` runs before routing and guards static files and missing routes too. `beforeRoute` runs only for matched routes and exposes their path variables. `afterRoute` and `responseFilter` must return a response; return the one passed in to keep it, never null.
- `body(replacement)` preserves headers. Update or remove old `Content-Length`, `ETag`, `Last-Modified`, and `Content-Encoding` with `withoutHeader(name)` when replacing content.
- `requestLogger((req, completion) -> ...)` reports `RequestCompletion`: `statusCode()` is the servlet status, `took()` a Duration, `response()` the response definition, `failure()`/`failed()` record decoration or writing failure independently of status, and `exception()`/`threw()` report what the handler threw (null for an `HttpException`). Record failures on a span or in an error list from there, never from a catch-all `exception(...)` handler.
- A before-filter returning `null` continues to the route; returning a response ends the request; `throw new HttpException(status, msg)` rejects and lets `statusPage(status, ...)` render the body. An `HttpException` passes `exception(RuntimeException.class, ...)` and `exception(Exception.class, ...)` by; only a handler for `HttpException` or a subtype catches it.
- Registering a second route that matches the same requests throws `IllegalStateException` at registration.
- Register everything before the app is served. Registration closes while an `AppServlet` serves the app — `app.start`, `new JettyServer(app).start()`, or an external container — and `stop()` reopens it. `app.cors/gzip/securityHeaders/staticFiles(value)` copy the value, so changing it afterwards does nothing. An external container maps `AppServlet` with load-on-startup (`holder.setInitOrder(0)`, `<load-on-startup>0</load-on-startup>`).
- `redirect(location)` is 302; say `HttpStatus.SEE_OTHER` (303) after a POST.
- Template names carry no extension (`template("deck")` renders `deck.jte`).
- Sessions are on by default; `req.flash(key, value)` + `req.flashed(key)` is the Post/Redirect/Get message pattern.
- Read a session attribute under its type: `req.session().get("user", User.class)` fails on that line with the key and both types named, where `req.session().get("user")` fails on the assignment. Writing is `req.session().set("user", user)`, a name of its own. Logging out is `req.session().invalidate()`, not `raw().getSession(false).invalidate()`.
- `app.routes()` returns every registered route as data (`Route(method, path, description)`); build introspection pages and OpenAPI export on it. `req.route()` is the entry that answered the current request (null before routing and for a static file, 404, 405, or OPTIONS): name a tracing span or a metric by `req.route().path()`, never by re-matching `req.path()` against the list.
- A JSON object whose keys are data rather than schema reads with for-each — `for (var member : json.asObject())` hands over a `Map.Entry` per member in document order — with `keys()` and `size()` beside it. `getObjectOrNull`/`getArrayOrNull` answer `null` for an absent container, and `isString()`/`isNumber()`/`isBoolean()` tell a primitive's type without a try/catch.
- An answer too large to hold in memory is `WebResponse.jsonArray(sink -> ...)` or `WebResponse.ndjson(sink -> ...)`, written a value at a time, and `req.bodyNdjson(reader)` reads a large body back lazily — never build a hundred thousand rows into one tree. See [references/content.md](references/content.md).

## Testing

```java
// End to end: starts the app on a free port, client keeps cookies, stops it after
WebTest.test(app, client -> {
    var created = client.postForm("/decks", Map.of("name", "English"));
    assertThat(created.statusCode()).isEqualTo(302);      // redirects are not followed
    assertThat(client.get("/api/decks").body()).contains("English");
});

// A handler alone: no port, no container, no mocks
WebResponse response = controller.createDeck(
        TestRequest.post("/api/decks").jsonBody(Json.object().put("name", "Spanish")).build());
assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
```

Both live in `spider-silk-test` (`net.benelog.spidersilk.test`), test scope only.
Details in [references/testing.md](references/testing.md).

## Verify a change before calling it done

The API is typed all the way through — `WebResponse` from every branch, `HttpStatus` rather than an int, a declared type on every extraction — so the compiler catches most mistakes if you let it:

```bash
./gradlew build     # or: mvn verify
```

`WebTest` needs no port, no container, and no mock library, so covering a new route is cheap enough that there is no reason to assert by hand that it works — add the test (see [references/testing.md](references/testing.md)).
To watch it serve a real request, `app.start(8080)` and `curl` it.
`app.routes()` prints the table the router actually walks, which settles "is my route registered, and at what path?" without guessing.

## When something fails

| Symptom | Cause |
|---|---|
| `Plugin [id: 'net.benelog.spidersilk'] was not found` | The plugin is on Maven Central, not the Plugin Portal. Add `mavenCentral()` to `pluginManagement.repositories` in `settings.gradle` — see [First-run setup](#first-run-setup) |
| `Could not resolve net.benelog.spidersilk:...` | `mavenCentral()` is missing from `repositories`, or the version was never released. Check the version on Central |
| `IllegalStateException` at startup, naming a path | Two routes match exactly the same requests (the same path, or the same shape with a variable renamed). Registration is the check, because one of them could never run |
| A template is not found | The name carried an extension. `template("deck")`, never `template("deck.jte")` — the engine appends its own suffix |
| A 400 where you expected a null | Typed extraction rejects rather than returning null. Use `paramOrNull(name)`, `queryParamOrNull(name)`, `formParamOrNull(name)`, or a `(name, default)` form for a parameter that may be absent; a present-but-unparseable value is still a 400 |
| `req.session().get("user", user)` does not compile | The write is `req.session().set("user", user)`; `get` only reads. Removing is `req.session().remove(key)` |
| `req.param("q", null)` does not compile | `null` matches both the default and the parser overloads. Say `req.paramOrNull("q")` |
| `IllegalStateException` adding a route, filter, or setting | The app is being served. Register before `start()` (or before the container initializes `AppServlet`), or `stop()` first |
| `IllegalStateException` from `bodyStream()`, `bodyReader()`, or `body()` | The body was already taken the other way: text (`body()`/`bodyJson()`) and unread (`bodyStream()`/`bodyReader()`/`bodyNdjson()`) do not mix. Pick one per request |
| A header set in `afterRoute(...)` is missing on 404s, error pages, redirects from a before-filter, or static files | An after-filter sees only a route that returned normally. Use `app.responseFilter(...)` for a header every response carries |
| A `{name}` route swallows a literal one | Registration order breaks ties, so register `/study/today` before `/study/{mode}` |
| A before-filter never runs for paths under its own | Use `beforeRequest` for static files and unmatched routes. A filter path needs the trailing `*` to cover what is under it: `/admin/*`, not `/admin` |
| A wildcard route's handler cannot see what came after the prefix | A bare `*` captures nothing. Register `/files/{path*}` and read the remainder with `req.pathParam("path")` |
| An after-filter's change is lost | `WebResponse` is immutable. The filter has to *return* the new response; calling a builder method and dropping the result changes nothing |
| A streamed response answers 200 and then fails | Once the headers are committed the status cannot change. `RequestCompletion.writeFailure()` still reports the write failure. Whatever can fail in a way the client should hear about belongs before the response is returned |
| `WebResponse.file(path)` throws where a 404 was expected | A file a handler chose is not a static file, so a missing one is not automatically a 404. Check `Files.isRegularFile` and throw `HttpException(HttpStatus.NOT_FOUND, ...)` when that is what missing means |
| Reflection errors under a native image | The framework needs no configuration; a library the application added does. Precompile jte templates, and generate metadata for the reflective library |

## Where to look next

| Task | Read |
|---|---|
| Dependencies, Maven, modules and exclusions, Gradle plugin, Maven parent | [references/setup.md](references/setup.md) |
| Routing, handlers, request/response API, filters, errors, sessions, CORS/gzip/security headers, logging, introspection | [references/web-api.md](references/web-api.md) |
| JSON (including large answers, NDJSON, and binding with another library), templates (jte/FreeMarker/Handlebars/Thymeleaf), static files, SSE, WebSocket, OpenAPI export | [references/content.md](references/content.md) |
| Server tuning, virtual threads, Tomcat/Undertow, external containers, Jib/Docker images, native image | [references/servers-and-deployment.md](references/servers-and-deployment.md) |
| WebTest and TestRequest in full | [references/testing.md](references/testing.md) |

For anything deeper — design rationale, edge cases, the exact semantics of a header — the manual at <https://spider-silk.benelog.net> is the source of truth.
