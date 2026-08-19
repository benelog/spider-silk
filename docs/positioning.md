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
| Handler shape | returns a `WebResponse` | writes to `Context` | returns a body, writes `Response` | writes to `ServerResponse` | returns a value |
| Reflection at runtime | none | JSON only (Jackson) | JSON only | JSON only | pervasive |
| DI container | none | none | none | none | yes |
| JSON | hand-built `Json` tree | `ctx.json(pojo)` | bring your own | JSON-P / JSON-B | Jackson |
| Templates | jte | many, pluggable | many, pluggable | none | many |
| Server | embedded Jetty or Tomcat, swappable | embedded Jetty | embedded Jetty | Loom-native Níma | Tomcat (or Jetty/Undertow) |
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
2. **A handler answers by returning, so the compiler checks that it answered.**
   `WebResponse handle(WebRequest)` makes a branch that forgets to respond a compile error and a double response unexpressible; the lambda-and-context frameworks in the table can only find both at runtime.
   Spark returns a body but keeps status and headers on a mutable `Response`, so it gets half of this.
   The response is an immutable value with a sealed body — `Empty`, `Text`, `Bytes`, `Template`, `Stream`, `Sse`, `Raw` — which is also what makes an after-filter a plain `WebResponse -> WebResponse` and lets a handler test assert on the answer with no servlet response to read it out of.
3. **Errors are structural, not conventional.**
   `pathParamLong` returns a `long` or throws a 400.
   There is no binder that maps an unparseable value to `null` and lets it reach the service layer.
4. **Stack traces are short and honest.**
   No proxy frames, no filter chains you did not add.
5. **No lock-in on the server.**
   `AppServlet` runs on Tomcat; `WebServer` is four methods, so a second implementation is a small job.
   That is no longer a claim: `spider-silk-tomcat` is the second implementation, an embedded Tomcat behind the same `WebServerFactory`, and swapping to it is one line.
   Jetty stays the default because Tomcat costs a working directory on disk, JULI logging, and a hand-rolled graceful shutdown — decision 22 has the detail.
   Javalin and Spark both marry Jetty.
6. **Startup cost is close to zero** because there is nothing to scan.

## Weaknesses, stated precisely

The original assessment, in the order these were judged likely to hurt, with what has since shipped struck through.
Keeping the fixed ones visible is the point: the list is a trajectory, not a to-do.
What is genuinely still open is the verbosity of hand-written JSON (1), content negotiation (7), and the helper-and-community gap (10), plus the residue noted under 4 and 8.

1. **JSON output is verbose.** Still true, and reduced rather than removed.
   `Json.obj().put("id", d.id()).put("name", d.name())` for every DTO is the single biggest ergonomic gap versus a reflective `json(deck)`.
   This is the cost of the core principle and does not go away, but `JsonWriter`/`JsonReader`/`JsonCodec` took it out of the handlers: the mapping is written once and reused, so what is left is one lambda per type rather than one tree per handler.
2. ~~**No route grouping and no path-scoped filters.**~~ Fixed: `before(path, filter)` with trailing-`*` patterns, and nestable `path(prefix, group -> ...)` groups.
   The group is an argument rather than Spark's ThreadLocal, so two apps in one JVM stay possible.
3. ~~**No status-code handlers.**~~ Fixed: `error(status, handler)` alongside `exception(Type, handler)`, covering the router's own 404s and 405s as well as thrown `HttpException`s.
4. ~~**Static file serving is minimal.**~~ Fixed: validators, conditional requests, and a hosted path prefix all ship.
   Pre-compressed variants and external directories still do not.
5. ~~**Linear route matching.**~~ Fixed: routes are indexed by method and first literal segment, and candidates merge by registration index so the tie-break is unchanged.
6. ~~**No test harness.**~~ Fixed: `WebTest.test(app, client -> ...)` in a `spider-silk-test` module of its own, so the production jar carries no test code.
7. **Thin request API.** Mostly fixed.
   Cookies, repeated parameters, `formParam` distinct from the query string, and automatic HEAD/OPTIONS all ship.
   Content negotiation does not, and is the one piece still missing.
8. ~~**No WebSocket or SSE.**~~ Split in two, because the two halves answer "can `AppServlet` on Tomcat follow?" oppositely.
   SSE can, and now ships: `WebResponse.sse(stream -> ...)` frames the events over the servlet response, so an SSE route is an ordinary `get` route that `routes()`, filters, and the request logger all still reach — and a servlet deployment gets it too.
   WebSocket cannot: an upgrade leaves servlet dispatch, and with it the router, `before`/`after`, `error(status, ...)`, `requestLogger`, `routes()`, and `WebTest`.
   It stays out of core as a Jetty recipe, and becomes a `spider-silk-ws` module only if the recipe earns one.
