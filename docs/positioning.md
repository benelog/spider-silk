# Positioning

Where Spider Silk sits among lightweight JVM web frameworks, and what it trades away to get there.

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
| Server | embedded Jetty, Tomcat, or Undertow, swappable | embedded Jetty | embedded Jetty | Loom-native Níma | Tomcat (or Jetty/Undertow) |
| Servlet deployable | yes | no | no | no | yes (war) |
| Core size | ~a dozen classes | large | medium | large | very large |
| Maintenance | one author | active | dormant at 2.9.x (community fork at 3.x) | Oracle | Pivotal/Broadcom |

Javalin is the closest neighbour by shape — embedded Jetty, lambda routes, a config lambda — and the one worth reading against.
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
   `AppServlet` runs on any servlet container, and `WebServer` is four methods, so `spider-silk-tomcat` and `spider-silk-undertow` sit behind the same `WebServerFactory` as the default Jetty and swapping to either is one line.
   Jetty stays the default on the strength of its lifecycle being entirely its own — decisions 22 and 23 have the detail, including the finding that Undertow is the cheapest of the three to embed and loses on reach rather than on design.
   Javalin and Spark both marry Jetty.
6. **Route introspection comes almost for free.**
   `app.routes()` reads back the same list the dispatcher walks, as data, with no reflection at all; Javalin needs a plugin for the equivalent.
7. **Startup cost is close to zero** because there is nothing to scan.

## Weaknesses, stated precisely

1. **JSON output is verbose.**
   `Json.obj().put("id", d.id()).put("name", d.name())` for every DTO is the single biggest ergonomic gap versus a reflective `json(deck)`.
   This is the cost of the core principle and does not go away.
   `JsonWriter`/`JsonReader`/`JsonCodec` take it out of the handlers — the mapping is written once and reused — so what is left is one lambda per type rather than one tree per handler.
2. **No content negotiation.**
   A handler that answers HTML to a browser and JSON to a client parses `Accept` itself.
   Every other framework in the table does this for you.
3. **No CORS, gzip, or security-header helpers.**
   Javalin ships all three as bundled plugins; here each is a filter the application writes.
4. **Static file serving stops short of pre-compressed variants and external directories.**
   Validators, conditional requests, and a hosted path prefix ship; a `.gz`/`.br` sibling next to an asset is not looked for.
5. **No WebSocket.**
   An upgrade leaves servlet dispatch, and with it the router, `before`/`after`, `error(status, ...)`, `requestLogger`, `routes()`, and `WebTest` — so it stays out of core as a Jetty recipe rather than an API core cannot reach.
   SSE, which servlet dispatch *can* carry, ships as `WebResponse.sse(stream -> ...)` on an ordinary `get` route.
6. **Ecosystem of one.**
   One author, no community, no starters, and an OpenAPI export that the example app demonstrates rather than core shipping, since a spec format is not the web tier.

## What it deliberately does not adopt

The neighbours in the table have four features that would be easy to copy and are refused on principle; [decisions.md](decisions.md) carries the reasoning for each.

- `json(Object)` / `bodyAsClass(Foo.class)`, and `bodyValidator(...)` in its Javalin form: reflection.
- Spark's static-import DSL: process-global state, no second app per JVM.
- Javalin's plugin/bundled-plugins system: a registry of things that configure themselves is the beginning of a container.
- `app.ws(path, config)` in core: a protocol upgrade leaves servlet dispatch, so core would be publishing an API that core's own routing, filters, error handlers, request logger, `routes()`, and test harness do not reach.
  It also ends strength 5 — `WebServer` is four methods precisely so Jetty is replaceable — and `jakarta.websocket` is no escape, since its default `Configurator` instantiates endpoints reflectively.

The reasoning behind each decision and the rejected list live in [decisions.md](decisions.md); what is still open lives in the [issue tracker](https://github.com/benelog/spider-silk/issues).
