# Design Decisions

Why Spider Silk has the shape it has: each decision, its reason, and what was rejected on the way.
What each thing *does* is the [manual](https://spider-silk.benelog.net).

- The numbers are load-bearing: the write-ups cross-reference each other by number.
- The [rejected list](#rejected--decisions-with-the-reason) at the end closes questions that would otherwise be asked again.
- Open items live in the [issue tracker](https://github.com/benelog/spider-silk/issues), one issue per item, each with the condition that would make it worth doing.

## Index

| # | Decision | Outcome |
|---|---|---|
| 1 | Embedded Jetty, `start`/`stop`/`join`/`port` | ✅ shipped |
| 2 | `WebServer` / `WebServerFactory` seam for other servers | ✅ shipped |
| 3 | Jetty settings + `customizeServer`/`Context`/`HttpConfiguration` | ✅ shipped |
| 4 | Path-scoped `before`/`after` with trailing-`*` patterns | ✅ shipped |
| 5 | `path(prefix, group -> ...)` route groups, nestable | ✅ shipped |
| 6 | `error(status, handler)` + `req.errorMessage()` | ✅ shipped |
| 7 | `WebTest.test(app, client -> ...)` harness | ✅ shipped |
| 8 | `JsonCodec<T>` seam | ✅ shipped |
| 9 | Static files: cache headers, hosted path | ✅ shipped |
| 10a | Cookies and repeated parameters | ✅ shipped |
| 10b | `formParam` distinct from query, HEAD/OPTIONS | ✅ shipped |
| 11 | Request logging hook | ✅ shipped |
| 12 | Graceful shutdown on by default | ✅ shipped |
| 13 | Route introspection: `routes()` and `guards()` → overview page, OpenAPI export | ✅ shipped |
| 14 | Router indexed by method and first segment | ✅ shipped |
| 15a | SSE: event framing over the servlet response | ✅ shipped |
| 15b | WebSocket in core | ❌ rejected |
| 15c | WebSocket as `spider-silk-jetty-websocket`, outside core | ✅ shipped |
| 16 | Virtual threads | ✅ shipped |
| 17 | Split `spider-silk-test` out of core | ✅ shipped |
| 18 | `WebContext` split into `WebRequest` + sealed `WebResponse` | ✅ shipped |
| 19 | Example: no `Controller` interface, one routing table | ✅ shipped |
| 20 | `TestRequest` in `spider-silk-test`, and no mock library | ✅ shipped |
| 21 | `HttpStatus`: an enum, not an int | ✅ shipped |
| 22 | `spider-silk-tomcat`, with Jetty still the default | ✅ shipped |
| 23 | `spider-silk-undertow`, the third server | ✅ shipped |
| 24 | `redirect` defaults to 302, and only accepts a 3xx | ✅ shipped |
| 25 | `spider-silk-gradle-plugin`: packaging conventions | ✅ shipped |
| 26 | `spider-silk-maven-parent`: a parent POM, not a Maven plugin | ✅ shipped |
| 27 | CORS, gzip, and security headers named on `App`, not filters or plugins | ✅ shipped |
| 28 | Content negotiation: `accepts(...)` answers with a type, not a serializer | ✅ shipped |
| 29 | `spider-silk-openapi`: the route list as an OpenAPI document, outside core | ✅ shipped |
| 30 | Route descriptions: an overload, not an annotation | ✅ shipped |
| 31 | Pre-compressed `.br`/`.gz` siblings, named on `StaticFiles` | ✅ shipped |
| 32 | One name for the group id, the package root, and the module name | ✅ shipped |
| 33 | Streamed JSON and NDJSON, on the `Stream` body already there | ✅ shipped |
| 34 | Six places the contract and the behaviour disagreed | ✅ shipped |
| 35 | The content type first, on every body that takes one | ✅ shipped |
| 36 | The request first in `ExceptionHandler`, as in every other handler | ✅ shipped |
| 37 | `RequestLogger` reports a `Duration`, not a count of milliseconds | ✅ shipped |
| 38 | `param(name, parser)`: one seam for every type with no named form | ✅ shipped |
| 39 | `WebResponse.file(Path)`, with the content-type table kept private | ✅ shipped |
| 40 | Read-only delegates for what only `raw()` reached | ✅ shipped |
| 41 | `sessionAttr(key, Class<T>)` and `invalidateSession()` | ✅ shipped |
| 42 | Uploads that stream, an optional upload as null, and `files(name)` | ✅ shipped |
| 43 | A named tail variable, `/files/{path*}`, read with `pathParam` | ✅ shipped |
| 44 | `JsonObject` iterates as members, with `optObject`/`optArray` and `isString`/`isNumber`/`isBoolean` beside it | ✅ shipped |
| 45 | `SseStream.retry(Duration)`, written where it is called | ✅ shipped |
| 46 | Response header names compare case-insensitively, and stay single-valued | ✅ shipped |
| 47 | `setSessionAttr` for the write, and `paramOrNull` for the optional string | ✅ shipped |
| 48 | `queryParam(name, parser)` and `formParam(name, parser)`: a parser on a named source | ✅ shipped |
| 49 | `body()` keeps the text it read, and the unread body goes out once | ✅ shipped |
| 50 | A response copies its cookies and its template model, and not its bytes | ✅ shipped |
| 51 | Registration closes when a servlet is initialized, and settings are copied when registered | ✅ shipped |
| 52 | `responseFilter(filter)`: one filter that sees every response, before CORS, security headers, and gzip | ✅ shipped |
| 53 | Explicit filter scope, parameter absence, response mutation, and transmission results | ✅ shipped |
| 54 | Nullness in the signatures: JSpecify `@NullMarked` packages, checked by NullAway | ✅ shipped |
| 55 | Releases go to Maven Central from a tag, as one signed bundle | ✅ shipped |
| 56 | `req.route()` reports the route that answered | ✅ shipped |
| 57 | An `HttpException` passes a broader exception handler by | ✅ shipped |
| 58 | `RequestCompletion` carries the exception the request was answered for | ✅ shipped |
| 59 | A pass over names a first reader guesses wrong, taken as a breaking 1.2.0 | ✅ shipped |
| 60 | `body()` and each `bodyNdjson` line bounded in bytes, 1MB by default | ✅ shipped |
| 61 | A body that fails after commit aborts the transfer | ✅ shipped |
| 62 | Strict RFC 8259 parsing, whole numbers read from the token's digits | ✅ shipped |
| 63 | A body the container or the JVM cannot read is a 4xx, never a 500 | ✅ shipped |
| 64 | A modification time before 2000 is a build's stamp, and the tag comes from the content | ✅ shipped |
| 65 | The application's own shutdown hook closes open SSE streams | ✅ shipped |
| 66 | An `Error` is answered and reported as an exception is | ✅ shipped |
| 67 | Closing an SSE stream does not wait for a blocked write | ✅ shipped |
| 68 | A header value core writes is made valid, or refused, where it is set | ✅ shipped |
| 69 | A body goes to one reader, and a HEAD under gzip carries no length | ✅ shipped |

Fifty-eight of the fifty-nine shipped.
The exception, 15b, is a decision rather than a gap.
"WebSocket / SSE" split into 15a–15c because SSE is HTTP and rides through `AppServlet`, while WebSocket is a protocol upgrade that does not.

## 1–7 · Server, lifecycle, routing, and the test harness

### 1. Embedded Jetty and lifecycle

`start(port)` binds and returns rather than blocking, because Jetty's threads are non-daemon and hold the JVM up.
`start(0)` plus `port()` makes tests portable.

### 2. Server seam

`WebServer` is four methods, and `App.server(factory)` swaps the implementation.

Rejected: `ServiceLoader`, which is reflection and classpath discovery.

### 3. Jetty configuration

Commonly tuned settings are methods, and three customizers reach the real Jetty objects for the rest.
Sessions default on, because `req.flash`/`req.sessionAttr` need them.

### 4. Path-scoped filters

A before-filter that returns a response ends the request, so the handler cannot answer a caller the guard turned away.

- Item 18 made the halt a signature: returning a value halts, returning nothing continues.
  It used to be inferred from a `bodyWritten` flag.
- `WebResponse.empty(401)` still halts, since it is an answer.
- A filter that only reads does not halt.

### 5. Route groups

`app.path("/api", api -> ...)` passes the group as an argument.

Rejected: Spark's static-import equivalent, whose process-global prefix rules out two apps per JVM.

### 6. Status-code error handlers

One place renders a 404 or 500, whatever set the status.

- A response that already carries a body is left alone, which the sealed `WebResponse.Body` states without sniffing the servlet response.
- `error(HttpStatus.NOT_FOUND, handler)` names the status, as `redirect(url, HttpStatus.MOVED_PERMANENTLY)` does in decision 24.

Rejected: per-status shorthands such as `notFound(handler)`, which existed and went.

- The shorthand hides the status, the one thing the call should say.
- A reader who has seen `notFound` expects `serverError` beside it, and the 500 page is written as often as the 404.

### 7. Test harness

`WebTest.test(app, client -> ...)` returns the JDK's raw `HttpResponse`, so the project keeps its own assertion library.

## 8–12 · JSON, static files, the request API, logging, shutdown

### 9. Static file caching

`StaticFiles.directory(path)` serves a directory on disk, because uploads and mounted volumes are not on the classpath.

- **`staticFiles(StaticFiles...)` takes several roots.**
  A deployment that wants a directory usually has jar assets too.
  The first root in order that holds the file answers, and `staticFiles()` with no arguments turns serving off.
- **The traversal guard is core's, not the container's.**
  Jetty and Tomcat reject `..` with a 400 before the servlet, and Undertow does not.
  `StaticFiles` serves only a regular file whose real path, links followed, lies under the root's real path, and all three modules' acceptance tests assert it.
  Anything else is a 404 that does not say why.
  A symlink out of the uploads directory is therefore refused with no configuration flag.
- **The root is read per request.**
  A volume mounted after start-up needs no restart, and an unmounted one 404s instead of failing to boot, as an empty classpath root already did.

Left out: pre-compressed `.gz`/`.br` variants, until someone deploys behind something that does not already provide them.

### 8. `JsonCodec<T>` seam

Codecs stay hand-written, so the mapping stays visible, but they are no longer re-inlined in every handler.

- **Two interfaces, not one.**
  Most codecs are write-only and would otherwise fill `read` with `UnsupportedOperationException`.
  Each half is a SAM and therefore a lambda, and `JsonCodec.of(writer, reader)` combines them.
- **Codecs live in the web layer, not on the record.**
  A codec on `Deck` would make the domain import `net.benelog.spidersilk.json.Json` to state its own wire format.
  Core ships only the interfaces, and the example shows where codecs sit.
- **Collections compose** through `JsonWriter.list(...)` and friends, by function composition.
- **`read` throws, and `req.bodyJson(reader)` turns `IllegalArgumentException` into a 400**, the same contract as `pathParamLong`.

Rejected: a `Result` or `Validator` type in core, since business rules already throw from the service layer.

### 10a. Cookies and repeated parameters

The convenience cookie forms default to `Path=/`, `HttpOnly`, and `SameSite=Lax`, since a server-set cookie is rarely one a script should read.
`params(name)` returns an empty list when nothing matched, because an unchecked checkbox group is an answer, not a 400.

### 10b. The rest of the request API

The query string is parsed here, because the servlet API merges it with the form body.

- `formParam` subtracts the query values **by count, not by position**, so a name in both places still splits correctly.
- HEAD runs the GET route and drops the body, so the headers match the GET's.
  `AppServlet` overrides `service` instead of using `HttpServlet`'s HEAD machinery, so this holds on any container.
- OPTIONS answers from the router, and `head`/`options` register a route when the automatic answer is wrong.

### 11. Request logging hook

The hook is one lambda, with no logging framework in core.

- It runs in a `finally` around the whole dispatch, after the error handler, so it reports the status actually sent.
- A logger that throws goes to the servlet log, since the response is already out and must not break.

### 12. Graceful shutdown

Graceful shutdown is on by default, and each half can be turned off.

- Any stop timeout made `stop()` wait it out for idle keep-alive connections and then throw.
  `ServerConnector.setShutdownIdleTimeout` limits the drain to requests in flight.
- The hook is Jetty's `setStopAtShutdown`, one per JVM and deregistered on stop, so a suite starting a server per test does not accumulate hooks.

## 13–16 · Introspection, routing index, streaming, threads

These decide whether this is more than a teaching framework.

### 13. Route introspection *(the differentiator)*

`app.routes()` reads back the list the dispatcher walks, as data and with no reflection, where Javalin needs a plugin.

- **The handler is left out**, since a lambda's only name is what reflection digs out of its synthetic class.
  `PathPattern` stays package-private: plain strings are exposed, not a matching engine.
- **Descriptions waited on a need.**
  `PathPattern`'s `{deckId}` is OpenAPI's path-template syntax, so the minimal shape already yielded a valid document.
  A description means a parameter on all seven registration methods of `App` and `RouteGroup`, an annotation with the reflection taken out.
  The need arrived with 29, and decision 30 added the overloads without changing `routes()` or the export.
  Response types stay out, for the reason 29 gives.
- **The overview page and the OpenAPI export are not in core.**
  A spec format and its version drift are not the web tier's.
  The export became a module, decision 29.
  The overview page stays in the example, since a page is a template and a look.
- **`guards()` lists the filters and handlers beside `routes()`**, read off the same registrations.
  - It is a sealed `Guard` of `Before(path)`, `After(path)`, and `Error(status)`, because one record with a half-null component would misreport a path scope or a status scope.
  - Decision 4's trailing `*` is reported verbatim, and matching stays the dispatcher's job.
  - `exception(Type, handler)` handlers stay out, since neither a path nor a status describes a type scope.
- **The automatic HEAD and OPTIONS answers are not listed**, since `routes()` lists only what was registered.

### 14. Router indexing

Routes are bucketed by method and first literal segment, and a lookup merges candidates **by registration index**.
The tie-break is unchanged from the full scan: `/study/today` registered before `/study/{mode}` still wins.

### 15a. SSE

`text/event-stream` is plain HTTP through `AppServlet`, so a servlet deployment gets it too, which is why this is in and 15b is out.
Core adds only the framing, with no new dependency.

- **An SSE endpoint is a `get` route**, so `routes()`, filters, and the request logger reach it, the property whose absence sinks 15b.
- **Events are strings**: `stream.send(writer.write(value).toJson())` keeps the mapping at the call site.
- **The stream blocks and holds its thread**: one open stream is one server thread, which `AppServlet` can keep on any container.
  Many streams call for item 16's virtual threads.
- **Ending a stream is never an error.**
  Writing to a gone stream throws `SseStream.Closed`, caught by that type alone so the handler's own IO failures still reach the exception handlers.
  Writing after `close()` throws the same, or graceful shutdown would log a failure per open stream.
- **Shutdown closes streams.**
  An open stream never finishes, so item 12's drain would wait it out and fail.
  `App` closes registered streams first in `stop()`, and writes are synchronized so that close from another thread cannot cut a frame in half.
- **A HEAD of an SSE route answers headers without running the handler**, since a bodiless stream would never end.
- **The heartbeat is the application's**, since core does not choose a connector idle timeout.

Rejected: `AsyncContext`, a second dispatch model for a feature not yet shown to need one.

### 15b. WebSocket in core — *rejected*

See [the table below](#rejected--decisions-with-the-reason).
It is not in core and will not be.
Where it does live is 15c.

### 15c. WebSocket as `spider-silk-jetty-websocket`

The Jetty recipe is a module named for its server, as 22's `spider-silk-tomcat` is, in place of the `app.ws(path, config)` 15b rejected.
`WebSockets` maps paths to a `WebSocketHandler` and is a `Consumer<Server>` for item 3's `customizeServer`, so core does not change.

- **Jetty's `Handler`-level WebSocket, not the same-named `ee10` one.**
  `ee10` brings annotation scanning, ASM, CDI, and JNDI: thirty-four jars against ten, none of the ten scanning bytecode.
- **`customizeServer`, not `customizeContext`**, because the WebSocket container is built from the `Server`'s buffer pool and executor, and a context links to its server only after context customizers run.
- **Reflection is confined to one class.**
  Jetty looks up an endpoint's callback methods as `MethodHandle`s, cached per class.
  Every connection uses the module's `SessionListener` adapter, so that is the only class looked up, and the application's `WebSocketHandler` is reached by an interface call, as with jte's generated classes.
- **Jetty's `Session` is not wrapped**, since the module's name already rules out portability and a facade would hide the knob, item 16's argument.
- **A refused upgrade is answered.**
  A Jetty creator returning null writes no handshake, and the client hangs.
  A `WebSocketFactory` returning null answers 403, or the status it set.
  That and mapping a path are all the module adds to Jetty.

After an upgrade, 15b's point stands: the router, `before`/`after`, `error(status, ...)`, the request logger, `routes()`, and `WebTest` stop at the servlet.
The tests use a real Jetty and the JDK's `java.net.http.WebSocket` client, since no harness can stand in.

### 16. Virtual threads

Virtual threads are documented, not wrapped.

Rejected: a `virtualThreads()` method.
It would hide two lines of the server's API behind a name.
The choice is the application's: it pays off only when handlers block, and `synchronized` around the blocking call takes the benefit back.

## 17–20 · Structural

### 17. Split `spider-silk-test`

The harness is its own module, so core's jar carries no test code, and core's tests consume it like anyone else.
The arrow runs core's *test* source set → the harness → core's *main* source set, so it is not circular.

### 18. `WebContext` split into `WebRequest` and a sealed `WebResponse`

`WebContext` held the request, the response, the session, and every way of answering, so it was split along the HTTP metaphor.
`Handler` returns the answer, so the compiler checks every branch answers and answering twice is inexpressible.

- **`WebRequest`/`WebResponse`, not `HttpRequest`/`HttpResponse`**, which would collide with `WebTest`'s `java.net.http.HttpResponse` and sit next to `HttpServletRequest`.
- **An envelope around a sealed `Body`.**
  Status, headers, and cookies live once, and only the body varies.
  A `switch` over body kinds needs no default, and type-stable `with`-style methods let an `AfterFilter` be `WebResponse -> WebResponse`.
- **Templates render during dispatch**, so their failures still reach `app.exception(...)`.
  `Stream`, `Sse`, and `Raw` cannot be materialized, so their failures go to the servlet log with a best-effort 500 after the headers are committed.
- **Filters return `null` to continue**, since `Optional.empty()` reads worse in the common case.
  After-filters run only after a route completes normally, not after a before-filter answered or on an exception handler's output.
- **`…Handler` answers a request, and `…Writer` fills a body**, so `RawHandler` no longer reads as a subtype of `Handler`.
  `Handler` kept its name as the central type, used by `App.error(status, Handler)` and shared with Javalin and Helidon.
- **Two deliberate behaviour changes.**
  - `redirect` sets `Location` instead of calling `sendRedirect`.
  - Cookies moved to the response, and the session and flash stayed on the request, since a session outlives the response.

### 19. The example's routing table, in one place

The example registers every route in one explicit list, which item 13's trust in `app.routes()` depends on.

- **A handler has one of three shapes**: a lambda with no state, an `Action` class answering one route, or public methods registered by reference when one class answers several.
- **Handler methods are public**, the price of the path and its handler sitting on one line in a list nothing else adds to.
- **`Action` is a class-naming convention**, not a rename of `Handler` (see the table below).

Rejected: a `Controller` interface with `register(App)`.
The table became the union of seven `register` methods, readable only by opening seven files, which is annotation scanning by hand.

### 20. `TestRequest`, and no mock library

Item 18 left handler tests needing no `MockHttpServletResponse`, but the request half still meant adding two Spring artifacts.
`TestRequest` builds the request, so testing a handler needs no mock library.

- **It lives in `spider-silk-test`, never core**, taking the servlet API `compileOnly` as core does, so consumers get only core and the JDK.
  Using the bundled server's transitive copy would tie it to one server, against the `WebServer` seam.
- **It builds the servlet request**, since `WebResponse`'s `with`-methods already are a builder and `WebRequest` is a view over the servlet request.
- **It is a hand-written stub**, answering what `WebRequest` reads and throwing elsewhere, so a gap fails loudly instead of returning null.
- **It is faithful where a handler can tell.**
  - `getParameterValues` returns query then form values, which 10b's subtraction relies on and a single-map mock cannot show.
  - The body is read once, and form parameter reads count as reading it: after one, `bodyStream()` is empty, and after streaming the body, no form fields remain.
  - Both orders were measured on Jetty, Tomcat, and Undertow, and none throws `IllegalStateException` when the stream follows a parameter read.
- **A query string in the path is rejected**, since a kept `?` would surface much later as a routing mismatch.

## 21 · The status type

### 21. `HttpStatus`: an enum, not an int

A status is an enum, because an int accepts `42` and `4040` as readily as `404`.
It is the same bet as `paramEnum`.

- **The IANA registry, under RFC 9110 names**: `CONTENT_TOO_LARGE` and `UNPROCESSABLE_CONTENT`, not the older names Spring readers know.
  Deprecated registrations are left out.
  The numbers were cross-checked against an existing constant table, since a transposed digit is the bug the type exists to prevent.
- **`of(int)` is the sanctioned runtime path** for a handler mirroring an upstream number.
  It throws on a code the registry does not know, so enforcement moves to the boundary.
- **The getter answers the enum too**, avoiding the asymmetry `paramEnum` avoids.
  `WebTest`'s `statusCode()` stays an int, because its client is the JDK's and the boundary is this framework's API.

## 22–23 · The other servers

### 22. `spider-silk-tomcat`, with Jetty still the default

Tomcat ships as a module, following 17, and Jetty stays the default.
It cashes in decision 2: `WebServer` has four methods so a second server is a small job.

- **Servlet level**: it first tracked Servlet 6.0 like core, and has since moved to Tomcat 11.0.x, which is Servlet 6.1.
  Core calls nothing Servlet 6.1 added, so the skew does not reach an application.
- **Costs that keep Jetty the default:**
  - A working directory on disk, where Jetty runs diskless.
  - Logging through JULI rather than slf4j.
  - No graceful shutdown of its own, so decision 12's guarantee is rebuilt by pausing the connector and draining the request pool.
  - A shutdown hook and JVM lifetime that decision 1 and decision 12 get from Jetty for free.
- **Two asymmetries stay visible:**
  - No `sessions(false)`, because Tomcat cannot drop its session manager and a no-op method would be worse than none.
  - `stopTimeout` waits on the connector's thread pool, so a virtual-thread executor passed to `executor(...)` makes the drain a no-op.
    The Javadoc says so, because tracking in-flight requests ourselves would be a second lifecycle model.
- **What Tomcat buys is operational**: existing organisational knowledge, Spring Boot's default runtime for anyone migrating, and an advisory pipeline enterprises already track.
  That is a reason to want it, not to make it the default.

### 23. `spider-silk-undertow`

A third server was built to show what the seam costs, not just that it works.

- **Undertow was the easiest of the three to embed**, unexpectedly.
  - Its graceful shutdown is a request-counting handler, so decision 12's guarantee is a wire-up.
  - Counting requests, it is the only one whose drain survives a virtual-thread executor.
  - It needs no working directory, and its logging finds slf4j itself.
- **The default still did not move, for non-technical reasons.**
  - Jetty's lifecycle is entirely its own, so less of this project's code sits between an application and its server.
  - Undertow is a WildFly component rather than a standalone product, so its operational familiarity and tooling are thinner than Tomcat's.
    That is advice for the manual, not a default for core.
- **Each server module carries its own copy of the acceptance tests**, asserting what core promises against each container.
  A shared parameterised suite would need a module every server depends on only to save test duplication.

## 24 · The redirect default

### 24. `redirect` defaults to 302, and only accepts a 3xx

`redirect` defaults to 302, because a 302 can be taken back and a 301 cannot.

- Browsers and intermediaries cache a 301, often indefinitely, so a wrong one outlives the fix.
- `HttpServletResponse.sendRedirect`, Javalin, Spark, Spring MVC, Express, Rails, and Django all default to 302.
- `redirect(location, HttpStatus)` overrides it, taking the enum for decision 21's reason.
- A status outside 3xx is rejected, because a `Location` on a 200 is no redirect, and failing at the call beats a response no client will follow.

Rejected: `redirectPermanent(...)`.
`redirect(url, HttpStatus.MOVED_PERMANENTLY)` already names the status, the argument that kept `virtualThreads()` out in decision 16.

## 25 · The Gradle plugin

### 25. `spider-silk-gradle-plugin`: packaging conventions, in an included build

The packaging block every application would copy became the convention plugin `net.benelog.spidersilk`, an included build the example applies as a published app would.

- **Contents**: jte precompilation with its native-resources extension, Jib with a JRE base and restated `targetCompatibility`, the `-Pnative` switch with the task dependency Jib's extension forgets, and `resolveDependencies` for Dockerfile layer caching.
- **No-reflection holds**, because it governs the runtime and the framework already does its magic at build time (precompiled templates, generated reflect-config).
- **Only shared packaging goes in.**
  The example's `domainReflectConfig` stays out as the price of that app's reflective row mapper.
- **Overrides are plain `jib { }` / `graalvmNative { }`**, since the plugin's settings land before the script's blocks.
  The manual gives each convention's expanded form.
- **Cost**: jte, Jib, and GraalVM build tool upgrades arrive as plugin releases, and the DSL joins the API frozen at 1.0.
- The Maven counterpart, first deferred, shipped as decision 26.

## 26 · The Maven counterpart

### 26. `spider-silk-maven-parent`: a parent POM, not a Maven plugin

Decision 25's conventions ship for Maven as a parent POM, because a Mojo runs goals and cannot configure other plugins.

- `pluginManagement` entries stay inert until the child declares the plugin, so the declaration is the opt-in, like `spiderSilk { jte() }`.
- **The `-Pnative` tag** cannot be a profile appending to the child's value.
  The parent defaults `spider-silk.image.tag` to `latest`, the `native` profile flips it, and the child puts the placeholder in its image name.
- **The POM is hand-written, published verbatim by a Gradle module**, which fails if their coordinates drift.
  Gradle's POM DSL cannot model `pluginManagement` or profiles, and generated XML would obscure a file meant to be read.
- **Versions follow upstream**: Jib's Maven plugin stops at 3.5.2 on Central while the Gradle plugin is at 3.5.4.

## 27 · The three every deployment turns on

### 27. CORS, gzip, and security headers: named on `App`, not filters and not plugins

Each is one method on `App` taking an inert value: `cors(Cors)`, `gzip(Gzip)`, `securityHeaders(SecurityHeaders)`.
They cannot be `before`/`after` filters, which run only after a route matches.

- **Answers with no matched route:**
  - A CORS preflight is an unregistered `OPTIONS`, decision 10b's automatic answer.
  - A cross-origin 404 without CORS headers shows up in the browser as a CORS failure.
  - Error pages need security headers.
  - Static files, decision 9, are answered before routing.
- Running filters for unmatched paths would change `before`/`after` for existing applications.
- **Applied in `AppServlet`** between computing the answer and writing it, like decision 9's `staticFiles(StaticFiles)` and decision 11's `requestLogger`.
  Unlike Javalin's plugin registry, nothing self-registers and nothing is on until named.
- **Compression transforms decision 18's sealed `WebResponse`**, not a servlet wrapper:
  - An in-memory body is compressed there, so its announced length is exact.
  - A `Stream` body wraps the output stream and drops `Content-Length`, so a large file never sits in memory.
  - SSE is excluded, since buffering it would stop it arriving.
  - `Raw` is excluded by definition.
- **Validators**: a compressed `ETag` is weak (same file, different bytes), which `StaticFiles` already accepts back, so revalidation works.
- **`Vary: Accept-Encoding`** goes on every compressible answer via `WebResponse.vary(field)`, since `header(name, value)` overwrites and CORS also adds a field.
- **A declined body keeps its type**, so decision 11's `requestLogger` still sees a `Text`.
  - Returning the already-encoded bytes would make the body type `decorate` passes on depend on whether gzip is on.
  - A `Text` is measured before encoding: a char is one to three bytes, so length decides most cases, and the rest are counted without allocating.
  - Only a body at the threshold that comes out no smaller is encoded twice.
- Gzip's stream wrapping is container-sensitive, so it is in the acceptance tests decision 22 and 23 mirror.

## 28 · The Accept header

### 28. Content negotiation: `accepts(...)` answers with a type, not a serializer

Handlers used to parse `Accept` themselves, and `accepts(...)` replaces that without reflection.

- **`accepts(candidates...)` returns one of its own arguments**, not a media-type object or a chosen serializer, so the branch is a plain `switch`.
  Picking a writer by type is decision 8's territory and stays refused.
- **It never returns null.**
  - Nothing acceptable is a 406, like `param`'s 400 and `pathParamLong` throwing, decision 10b's rule.
  - No `Accept` means "anything", so the first candidate wins and argument order is the handler's preference.
- **One parser**: package-private `AcceptHeader` also parses `Accept-Encoding` for decision 27's `gzip()`.
  - `q=0` is a refusal, even under a wildcard.
  - Ties sort by specificity (`text/html`, `text/*`, `*/*`), visible only through `acceptedTypes()`.
- **Asking adds `Vary: Accept` automatically**, so a shared cache never hands JSON to a browser and a handler cannot forget the header.
- **`acceptedTypes()`** is the parsed list, empty when there is no `Accept`, which means anything, not nothing.

## 29 · The OpenAPI export

### 29. `spider-silk-openapi`, outside core

The export moved from the example into a module, as 15c did for a server, because decision 13 judged it not worth putting in every application's dependency.

- **Not a method on `App`**: pinning `3.1.0` in core would tie core to another document version forever.
  As a module it is a dependency an application chooses, like 17's split.
- **Core does not change by one line.**
  `routes()` suffices, since `{deckId}` is the path template and a `Route` is a record, which confirms 13's minimal shape.
- **`document(title, version, routes)` takes a list, not the `App`.**
  - The application picks the routes, for instance only `/api`, since the module cannot tell a page from an endpoint.
  - Taking the `App` would have made that guess mandatory and hidden it.
  - Title and version are required by OpenAPI and are not the module's to name.
- **A wildcard throws**, where the example silently dropped `*` routes.
  A missing route understates the API, and throwing moves the filter to the call site, like `HttpStatus.of`.
- **Two routes OpenAPI would read as one path throw too**, for the same reason.
  OpenAPI tells templated paths apart by their segments alone, so `/decks/{deckId}` beside `/decks/{id}` is an invalid document, and `/files/{name}` beside `/files/{name*}` loses an operation.
- **Omitted**: request and response schemas, servers, security schemes, none of which a route can supply.
  That is the door 13's deferred description parameter came through, and decision 30 opened it one line wide.

## 30 · Route descriptions

### 30. An overload, not an annotation

A route takes an optional description as a `String`, because 29 gave 13's deferred parameter a reader: OpenAPI's `summary`.

- **Between path and handler**: `get(path, description, handler)`, seven methods each on `App` and `RouteGroup`.
  After the handler, it would push the lambda's closing brace away from the call.
  It is an annotation without the classpath scan.
- **A third `Route` component, `""` when absent.**
  - Not null, per the guard write-up's rule against half-null components.
  - A `Guard` needed three records because a path and a status differ, but described and undescribed routes are one kind.
  - `Route(method, path)` stays as a second constructor, so existing code compiles.
- **The framework never reads it.**
  Neither dispatcher, router index, nor duplicate check sees it, and `routes()` only hands it back.
  A description that changed behaviour would be a configuration language inside a documentation field.
- **`spider-silk-openapi`** maps a non-empty description to `summary` and omits the field otherwise, sparing UIs a blank line.
- 29's second bullet still holds: core changed here for its own need, not the export's.

## 31 · The pre-compressed sibling

### 31. `.br` and `.gz` siblings: `precompressed()` on `StaticFiles`, not on by default

With `precompressed()`, `StaticFiles` serves a `.br` or `.gz` sibling a build left beside an asset.
Decision 27's `gzip()` recompresses every request and cannot produce brotli, since the JDK has no encoder.

- **Off by default.**
  - 27 says nothing is on until named, but 9 serves `classpath:/public` unasked.
  - The case for on: placing `app.css.br` is the naming.
  - It lost to upgrades, where a `.gz` left by a retired pipeline would start being served by a version bump, a content change nobody wrote.
  - It would also cost every static request up to two lookups for siblings usually absent.
  - So it is `precompressed()`, one more method on the value 9 hands to `staticFiles(...)`.
- **Validators come from the original.**
  - `ETag` from its modification time and length, and its `Last-Modified`.
  - The tag is weak on an encoded answer, as in 27, so a browser caching the plain body keeps its 304.
  - `Content-Type` comes from the original name, since `.gz` is the encoding.
- **`StaticFiles` sets `Vary: Accept-Encoding` itself**, because `Gzip` skips answers that already carry `Content-Encoding`.
  With `precompressed()` on, every answer varies, and `WebResponse.vary` dedupes the field.
- **A stale sibling is skipped.**
  - One older than the original is from a build that did not rerun, and serving it is a wrong answer, not a slow one.
  - The timestamps are already read, so the check is free.
  - Missing times do not mean stale.
- **Brotli wins over gzip regardless of `q=`**, being smaller and otherwise unavailable.
  Acceptance is read through `AcceptHeader`, decision 28's parser.
- **Not mirrored onto 22 and 23's acceptance tests**, since it touches no output stream, unlike 27's wrapping.
- The external-directory half shipped separately as `StaticFiles.directory(path)`.

## 32 · The public surface, before 1.0

### 32. One name — `net.benelog.spidersilk` — and a pass over what is public

1.0 promises that what is public keeps its shape, so everything public was read once before it became permanent.
The name came first, since a package rename decides what the other answers are written in.

**The name.**
The group id `io.github.benelog.spidersilk`, the package root `spidersilk`, and a module name derived from the project name are now one string, `net.benelog.spidersilk`.
It is also the `Automatic-Module-Name` in every manifest and the Gradle plugin id.

- **`net.benelog`, not `io.github.benelog`.**
  Maven Central lends `io.github.<user>` to a publisher with no domain, and this project owns `spider-silk.benelog.net`.
  A group id claims ownership of a domain, whereas a GitHub handle moves with the account.
- **`net.benelog.spidersilk`, not `net.benelog`.**
  - The plugin marker artifact's group is the plugin id, `net.benelog.spidersilk`, so a shorter group would put two group ids in one repository.
  - A bare domain stops distinguishing anything once a second project publishes under it.
  - It follows `org.springframework.boot:spring-boot-*` and `org.eclipse.jetty:jetty-*`, and makes the group id equal the package root.
- **The module name is the package root, hyphens read as dots.**
  The old rule, `'spidersilk.' + project.name.substring('spider-silk-'.length())`, gave `spidersilk.jetty-websocket`, an illegal module name for a package named `spidersilk.jetty.websocket`.
  The module name and the `import` name are now the same string.

**What is public.**
Most of the surface was already deliberate, so the pass mostly wrote down why things stay.

- **`RouteGroup.resolve(String)` is private.**
  Nothing outside the class called it, and a caller holding a group has no use for it.
- **`WebRequest`'s constructor stays public, with the reason restated.**
  "Public so a test can build a request" named a caller, not a contract with boundaries.
  The contract: anything holding a servlet request and the path variables can build a handler's argument, and `TestRequest` in `spider-silk-test` is such a caller in another module.
- **`raw()` stays, documented with what it costs.**
  - Core reads through it, and `WebResponse.raw(...)` allows the outbound direction, so removing it would leave a one-directional escape hatch.
  - A read through it bypasses the framework: `accepts` records that an answer varies by `Accept` and a direct read does not, and a body consumed there is lost to `body()`.
  - A handler using it is tied to the servlet API.
- **What escapes is frozen where freezing is possible.**
  - `queryParams(name)` returned the live cached `ArrayList`, and `cookies()` an unwrapped `LinkedHashMap`.
    Both use `Collections.unmodifiableMap` now, not `Map.copyOf`, because the request's order is part of the answer.
  - Where an element is mutable by nature, the javadoc says so instead of copying:
    - `WebResponse.cookies()` returns the mutable `jakarta` `Cookie` objects it was given.
    - A `Bytes` body holds its `byte[]`, because a second copy is the cost a download cannot afford.
    - A `Template` holds its model, because a model takes null values that unmodifiable copies refuse.
- **`AppServlet` stays non-final.**
  A `web.xml` calls a no-argument constructor, so a subclass that builds its `App` is the only way to deploy that way.
  `dispatch` and `write` are private, so the two halves of decision 18's split are not an extension point.
- **`net.benelog.spidersilk.server` and `net.benelog.spidersilk.test` stay as they are.**
  - The server package is decision 2's seam: `WebServer`, `WebServerFactory`, and `JettyServer`.
    It is in core while `TomcatServer` is not because Jetty is the default, and the servers core does not bundle name what they are tied to.
  - In the test package, `StubServletRequest` and `TestClient`'s constructor were already package-private, and the three public types are the three a test calls.

**Not yet: japicmp or revapi.**
Binary-compatibility tooling needs a released baseline, so it belongs to the release after 1.0.

## 33 · JSON too big to hold

### 33. Streamed JSON and NDJSON, on the `Stream` body already there

`jsonArray(sink -> ...)` and `ndjson(sink -> ...)` write elements as they are produced, and `req.bodyNdjson(reader)` reads them the same way.
`WebResponse.json(list, JsonWriter.list(w))` holds a large answer in memory twice, as a tree and as a string, which suits an answer that fits but not an export.

- **No new `Body` kind.**
  Both are a `WebResponse.stream(...)` body from two static factories that supply the framing.
  A seventh member of the sealed `Body` would be a change every server module, filter, and the compression must answer for, for nothing new.
  The inherited costs are documented: headers commit before the writer runs, a HEAD runs the writer to find the length, and gzip applies, since `application/x-ndjson` joined `Gzip.DEFAULT_TYPES`.
- **The framing belongs to the response, the mapping to the writer.**
  A `JsonSink` takes one value at a time and the factory writes brackets, commas, and newlines, so one hand-written `JsonWriter<T>` serves held and streamed answers.
- **`JsonSink.write` throws `UncheckedIOException`, not `IOException`.**
  Values come from a database cursor whose row callback is a `Consumer`, and a checked exception would make `card -> sink.write(card, CARD)` illegal there.
  The exception only means the client is gone after the commit, and `req.body()` set the precedent.
- **NDJSON is bulk transfer, and SSE stays the live one.**
  Each line stands alone, so a consumer acts on record one immediately and a cut-off transfer leaves whole records.
  It neither flushes per value nor reconnects, which is decision 15a's job.
- **`bodyNdjson` is lazy, and says which line.**
  A hundred thousand records never become a list, and a rejected line answers 400 naming it.
  The failure surfaces where the stream is consumed, so the javadoc says to consume it before returning the response.
- **No streaming parser in core.**
  A hand-written `JsonReader` reads a tree, and a pull parser would be a second JSON API.
  A document too large to hold should be NDJSON, and a format the application does not own goes through `bodyStream()` to another library's parser.

**The seam for that library is written down.**
`WebResponse.json(String)`, `bodyStream()`, and `bodyReader()` are the whole of it, with a manual page.
Hand-written mapping is core's rule for core, not for an application with a wire format it does not own.

- avaje-jsonb generates adapters at compile time with no reflective fallback, so a native image needs no entry.
- Its field names still come from record components, so a rename silently changes the wire, the stronger promise the rejected `json(Object)` was rejected for.
- No reflection was the mechanism that kept the rule, never the whole rule.

## 34 · The contract, checked against the code

### 34. Six places the contract and the behaviour disagreed

The input contract is "a value or a 400", and six places did something else.
Decision 32 read what was public, and this one read what it did.
Each fix is cheaper before 1.0, because correcting a behaviour callers coded around is a breaking change disguised as a bug fix.

- **`Json.JsonException`, a subtype of `IllegalArgumentException`.**
  - `Json`'s accessors threw the plain type, as did `pathParam` for an undeclared variable, so the example's `IllegalArgumentException`-to-404 mapping answered 404 for a bad body and for a handler typo.
  - The subtype keeps decision 8's reader contract, where a reader's plain `IllegalArgumentException` is still a 400 through `bodyJson(reader)`, and lets parsing failures be mapped separately.
  - The `pathParam` case became `IllegalStateException`, since a pattern/handler mismatch is not bad input.
- **The most specific exception handler runs.**
  - First-registered-wins made every handler after `Exception.class` silently unreachable.
  - Throwing on a subtype after its supertype, as decision 14's router throws on an unreachable route, would forbid a readable order.
  - All types matching one exception lie on one inheritance chain, so specificity is well defined.
  - `isAssignableFrom` is `Class` API like the `isInstance` already in use, not scanning.
- **Registration closes at `start()`.**
  Request threads read the router's maps without a lock, and decision 13's claim that `routes()` is the list the dispatcher walks holds only while it does not change.
  Registering on a running `App` throws, and `stop()` reopens registration for suites that reconfigure between starts.
- **Three strict readings.**
  - `paramBoolean` read `yes` as false via `Boolean.parseBoolean`, where `paramLong` answers 400 for `x`.
    It now accepts only `true` and `false`, and gained the required form.
  - `asLong` truncated `1.5` to 1, and now rejects fractions while still reading `2.0` and `1e3`.
  - `AppServlet` forced UTF-8 over a declared request charset, and now defaults only where none was declared.

Rejected: turning an uncaught `JsonException` into a 400 in the framework.
A reader-less `bodyJson()` would answer 400 through a mapping nobody wrote, in an API whose point is that mappings are written.
The example and the agent skill carry the explicit line, which specificity matching lets sit after the `IllegalArgumentException` one.

## 35 · The argument order of a body

### 35. The content type first, on every body that takes one

`bytes` takes the content type first, as `stream` does, so every body that takes a content type takes it first.
The two are the same answer, held or streamed, and opposite orders made a reader of one guess the other wrong.

- `stream` could not move, because its lambda reads as a block only as the last argument.
- No deprecated overload was kept: `bytes(byte[], String)` beside `bytes(String, byte[])` is the ambiguity being removed.
- Every call site breaks as a compile error whose fix the error shows.
- It is taken before 1.0 for the reason decision 34 gives, that the cost only grows.

## 36 · The argument order of an exception handler

### 36. The request first, in `ExceptionHandler` too

`ExceptionHandler.handle` takes `(WebRequest request, E exception)`, where it was the one handler interface taking the exception first.
`Handler`, `BeforeFilter`, `AfterFilter`, and `RequestLogger` name the request first, so decision 18's naming rule now has an argument rule: the request first, then the rest.

- The old order read like a `catch` clause, as Javalin's `(e, ctx)` does, and lost to consistency across five interfaces.
- The interface is functional, so its shape is final at 1.0.
- No deprecated overload was kept, for the reason decision 35 gives.
- A lambda touching the exception, such as one calling `e.getMessage()`, stops compiling until swapped.
  One using neither parameter compiles either way, where the order does not matter.
- It is taken before 1.0 for the reason decision 34 gives, that the cost only grows.

## 37 · The type of an elapsed time

### 37. `RequestLogger` reports a `Duration`

`RequestLogger.log` takes a `Duration` instead of a `long` of milliseconds, the API's one duration handed out as a number.

- `maxAge`, `hsts`, `stopTimeout`, and the cookie forms all take a `Duration`.
- The measurement is `System.nanoTime`, and milliseconds reported a 400-microsecond request as `0`.
  `took.toMillis()` gives the old number.
- The break is not mechanical: a lambda passing the value to a logger compiles and prints `PT0.4S` instead of `400`.
  Decision 34's argument for breaking before 1.0 applies with more force.

Rejected: nanoseconds as a `long`.
It keeps the precision and loses the unit, the costlier half, while `Duration` names its unit at every call site.

## 38 · Typed parameters beyond the named forms

### 38. `param(name, parser)`, alongside the named forms rather than in their place

`param(name, parser)`, `param(name, parser, default)`, and `pathParam(name, parser)` read any type through a `Function<String, T>` at the call site, with no reflection.
Each new type used to cost two or four overloads, and `int`, `double`, `UUID`, and `LocalDate` had none.

- **The named forms stay.**
  `paramLong`, `paramBoolean`, and `paramEnum` (required and default forms, for parameters and path variables) are shorter for common types, as `req.paramLong("id")` against `req.param("id", Long::parseLong)` shows.
  The seam covers the rest, so the overload list stops growing.
- **The contract is decision 8's, the one `bodyJson(reader)` has.**
  A parser that rejects the text answers 400 naming the parameter.
  Rejecting is throwing `IllegalArgumentException` **or `DateTimeException`**, because `DateTimeParseException` descends from `DateTimeException`, not `IllegalArgumentException`, and `LocalDate::parse` would otherwise give a 500.

Rejected: catching `RuntimeException`.
A parser never receives null, since `param(name)` answers 400 for an absent value first.
A `NullPointerException` or `IllegalStateException` from a parser is a parser fault, and 500 is the honest answer.

## 39 · A file a handler chose

### 39. `WebResponse.file(Path)`, and the content-type table stays private

`WebResponse.file(path)` answers with the content type the name implies, the size as `Content-Length`, and a `Stream` body.
Handlers were writing `stream(contentType, out -> Files.copy(path, out))` plus their own content type, while `ContentTypes.byPath` and `StaticFiles` already did this.

- **The table stays package-private.**
  - Under decision 32 a public table is permanent, and its fourteen extensions are deliberately incomplete: `.pdf`, `.webp`, and `.mp4` would each become a request to extend a published list.
  - A handler that disagrees with the implied type writes `.contentType(...)`.
  - A database blob with a stored filename has no `Path`, and that handler writes its own content type rather than getting a permanent public list.
- **A missing file throws `UncheckedIOException`, as `bodyStream()` does, and does not answer 404.**
  - Only the handler can tell a cleaned-up export from a wrongly built path, so the 404 is its line.
  - The check runs in the factory, not the writer, because of decision 18's trap: a body produced after dispatch has left its `try` block cannot reach `app.exception(...)`.
  - One `readAttributes` call gives the size and whether it is a regular file, so a directory fails before the headers commit.
- **`StaticFiles` is not refactored onto this.**
  It works on a `Resource`, not a `Path`, and carries the `ETag`, `Last-Modified`, and decision 31's pre-compressed sibling branch.
  Only the handler knows whether its file can change.

Rejected: a `file(contentType, path)` overload.
Decision 35 would put the content type first, `.contentType(...)` already overrides, and that decision removed two ways of saying one thing.

## 40 · The reads that only `raw()` reached

### 40. Eight read-only delegates on `WebRequest`, and `SecurityHeaders` stops going behind the API

`isSecure()`, `remoteAddress()`, `contentType()`, `queryString()`, `scheme()`, `host()`, `headers(name)`, and `headers()` are `WebRequest` methods, where each went through `raw()` before.
They are ordinary request questions, and `SecurityHeaders` calling `request.raw().isSecure()` was core bypassing its own API.

- **The bar is decision 32's**: a use a handler or `RequestLogger` has today, such as a logger's client address, a signature's raw query string, or HSTS's scheme, not parity with `HttpServletRequest`.
- **`raw()` stays what decision 32 made it**, the escape hatch for an async context, a client certificate, or a container attribute.
  The eight are read-only, so the class's one deliberate write is unchanged.
- **`host()` carries the port when it is not the scheme's default.**
  Otherwise `scheme() + "://" + host() + path()` breaks on every development server off port 80.
  Both halves come from the container, so `X-Forwarded-Host` applies as it does to `scheme()`, and `header("Host")` still reads the raw header.
- **`headers()` answers `Map<String, List<String>>` in request order**, with case-sensitive names, the shape `cookies()` has.
  Case-insensitive lookup is what `headers(name)` already provides.
- **`TestRequest` gains only `remoteAddress(addr)`.**
  `secure()`, `header("Content-Type")`, and `queryParam(name, value)` already state the rest.
  The host is set with `header("Host", ...)`, and the stub derives name and port from it as a container does.

## 41 · The session, read under a type and ended

### 41. `sessionAttr(key, Class<T>)` and `invalidateSession()`

`sessionAttr(key, User.class)` casts with `Class.cast` and fails on the reading line, naming the key and both types.
The unchecked `sessionAttr(key)` fails on the assignment instead.

- The type is written at the call site, so this is not reflection, any more than `paramEnum(name, Class<E>)` is.
- The unchecked form stays for obvious types, and it is the only one that reads a type variable.
- **A wrong type is an `IllegalStateException` and a 500, not a 400.**
  The application put the value there, so the mismatch is its own, as with `pathParam(name)`.
- `sessionAttr(key, User.class)` reads rather than writes, because `Class<T>` is more specific than `Object`.
  Storing a `Class` needs an `(Object)` cast, a price paid by nobody.
- **`invalidateSession()`** replaces `raw().getSession(false).invalidate()`, the escape hatch decision 40 had just narrowed.
  With no session it does nothing, so logging out twice is not an error.

Rejected: a `ClassCastException` with a better message.
It would read as the framework failing to cast, not the application disagreeing with itself.

## 42 · An upload the handler need not hold, and one it need not have

### 42. `inputStream()` and `writeTo(Path)`, an absent upload as null, and `files(name)`

`inputStream()` and `writeTo(path)` hand an upload over without holding it in memory, as `bytes()` and `asText()` do.

- **`writeTo` is `Part.write`**, so a container that buffered the upload to disk moves the file.
  - The content can therefore be written only once.
  - The path is made absolute first, because `Part.write` resolves a relative name against the container's multipart location.
    Each server module's acceptance test checks this.
- **`fileOrNull(name)` answers null for an absent upload**, since there is no default file to pass.
  It joins `cookie`, `queryParam`, `formParam`, and `flashed`, and `file(name)` answers 400.
- **A part counts only with a submitted file name.**
  A browser sends an untouched file input as an empty part, and text fields are parts too.
  `file(name)` therefore answers 400 where it used to hand back an empty file, the mismatch decision 34 describes.
- **`files(name)` is empty when nothing was sent**, like `params(name)`, and `file(name)` stays the first file.
- `TestRequest` keeps parts as a list, and its stub `write` makes `writeTo` testable without a server, as decision 20 asks.

Rejected: `Optional<UploadedFile>`, which would be the only `Optional` in the API.

## 43 · The tail of a wildcard route

### 43. A named tail variable, `/files/{path*}`

`/files/{path*}` binds the rest of the path, slashes included, to `req.pathParam("path")`.
`/files/*` matched the same requests but dropped the tail, so handlers cut `req.path()` themselves.

- The bare `*` stays the filter form, and no existing pattern changes.
- **An empty tail matches and binds `""`**, as `/admin/*` covers `/admin`.
  The two forms match the same paths, so registering both under one method is refused as a duplicate.
- The router's index is untouched, as decision 14 asks, since a tail is the last segment.
- **`routes()` reports the tail as a variable**, which decision 13 makes useful.
  `spider-silk-openapi` writes `/files/{path}` with the description "The rest of the path, slashes included.", and refuses a bare `*`.
  OpenAPI has no wildcard, so the description is the only place to say so.

Rejected: `req.pathTail()`, which is meaningless on most routes.

## 44 · Reading an object whose keys are data

### 44. `JsonObject` iterates as members, rather than handing out a `Map`

`JsonObject` is `Iterable<Map.Entry<String, JsonValue>>` with `size()` and `keys()`, mirroring `JsonArray`.
With only `has` and `get`, an object whose keys are data could not be read.

- **`optObject` and `optArray` answer `null`**, where the primitive `opt*` forms take a default.
  A container has no literal default, and an empty one would claim the member was present.
- **`isString()`, `isNumber()`, and `isBoolean()` join `isNull()`**, so a value is not told apart by catching `JsonException`.
  `instanceof` covers objects and arrays.
- The parser needed no change, since `LinkedHashMap` already kept document order.

Rejected: `members()` returning a `Map`.
`Map.copyOf` loses order, the backing map exposes state, and a copy typed `Map` promises neither order nor immutability.

## 45 · The fourth SSE field

### 45. `SseStream.retry(Duration)`, written where it is called

`stream.retry(Duration.ofSeconds(2))` writes a `retry:` line in milliseconds.
`SseStream` wrote only `id`, `event`, `data`, and decision 15a's heartbeat, so `retry` used to need `WebResponse.raw`.

- Only the server knows it will close every stream on a deploy and wants browsers back sooner.
- **The line is written at the call, unlike `id`**, which waits for the next event.
  A delay is a stream setting, and a stream that sends nothing more would otherwise never send it.
- A negative delay throws, for decision 24's reason: a browser silently drops a line it cannot read.

Rejected: a delay set once on `App` or `WebResponse.sse`.
The value travels in the body and can change mid-stream, so it is not a server setting.

## 46 · The response header map

### 46. Header names compare without regard to case, and a field still holds one value

`WebResponse.headers()` stays a `Map<String, String>` whose keys compare case-insensitively, as HTTP field names do.
`header("content-type", ...)` then `header("Content-Type")` used to answer null.

- The package-private `headerIgnoringCase` workaround is gone.
- **The map stays single-valued**, the shape decision 18 published and decision 32 freezes.
  Cookies have their own list, and a repeated header such as `Link` goes through `WebResponse.raw`.
- **Insertion order is kept**, so the map is a package-private `Headers` over a `LinkedHashMap` keyed by the lower-cased name, not a sorted `TreeMap`.
- The first spelling and position go on the wire, and a later spelling changes only the value.
  `AppServlet` calls `setHeader` once per entry, so one field is one line.

Rejected: normalising only in the setter, which leaves `headers().get("content-type")` answering null.

## 47 · A call site that says whether it reads or writes

### 47. `setSessionAttr(key, value)` for the write, and `paramOrNull(name)` for the optional string

A session write is `setSessionAttr(key, value)`, so `sessionAttr` is only ever a read.

- The write used to share a name and arity with decision 41's read, so `sessionAttr("user", null)` picked the read and never removed the attribute.
  The "price paid by nobody" was paid by every ordinary removal.
- A literal `null` type now fails naming `removeSessionAttr(key)`.
- No deprecated overload stays, for decision 35's reason.
- `TestRequest.sessionAttr(key, value)` keeps its name, as a builder with no read beside it.
- **`paramOrNull(name)` names the null default**, because `param(name, null)` is ambiguous with decision 38's parser form.
  `OrNull` is decision 42's suffix.

Rejected: dropping `param(name, defaultValue)` so `null` resolves to the parser, which trades a compile error for a runtime `NullPointerException`.

## 48 · A parser on a named source

### 48. `queryParam(name, parser)` and `formParam(name, parser)`, with the contract `param(name, parser)` has

Decision 10b chose the source and decision 38 added a parser, and these give both at once.

- Absent answers 400 naming the source, a rejected value answers 400 naming the parameter, and any other exception stays a 500.
- The other source's value of the same name counts as absent.
- The one-argument forms stay null-answering, as decision 10b settled, and the parser forms are required unless given a default.

Rejected: a `(name, defaultString)` form, which repeats decision 47's `null` ambiguity and adds no capability.

## 49 · A body read twice

### 49. `body()` keeps the text it read, and the unread body goes out once

`body()` reads the body once and keeps the text.
A second call used to answer `""`, so a signature-checking filter left `bodyJson()` rejecting a valid body.

- `bodyJson()` and `bodyJson(reader)` parse the kept text.
- **The text is a servlet request attribute**, because decision 11's logger and `withPathParams` see different `WebRequest` copies.
- **The unread modes do not mix with it.**
  `bodyStream()`, `bodyReader()`, and `bodyNdjson()` still hand the body over, and decision 33 keeps NDJSON lazy.
  Whichever of the two modes goes second throws `IllegalStateException`.
- A form `param()` read and `raw()` stay outside the bookkeeping.
  After a form parse `body()` answers `""`, as decision 20's stub models, and `raw()` reads behind the framework's back, as decision 32 says.

Rejected: caching bytes so `bodyStream()` can replay them, which holds large uploads in memory.

## 50 · What an immutable response is immutable about

### 50. A response copies its cookies and its template model, and not its bytes

`WebResponse` clones a `Cookie` when it is added and again when it is read, and `Template` copies its model into a read-only map.
Before, decision 18's immutable response could change after an `AfterFilter` or a test had read it.

- `Cookie.clone()` keeps `SameSite`, and a test asserts it.
  Cloning is a declared method, not reflection.
- **The model copy keeps nulls and order**, so it is not `Map.copyOf`, and it is shallow.
  It sits in the record's compact constructor, because `new WebResponse.Template(...)` bypasses the factory.
  The FreeMarker, Handlebars, and Thymeleaf tests confirm no renderer writes into the model.
- **Bytes and writers are handed over.**
  A download's second copy is what the caller avoided, and a writer has no content to copy.

Rejected: a defensive copy of `Bytes`, which doubles every in-memory download's memory.

## 51 · When registration closes

### 51. Registration closes when `AppServlet` is initialized, and a setting is copied when it is registered

`AppServlet.init` snapshots the routes and settings, and registration stays closed until `destroy`.
Decision 34 closed it at `start()`, which `new JettyServer(app).start()` and external containers bypassed.

- **The servlet lifecycle is what all deployments share.**
  `App` counts live servlets, so two servers keep registration closed until both are gone.
  One lock covers the check and the snapshot, and only startup takes it.
- **The snapshot is a copy**, a package-private `Deployment`, so a restart builds a new table instead of changing one a draining request reads.
- **Every server module loads the servlet on startup**, since containers default to the first request.
  An external deployment uses `<load-on-startup>`.
- `stop()` works as decision 34 promised, registration stays closed during the drain, and a failed start reopens it through `destroy`.
- **A setting is copied when registered.**
  `gzip`, `cors`, `securityHeaders`, and `staticFiles` copy their values, so a kept `Gzip` can no longer retune `minBytes` on a running server.
  A `TemplateRenderer` is not copied, since an interface has no general copy.

Rejected:

- A builder that freezes into an immutable `App`, a second API for a guarantee the snapshot already gives.
- A snapshot in the `AppServlet` constructor, which would close registration forever if a server failed before `init`.

## 52 · A filter over every response

### 52. `responseFilter(filter)`, which sees every response and runs before CORS, security headers, and gzip

`app.responseFilter((req, res) -> ...)` runs on, and may replace, every response `AppServlet` answers.
Decision 4's after-filter misses early before-filter answers, exception-handler answers, router 404 and 405, automatic `OPTIONS`, and static files, and decision 27 left no application extension point there.

- The manual's own after-filter example set an `X-Request-Id`, missing on exactly the responses an operator most wants to trace.
- **`after` keeps its meaning**, since changing it would change every filter already written.
  - `ResponseFilter` is a separate interface shaped like `AfterFilter`, so a lambda moves between the two unchanged.
  - It takes no path.
    A filter wanting a subset branches on `req.path()`, as the rejected path-scoped `error(...)` already advises.
- **It runs after the answer is final and before the decoration.**
  - It sees the filled-in error body and the rendered template.
  - `Vary: Accept`, CORS, security headers, and compression apply to its result, so it cannot strip a security header or skip gzip.
  - A template body it returns is rendered after it.
  - Its answer skips `error(status, ...)`: an empty 404 returned on purpose stays empty.
- **A throwing filter is answered once**, through decision 34's most-specific exception handler and then `error(status, ...)`, so a styled 500 stays styled and decorated.
  The response filters do not run over that answer, or a filter that always fails would never produce a response.
- **`guards()` lists it as `Guard.ResponseFilter()`**, with no components, per decision 13.
  The new sealed case breaks exhaustive `switch`es, a compile error accepted before 1.0 for decision 34's reason.
- **It shapes responses and does not authorize requests.**
  The handler has already run, so a guard stays a before-filter, which still skips static files.
  A `Raw` writer's own writes and post-commit failures happen after the filter, as the javadoc says.

Rejected: making CORS, gzip, and security headers response filters.
An application could get their order wrong, and a CORS preflight is answered while `Allow` is computed, before any filter runs.
Decision 27's reasons for naming them on `App` stand.

## 53 · Consistent API contracts before 1.0

### 53. Filter scope, parameter absence, response mutation, and transmission results are explicit

This decision supersedes the API contracts in decisions 4, 10b, 11, 37, 46, 48, and 52 where they differ below, and those sections keep their original reasons.
The changes land before 1.0 without deprecated aliases.

- **Filter scope.**
  - `beforeRoute` and `afterRoute` replace `before` and `after` on `App` and `RouteGroup`, naming that only matched routes reach them.
  - `beforeRequest`, with an optional path pattern, runs before routing, over static files, missing routes, and automatic OPTIONS.
    It has no path variables, its early response or exception takes the normal error, response-filter, and decoration pipeline, and it must allow preflights itself because it precedes automatic CORS.
  - `Guard.BeforeRequest`, `Guard.BeforeRoute`, and `Guard.AfterRoute` report the scopes, all in the deployment snapshot and closed with the servlet lifecycle.
- **Filter results.**
  `afterRoute` and `responseFilter` must return a non-null response, and a null is a programming error handled as an exception.
  `BeforeFilter` keeps null as "continue", having no response yet.
- **Parameter absence.**
  - `queryParam(name)` and `formParam(name)` require a value, like their parser overloads.
    `queryParamOrNull` and `formParamOrNull` are the optional reads, replacing decision 48's overload-dependent policy.
  - A default makes a parser read optional, and a value in another source never counts as present.
- **Body readers** reject input with `IllegalArgumentException` or `DateTimeException`, like parameter parsers, so a malformed date is a 400 from a query string or a JSON field alike.
  Other reader exceptions stay server errors, and NDJSON errors still name the line and surface during consumption.
- **Response mutation.**
  - `WebResponse.withoutHeader(name)` is public and case-insensitive, so filters need no raw servlet writer to remove a header.
  - `body(replacement)` keeps headers, so the caller fixes Content-Length, ETag, Last-Modified, Content-Encoding, and a changed content type.
    Nothing is deleted automatically, because template rendering and compression also replace bodies and carry their metadata on purpose.
- **Transmission results.**
  - `RequestLogger` takes `(request, completion)`.
    `RequestCompletion` carries the response definition, the final servlet status as an int (a raw writer can set any code), the elapsed Duration, and any decoration or write exception.
  - Failure and status are independent: a pre-commit failure can yield a 500, a post-commit one a 200 with a partial body.
  - Handled exceptions appear as their response, normal SSE closure is normal completion, and completion does not confirm client receipt.
- **Migration.**
  Old filter and logger call sites fail to compile, but optional string reads must be moved to `OrNull` by hand because the required signatures still compile.
  The examples, agent references, and manual use the new contracts.

## 54 · Nullness in the signatures

### 54. Every published package is `@NullMarked`, and NullAway checks it at compile time

Every published package carries JSpecify's `@NullMarked`, so nullability is in signatures rather than Javadoc, and NullAway fails the build when they disagree with the code.

- `org.jspecify:jspecify` is an `api` dependency of every published module, and holds annotations only, so no reflection.
- NullAway runs through the existing Error Prone setup, JSpecify mode, error severity, on published main sources.
  Tests pass null on purpose, and `example-flashcard` is an application, so neither is checked.
- **The annotations record earlier contracts.**
  - `BeforeFilter.handle` returns `@Nullable WebResponse`, while `Handler`, `AfterFilter`, and `ResponseFilter` return non-null, per decision 53.
  - `@Nullable` returns: `WebRequest`'s `OrNull` reads, `header`, `contentType`, `queryString`, `cookie`, `sessionAttr`, `flashed`, `errorMessage`, plus `WebResponse.header`, `Json.optObject`, `Json.optArray`, `RequestCompletion.failure`, `UploadedFile.contentType`, `WebSocketFactory.create`.
  - `@Nullable` values: `setSessionAttr` and `flash` (null removes the key), `Json.put` and `Json.add` (null writes JSON `null`).
  - A template model is `Map<String, @Nullable Object>`, because decision 50 keeps null values.
  - Server settings `host` and `multipart` accept null, and `jetty()`, `tomcat()`, `undertow()` return `@Nullable` before `start()`.
- **A default is never null.**
  `param(name, default)`, parser forms with a default, and `Json.optString(key, default)` take and return non-null, since `paramOrNull`, `queryParamOrNull`, and `formParamOrNull` already cover the optional case.
- **The Servlet API was checked by hand**, since it is unannotated and NullAway treats it as non-null.
  - `getHeader`, `getContentType`, `getQueryString`, and `Part.getContentType` answer null.
  - `Part.getSubmittedFileName` can too, but `UploadedFile` exists only for named parts, so `fileName()` is non-null.
  - `threadPool`, `executor`, `baseDir`, and `SseStream.id` now reject null, like `contextPath` and `stopTimeout`.

## 55 · Where a release goes

### 55. Releases go to Maven Central from a tag, as one signed bundle

All artifacts go to Maven Central, replacing GitHub Packages, which demanded a personal access token and credentials block even for a public repository.
Central needs no Maven repository declaration and one `mavenCentral()` line in Gradle.

- **A `v*` tag starts a release**, not a push to `main`, because a Central version is permanent.
  - The version lives in `gradle.properties`.
    `verifyVersionReferences` fails the build if the README, the manual's `project-version`, the Maven parent, or the agent skill disagree.
  - `CHANGELOG.md` is written first, and its section is copied into the GitHub Release.
- **One signed bundle.**
  All publications, including the Gradle plugin and the Maven parent, are signed into one `build/` directory and zipped into a single Central Portal deployment.
  It is `USER_MANAGED`: the portal validates, a person presses Publish.
  Uploading is two `curl` calls, so the build adds only the `signing` plugin.
- The Gradle plugin's users add `mavenCentral()` to `pluginManagement.repositories`, and the Gradle Plugin Portal is left to its own issue.

Rejected:

- `com.vanniktech.maven.publish`, `com.gradleup.nmcp`, and JReleaser, each a plugin replacing two `curl` calls, a trade declined elsewhere.
- The OSSRH-compatible staging endpoint, which uploads modules separately, while the bundle is one file inspectable locally.
- Central snapshots, until someone asks for an unreleased version.

## 56 · The route a request matched

### 56. `req.route()` reports the route that answered

`WebRequest.route()` returns the `Route` the router chose, as listed by `app.routes()` with the group prefix resolved, because the router knows it at match time and decision 13's list is already data.

- It is null before routing or with no match, and set from `beforeRoute` through the handler, after-filters, exception and error handlers, response filters, and request logger.
- **Motivation: tracing.**
  With one `AppServlet` at `/*`, the OpenTelemetry agent named every span `GET /*`.
  Renaming it meant re-matching the path against `app.routes()` with the application's own matcher, which preferred literals while the router uses registration order, so spans could name a route that did not run.
- It lives on the request because every request-taking interface is functional and settled at 1.0 (decision 36), and loggers and response filters need it too.
- A HEAD answered by a GET route reports that GET route.

## 57 · What an exception handler catches

### 57. An `HttpException` passes a broader exception handler by

Only a handler for `HttpException` or a subtype catches one.
A `RuntimeException` or `Exception` handler never sees it, so it answers with its status and message via `error(status, ...)`, as with no handler at all.

- Under decision 34's most-specific rule, a logging catch-all for `RuntimeException` swallowed every `HttpException`, turning deliberate 404s into 500s unless it re-added `if (e instanceof HttpException http) return ...status(http.status())`.
- The trap is silent, because a catch-all reads as covering failures, and a deliberate status is not one.
- `HttpException` is the one exception whose meaning, a status, the framework defines, so only it is withheld from handlers that did not name it.

Rejected: keeping decision 34's rule and documenting the trap.
The re-added line was the framework's own `fail` again, and a rule restated in every catch-all is in the wrong place.

## 58 · What the request logger is told

### 58. `RequestCompletion` carries the exception the request was answered for

`RequestCompletion.exception()` holds what a handler, filter, or template threw, whether an exception handler or the framework's 500 answered it, because the logger already sees every outcome.

- It is null when nothing threw and for an `HttpException`, a status per decision 57.
- `failure()` stays decision 53's transmission failure: one interrupts computing the answer, the other sending it.
- Previously the logger saw only the 500, and capturing the exception required decision 57's catch-all trap, which also answered a request it only meant to observe.
- Mapped exceptions are reported too, with their status, so the application decides that a 400 is the caller's mistake and a 500 its own.

Rejected: a separate `app.onException(...)` observer.
It would get the request and the exception but no answer, and the logger already has all three.

Decision 59 renamed the two components `thrown` and `writeFailure`.

## 59 · Names a first reader guesses wrong

### 59. A pass over the public names, taken as a breaking 1.2.0

Every name a first-time reader would guess wrong was renamed outright, with no deprecated alias.
The framework has one user, and every break is a compile error whose fix the error shows.

- **Absence has one naming rule.**
  The plain name requires the value, a default as the last argument makes it optional, and `OrNull` answers null.
  - `JsonObject` followed org.json's `opt*` instead: `optString(key, default)` is now `getString(key, default)`, and `optObject(key)` is `getObjectOrNull(key)`.
  - `header`, `cookie`, and a session read keep null under the plain name, because absence is their usual case.
- **`Guard` became `Hook`.**
  - A response filter and a status page guard nothing, as `ResponseFilter`'s own javadoc said.
  - `Guard.ResponseFilter` collided with the `ResponseFilter` type, and `Guard.Error` with `java.lang.Error`.
  - Each record names the scope it carries: `Hook.StatusPage(status)`, `Hook.EveryResponse()`.
- **`error(status, handler)` became `statusPage(status, handler)`**, which says what it produces.
- **`RequestCompletion` names each exception for its half of the request.**
  `failure`/`exception` and `failed()`/`threw()` were synonyms.
  They are now `writeFailure`/`writeFailed()` and `thrown`/`threw()`.
- **`WebResponse.json(String)` became `rawJson(String)`.**
  `json("hi")` compiled and sent `hi`, which is not JSON.
- **The session is one object.**
  - `req.session()` answers a `WebSession` with `get`, `set`, `remove`, and `invalidate`, replacing four differently shaped names among forty request methods.
  - Asking for it starts no session.
  - Flash stays on the request, which is what it is delivered to.
  - `TestRequest.sessionAttr(key, value)` became `session(key, value)`.
- **The JSON types are top-level**: `JsonValue`, `JsonObject`, `JsonArray`, `JsonPrimitive`, `JsonException`, built with `Json.object()` and `Json.array()`.
- **Smaller names.**
  - The `Stream` body record became `Streamed`, apart from the `java.util.stream.Stream` that `bodyNdjson` returns.
  - `app.server()`, the running server, became `runningServer()`, apart from `server(factory)`.
- **Two failures say what went wrong.**
  - A path with whitespace is rejected at registration, because `get("List decks", "/decks", handler)` registered a route nothing could reach.
  - `pathParam` before routing reports that no route has matched yet.

Rejected: filling in `queryParamLong`, `formParamBoolean`, and the rest of the grid.
The parser overload covers every type on every source, and the rule is written down in `WebRequest`'s javadoc instead.

## 60 · How much of a request body core holds

### 60. `body()` and each `bodyNdjson` line are bounded in bytes, 1MB each by default

A body over its limit is a 413; `app.bodyLimits(BodyLimits)` changes the limits, and `BodyLimits.unlimited()` lifts them.

- **Bytes, counted as they arrive.**
  Memory is bytes whatever the charset, and Content-Length counts bytes.
  The header is checked first to refuse without reading, but it never bounds the read.
- **NDJSON bounds a line, not the body.**
  The lazy stream exists so that the number of records is free.
  Lines are split on the bytes, so a line with no newline is refused at the limit instead of being buffered whole.
- **A refused body stays refused.**
  A handler that catches the 413 cannot read on and get the rest of a body whose start is gone.
- **`bodyStream()`, `bodyReader()`, forms, and multipart are not counted.**
  A stream's reader decides what to keep, and the container parses forms and uploads under its own limits.
- **The limit is on `App`, not the server**, because the reads are core's: it holds under every server and in an external container.

Rejected: no default, which leaves every endpoint unbounded unless someone remembers.
Rejected: one limit for the body and the NDJSON line together, because a limit that fits a line would cap an import.
Rejected: a limit in characters, which does not bound memory for multibyte input.
Rejected: limiting `bodyStream()`, the escape hatch for large uploads.

## 61 · A body that fails halfway

### 61. A body that fails after the response is committed is rethrown into the container

The status can no longer change, but the container can still abort the transfer, and an aborted transfer is the only signal left for the client.

- **A gzip stream that fails releases its Deflater without writing the trailer**, so a writer that fails before writing anything still becomes a 500.
- **Undertow finishes a started response normally after a servlet exception**, so `spider-silk-undertow` closes the connection from its exception handler.
  That behavior lives in the Undertow module, not in core.
- `RequestCompletion.writeFailure()` still reports the original failure.

Rejected: swallowing committed failures, which makes a truncated download look complete.
Rejected: a core-side abort API, which the servlet API has no way to carry out.

## 62 · How strict the JSON parser is

### 62. The parser accepts RFC 8259 and nothing more, and whole-number checks read the token's digits

- **The grammar is the parser's, not the JDK's.**
  Number syntax, raw control characters in strings, JSON whitespace, and hex digits are checked by the parser, because `Double.parseDouble`, `Character.isWhitespace`, and `Character.digit` accept more than JSON does.
- **A decimal token keeps its text beside the double.**
  `asLong` converts the text exactly with `BigDecimal.longValueExact`, and `asDouble` stays the approximate reading.
- **An integer past the range of a long is kept the same way.**
  It is still a number, so `asDouble` reads it and only `asLong` refuses it.

Rejected: a lenient mode, because a body another JSON implementation rejects should not reach a handler.
Rejected: storing every decimal as a `BigDecimal`, which makes every parse and every `asDouble` pay for what only `asLong` needs.

## 63 · A body core cannot read

### 63. A body the container or the JVM cannot read is a 4xx, never a 500

The request carried the fault, so the answer names the client's side, whichever container parsed the body.

- **Multipart is decided by `Content-Type`, not by the exception.**
  The servlet API throws the same `ServletException` for "not multipart" as Jetty does for a multipart body that will not parse.
- **A size refusal is an `IllegalStateException` on the cause chain, and answers 413.**
  - The servlet API names it for a part over `maxFileSize` and a body over `maxRequestSize`.
  - Tomcat and Undertow carry the size exception as its cause, and Jetty wraps its own in a `ServletException`.
  - Anything else from `getParts` is a body that will not parse, and answers 400.
- **A bare `IllegalStateException` stays a 500.**
  Tomcat and Undertow throw it, with no cause and before reading anything, for a servlet with no multipart configuration, which is the deployment's fault.
  Jetty wraps that case like a size refusal, so there it answers 413 with Jetty's message.
- **The parameter reads go through `getParts` first on a multipart form.**
  Tomcat and Undertow report both failures through `getParameter` as the same `IllegalStateException`, and only `getParts` tells them apart.
- **A form-encoded body the container refuses is a 400 with the container's reason.**
  The servlet API defines no size signal for one, so a form over the container's limit is a 400 too.
- **A charset the JVM cannot decode is a 415**, settled before a byte is read.
- **A body cut short is a 400.**
  An `IOException` while `body()` or `bodyNdjson` reads is a client that sent less than its `Content-Length` or a connection that failed.
  The body then stays refused, as one refused for size does, so a second read cannot answer what was left of it.
  Tomcat answers such a request with its own 400 page whatever the application returns.

Rejected: parsing form bodies in core, which would move a parser and its limits out of the container for one status code.
Rejected: telling containers apart by class or message, which ties core to each container's internals.

## 64 · Validators for a file a build stamped

### 64. A modification time before 2000 is a build's stamp, and the tag comes from the content

Jib stamps every file of an image with 1970-01-01T00:00:01Z, so a time-and-length `ETag` stayed the same across releases for a same-length edit.

- **The cutoff is 2000.**
  - Jib and Nix stamp 1970-01-01T00:00:01Z, Cloud Native Buildpacks 1980-01-01T00:00:01Z, and a zip entry holds nothing earlier than 1980.
  - No file an application serves was last written before 2000.
- **Such a file is tagged by a CRC-32 of its content and its length.**
  - It is worked out once per path, time, and length, because a file in an image does not change within a deployment.
  - A jar entry's CRC is in the jar's central directory, so a stamped jar is not read for it.
- **A stamp is not sent as `Last-Modified`, and `If-Modified-Since` is not compared against it.**
- A file with a real time keeps the time-and-length tag, so a directory of uploads is never read for a checksum.

Rejected: a content tag for every file, which reads every large upload once for nothing.
Rejected: `USE_CURRENT_TIMESTAMP` in the Gradle plugin, which gives every build's layers a new digest.

## 65 · SSE streams at JVM shutdown

### 65. The application's own shutdown hook closes open SSE streams

Ctrl-C and SIGTERM stop the server through its own hook, which calls the server's stop rather than `App.stop()`.

- **The first `deploy()` registers the hook, and the last `undeploy()` removes it.**
  It covers every server and an external container alike, and a suite starting a server per test accumulates none.
- **The JVM runs its hooks at once**, so the streams close while the server drains, and the drain ends with them.

Rejected: a pre-stop callback on `WebServer`, which is an interface change every server module would have to follow.
Rejected: `App.start` owning the only hook, which leaves a server started directly and an external container uncovered.

## 66 · An `Error` from the application

### 66. An `Error` is answered and reported as an exception is

`AppServlet` catches `Throwable` wherever it caught `Exception`: in dispatch, the response filters, the exception and status-page handlers, and the write.

- **The answer is the framework's 500**, through the same decoration, so it carries the security and CORS headers.
  `exception(Type, handler)` takes an `Exception` type, so no handler sees an `Error`.
- **The logger hears of it**, as `thrown()` or `writeFailure()`, which became a `Throwable`.
  Left to the container, an `Error` from dispatch skipped the logger, and one from a writer was logged as a 200 that succeeded.
- **`VirtualMachineError` is included.**
  `StackOverflowError` is one, and the most common `Error` a handler throws; the stack has unwound by the time the servlet sees it.
  The container would answer it with a 500 of its own anyway, only without the headers and the log.

Rejected: rethrowing a `VirtualMachineError` to the container, which gives up the headers and the log for no gain.
Rejected: `exception(...)` handlers for `Error` types, which would invite an application to recover from one.

## 67 · A stream whose client stopped reading

### 67. Closing an SSE stream does not wait for a blocked write

A client that stops reading blocks the write in a flush until the connector gives up, which was 30 seconds on Jetty, and `close()` used to wait for it on the stream's monitor.

- **The stream holds a lock `close()` only tries.**
  A close that finds a write under way marks the stream closed, and the write closes the output on its way out.
  The write checks the flag after unlocking, so a close cannot slip between the two.
- **The server's stop timeout ends the blocked write**, since the drain stops waiting for the request and the connector closes its connection.
- **`isOpen()` reads a volatile flag**, so a loop that asks it does not wait behind a write either.
- **Tomcat's `unloadDelay` is 100ms**, since it waited two seconds more after the drain had waited the stop timeout.
  Zero is not usable: Tomcat waits a twentieth of it at a time, and `wait(0)` never returns.

Rejected: closing the servlet output from the stopping thread while another thread writes to it, which the servlet API leaves undefined.

## 68 · A header value the container would refuse

### 68. A header value core writes is made valid, or refused, where it is set

A container checks a header value only while the response is written, after the handler has returned, and each one fails in its own way: Jetty replaced a character, Undertow kept its low byte, and Tomcat dropped the header or threw.

- **A redirect location is percent-encoded**, since a browser encodes the same text in an `href` the same way.
  Only what a header cannot carry is encoded: anything outside ASCII, a space, and a control character, so an escape already there stays one escape.
- **A cookie value is refused**, with `IllegalArgumentException` at `cookie(...)`, inside the handler, where `exception(...)` sees it.
  A cookie has no encoding every reader agrees on, so choosing one is the application's call.

Rejected: refusing a non-ASCII redirect, since `redirect("/decks/" + name)` is ordinary code and the encoding is not in doubt.
Rejected: URL-encoding a cookie value, which the reading side would have to know to decode.

## 69 · Readers of one body, and a HEAD whose length is unknown

### 69. A body goes to one reader, and a HEAD under gzip carries no length

- **One reader takes the body.**
  After `bodyStream()`, `bodyReader()`, or `bodyNdjson`, any other reader throws `IllegalStateException`.
  Asking for the same stream or reader again answers it, because it is the container's.
  `bodyNdjson` twice throws, since the first one's buffer holds part of the body.
- **A HEAD that gzip would compress runs no writer.**
  The compressed length is known only by compressing the whole body, which for a static file means reading it.
  The HEAD carries the GET's `Content-Encoding` and `Vary` and no `Content-Length`, and the headers are committed so the container cannot announce a length of 0.

Rejected: compressing on HEAD to report the exact length, which is what decision 9's "a HEAD opens nothing" rules out.

## Rejected — decisions, with the reason

These are closed: reopening one changes what the framework is.

| Idea | Why not |
|---|---|
| `WebResponse.json(Object)`, `req.bodyAsClass(Foo.class)` | Reflection. The whole point is that the wire format changes only when someone edits it. |
| Annotation-driven routing | Reflection, plus scanning. |
| Renaming `Handler` to `Action` | In MVC frameworks an `Action` is a per-request object populated by reflection, returning a *result name* that XML or an annotation resolves to a view. `Handler` is a stateless function returning the response itself. The name also breaks the suffix rule (`…Handler` answers a request, `…Writer` fills a body) and strands `ExceptionHandler`. `Action` stays a naming convention for classes that implement `Handler`. |
| A `Controller` interface with `register(App)` in the example | Reading the routes would mean reading every controller. Things that register themselves are the container this framework exists without. |
| A DI container | Not the web tier, and `FlashcardContext` shows the alternative. |
| `ServiceLoader`-based server discovery | Classpath-driven binding is the magic this framework exists without. |
| Javalin-style plugin registry | Things that configure themselves are how a container starts. Decision 27 is the alternative: three named methods, each taking an inert value. |
| Spark's static-import DSL | Process-global mutable state: one app per JVM, no parallel tests. |
| Path-scoped `error(status, handler)`, or `RouteGroup.error(...)` | JSON under `/api` and HTML elsewhere is one visible branch on `req.accepts(...)` or `req.path()`. A path on `Guard.Error` would split decision 6's one place that renders a 404, and report a status handler as covering a pattern. |
| `app.ws(path, config)` in core | An upgrade leaves servlet dispatch, so the router, `before`/`after`, `error(status, ...)`, `requestLogger`, `routes()`, and `WebTest` silently stop applying. It would also end the no-lock-in claim of `WebServer`, since `AppServlet` on another container cannot follow. `jakarta.websocket`'s default `Configurator` instantiates endpoints reflectively. It lives in `spider-silk-jetty-websocket` instead (decision 15c). |
