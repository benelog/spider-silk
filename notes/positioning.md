# Positioning

Where Spider Silk sits among lightweight JVM web frameworks, and what it trades away to get there.

## The one-line position

> **Thin call stack, strong signature.**

The README and the manual lead with this line: a servlet-native web layer with no reflection anywhere, small enough to read in one sitting.

- **Thin** is not "lightweight", which half a dozen frameworks are.
  It means *nothing* between the socket and your handler is resolved at runtime by name: not routing, not parameter conversion, not JSON.
  - Every dispatch is a lambda you registered on a line you can point at.
  - The build asserts it: two frames, `AppServlet.service` and `AppServlet.dispatch`, stand between `HttpServlet.service` and a handler.
    `CallStackDepthTest` names them, and `TomcatServerTest` and `UndertowServerTest` check that the container does not change the count.
- **Strong** means a handler answers by returning, so a branch that forgets to answer is a compile error, and a path variable arrives as a `long` or not at all.
  [Strengths](#strengths-stated-precisely) has the rest.

**Who it is for**

- Server-rendered apps and modest JSON APIs whose whole request path should be traceable in a debugger, with no proxy to step through.
- Teaching and reading: the core is about a dozen classes.
- Deployments that must stay on a plain servlet container, since `AppServlet` is just a servlet.

**Who it is not for**

- Large applications that want a component model, transactions, security, and messaging from the framework.
- High-concurrency reactive workloads.
- Teams that need an ecosystem: starters, OpenAPI generators, a hiring pool.

**Non-goals** are decisions, not backlog items, and are listed [at the end](#what-it-deliberately-does-not-adopt).

## The landscape

| | Spider Silk | Javalin 7 | Spark 2.9 | Helidon SE 4 | Spring Boot MVC |
|---|---|---|---|---|---|
| Route registration | lambdas | lambdas | static lambdas | lambdas | annotations |
| Handler shape | returns a `WebResponse` | writes to `Context` | returns a body, writes `Response` | writes to `ServerResponse` | returns a value |
| Reflection at runtime | none | JSON only (Jackson) | JSON only | JSON only | pervasive |
| DI container | none | none | none | none | yes |
| JSON | hand-built `Json` tree | `ctx.json(pojo)` | bring your own | JSON-P / JSON-B | Jackson |
| Templates | jte by default, one-method `TemplateRenderer` seam, FreeMarker/Handlebars/Thymeleaf modules | many, pluggable | many, pluggable | none | many |
| Server | embedded Jetty, Tomcat, or Undertow, swappable | embedded Jetty | embedded Jetty | Loom-native Níma | Tomcat (or Jetty/Undertow) |
| Servlet deployable | yes | no | no | no | yes (war) |
| Core size | ~a dozen classes | large | medium | large | very large |
| Maintenance | one author | active | dormant at 2.9.x (community fork at 3.x) | Oracle | Pivotal/Broadcom |

- Javalin is the closest neighbour by shape: embedded Jetty, lambda routes, a config lambda.
- Spark is the ancestor of that style.
  Its static-import DSL is the part *not* to borrow: process-global state rules out two apps in one JVM, and so parallel tests.

## Strengths, stated precisely

1. **No reflection across the whole request.**
   Javalin and Helidon hand JSON to Jackson or JSON-B, so renaming a record field silently changes the wire format.
   Here the wire format changes only when someone edits the handler.
   Templates are the one exception, and each engine answers for itself: jte compiles `${deck.title}` to a method call, while the FreeMarker, Handlebars, and Thymeleaf modules bring their reflection with them.
2. **A handler answers by returning, so the compiler checks that it answered.**
   - A branch that forgets to respond is a compile error, and a double response cannot be written.
   - The lambda-and-context frameworks find both only at runtime, and Spark gets half of this, keeping status and headers on a mutable `Response`.
   - The response is an immutable value with a sealed body (`Empty`, `Text`, `Bytes`, `Template`, `Streamed`, `Sse`, `Raw`).
     An after-filter is therefore a plain `WebResponse -> WebResponse`, and a handler test asserts on the answer with no servlet response.
3. **Errors are structural.**
   `pathParamLong` returns a `long` or answers 400, and no binder turns a bad value into a `null` for the service layer.
4. **Stack traces are short**: no proxy frames, no filter chains you did not add.
5. **No lock-in on the server.**
   - `AppServlet` runs on any servlet container.
   - `WebServer` is four methods, so `spider-silk-tomcat` and `spider-silk-undertow` swap in with one line behind the same `WebServerFactory`.
   - Jetty stays the default because its lifecycle is entirely its own, and decisions 22 and 23 give what each server costs to embed.
   - Javalin and Spark are tied to Jetty.
6. **Route introspection comes almost for free.**
   - `app.routes()` reads back the list the dispatcher walks, with no reflection, where Javalin needs a plugin.
   - `app.hooks()` does the same for filters and status pages, so "which filter covers this path" is answered from data, not from reading the startup code.
   - A description, `get(path, "List every deck", handler)`, lets `spider-silk-openapi` write a `summary`.
7. **Content negotiation asks the handler's question.**
   `req.accepts("text/html", "application/json")` returns one of its arguments, so the branch is a `switch` over values on that line, and a caller that takes none gets a 406.
   Nothing picks a serializer for you.
8. **CORS, gzip, and security headers are named methods, not plugins.**
   - `cors(Cors)`, `gzip(Gzip)`, and `securityHeaders(SecurityHeaders)` each take one inert value and are off until named.
   - Each applies to every answer: static files, error pages, and the automatic `OPTIONS` of a preflight.
   - Javalin ships them as bundled plugins, the registry decision 27 refuses, and the same decision explains why they are not filters.
9. **Startup cost is close to zero**, because there is nothing to scan.

## Weaknesses, stated precisely

1. **JSON output is verbose.**
   `Json.object().put("id", d.id()).put("name", d.name())` per DTO is the biggest ergonomic gap versus a reflective `json(deck)`, and the price of the core principle.
   `JsonWriter`, `JsonReader`, and `JsonCodec` reduce it to one lambda per type rather than one tree per handler.
2. **Static files are never compressed by core itself.**
   - Validators, conditional requests, a hosted path prefix, and a directory on disk all ship.
   - `precompressed()` serves the `.br` or `.gz` a build left beside the asset.
   - Without one, `gzip()` compresses the asset again on every request, and brotli needs a build-time file, because the JDK has no brotli encoder and bundling one would burden every application.
3. **No WebSocket in core.**
   - An upgrade leaves servlet dispatch, and with it the router, filters, status pages, request logger, `routes()`, and `WebTest` (decisions 15b and 15c).
   - `spider-silk-jetty-websocket` maps one on Jetty, named for the server it is tied to, and states the same limit.
   - SSE stays on servlet dispatch, as `WebResponse.sse(stream -> ...)` on an ordinary `get` route.
4. **Ecosystem of one**: one author, no community, no starters.
   The OpenAPI export is a separate module, since a spec format is not the web tier.

## What it deliberately does not adopt

These are decisions, not backlog items.
[decisions.md](decisions.md) has the reasoning, its [rejected list](decisions.md#rejected--decisions-with-the-reason) keeps them closed, and open items live in the [issue tracker](https://github.com/benelog/spider-silk/issues).

- Annotation-driven routing, automatic POJO binding (`json(Object)`, `bodyAsClass(Foo.class)`, Javalin's `bodyValidator(...)`), and the classpath scanning they need: all reflection.
- A DI container, which is not the web tier, and `ServiceLoader` discovery of the server, which is binding by classpath.
- Spark's static-import DSL: process-global state, one app per JVM.
- Javalin's plugin system: things that configure themselves are the beginning of a container, and strength 8 is the alternative.
- `app.ws(path, config)` in core: weakness 3, and it would break strength 5.
  Sockets live in `spider-silk-jetty-websocket`, Jetty-only and named so.