9. ~~**No virtual-thread story.**~~ Fixed as documentation rather than API: a `QueuedThreadPool` with a virtual-thread executor, passed to `threadPool(...)`, with a test asserting handlers really run on one.
   No `virtualThreads()` method, because it would hide which Jetty knob was turned and it only pays off when handlers block.
10. **Ecosystem of one.** Partly fixed, and partly a decision.
    The request logging hook ships, and `routes()` makes an OpenAPI export about forty lines — which the example app demonstrates rather than core shipping, since a spec format is not the web tier.
    CORS, gzip, and security-header helpers still do not exist, and neither does a community.

## The API review: what to take from Javalin and Spark

**Adopt** — fits the principles, clear win. All seven shipped:

| Idea | Source | Note |
|---|---|---|
| `app.start(port)` / `stop()` / `port()` | both | **done** — embedded Jetty with a `WebServerFactory` seam |
| `config.jetty.modifyServer/ModifyServletContextHandler/modifyHttpConfiguration` | Javalin | **done** — `customizeServer`/`customizeContext`/`customizeHttpConfiguration` |
| `before(path, handler)` / `after(path, handler)` | both | **done** — trailing-`*` patterns over `PathPattern` |
| `path("/api", () -> {...})` groups | both | **done** — nestable, with an explicit registrar argument, never Spark's ThreadLocal |
| `error(status, handler)` | Javalin | **done** — complements `exception(...)`, and sees `req.errorMessage()` |
| `JavalinTest.test(app, client -> ...)` | Javalin | **done** — `WebTest`, port-0 startup plus a tiny client, in its own module |
| static files with hosted path + cache headers | both | **done** — `StaticFiles` with validators, 304s, `hostedPath`, `maxAge` |

**Propose** — needed a design decision before building. All five have since been decided and shipped, and the decision is recorded with each:

- A reflection-free typed JSON seam: `interface JsonCodec<T> { Json.JsonValue write(T); T read(Json.JsonValue); }` plus `WebResponse.json(value, codec)`.
  **Done** — and split in two, because most codecs are write-only: `JsonWriter<T>` and `JsonReader<T>` are each a SAM and therefore a lambda, with `JsonCodec<T>` for the types that travel both ways.
  Codecs stay hand-written — the mapping is still visible — but they became reusable instead of inlined in every handler.
- Route introspection.
  **Done** — `app.routes()` returns `List<Route>` with **no reflection at all**: it is the same list the dispatcher walks, read back as data.
  Javalin needs a plugin for this; Spider Silk gets it almost for free, which is the differentiator worth leaning on.
  The overview page and the OpenAPI export stayed out of core and live in the example app, because a spec format is not the web tier.
- Virtual threads as a documented recipe on `JettyServer.threadPool(...)`, then possibly a one-liner.
  **Done** as the recipe; the one-liner was rejected, since it would hide which knob was turned.
- Graceful shutdown: a shutdown hook and a stop timeout, on by default.
  **Done** — and it needed `ServerConnector.setShutdownIdleTimeout`, without which a stop waited out the whole timeout for *idle* keep-alive connections and then threw.
- SSE as framing over the servlet response, exposed as `WebResponse.sse(stream -> ...)` on an ordinary `get` route.
  **Done** — the transport is the response that was already there, so this was `Json`'s kind of work, a wire format written out, and it cost no new dependency.
  (This entry started as "WebSocket support through Jetty, exposed as `app.ws(path, config)`"; the WebSocket half moved to the rejected list below.)

**Reject on principle** — do not adopt, and say why in the docs:

- `json(Object)` / `bodyAsClass(Foo.class)`: reflection.
- `bodyValidator(...)` in its Javalin form: reflection.
- Spark's static-import DSL: process-global state, no second app per JVM.
- Javalin's plugin/bundled-plugins system: a registry of things that configure themselves is the beginning of a container.
- `app.ws(path, config)` in core: a protocol upgrade leaves servlet dispatch, so core would be publishing an API that core's own routing, filters, error handlers, request logger, `routes()`, and test harness do not reach.
  It also ends strength 5 — `WebServer` is four methods precisely so Jetty is replaceable — and `jakarta.websocket` is no escape, since its default `Configurator` instantiates endpoints reflectively.

## Priorities

**P0 — the gaps a user hits in the first hour.**
All shipped: embedded server and lifecycle, path-scoped filters, route groups, `error(status, handler)`, and a port-0 test harness.

**P1 — the gaps a user hits in the first week.**
All shipped: `JsonCodec<T>` seam, static file caching, cookies and multi-value parameters, the rest of the request API, request logging, graceful shutdown.

**P2 — the gaps that decide whether it is more than a teaching framework.**
Route introspection (overview page, OpenAPI export), router indexing, SSE, virtual threads.
All shipped, and WebSocket in core is a decision rather than a gap.

The reasoning behind each decision and the rejected list live in [decisions.md](decisions.md); what is still deferred lives in [PLAN.md](../PLAN.md).
