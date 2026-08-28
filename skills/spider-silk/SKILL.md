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
The current version is `0.1.0-SNAPSHOT`, published to GitHub Packages, which needs authentication even for public repositories — see [First-run setup](#first-run-setup) before touching the build file.
The full manual is at <https://spider-silk.benelog.net>; the reference files beside this one are distilled from it.

## Principles the code you write must respect

Spider Silk is built around explicitness, and code that fights this reads as wrong to its users.

1. **No reflection.**
   There is no annotation scanning, no proxies, no automatic binding.
   Never introduce annotation-based routing, classpath scanning, or reflective JSON binding (no Jackson/Gson on domain objects).
   JSON mapping is hand-written with the framework's `Json` API — that is a feature, not a gap to fill.
2. **A handler is a function from a request to a response**: `WebResponse handle(WebRequest req)`.
   Handlers return a `WebResponse` value; they never write to a servlet response directly (except a deliberate `WebResponse.raw(...)`).
3. **No DI container.**
   The object graph is assembled by calling constructors directly, conventionally in one `...Context` class.
   Do not add Spring, Guice, or any container.
4. **Routes are an explicit list.**
   Every route is one registration statement; there is no `Controller` interface and no self-registering class.
   Handlers arrive as a lambda, a single-route `...Action` class implementing `Handler`, or a public method reference (`decks::showDeck`).
5. **The framework covers the web tier only.**
   Persistence, transactions, and scheduling are the application's own code (see the `example-flashcard` app in the framework repo for the pattern: plain `NamedParameterJdbcTemplate`, a hand-rolled `Transactions` wrapper, a hand-wired context class).

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
        (e, req) -> WebResponse.text(e.getMessage()).status(HttpStatus.NOT_FOUND));
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
- `param(name, default)` forms cover absence only; a present-but-unparseable value is still a 400.
- `WebResponse` is immutable: every builder method returns a new value, so chains work and after-filters can rewrite responses.
- Statuses are `HttpStatus` constants, never raw ints; `HttpStatus.of(int)` when the number arrives at runtime.
- A before-filter returning `null` continues to the route; returning a response ends the request; `throw new HttpException(status, msg)` rejects and lets `error(status, ...)` render the body.
- Registering a second route that matches the same requests throws `IllegalStateException` at registration.
- `redirect(location)` is 302; say `HttpStatus.SEE_OTHER` (303) after a POST.
- Template names carry no extension (`template("deck")` renders `deck.jte`).
- Sessions are on by default; `req.flash(key, value)` + `req.flashed(key)` is the Post/Redirect/Get message pattern.
- `app.routes()` returns every registered route as data (`Route(method, path, description)`); build introspection pages and OpenAPI export on it.

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
        TestRequest.post("/api/decks").jsonBody("{\"name\": \"Spanish\"}").build());
assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
```

Both live in `spider-silk-test` (`net.benelog.spidersilk.test`), test scope only.
Details in [references/testing.md](references/testing.md).

## Where to look next

| Task | Read |
|---|---|
| Dependencies, Maven, modules and exclusions, Gradle plugin, Maven parent | [references/setup.md](references/setup.md) |
| Routing, handlers, request/response API, filters, errors, sessions, CORS/gzip/security headers, logging, introspection | [references/web-api.md](references/web-api.md) |
| JSON, templates (jte/FreeMarker/Handlebars/Thymeleaf), static files, SSE, WebSocket, OpenAPI export | [references/content.md](references/content.md) |
| Server tuning, virtual threads, Tomcat/Undertow, external containers, Jib/Docker images, native image | [references/servers-and-deployment.md](references/servers-and-deployment.md) |
| WebTest and TestRequest in full | [references/testing.md](references/testing.md) |

For anything deeper — design rationale, edge cases, the exact semantics of a header — the manual at <https://spider-silk.benelog.net> is the source of truth.
