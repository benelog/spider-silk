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

**Non-goals** — annotation-driven routing, automatic POJO binding, a DI container, and the rest — are decisions rather than backlog items, and are listed [at the end](#what-it-deliberately-does-not-adopt).

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

Javalin is the closest neighbour by shape — embedded Jetty, lambda routes, a config lambda — and the one worth reading against.
Spark is the ancestor of that style; its static-import DSL is the thing *not* to borrow, because process-global mutable state makes two apps in one JVM (and therefore parallel tests) impossible.

## Strengths, stated precisely

1. **The no-reflection claim survives the whole request.**
   Javalin and Helidon avoid annotation scanning but hand JSON to Jackson or JSON-B, so a rename in a record still changes the wire format silently.
   Here the wire format is written out in the handler, so it changes only when someone edits it.
   The one place a model can still be reflected over is a template engine, and each answers for that itself: the default jte compiles `${deck.title}` to a method call, while taking the FreeMarker, Handlebars, or Thymeleaf module takes its reflection with it.
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
   Jetty stays the default on the strength of its lifecycle being entirely its own; what each of the three costs to embed is decisions 22 and 23.
   Javalin and Spark both marry Jetty.
6. **Route introspection comes almost for free.**
   `app.routes()` reads back the same list the dispatcher walks, as data, with no reflection at all; Javalin needs a plugin for the equivalent.
   `app.guards()` is the same trick on the registrations beside it, so "which filter covers this path" and "which statuses have a body of their own" are answered off the table rather than by reading the startup code.
   A route registered as `get(path, "List every deck", handler)` reports that line too, so `spider-silk-openapi` can write a `summary` a method and a path could never imply.
7. **Content negotiation asks the handler's question.**
   `req.accepts("text/html", "application/json")` answers with one of the strings that were passed in, so the branch is a `switch` over values written on that line; a caller that will take none of them gets a 406 rather than a null.
   What stays out is the reflective half — nothing picks a serializer for you once the type is known.
8. **The three every deployment turns on are named methods, not plugins.**
   `cors(Cors)`, `gzip(Gzip)`, and `securityHeaders(SecurityHeaders)` each take one inert value, each is off until it is named, and each applies to every answer — a static file, an error page, and the automatic `OPTIONS` a preflight lands on.
   Javalin ships the same three as bundled plugins, which is the registry decision 27 refuses; the same decision carries why they are named methods rather than `before`/`after` filters.
9. **Startup cost is close to zero** because there is nothing to scan.

## Weaknesses, stated precisely

1. **JSON output is verbose.**
   `Json.obj().put("id", d.id()).put("name", d.name())` for every DTO is the single biggest ergonomic gap versus a reflective `json(deck)`.
   This is the cost of the core principle and does not go away.
   `JsonWriter`/`JsonReader`/`JsonCodec` take it out of the handlers — the mapping is written once and reused — so what is left is one lambda per type rather than one tree per handler.
2. **Static file serving compresses nothing itself.**
   Validators, conditional requests, a hosted path prefix, and a directory on disk all ship, and `precompressed()` answers with the `.br` or `.gz` a build left next to the asset — but core produces neither, so an asset with no sibling is deflated again by `gzip()` on every request that asks for it, and brotli is answerable only where a build wrote the file.
   That is the JDK's boundary rather than a decision: it has no brotli encoder, and a bundled one would be a dependency in the artifact every application carries.
3. **No WebSocket in core.**
   An upgrade leaves servlet dispatch, and with it the router, the filters, the error handlers, the request logger, `routes()`, and `WebTest` — decisions 15b and 15c have why that keeps it out of core.
   `spider-silk-jetty-websocket` maps one on Jetty, under a name that says which server it is tied to, and states the same limit rather than papering over it.
   SSE, which servlet dispatch *can* carry, ships as `WebResponse.sse(stream -> ...)` on an ordinary `get` route.
4. **Ecosystem of one.**
   One author, no community, no starters, and an OpenAPI export that is a module of its own rather than something core ships, since a spec format is not the web tier.

## What it deliberately does not adopt

These are decisions, not backlog items: some are features the neighbours in the table have and this framework refuses, some are shapes no one asked for.
The reasoning for each is in [decisions.md](decisions.md), and its [rejected list](decisions.md#rejected--decisions-with-the-reason) is where they stay closed; what is still open lives in the [issue tracker](https://github.com/benelog/spider-silk/issues).

- Annotation-driven routing, automatic POJO binding — `json(Object)`, `bodyAsClass(Foo.class)`, and `bodyValidator(...)` in its Javalin form — and the classpath scanning either would need: all reflection, which is the one thing the framework exists to avoid.
- A DI container, which is not the web tier, and `ServiceLoader`-based discovery of the server, which is binding by classpath.
- Spark's static-import DSL: process-global state, no second app per JVM.
- Javalin's plugin/bundled-plugins system: a registry of things that configure themselves is the beginning of a container, and strength 8 is what core does instead.
- `app.ws(path, config)` in core: weakness 3 above, and it would end strength 5, since `WebServer` is four methods precisely so Jetty is replaceable.
  A socket lives in `spider-silk-jetty-websocket` instead, which is Jetty-only and named that way on purpose.
