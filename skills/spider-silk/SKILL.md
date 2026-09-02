---
name: spider-silk
description: >-
  Build web applications and HTTP APIs with Spider Silk, a no-reflection Java web framework on the Jakarta Servlet API
  (group net.benelog.spidersilk, embedded Jetty by default).
  Use this skill whenever the user mentions Spider Silk or spider-silk-core, whenever a build file depends on
  net.benelog.spidersilk, and for any task in such a project even when the framework is not named:
  setting up the dependency (GitHub Packages), adding routes, handlers, filters, error handling, JSON endpoints,
  templates (jte, FreeMarker, Handlebars, Thymeleaf), static files, SSE, WebSocket, sessions, CORS, compression,
  tests (WebTest, TestRequest), server tuning (Jetty, Tomcat, Undertow), or deployment (Jib, Docker, GraalVM native image).
license: Apache-2.0
metadata:
  version: "0.1.0"
  homepage: https://spider-silk.benelog.net
---

# Spider Silk

A thin Java web framework on top of the Jakarta Servlet API.
Requires Java 21 or later.
Releases are published to GitHub Packages, which needs authentication even for public repositories — see [First-run setup](#first-run-setup) before touching the build file.
The full manual is at <https://spider-silk.benelog.net>; the reference files beside this one are distilled from it.

**Version.** This skill writes `0.1.0-SNAPSHOT` throughout, which is the release it was written against and the only version string in it.
Before putting it in a build file, prefer whatever the project already declares.
For a project starting fresh, check <https://github.com/benelog/spider-silk/packages> for the current version, since this skill's copy ages with each release.

## Principles the code you write must respect

Spider Silk is built around explicitness, and code that fights this reads as wrong to its users.

1. **No reflection.**
   There is no annotation scanning, no proxies, no automatic binding.
   Never introduce annotation-based routing or classpath scanning: those fight the framework itself, and there is no seam for them.
   JSON mapping is hand-written with the framework's `Json` API — that is a feature, not a gap to fill, so do not reach for a binding library unasked.
   A library the *application* adds is a different question, and the answer to it is not "no".
   When the user asks for Jackson, Gson, or avaje-jsonb, wire it through the seam that exists for exactly that: `WebResponse.json(String)` on the way out, `req.bodyStream()` or `bodyReader()` on the way in.
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

The most common first-run failure is a `401`/`Could not resolve net.benelog.spidersilk:...` from Gradle or Maven: GitHub Packages rejects anonymous downloads.
The user needs a personal access token (classic) with the `read:packages` scope, used as the password.

```groovy
repositories {
    mavenCentral()
    maven {
        url = uri('https://maven.pkg.github.com/benelog/spider-silk')
        credentials {
            username = project.findProperty('gpr.user') ?: System.getenv('GITHUB_ACTOR')
            password = project.findProperty('gpr.token') ?: System.getenv('GITHUB_TOKEN')
        }
    }
}

dependencies {
    implementation 'net.benelog.spidersilk:spider-silk-core:0.1.0-SNAPSHOT'
    testImplementation 'net.benelog.spidersilk:spider-silk-test:0.1.0-SNAPSHOT'
}
```

Credentials belong in `~/.gradle/gradle.properties` (`gpr.user`, `gpr.token`), never in the build file.
For Maven, the module list, optional modules, the Gradle plugin, and the Maven parent, read [references/setup.md](references/setup.md).

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
        Json.arr().add(Json.obj().put("id", 1L).put("name", "English"))));

app.post("/api/decks", req -> {
    String name = req.bodyJson().asObject().getString("name");   // missing key -> 400
    return WebResponse.json(Json.obj().put("name", name)).status(HttpStatus.CREATED);
});

// Routes sharing a prefix: the group is an argument, not ambient state
app.path("/api/decks", group -> {
    group.before(req -> requireApiKey(req));    // covers /api/decks and everything under it
    group.get("", api::listDecks);              // GET  /api/decks
    group.get("/{deckId}", api::showDeck);      // GET  /api/decks/{deckId}
});

