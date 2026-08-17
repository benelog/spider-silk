# Positioning

Where Spider Silk sits among lightweight JVM web frameworks, what it trades away to get there, and the improvements that comparison points at.

## The one-line position

> **A servlet-native web layer with no reflection anywhere, small enough to read
> in one sitting.**

The distinguishing claim is not "lightweight" — half a dozen frameworks are lightweight.
It is that *nothing* between the socket and your handler is resolved at runtime by name: not routing, not parameter conversion, not JSON.
Every dispatch is a lambda you registered on a line you can point at.

**Who it is for**

- Server-rendered apps and modest JSON APIs where the whole request path should be traceable in a debugger without stepping through a proxy.
- Teaching and reading: the core is about a dozen classes, so "what does a web framework actually do" is answerable by reading it.
- Deployments that must stay on a plain servlet container, since `AppServlet` is just a servlet.

**Who it is not for**

- Large applications that want a component model, transactions, security, and messaging supplied by the framework.
- High-concurrency reactive workloads.
- Teams that need an ecosystem — starters, OpenAPI generators, hiring pool.

**Non-goals** (these are decisions, not backlog items): annotation-driven routing, automatic POJO binding, classpath scanning, a DI container, `ServiceLoader`-based discovery.
Each requires reflection, which is the one thing the framework exists to avoid.

## The landscape

| | Spider Silk | Javalin 7 | Spark 2.9 | Helidon SE 4 | Spring Boot MVC |
|---|---|---|---|---|---|
| Route registration | lambdas | lambdas | static lambdas | lambdas | annotations |
| Reflection at runtime | none | JSON only (Jackson) | JSON only | JSON only | pervasive |
| DI container | none | none | none | none | yes |
| JSON | hand-built `Json` tree | `ctx.json(pojo)` | bring your own | JSON-P / JSON-B | Jackson |
| Templates | jte | many, pluggable | many, pluggable | none | many |
| Server | embedded Jetty, swappable | embedded Jetty | embedded Jetty | Loom-native Níma | Tomcat |
| Servlet deployable | yes | no | no | no | yes (war) |
| Core size | ~a dozen classes | large | medium | large | very large |
| Maintenance | one author | active | dormant at 2.9.x (community fork at 3.x) | Oracle | Pivotal/Broadcom |

