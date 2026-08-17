<p align="center">
  <img src="docs/logo.svg" alt="Spider Silk" width="160">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-e2603f?style=flat-square&labelColor=2b303b" alt="Java 21">
  <img src="https://img.shields.io/badge/Jakarta%20Servlet-6.0-8a93a6?style=flat-square&labelColor=2b303b" alt="Jakarta Servlet 6.0">
  <img src="https://img.shields.io/badge/jte-3.2.4-8a93a6?style=flat-square&labelColor=2b303b" alt="jte 3.2.4">
  <img src="https://img.shields.io/badge/reflection-none-e2603f?style=flat-square&labelColor=2b303b" alt="No reflection">
</p>

# Spider Silk

A very thin web framework on top of the Jakarta Servlet API.

Two core principles:

- **No reflection.** There is no annotation scanning, no proxies, no automatic
  binding. Routes are registered as lambdas, and type conversion happens only
  through explicit methods such as `pathParamLong`. What runs is exactly what
  you see in the code, stack traces stay short, and startup is fast.
- **Better RESTful API support than raw servlets.** Per-method routing with
  path variables, typed parameter extraction, exception-to-status mapping,
  automatic 405 (Method Not Allowed) responses, and a reflection-free JSON
  builder/parser.

The template engine is [jte](https://jte.gg). jte also compiles templates to
Java code, which fits the framework's character.

## Modules

| Module | Contents | Dependencies |
|---|---|---|
| `spider-silk-core` | The framework itself | `gg.jte:jte`, embedded Jetty (`jetty-ee10-servlet`) |
| `spider-silk-test` | The `WebTest` harness, for test scope | core, and otherwise the JDK only |
| `example-flashcard` | Example: a flashcard study app | core, spring-jdbc, H2 |

## At a Glance

```java
App app = new App()
        .templates(new JteTemplates("jte"))     // classpath:/jte/*.jte
        .staticFiles("/public");                // serves classpath:/public/* statically

// Server-side rendering
app.get("/decks/{deckId}", ctx -> {
    long deckId = ctx.pathParamLong("deckId");  // non-numeric input becomes a 400
    ctx.render("deck.jte", model);
});

// JSON API — you state in code what goes out (no automatic serialization)
app.get("/api/decks", ctx -> ctx.json(
        Json.arr().add(Json.obj().put("id", 1L).put("name", "English"))));

app.post("/api/decks", ctx -> {
    String name = ctx.bodyJson().asObject().getString("name");
    ctx.status(201).json(Json.obj().put("name", name));
});

// Routes sharing a prefix — the group is an argument, not ambient state
app.path("/api/decks", decks -> {
    decks.before(ctx -> requireApiKey(ctx));    // covers /api/decks and everything under it
    decks.get("", this::listDecks);             // GET  /api/decks
    decks.get("/{deckId}", this::showDeck);     // GET  /api/decks/{deckId}
});

// Exception-to-response mapping
app.exception(IllegalArgumentException.class,
        (e, ctx) -> ctx.status(404).text(e.getMessage()));

// One place for a styled error page, whatever produced the status
app.error(404, ctx -> ctx.render("not-found.jte", Map.of("path", ctx.path())));

app.start(8080);                                // embedded Jetty, sessions on
```

`start` returns once the port is bound, and the server's threads keep the JVM
alive — `join()` is there if you want to block the main thread anyway.
`stop()` shuts it down; `port()` reports the bound port, which is how you read
back the one the OS picked for `start(0)` in a test.

### Filters and errors

`before`/`after` take an optional path. A trailing `*` covers the prefix *and*
everything under it, so `/admin/*` guards `/admin` as well as `/admin/users` —
which is what a guard almost always means:

```java
app.before("/admin/*", ctx -> {
    if (ctx.sessionAttr("user") == null) {
        ctx.redirect("/login");     // writes a response, so the route handler does not run
    }
});
```

A before-filter that writes a response ends the request there. To reject without
writing a body, `throw new HttpException(401, "...")` and let `error(401, ...)`
render it. `error(status, handler)` fills in the body for any response that
ended on that status with nothing written — from the router, from an
`HttpException`, or from a handler that called `ctx.status(...)` and returned.
A handler that already wrote a body is left alone. Inside an error handler,
`ctx.errorMessage()` is the plain-text message the framework would have used.

### JSON writers and readers

`Json.obj()` builds a tree inline, which gets repetitive once the same record is
serialized in three handlers. `JsonWriter<T>` and `JsonReader<T>` name that
mapping so it can be reused. Both have one method, so both are lambdas:

```java
static final JsonWriter<Deck> DECK = deck -> Json.obj()
        .put("id", deck.id())
        .put("name", deck.name());

static final JsonWriter<List<Deck>> DECKS = JsonWriter.list(DECK);

record NewDeck(String name) { }

static final JsonReader<NewDeck> NEW_DECK =
        json -> new NewDeck(json.asObject().getString("name"));
```

```java
app.get("/api/decks", ctx -> ctx.json(deckService.decks(), DECKS));

app.post("/api/decks", ctx -> {
    Deck deck = deckService.create(ctx.bodyJson(NEW_DECK).name());   // no key -> 400
    ctx.status(201).json(deck, DECK);
});
```

Still no reflection: the mapping is code you wrote, so a field rename changes
the wire format only if you edit it. `getString` throws
`IllegalArgumentException` on a missing key or a value of the wrong type, and
`ctx.bodyJson(reader)` turns that into a 400 — a handler gets a whole value or
none, the same contract as `pathParamLong`.

Most types only go out, which is why the two halves are separate interfaces
rather than one with an unimplementable `read`. When a type does travel both
ways, `JsonCodec<T>` is both at once — `JsonCodec.of(writer, reader)` to build
one, `JsonCodec.list(codec)` for the list form.

### Cookies and repeated parameters

```java
ctx.cookie("theme", "dark");                        // session cookie
ctx.cookie("token", value, Duration.ofDays(7));     // survives a browser restart
ctx.removeCookie("token");

String theme = ctx.cookie("theme");                 // null when absent
List<String> tags = ctx.params("tag");              // ?tag=java&tag=web, or a checkbox group
```

A cookie set through the two-argument form gets `Path=/`, `HttpOnly`, and
`SameSite=Lax` — what a cookie holding anything worth stealing should have.
`cookie(Cookie)` takes a hand-built `jakarta.servlet.http.Cookie` when you need
`Secure`, a `Domain`, or `SameSite=None`.

`params(name)` returns every value in request order, and an empty list when the
parameter is absent — "no boxes checked" is an answer, not a 400. `param(name)`
still returns the first value and still 400s when there is none.

### Query string vs. form body

The servlet API merges the two, so `param("id")` answers to an `id` in the URL
and an `id` in the form alike. When the difference matters, say which one:

```java
String page = ctx.queryParam("page");     // query string only, null when absent
String name = ctx.formParam("name");      // form body only
List<String> tags = ctx.formParams("tag");
```

### HEAD and OPTIONS

Both are answered without registering anything. A `HEAD` runs the `GET` route
and drops the body, keeping the headers — including a `Content-Length` counted
from what the `GET` would have sent. An `OPTIONS` answers with the `Allow`
header the path's routes imply, and a 404 when the path has none:

```
$ curl -X OPTIONS -i localhost:8080/decks
HTTP/1.1 200 OK
Allow: GET, POST, HEAD, OPTIONS
```

`app.head(...)` and `app.options(...)` register a route of their own when the
automatic answer is not the one you want — a CORS preflight, usually.

### Request logging

```java
app.requestLogger((ctx, millis) -> logger.info("{} {} -> {} ({}ms)",
        ctx.method(), ctx.path(), ctx.res().getStatus(), millis));
```

One lambda, and no logging framework in core: which logger, at which level, and
in which format is the application's call. It runs once per request after the
response is complete, so the status it sees is the one that was actually sent —
the error handler's, if one ran. A logger that throws is reported to the servlet
log and leaves the response alone.

### Static files

`staticFiles("/public")` serves `classpath:/public/*` at the root. Every
response carries an `ETag` and `Last-Modified` derived from the resource, so a
reload comes back as a bodyless 304 instead of the file again. The default
`Cache-Control: no-cache` means "cache it, but check with me first" — right for
names that never change. For fingerprinted names, say so:

```java
app.staticFiles(new StaticFiles("/public")
        .hostedPath("/assets")              // classpath:/public/* at /assets/*
        .maxAge(Duration.ofDays(365)));     // only when the name carries a content hash
```

Routes are matched first, so a route can shadow a file. Directories are never
served.

### Testing

The harness is its own module, so the production jar carries no test code:

```groovy
testImplementation project(':spider-silk-test')
```

`WebTest` starts the app on a free port, hands you a client that keeps cookies,
and stops it again — including when the body throws:

```java
@Test
void createsADeck() {
    WebTest.test(app, client -> {
        var created = client.postForm("/decks", Map.of("name", "English"));
        assertEquals(302, created.statusCode());           // redirects are not followed
        assertTrue(client.get("/api/decks").body().contains("English"));
    });
}
```

`get`/`post`/`put`/`patch`/`delete`/`head`/`options`, plus `postForm` and
`postJson`, all return the raw `HttpResponse<String>` — assertions stay in
whatever library the project already uses. `send(builder -> ...)` is the way out
for anything else.

## The Server

Everything usually worth tuning is a method on `JettyServer`, and anything else
is reachable through customizers that run against the real Jetty objects just
before startup:

```java
new JettyServer(app)
        .port(8443)
        .host("127.0.0.1")
        .contextPath("/app")
        .sessions(false)
        .threadPool(new QueuedThreadPool(200, 8))
        .multipart(new MultipartConfigElement(tmp, 10_485_760L, 10_485_760L, 1_048_576))
        .stopTimeout(Duration.ofSeconds(20))    // longer drain for slow requests
        .shutdownHook(false)                    // something else owns the lifecycle
        .customizeHttpConfiguration(http -> http.setSendServerVersion(false))
        .customizeContext(context -> context.addFilter(MyFilter.class, "/*", null))
        .customizeServer(server -> server.setDumpBeforeStop(true))
        .start();
```

Handlers can run on virtual threads. That is a thread pool setting, not a
framework feature, so it stays two lines of Jetty's own API — platform threads
keep running the selectors, the handlers get the virtual ones:

```java
QueuedThreadPool pool = new QueuedThreadPool();
pool.setVirtualThreadsExecutor(VirtualThreads.getDefaultVirtualThreadsExecutor());

app.server((a, port) -> new JettyServer(a).port(port).threadPool(pool))
   .start(8080);
```

Worth it only if the handlers block — on I/O, on a database. A `synchronized`
block around that blocking call pins the carrier thread and takes the benefit
back.

Shutdown is graceful out of the box: a JVM shutdown hook stops the server on
Ctrl-C or SIGTERM, and `stop()` gives requests in flight five seconds to finish
before dropping them. Idle keep-alive connections do not hold that up — they are
closed as soon as the drain starts, so a stop with nothing running returns
immediately. `stopTimeout(Duration.ZERO)` turns the drain off entirely.

To keep `app.start(port)` as the entry point while still configuring the server,
or to run a different server entirely, replace the factory:

```java
app.server((a, port) -> new JettyServer(a).port(port).sessions(false))
   .start(9000);

app.server((a, port) -> new MyUndertowServer(a, port))   // implements WebServer
   .start(9000);
```

`WebServer` is four methods — `start`, `stop`, `join`, `port` — so a second
implementation is a small job.

Deploy to an external servlet container instead by skipping `start` and mapping
`AppServlet` yourself:

```java
ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
context.addServlet(new ServletHolder(new AppServlet(app)), "/*");
```

### What App provides

- Routes: `get`, `post`, `put`, `patch`, `delete`, and `path(prefix, group -> ...)`
- Filters: `before(handler)` / `before(path, handler)`, same for `after`
- Errors: `exception(Type, handler)`, `error(status, handler)`, `notFound(handler)`
- Rendering and assets: `templates(renderer)`, `staticFiles(classpathRoot)`, `staticFiles(StaticFiles)`
- Server: `start()` / `start(port)`, `stop()`, `join()`, `port()`, `server(factory)`

### What WebContext provides

- Path variables: `pathParam`, `pathParamLong`, `pathParamEnum`
- Parameters: `param` (400 when missing), `param(name, default)`, `paramLong`, `paramBoolean`, `paramEnum`, `params(name)` for repeated values
- Cookies: `cookie(name)` / `cookies()` to read, `cookie(name, value)` / `cookie(name, value, maxAge)` / `cookie(Cookie)` to set, `removeCookie(name)`
- Body: `body()`, `bodyJson()`, `bodyJson(reader)` (400 on a body the reader rejects), multipart upload via `file(name)`
- Session: `sessionAttr(key)` / `sessionAttr(key, value)` / `removeSessionAttr`
- Flash: `flash(key, value)` → read exactly once with `flashed(key)` on the request after a redirect
- Response: `status`, `header`, `redirect`, `html`, `text`, `json`, `json(value, writer)`, `bytes`, `attachment`, `render`
- Errors: `errorMessage()` inside an `error(status, handler)` handler

### The scope of "no reflection"

The principle applies to the **framework core**: routing, parameter extraction,
and JSON handling use no reflection anywhere. The example project's choice of
spring-jdbc's `DataClassRowMapper` and the transaction internals do use
reflection — that is the example's choice, and switching to repositories that
handle JDBC directly would remove even that.

## Example: Flashcard

The same features as ch07-jdbc-plus from `spring-jdbc-book` (decks, cards,
tags, spaced-repetition study, smart decks, CSV import/export, statistics)
built without Spring Boot.

- **DB**: repositories use `NamedParameterJdbcTemplate` directly. The executed
  SQL is visible in the code.
- **DI**: no container — `FlashcardContext` wires everything by calling
  constructors directly, playing the role of Spring's ApplicationContext by hand.
- **Transactions**: instead of AOP, services call `Transactions.write()/read()`,
  a thin wrapper around `TransactionTemplate`. The wrapped block is exactly the
  transaction scope.
- As a bonus, a JSON API (`/api/decks`, `/api/decks/{id}/cards`) sits on the
  same service layer to show the framework's REST support.

### Run

```bash
gradle :example-flashcard:run
# http://localhost:8080
```

The database is an H2 file (`~/db/spider-silk/flashcard`), so data survives
restarts.

### Tests

```bash
gradle test
```

Repository and service tests run against in-memory H2 without mocking.
The rollback test in `DeckServiceTest` shows the `TransactionTemplate`
boundary actually working.

## Deployment Notes

- jte compiles templates at runtime by default (requires a JDK). For production,
  precompile with the [jte Gradle plugin](https://jte.gg/pre-compiling/) and
  pass the engine through the `JteTemplates(TemplateEngine)` constructor.
- `AppServlet` is a standard servlet, so it runs on any container (Tomcat and
  others), not just Jetty. Deploying that way? Exclude the bundled Jetty:
  `implementation('io.github.benelog.spidersilk:spider-silk-core') { exclude group: 'org.eclipse.jetty.ee10' }`

## Positioning and Roadmap

Where Spider Silk sits next to Javalin, Spark, Helidon SE, and Spring Boot, and
what that comparison says should change: [docs/positioning.md](docs/positioning.md).
The work that follows from it, with a progress table: [PLAN.md](PLAN.md).
