# Design Decisions

Why Spider Silk has the shape it has, item by item.

This is the reasoning behind the framework, not a description of it — what each thing *does* is the [manual](https://spider-silk.benelog.net).
What is kept here is the decision, why it went that way, and what was rejected on the way.
The numbers are load-bearing: the write-ups cross-reference each other by number, and the [rejected list](#rejected--decisions-with-the-reason) at the end closes questions that would otherwise be asked again.

What is still open lives in the [issue tracker](https://github.com/benelog/spider-silk/issues), with the deferred list in [issue 8](https://github.com/benelog/spider-silk/issues/8).

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
| 13 | Route introspection → overview page, OpenAPI export | ✅ shipped |
| 14 | Router indexed by method and first segment | ✅ shipped |
| 15a | SSE: event framing over the servlet response | ✅ shipped |
| 15b | WebSocket in core | ❌ rejected |
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

Twenty-seven of the twenty-eight shipped.
The remaining one is 15b, which is a decision rather than a gap.
What was one entry — "WebSocket / SSE" — split once the two halves were asked the same question and gave opposite answers: SSE is HTTP and rides through `AppServlet`, WebSocket is a protocol upgrade and does not.

## 1–7 · Server, lifecycle, routing, and the test harness

### 1. Embedded Jetty and lifecycle

`start(port)` binds and returns rather than blocking, because Jetty's threads are non-daemon and hold the JVM up on their own.
`start(0)` plus `port()` is what makes tests portable.

### 2. Server seam

`WebServer` is four methods; `App.server(factory)` swaps the implementation.
Deliberately *not* `ServiceLoader` — that is reflection, and discovery-by-classpath is the thing this framework avoids.

### 3. Jetty configuration

The settings that usually need tuning are methods; three customizers cover everything else, running against the real Jetty objects.
Sessions default on, because `req.flash`/`req.sessionAttr` need them.

### 4. Path-scoped filters

A before-filter that returns a response ends the request — a guard that turned the caller away must not be followed by the handler answering anyway.
Item 18 made that a signature rather than a convention: the halt used to be inferred from a `bodyWritten` flag, and is now the difference between returning a value and returning nothing.
Deliberate edge: `WebResponse.empty(401)` still halts, since it *is* an answer; what does not halt is a filter that only reads.

### 5. Route groups

`app.path("/api", api -> ...)` passes the group as an argument.
Spark's static-import equivalent keeps the prefix in process-global state, which rules out two apps per JVM.

### 6. Status-code error handlers

One place to render a 404 or 500, whatever set the status.
A response that already carries a body is left alone, which the sealed `WebResponse.Body` states outright rather than by sniffing the servlet response.

### 7. Test harness

`WebTest.test(app, client -> ...)` returns the JDK's raw `HttpResponse`, so the project's own assertion library stays in charge.

## 8–12 · JSON, static files, the request API, logging, shutdown

### 9. Static file caching

Deliberately left out: pre-compressed variants (`.gz`/`.br`) and an external-directory location.
Both are real, neither is needed until someone deploys behind something that is not already doing it.

### 8. `JsonCodec<T>` seam

Codecs stay hand-written, so the mapping is still visible; they just stopped being re-inlined in every handler.
The four decisions:

- **Two interfaces, not one with two methods.**
  Most codecs are write-only, and a combined interface would make those fill `read` with `UnsupportedOperationException`.
  Split, each half is a SAM and therefore a lambda — which is also why `JsonCodec.of(writer, reader)` exists, since a two-method interface cannot be a lambda at all.
- **Codecs live in the web layer**, not on the record.
  A codec on `Deck` would make the domain import `spidersilk.json.Json` — the domain depending on the web framework to state its own wire format.
  Core ships the interfaces only; where codecs sit is a convention the example demonstrates.
- **Collections compose** through `JsonWriter.list(...)` and friends: function composition, no reflection.
- **`read` throws, and `req.bodyJson(reader)` turns `IllegalArgumentException` into a 400.**
  The same contract as `pathParamLong`: it returns the value or it rejects the request.
  No `Result` or `Validator` type in core — business rules stay in the service layer, where they already throw.

### 10a. Cookies and repeated parameters

The convenience cookie forms default to `Path=/`, `HttpOnly`, and `SameSite=Lax`, since a cookie worth setting from the server is usually one a script has no business reading.
`params(name)` returns an empty list rather than throwing when nothing matched — an unchecked checkbox group is an answer, not a 400.

### 10b. The rest of the request API

The query string is parsed here rather than through the servlet API, which merges it with the form body; `formParam` is what is left once the query values are subtracted **by count, not by position**, so a name appearing in both places still splits correctly.
HEAD runs the GET route and drops the body, so the headers are the ones the GET would have sent.
`AppServlet` overrides `service` rather than relying on `HttpServlet`'s own HEAD machinery, so the behaviour is the framework's on any container.
OPTIONS answers from the router, and `head`/`options` register a route for when the automatic answer is wrong.

### 11. Request logging hook

One lambda, no logging framework in core.
It runs in a `finally` around the whole dispatch, *after* the error handler, so the status it reports is the one that was sent rather than the one the router set.
A logger that throws goes to the servlet log: the response is already out by then, and a broken logger must not become a broken response.

### 12. Graceful shutdown

On by default, with a method to turn each half off.
The discovery that shaped it: a stop timeout of *any* size made `stop()` wait out the whole timeout for **idle** keep-alive connections and then throw.
`ServerConnector.setShutdownIdleTimeout` limits the drain to requests in flight, which is what it was ever supposed to mean.
The hook is Jetty's own `setStopAtShutdown` rather than a thread of ours — one per JVM, deregistered on stop, so a suite that starts a server per test does not accumulate them.

## 13–16 · Introspection, routing index, streaming, threads

These are what decides whether this is more than a teaching framework.

### 13. Route introspection *(the differentiator)*

`app.routes()` is the same list the dispatcher walks, read back as data — **no reflection at all**, where Javalin needs a plugin.
The four decisions:

- **Method and path, and nothing else.**
  The handler is left out: it is a lambda, so the only name it has is what reflection would dig out of its synthetic class.
  `PathPattern` stays package-private too — what is exposed is plain strings, not a matching engine.
- **No description, no response types.**
  `PathPattern`'s `{deckId}` *is* OpenAPI's path-template syntax verbatim, so the minimal shape already yields a valid document.
  A description would mean a parameter on all seven registration methods on both `App` and `RouteGroup` — an annotation with the reflection taken out — and the overloads stay purely additive if the need proves real.
- **The overview page and the OpenAPI export are not in core.**
  A spec format is not the web tier, and its version drift is not a web framework's to own.
  The example demonstrates both; if the export earns its keep, it becomes a module the way `spider-silk-test` did.
- **Routes only** — not filters, not error handlers.
  A record for "which guard covers this path" is nice-to-have, and additive later.

The automatic HEAD and OPTIONS answers do **not** appear: `routes()` lists what was registered, which is the honest answer for a framework whose pitch is that only what you register runs.

### 14. Router indexing

Routes are bucketed by method and first literal segment, and a lookup merges the candidate lists **by registration index** — so the tie-break is exactly what it was when `find` scanned everything.
That is the constraint the index had to preserve: `/study/today` registered before `/study/{mode}` still wins.

### 15a. SSE

`text/event-stream` is plain HTTP, so it goes through `AppServlet` unchanged and a servlet deployment gets it too — which is the whole reason this half is in and 15b is out.
Core adds the framing and nothing else; no new dependency.
The five decisions:

- **An SSE endpoint is a `get` route**, not a registration of its own, so `routes()`, filters, and the request logger all still reach it.
  That is exactly the argument that sinks 15b, so the implementation had to keep it true.
- **The events are strings.**
  `stream.send(writer.write(value).toJson())` rather than an overload taking a codec — the mapping stays visible at the call site, the same rule as everywhere else.
- **The stream is blocking and holds its thread.**
  One open stream is one server thread, which is the honest servlet answer and the one `AppServlet` can keep on any container; `AsyncContext` would be a second dispatch model in core for a feature that has yet to prove it needs one.
  The recipe for many streams is item 16's virtual threads, where a parked thread costs almost nothing.
- **Ending a stream is never an error.**
  A write to a stream that has gone throws `SseStream.Closed`, caught by that one type so an IO failure in the handler's own code still reaches the exception handlers.
  A write *after* `close()` throws the same thing, because otherwise every graceful shutdown would log a handler failure per open stream.
- **Graceful shutdown is resolved, not documented away.**
  An open stream is a request in flight that never finishes, so item 12's drain would wait it out and report failure.
  `App` closes registered streams as the first statement of `stop()`, and the writes are synchronized because that close comes from another thread and must not cut a frame in half.

Two deliberate edges: a HEAD of an SSE route answers with the headers and never runs the handler, since a stream with the body thrown away would never end; and the heartbeat stays the application's, because a connector idle timeout is a number core has no business choosing for anyone.

### 15b. WebSocket in core — *rejected*

See [the table below](#rejected--decisions-with-the-reason).
It stays a recipe an application writes itself, so the WebSocket dependency sits in that application's build file rather than core's.
If the recipe proves worth wrapping, it becomes a `spider-silk-ws` module where being tied to one server is stated in the module's name.

### 16. Virtual threads

Documented, not wrapped.
No `virtualThreads()` method — it would be two lines of the server's own API behind a name that hides which knob was turned, and the choice is not ours to make: it pays off only when handlers block, and `synchronized` around the blocking call takes the benefit back.

## 17–20 · Structural

### 17. Split `spider-silk-test`

The harness is its own module, so core's jar carries no test code at all.
Core's own tests are a consumer of it like anyone else; that looks circular and is not, since the arrow runs core's *test* source set → the harness → core's *main* source set.

### 18. `WebContext` split into `WebRequest` and a sealed `WebResponse`

"The request, the response, the session, and every way of answering" is four responsibilities wearing one name.
Splitting it along the HTTP metaphor was the obvious half; the other half was making `Handler` *return* the answer, so the compiler checks that every branch answers and answering twice stopped being expressible.
The five decisions:

- **`WebRequest`/`WebResponse`, not `HttpRequest`/`HttpResponse`.**
  `WebTest`'s own idiom asserts on `java.net.http.HttpResponse`, so the JDK name would collide inside this framework's own test style, and `HttpServletRequest` is one import away in the other direction.
- **An envelope around a sealed `Body`, not a sealed response.**
  Status, headers, and cookies are the same for every kind of answer, so they live once; what differs is the body, and *that* is the sealed type.
  A `switch` over the kinds needs no default case, and the `with`-style methods stay type-stable — which is what lets an `AfterFilter` be `WebResponse -> WebResponse` at all.
- **Templates are rendered during dispatch, not while writing.**
  This is the trap the return-based model sets: a body produced after dispatch has left its `try` block can no longer reach `app.exception(...)`.
  `Stream`, `Sse`, and `Raw` genuinely cannot be materialized, and their failures land in the servlet log with a best-effort 500 — the honest limit, since the headers are already committed.
- **Filters return `null` to continue**, rather than `Optional`: an observational filter stays a one-liner either way, and `Optional.empty()` reads worse in the common case.
  After-filters run only on a route that completed normally — not after a before-filter answered, not on an exception handler's output.
- **`…Handler` answers a request; `…Writer` fills a body.**
  Without the rule, `RawHandler` read as a relative of `Handler` and reviewers guessed one extended the other.
  `Handler` itself stayed: it is the central type, `App.error(status, Handler)` uses it too, and it is what Javalin and Helidon call the same thing.

Two behaviours changed on purpose: `redirect` sets a `Location` header rather than calling `sendRedirect`, and cookies moved to the response while the session and flash stayed on the request, since a session outlives the response and cannot be a value returned from one.

### 19. The example's routing table, in one place

A `Controller` interface with `register(App)` read like good structure and was the wrong shape: the routing table became the *union* of what seven `register` methods each decided, so "what does this application answer?" was answered by reading seven files.
That is what annotation scanning produces, arrived at by hand — and item 13's whole claim is that `app.routes()` is trustworthy because routing is an explicit list.
The three decisions:

- **A handler arrives in one of three shapes, chosen by how much there is to hold**: a lambda when there is no state worth a class, an `Action` class when a class answers exactly one route, public methods registered by reference when one class answers several.
- **The handler methods are public, and that is the trade.**
  It buys the property that mattered more: the path and the method that answers it sit on one line, in a list nothing else contributes to.
- **`Action` is a class-naming convention, not a rename of `Handler`** — see the table below for why the interface kept its name.

### 20. `TestRequest`, and no mock library

Item 18 left handler tests needing no `MockHttpServletResponse`; the request half stayed behind, so a framework whose pitch is that nothing is resolved by name at runtime was answering "how do I test a handler?" with "add two Spring artifacts".
The four decisions:

- **`spider-silk-test`, never core**, with the servlet API `compileOnly` exactly as core takes it, so what the module puts on a consumer's classpath stays core and the JDK.
  Depending on the bundled server's transitive copy would have tied the test module to the server the `WebServer` seam exists to keep replaceable.
- **A builder for the request, not for `WebRequest` or `WebResponse`.**
  Neither wants one: `WebResponse`'s `with`-style methods already are a builder, and `WebRequest` is a read view over the servlet request — so what actually needed building was that.
- **A hand-written stub, not a mock library.**
  It answers the servlet methods `WebRequest` reads and throws from the rest, so a method added to `WebRequest` and missing here fails loudly rather than returning a quiet null.
  The same bet as the rest of the framework: a small explicit thing over a general mechanism.
- **Faithful where a handler can tell the difference.**
  `getParameterValues` returns query values then form values, which is what makes 10b's subtraction behave as it does behind a container — precisely what a mock holding one parameter map cannot show.

A query string in the path is rejected rather than parsed, since a path that quietly kept its `?` would fail much later as a routing mismatch.

## 21 · The status type

### 21. `HttpStatus`: an enum, not an int

An int accepts `42` and `4040` as readily as `404`; the enum makes a status that does not exist unwritable — the same bet as `paramEnum` and the rest of the typed extraction.
Three calls inside that decision:

- **The IANA registry, under RFC 9110 names**: `CONTENT_TOO_LARGE` and `UNPROCESSABLE_CONTENT`, not the older names Spring readers know.
  Deprecated registrations are left out, and the numbers were cross-checked against an existing constant table rather than written from memory, since a transposed digit is exactly the bug the type exists to prevent.
- **`of(int)` is the sanctioned runtime path, not a loophole.**
  A handler mirroring an upstream answer has a number, not a name; `of` throws on a code the registry does not know, so the enforcement moves to the boundary instead of disappearing.
- **The getter answers the enum too**, because a setter taking `HttpStatus` and a getter handing back `int` would be the asymmetry `paramEnum` avoids.
  `WebTest`'s client is the JDK's, so `statusCode()` there stays an int: the boundary is this framework's API, not other people's.

## 22–23 · The other servers

### 22. `spider-silk-tomcat`, with Jetty still the default

Decision 2 cashed in: `WebServer` was made four methods so a second server would be a small job, and this is what proves it.
A module rather than a class in core, following 17 — being tied to one server is stated in the artifact's name.
The dependency tracks Servlet 6.0, matching core's servlet API, so the three servers stay on one specification level.

Jetty stays the default, which is the actual decision.
Tomcat wants a working directory on disk where Jetty runs diskless, logs through JULI rather than slf4j, and — the one that cost real code — has no graceful shutdown of its own, so decision 12's guarantee is rebuilt here by pausing the connector and draining the request pool.
Its shutdown hook and its JVM lifetime are the same story: both are things decision 1 and decision 12 get from Jetty for free and have to be assembled for Tomcat.

Two asymmetries are left visible rather than papered over.
There is no `sessions(false)`, because Tomcat offers no way out of its session manager and a method that silently did nothing would be worse than its absence.
And `stopTimeout` only has something to wait on while the connector runs a thread pool, so handing `executor(...)` a virtual-thread executor turns the drain into a no-op — said in the Javadoc rather than worked around, since the alternative is tracking in-flight requests ourselves, a second lifecycle model in a module that exists to have none.

What Tomcat buys is not technical: the operational knowledge an organisation already has, the runtime Spring Boot defaults to for anyone migrating off it, and a security-advisory pipeline most enterprise processes already track.
That is a real reason to want it and not a reason to make it the default, which is exactly what a seam is for.

### 23. `spider-silk-undertow`

Two implementations prove a seam works; the third tells you what it costs, which is why this was built rather than asserted.

Undertow turned out to be the *easiest* of the three to embed, which was not the expected answer.
Its graceful shutdown is a first-class handler that counts requests, so decision 12's guarantee is a wire-up rather than the reconstruction Tomcat needed — and because it counts requests rather than shutting a pool down, it is the only one of the three where the drain survives being handed a virtual-thread executor.
It needs no working directory, and its logging finds slf4j by itself, so neither of Tomcat's environmental costs applies.

So the default did not move again, and the reason is worth being honest about: it is not technical.
Jetty is still the one whose lifecycle is entirely its own, and that is a real difference in how much of this project's code sits between an application and its server.
Undertow's disadvantage is reach rather than design — it is a WildFly component rather than a standalone product, so operational familiarity and tooling are thinner than Tomcat's.
That is a deployment fact, not an engineering one, and it belongs in the manual as advice rather than in core as a default.

One structural note.
Each server module carries its own copy of the acceptance tests, deliberately: they assert what *core* promises against each container in turn.
Factoring them into one parameterised suite would mean a shared module that every server module depends on — a dependency built to save duplication in tests.

## 24 · The redirect default

### 24. `redirect` defaults to 302, and only accepts a 3xx

The choice between 301 and 302 is not symmetric, and that asymmetry decides it: a 302 can be taken back, a 301 cannot.
Browsers and intermediaries cache a 301 — often indefinitely — so a wrong one keeps sending visitors to the wrong place long after the code is fixed, with no way to call it back.
Every comparable default agrees: `HttpServletResponse.sendRedirect`, Javalin, Spark, Spring MVC, Express, Rails, and Django all send 302, so a framework defaulting to 301 would be surprising in the one direction that cannot be undone.

`redirect(location, HttpStatus)` is how an application says otherwise, and it takes the enum for decision 21's reason.
It rejects a status outside 3xx: a `Location` header on a 200 is not a redirect, and failing at the call beats shipping a response no client will follow.

No `redirectPermanent(...)` convenience method.
`redirect(url, HttpStatus.MOVED_PERMANENTLY)` already names the status out loud, and a second spelling would only hide which one was chosen — the same argument that kept `virtualThreads()` out in decision 16.

## 25 · The Gradle plugin

### 25. `spider-silk-gradle-plugin`: packaging conventions, in an included build

The example's build file had grown a packaging block any application would copy verbatim: jte precompilation with its native-resources extension, Jib with a JRE base and a restated `targetCompatibility`, the `-Pnative` switch with the task dependency Jib's extension forgets to declare, and a `resolveDependencies` task for Dockerfile layer caching.
Code copied unchanged into every consumer is a convention plugin by definition, so it became one: `io.github.benelog.spidersilk`, an included build so the example applies it exactly as a published application would.

The no-reflection principle is about the runtime, and this framework already puts its magic at build time — precompiled templates, generated reflect-config — so a build-time convention plugin extends the pattern rather than breaking it.
The line it holds: only packaging every application shares goes in; the example's `domainReflectConfig` stays out, because it is the price of that app's reflective row mapper, not a shared convention.
Everything the plugin sets lands before the build script's own blocks run, so overriding is plain `jib { }` / `graalvmNative { }` configuration, and every convention has its expanded form in the manual for a build that would rather own it.

The cost accepted: the plugin pins jte, Jib, and the GraalVM build tools, so their upgrades now arrive as plugin releases, and its DSL joins the API that freezes at 1.0.
A Maven counterpart was initially deferred, then shipped as decision 26.

## 26 · The Maven counterpart

### 26. `spider-silk-maven-parent`: a parent POM, not a Maven plugin

Maven's counterpart of a Gradle convention plugin is not a Maven plugin: a Mojo runs goals, and cannot declare other plugins' configuration.
Inheritance is where Maven puts build conventions, so decision 25's conventions ship for Maven as a parent POM — `pluginManagement` entries that stay inert until the child declares the plugin, which makes the declaration itself the opt-in, the way `spiderSilk { jte() }` is on the Gradle side.

The one convention that could not carry over structurally is the `-Pnative` tag switch: a profile cannot append to a value the child wrote, so the parent defaults a `spider-silk.image.tag` property to `latest`, the `native` profile flips it, and the child places the placeholder in its image name — a documented convention where Gradle has an override.
The parent POM is a hand-written file published verbatim by a Gradle module, with the publication failing if the file's coordinates and the module's ever drift; Gradle's POM DSL has no model for `pluginManagement` or profiles, and generating XML through it would only obscure a file whose whole value is being readable.

The versions diverge where upstream does: Jib's Maven plugin stops at 3.5.2 on Central while its Gradle plugin is at 3.5.4, and the parent pins what exists rather than what would be symmetric.

## Rejected — decisions, with the reason

These are closed.
If one is reopened, it is a change to what the framework is.

| Idea | Why not |
|---|---|
| `WebResponse.json(Object)`, `req.bodyAsClass(Foo.class)` | Reflection. The whole point is that the wire format changes only when someone edits it. |
| Annotation-driven routing | Reflection, plus scanning. |
| Renaming `Handler` to `Action` | In the MVC frameworks that made the name familiar, an `Action` is the opposite model: an object instantiated per request and populated by reflection, whose execute method returns a *result name* that XML or an annotation resolves into a view. `Handler` is a stateless function returning the response itself. The name would import expectations this framework refuses. It also breaks the suffix rule — `…Handler` answers a request, `…Writer` fills a body — leaving `ExceptionHandler` stranded. `Action` stays what it is worth being: a convention for naming the classes that implement `Handler` directly. |
| A `Controller` interface with `register(App)` in the example | The routing table becomes the union of what every implementation decided, so reading the application's routes means reading every controller. A registry of things that register themselves is the shape of the container this framework exists without. |
| A DI container | Not the web tier, and `FlashcardContext` shows the alternative. |
| `ServiceLoader`-based server discovery | Classpath-driven binding is the magic this framework exists without. |
| Javalin-style plugin registry | A registry of things that configure themselves is how a container starts. |
| Spark's static-import DSL | Process-global mutable state: one app per JVM, no parallel tests. |
| `app.ws(path, config)` in core | A WebSocket is a protocol upgrade, so it leaves servlet dispatch: the router, `before`/`after`, `error(status, ...)`, `requestLogger`, `routes()`, and `WebTest` all stop applying to it. Core would be handing out an API that core's own features silently do not cover. It also ends the no-lock-in claim that `WebServer` exists for, since `AppServlet` on another container cannot follow. `jakarta.websocket` is no escape either: its default `Configurator` instantiates endpoints reflectively. Recipe now, `spider-silk-ws` module if it earns one. |