Reference points for the API review below: [Javalin](https://javalin.io/documentation) and [Spark](https://sparkjava.com/documentation).
Javalin is the closest competitor by shape — embedded Jetty, lambda routes, a config lambda — and the one worth borrowing from.
Spark is the ancestor of that style; its static-import DSL is the thing *not* to borrow, because process-global mutable state makes two apps in one JVM (and therefore parallel tests) impossible.

## Strengths, stated precisely

1. **The no-reflection claim survives the whole request.**
   Javalin and Helidon avoid annotation scanning but hand JSON to Jackson or JSON-B, so a rename in a record still changes the wire format silently.
   Here the wire format is written out in the handler, so it changes only when someone edits it.
2. **Errors are structural, not conventional.**
   `pathParamLong` returns a `long` or throws a 400.
   There is no binder that maps an unparseable value to `null` and lets it reach the service layer.
3. **Stack traces are short and honest.**
   No proxy frames, no filter chains you did not add.
4. **No lock-in on the server.**
   `AppServlet` runs on Tomcat; `WebServer` is four methods, so a second implementation is a small job.
   Javalin and Spark both marry Jetty.
5. **Startup cost is close to zero** because there is nothing to scan.

## Weaknesses, stated precisely

Ordered by how often they will actually hurt.

1. **JSON output is verbose.**
   `Json.obj().put("id", d.id()).put("name", d.name())` for every DTO is the single biggest ergonomic gap versus `ctx.json(deck)`.
   This is the cost of the core principle, but the cost can be reduced (see W1).
2. **No route grouping and no path-scoped filters.**
   `before(...)` is global, so `/admin/*` authentication has to re-check the path inside the filter.
   Javalin and Spark both have `path()` groups and `before(path, handler)`.
3. **No status-code handlers.**
   `exception(Type, handler)` exists but Javalin's `error(404, handler)` — one place to render a styled 404/500 — does not.
4. ~~**Static file serving is minimal.**~~ Fixed: validators, conditional requests, and a hosted path prefix all ship.
   Pre-compressed variants and external directories still do not.
5. **Linear route matching.**
   `Router.find` scans every route on every request.
   Fine at 30 routes, wrong shape at 300.
6. **No test harness.**
   Javalin ships `JavalinTest`; here every integration test hand-rolls port-0 startup and an `HttpClient`.
7. **Thin request API.**
   Cookies and repeated parameters now ship; `formParam` distinct from query, HEAD/OPTIONS, and content negotiation do not.
8. **No WebSocket or SSE.**
   Jetty is right there; the framework does not expose it.
   Since split in two, because the two halves answer "can `AppServlet` on Tomcat follow?" oppositely.
   SSE can: it is plain HTTP, so core will add the event framing and nothing else (`ctx.sse(...)`) and the servlet deployment keeps working.
   WebSocket cannot: an upgrade leaves servlet dispatch, and with it the router, `before`/`after`, `error(status, ...)`, `requestLogger`, `routes()`, and `WebTest`.
   It stays out of core as a Jetty recipe, and becomes a `spider-silk-ws` module only if the recipe earns one.
9. **No virtual-thread story.**
   Javalin has `config.useVirtualThreads`; Helidon SE is built on Loom.
   Jetty 12 can do it, but Spider Silk documents nothing.
10. **Ecosystem of one.**
    No OpenAPI, no CORS/gzip/security-header helpers, no request logging hook, no community.

## The API review: what to take from Javalin and Spark

**Adopt** — fits the principles, clear win:

| Idea | Source | Note |
|---|---|---|
| `app.start(port)` / `stop()` / `port()` | both | **done** — embedded Jetty with a `WebServerFactory` seam |
| `config.jetty.modifyServer/ModifyServletContextHandler/modifyHttpConfiguration` | Javalin | **done** — `customizeServer`/`customizeContext`/`customizeHttpConfiguration` |
| `before(path, handler)` / `after(path, handler)` | both | reuses `PathPattern`; small change, removes real boilerplate |
| `path("/api", () -> {...})` groups | both | but with an explicit registrar argument, never Spark's ThreadLocal |
| `error(status, handler)` | Javalin | complements `exception(...)` |
| `JavalinTest.test(app, client -> ...)` | Javalin | port-0 startup plus a tiny client, as a `spider-silk-test` source set |
| static files with hosted path + cache headers | both | **done** — `StaticFiles` with validators, 304s, `hostedPath`, `maxAge` |

**Propose** — worth doing, needs a design decision first:

- A reflection-free typed JSON seam: `interface JsonCodec<T> { Json.JsonValue write(T); T read(Json.JsonValue); }` plus `ctx.json(value, codec)`.
  Codecs stay hand-written — the mapping is still visible — but they become reusable instead of inlined in every handler.
- Route introspection.
  Because routes are an explicit list, `app.routes()` can return them with **no reflection at all**, which makes a route-overview page and even a static OpenAPI export cheap.
  Javalin needs a plugin for this; Spider Silk gets it almost for free.
  This is a genuine differentiator worth leaning on.
- Virtual threads as a documented recipe on `JettyServer.threadPool(...)`, then possibly a one-liner.
- Graceful shutdown: a shutdown hook and a stop timeout, on by default.
- SSE as framing over the servlet response, exposed as `ctx.sse(stream -> ...)` on an ordinary `get` route.
  The transport is the response that was already there, so this is `Json`'s kind of work — a wire format written out — and it costs no new dependency.
  (This entry started as "WebSocket support through Jetty, exposed as `app.ws(path, config)`"; the WebSocket half moved to the rejected list below.)

**Reject on principle** — do not adopt, and say why in the docs:

- `ctx.json(Object)` / `ctx.bodyAsClass(Foo.class)`: reflection.
- `ctx.bodyValidator(...)` in its Javalin form: reflection.
- Spark's static-import DSL: process-global state, no second app per JVM.
- Javalin's plugin/bundled-plugins system: a registry of things that configure themselves is the beginning of a container.
- `app.ws(path, config)` in core: a protocol upgrade leaves servlet dispatch, so core would be publishing an API that core's own routing, filters, error handlers, request logger, `routes()`, and test harness do not reach.
  It also ends strength 4 — `WebServer` is four methods precisely so Jetty is replaceable — and `jakarta.websocket` is no escape, since its default `Configurator` instantiates endpoints reflectively.

## Priorities

**P0 — the gaps a user hits in the first hour.**
All shipped: embedded server and lifecycle, path-scoped filters, route groups, `error(status, handler)`, and a port-0 test harness.

**P1 — the gaps a user hits in the first week.**
`JsonCodec<T>` seam, static file caching, cookies and multi-value parameters, request logging, graceful shutdown.

**P2 — the gaps that decide whether it is more than a teaching framework.**
Route introspection (overview page, OpenAPI export), router indexing, SSE, virtual threads.
All shipped but SSE, and WebSocket in core is now a decision rather than a gap.

Item-by-item status, the reasoning behind each decision, and the rejected list live in [PLAN.md](../PLAN.md).