// Exception-to-response mapping, and a styled error page for any 404
app.exception(IllegalArgumentException.class,
        (req, e) -> WebResponse.text(e.getMessage()).status(HttpStatus.NOT_FOUND));
app.exception(Json.JsonException.class,        // the more specific type wins, whatever the order
        (req, e) -> WebResponse.text(e.getMessage()).status(HttpStatus.BAD_REQUEST));
app.error(HttpStatus.NOT_FOUND, req -> WebResponse.template("not-found", Map.of("path", req.path())));

// What almost every deployed app turns on (off until named)
app.cors(Cors.allowOrigin("https://app.example.com").forPath("/api/*"))
   .gzip()
   .securityHeaders();

app.start(8080);
```

Key packages: `net.benelog.spidersilk` (App, WebRequest, WebResponse, HttpStatus, Handler, HttpException), `net.benelog.spidersilk.json` (Json, JsonWriter, JsonReader, JsonCodec), `net.benelog.spidersilk.server` (JettyServer, WebServer).

## Contracts that hold everywhere

- Typed extraction fails as a 400, not a null: `pathParamLong`, `paramLong`, `paramEnum`, `bodyJson(reader)`, `file(name)` all answer the request with 400 on bad or missing input, so handlers have no null branches to write.
- A type with no named form takes a parser: `req.param("since", LocalDate::parse)`, `req.param("page", Integer::parseInt, 1)`, `req.pathParam("deckId", UUID::fromString)`. A parser that throws `IllegalArgumentException` or `DateTimeException` answers 400 naming the parameter. Do not write `Long.parseLong(req.param(...))` by hand: that is a 500 on bad input.
- `param(name, default)` forms cover absence only; a present-but-unparseable value is still a 400.
- `WebResponse` is immutable: every builder method returns a new value, so chains work and after-filters can rewrite responses.
- Statuses are `HttpStatus` constants, never raw ints; `HttpStatus.of(int)` when the number arrives at runtime.
- A before-filter returning `null` continues to the route; returning a response ends the request; `throw new HttpException(status, msg)` rejects and lets `error(status, ...)` render the body.
- Registering a second route that matches the same requests throws `IllegalStateException` at registration.
- `redirect(location)` is 302; say `HttpStatus.SEE_OTHER` (303) after a POST.
- Template names carry no extension (`template("deck")` renders `deck.jte`).
- Sessions are on by default; `req.flash(key, value)` + `req.flashed(key)` is the Post/Redirect/Get message pattern.
- `app.routes()` returns every registered route as data (`Route(method, path, description)`); build introspection pages and OpenAPI export on it.
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
        TestRequest.post("/api/decks").jsonBody(Json.obj().put("name", "Spanish")).build());
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
| `401`, or `Could not resolve net.benelog.spidersilk:...` | GitHub Packages rejects anonymous downloads — see [First-run setup](#first-run-setup) |
| `IllegalStateException` at startup, naming a path | Two routes match exactly the same requests (the same path, or the same shape with a variable renamed). Registration is the check, because one of them could never run |
| A template is not found | The name carried an extension. `template("deck")`, never `template("deck.jte")` — the engine appends its own suffix |
| A 400 where you expected a null | Typed extraction rejects rather than returning null. Use the `(name, default)` form for a parameter that may be absent; a present-but-unparseable value is still a 400 |
| A `{name}` route swallows a literal one | Registration order breaks ties, so register `/study/today` before `/study/{mode}` |
| A before-filter never runs for paths under its own | A filter path needs the trailing `*` to cover what is under it: `/admin/*`, not `/admin` |
| An after-filter's change is lost | `WebResponse` is immutable. The filter has to *return* the new response; calling a builder method and dropping the result changes nothing |
| A streamed response answers 200 and then fails | The headers commit before the writer runs. Whatever can fail in a way the client should hear about belongs before the response is returned |
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
