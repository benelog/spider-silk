# Design Decisions

Why Spider Silk has the shape it has, item by item.

This is the reasoning behind the framework, not a description of it.
What each thing *does* is the [manual](https://spider-silk.benelog.net).
What is kept here is the decision, why it went that way, and what was rejected on the way.
The numbers are load-bearing: the write-ups cross-reference each other by number, and the [rejected list](#rejected--decisions-with-the-reason) at the end closes questions that would otherwise be asked again.

What is still open lives in the [issue tracker](https://github.com/benelog/spider-silk/issues), one issue per item, each carrying the condition that would make it worth doing.

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

Forty-two of the forty-three shipped.
The remaining one is 15b, which is a decision rather than a gap.
One entry, "WebSocket / SSE", split once the two halves were asked the same question and gave opposite answers: SSE is HTTP and rides through `AppServlet`, and WebSocket is a protocol upgrade that does not.

## 1–7 · Server, lifecycle, routing, and the test harness

### 1. Embedded Jetty and lifecycle

`start(port)` binds and returns rather than blocking, because Jetty's threads are non-daemon and hold the JVM up on their own.
`start(0)` plus `port()` is what makes tests portable.

### 2. Server seam

`WebServer` is four methods, and `App.server(factory)` swaps the implementation.
Deliberately *not* `ServiceLoader`: that is reflection, and discovery-by-classpath is the thing this framework avoids.

### 3. Jetty configuration

The settings that usually need tuning are methods.
Three customizers cover everything else, running against the real Jetty objects.
Sessions default on, because `req.flash`/`req.sessionAttr` need them.

### 4. Path-scoped filters

A before-filter that returns a response ends the request, because a guard that turned the caller away must not be followed by the handler answering anyway.
Item 18 made that a signature rather than a convention: the halt used to be inferred from a `bodyWritten` flag, and is now the difference between returning a value and returning nothing.
Deliberate edge: `WebResponse.empty(401)` still halts, since it *is* an answer.
What does not halt is a filter that only reads.

### 5. Route groups

`app.path("/api", api -> ...)` passes the group as an argument.
Spark's static-import equivalent keeps the prefix in process-global state, which rules out two apps per JVM.

### 6. Status-code error handlers

One place to render a 404 or 500, whatever set the status.
A response that already carries a body is left alone, which the sealed `WebResponse.Body` states outright rather than by sniffing the servlet response.
No per-status shorthand: `notFound(handler)` was one, and it went, because the status a shorthand stands for is the one thing the call no longer says.
`error(HttpStatus.NOT_FOUND, handler)` names it, the way `redirect(url, HttpStatus.MOVED_PERMANENTLY)` names its status in decision 24 and for the same reason.
A family that stops at one status is also the harder thing to read: a reader who has seen `notFound` expects `serverError` beside it, and the 500 page is written as often as the 404.

### 7. Test harness

`WebTest.test(app, client -> ...)` returns the JDK's raw `HttpResponse`, so the project's own assertion library stays in charge.

## 8–12 · JSON, static files, the request API, logging, shutdown

### 9. Static file caching

Deliberately left out: pre-compressed variants (`.gz`/`.br`).
Real, and not needed until someone deploys behind something that is not already doing it.

A directory on disk is in, as `StaticFiles.directory(path)`, because uploads and mounted volumes are not on the classpath and had no way in at all.
Three things came with it:

- **`staticFiles(StaticFiles...)`, not one root.**
  The deployment that wants a directory is the one that already has assets in the jar, so a single slot would have made the two exclusive.
  Roots are read in the order given and the first that holds the file answers.
  `staticFiles()` with nothing at all is how you turn file serving off.
- **The traversal guard is ours, not the container's.**
  Jetty and Tomcat answer a `..` with a 400 before the servlet sees it, Undertow does not, and none of that is core's promise to make.
  The rule is therefore enforced in `StaticFiles` and asserted in all three modules' acceptance tests: a regular file whose real path, symbolic links followed, lies under the root's own real path.
  Everything else is a 404, which does not say which of the reasons it was.
  Following links out of the root is therefore refused, which is the answer to "the uploads directory has a symlink in it" that needs no configuration flag.
- **The root is read per request.**
  A volume mounted after start-up needs no restart, and one that is never mounted 404s instead of failing to boot.
  That is the same posture the classpath root already had, where a root with nothing in it still routes.

### 8. `JsonCodec<T>` seam

Codecs stay hand-written, so the mapping is still visible.
They just stopped being re-inlined in every handler.
The four decisions:

- **Two interfaces, not one with two methods.**
  Most codecs are write-only, and a combined interface would make those fill `read` with `UnsupportedOperationException`.
  Split, each half is a SAM and therefore a lambda.
  That is also why `JsonCodec.of(writer, reader)` exists, since a two-method interface cannot be a lambda at all.
- **Codecs live in the web layer**, not on the record.
  A codec on `Deck` would make the domain import `net.benelog.spidersilk.json.Json`, which is the domain depending on the web framework to state its own wire format.
  Core ships the interfaces only.
  Where codecs sit is a convention the example demonstrates.
- **Collections compose** through `JsonWriter.list(...)` and friends: function composition, no reflection.
- **`read` throws, and `req.bodyJson(reader)` turns `IllegalArgumentException` into a 400.**
  The same contract as `pathParamLong`: it returns the value or it rejects the request.
  No `Result` or `Validator` type in core: business rules stay in the service layer, where they already throw.

### 10a. Cookies and repeated parameters

The convenience cookie forms default to `Path=/`, `HttpOnly`, and `SameSite=Lax`, since a cookie worth setting from the server is usually one a script has no business reading.
`params(name)` returns an empty list rather than throwing when nothing matched: an unchecked checkbox group is an answer, not a 400.

### 10b. The rest of the request API

The query string is parsed here rather than through the servlet API, which merges it with the form body.
`formParam` is what is left once the query values are subtracted **by count, not by position**, so a name appearing in both places still splits correctly.
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
The hook is Jetty's own `setStopAtShutdown` rather than a thread of ours: one per JVM, deregistered on stop, so a suite that starts a server per test does not accumulate them.

## 13–16 · Introspection, routing index, streaming, threads

These are what decides whether this is more than a teaching framework.

### 13. Route introspection *(the differentiator)*

`app.routes()` is the same list the dispatcher walks, read back as data, with **no reflection at all**, where Javalin needs a plugin.
The four decisions:

- **The handler is left out.**
  It is a lambda, so the only name it has is what reflection would dig out of its synthetic class.
  `PathPattern` stays package-private too: what is exposed is plain strings, not a matching engine.
- **No description, no response types — at first.**
  `PathPattern`'s `{deckId}` *is* OpenAPI's path-template syntax verbatim, so the minimal shape already yielded a valid document.
  A description would mean a parameter on all seven registration methods on both `App` and `RouteGroup`, which is an annotation with the reflection taken out.
  So it waited on a need rather than on a nicer document, and the overloads were purely additive whenever that need arrived.
  It arrived with 29: decision 30 added them, and the wait cost nothing, since neither `routes()` nor the export changed shape to take them.
  Response types are still out, for the reason 29 gives.
- **The overview page and the OpenAPI export are not in core.**
  A spec format is not the web tier, and its version drift is not a web framework's to own.
  The export earned its keep and became a module rather than entering core, which is decision 29.
  The overview page stays the example's, since a page is a template and a look, which is nobody's to ship.
- **A second list for the guards, not a richer route.**
  "Which guard covers this path" was left out at first as nice-to-have, and came back as `guards()`: the `before`/`after` filters and the `error(status, ...)` handlers, read off the same registrations and landing beside `routes()` without changing it.
  It is a sealed `Guard` of three records, `Before(path)`, `After(path)`, and `Error(status)`, because a filter is scoped to a path and an error handler to a status, and one record with a component that is null half the time would report that dishonestly.
  A filter's coverage is a pattern and not a path, so decision 4's trailing `*` is reported verbatim rather than expanded, and matching against it stays the dispatcher's job: still plain strings, still not a matching engine.
  The `exception(Type, handler)` handlers stay out, since neither a path nor a status describes where a type-scoped handler applies.

The automatic HEAD and OPTIONS answers do **not** appear: `routes()` lists what was registered, which is the honest answer for a framework whose pitch is that only what you register runs.

### 14. Router indexing

Routes are bucketed by method and first literal segment, and a lookup merges the candidate lists **by registration index**, so the tie-break is exactly what it was when `find` scanned everything.
That is the constraint the index had to preserve: `/study/today` registered before `/study/{mode}` still wins.

### 15a. SSE

`text/event-stream` is plain HTTP, so it goes through `AppServlet` unchanged and a servlet deployment gets it too.
That is the whole reason this half is in and 15b is out.
Core adds the framing and nothing else, with no new dependency.
The five decisions:

- **An SSE endpoint is a `get` route**, not a registration of its own, so `routes()`, filters, and the request logger all still reach it.
  That is exactly the argument that sinks 15b, so the implementation had to keep it true.
- **The events are strings.**
  `stream.send(writer.write(value).toJson())` rather than an overload taking a codec: the mapping stays visible at the call site, the same rule as everywhere else.
- **The stream is blocking and holds its thread.**
  One open stream is one server thread, which is the honest servlet answer and the one `AppServlet` can keep on any container.
  `AsyncContext` would be a second dispatch model in core for a feature that has yet to prove it needs one.
  The recipe for many streams is item 16's virtual threads, where a parked thread costs almost nothing.
- **Ending a stream is never an error.**
  A write to a stream that has gone throws `SseStream.Closed`, caught by that one type so an IO failure in the handler's own code still reaches the exception handlers.
  A write *after* `close()` throws the same thing, because otherwise every graceful shutdown would log a handler failure per open stream.
- **Graceful shutdown is resolved, not documented away.**
  An open stream is a request in flight that never finishes, so item 12's drain would wait it out and report failure.
  `App` closes registered streams as the first statement of `stop()`, and the writes are synchronized because that close comes from another thread and must not cut a frame in half.

Two deliberate edges.
A HEAD of an SSE route answers with the headers and never runs the handler, since a stream with the body thrown away would never end.
The heartbeat stays the application's, because a connector idle timeout is a number core has no business choosing for anyone.

### 15b. WebSocket in core — *rejected*

See [the table below](#rejected--decisions-with-the-reason).
It is not in core and will not be.
Where it does live is 15c.

### 15c. WebSocket as `spider-silk-jetty-websocket`

The half 15b rejected was `app.ws(path, config)` on `App`.
What was left open was narrower: whether the Jetty recipe an application writes for itself is worth wrapping, and the answer is a module whose name carries the tie to one server, the way 22's `spider-silk-tomcat` does.
`WebSockets` maps paths to a `WebSocketHandler`, and core does not change at all to allow it: the module is a `Consumer<Server>`, which is what item 3's `customizeServer` already took.

Five decisions inside it:

- **Jetty's `Handler`-level WebSocket, not the `ee10` one.**
  The two artifacts share a name.
  The `ee10` one is the servlet-container integration, and arrives with annotation scanning, ASM, CDI, and JNDI behind it.
  Ten jars against thirty-four, and none of the ten does bytecode scanning, which is the difference between a module this framework can carry and one it cannot.
- **`customizeServer`, not `customizeContext`.**
  Jetty builds its WebSocket container from the `Server`, from its buffer pool and its executor, and a context is not linked to a server until after the context customizers have run.
  The hook that reads right in a sketch is not the one the API can actually use.
- **The reflection is confined to one class.**
  Jetty binds an endpoint's callbacks by looking its methods up and taking a `MethodHandle` to each, cached per endpoint class.
  Every connection the module opens is the same class, its own `SessionListener` adapter, so that is the one class Jetty ever looks up and an application's `WebSocketHandler` is reached through an interface call.
  Same confinement as jte's generated classes, arrived at the same way: not by avoiding the library's mechanism, but by making sure it never points at a class the application wrote.
- **`Session` is Jetty's and is not wrapped.**
  A facade would buy portability the module's own name has already ruled out, and would hide which Jetty knob was turned, which is item 16's argument applied again.
- **A refused upgrade is answered.**
  Jetty's creator contract leaves that to the creator: return null and the handshake is never written, so the client waits on a response that never comes.
  Returning null from a `WebSocketFactory` answers 403 instead, or the error status the factory set.
  That, and mapping a path, is the whole of what the module does beyond passing Jetty through.

What does not follow an upgrade is unchanged and is the point of 15b: the router, `before`/`after`, `error(status, ...)`, the request logger, `routes()`, and `WebTest` all stop at the servlet.
The module's own tests say so from the other side.
They start a real Jetty and talk to it with the JDK's `java.net.http.WebSocket` client, because no harness could stand in.

### 16. Virtual threads

Documented, not wrapped.
No `virtualThreads()` method.
It would be two lines of the server's own API behind a name that hides which knob was turned, and the choice is not ours to make: it pays off only when handlers block, and `synchronized` around the blocking call takes the benefit back.

## 17–20 · Structural

### 17. Split `spider-silk-test`

The harness is its own module, so core's jar carries no test code at all.
Core's own tests are a consumer of it like anyone else.
That looks circular and is not, since the arrow runs core's *test* source set → the harness → core's *main* source set.

### 18. `WebContext` split into `WebRequest` and a sealed `WebResponse`

"The request, the response, the session, and every way of answering" is four responsibilities wearing one name.
Splitting it along the HTTP metaphor was the obvious half.
The other half was making `Handler` *return* the answer, so the compiler checks that every branch answers and answering twice stopped being expressible.
The five decisions:

- **`WebRequest`/`WebResponse`, not `HttpRequest`/`HttpResponse`.**
  `WebTest`'s own idiom asserts on `java.net.http.HttpResponse`, so the JDK name would collide inside this framework's own test style, and `HttpServletRequest` is one import away in the other direction.
- **An envelope around a sealed `Body`, not a sealed response.**
  Status, headers, and cookies are the same for every kind of answer, so they live once.
  What differs is the body, and *that* is the sealed type.
  A `switch` over the kinds needs no default case, and the `with`-style methods stay type-stable, which is what lets an `AfterFilter` be `WebResponse -> WebResponse` at all.
- **Templates are rendered during dispatch, not while writing.**
  This is the trap the return-based model sets: a body produced after dispatch has left its `try` block can no longer reach `app.exception(...)`.
  `Stream`, `Sse`, and `Raw` genuinely cannot be materialized, and their failures land in the servlet log with a best-effort 500.
  That is the honest limit, since the headers are already committed.
- **Filters return `null` to continue**, rather than `Optional`: an observational filter stays a one-liner either way, and `Optional.empty()` reads worse in the common case.
  After-filters run only on a route that completed normally: not after a before-filter answered, and not on an exception handler's output.
- **`…Handler` answers a request; `…Writer` fills a body.**
  Without the rule, `RawHandler` read as a relative of `Handler` and reviewers guessed one extended the other.
  `Handler` itself stayed: it is the central type, `App.error(status, Handler)` uses it too, and it is what Javalin and Helidon call the same thing.

Two behaviours changed on purpose: `redirect` sets a `Location` header rather than calling `sendRedirect`, and cookies moved to the response while the session and flash stayed on the request, since a session outlives the response and cannot be a value returned from one.

### 19. The example's routing table, in one place

A `Controller` interface with `register(App)` read like good structure and was the wrong shape: the routing table became the *union* of what seven `register` methods each decided, so "what does this application answer?" was answered by reading seven files.
That is what annotation scanning produces, arrived at by hand.
Item 13's whole claim is that `app.routes()` is trustworthy because routing is an explicit list.
The three decisions:

- **A handler arrives in one of three shapes, chosen by how much there is to hold**: a lambda when there is no state worth a class, an `Action` class when a class answers exactly one route, public methods registered by reference when one class answers several.
- **The handler methods are public, and that is the trade.**
  It buys the property that mattered more: the path and the method that answers it sit on one line, in a list nothing else contributes to.
- **`Action` is a class-naming convention, not a rename of `Handler`.**
  See the table below for why the interface kept its name.

### 20. `TestRequest`, and no mock library

Item 18 left handler tests needing no `MockHttpServletResponse`.
The request half stayed behind, so a framework whose pitch is that nothing is resolved by name at runtime was answering "how do I test a handler?" with "add two Spring artifacts".
The four decisions:

- **`spider-silk-test`, never core**, with the servlet API `compileOnly` exactly as core takes it, so what the module puts on a consumer's classpath stays core and the JDK.
  Depending on the bundled server's transitive copy would have tied the test module to the server the `WebServer` seam exists to keep replaceable.
- **A builder for the request, not for `WebRequest` or `WebResponse`.**
  Neither wants one: `WebResponse`'s `with`-style methods already are a builder, and `WebRequest` is a read view over the servlet request.
  What actually needed building was that.
- **A hand-written stub, not a mock library.**
  It answers the servlet methods `WebRequest` reads and throws from the rest, so a method added to `WebRequest` and missing here fails loudly rather than returning a quiet null.
  The same bet as the rest of the framework: a small explicit thing over a general mechanism.
- **Faithful where a handler can tell the difference.**
  `getParameterValues` returns query values then form values, which is what makes 10b's subtraction behave as it does behind a container.
  That is precisely what a mock holding one parameter map cannot show.

A query string in the path is rejected rather than parsed, since a path that quietly kept its `?` would fail much later as a routing mismatch.

## 21 · The status type

### 21. `HttpStatus`: an enum, not an int

An int accepts `42` and `4040` as readily as `404`, and the enum makes a status that does not exist unwritable.
That is the same bet as `paramEnum` and the rest of the typed extraction.
Three calls inside that decision:

- **The IANA registry, under RFC 9110 names**: `CONTENT_TOO_LARGE` and `UNPROCESSABLE_CONTENT`, not the older names Spring readers know.
  Deprecated registrations are left out, and the numbers were cross-checked against an existing constant table rather than written from memory, since a transposed digit is exactly the bug the type exists to prevent.
- **`of(int)` is the sanctioned runtime path, not a loophole.**
  A handler mirroring an upstream answer has a number, not a name.
  `of` throws on a code the registry does not know, so the enforcement moves to the boundary instead of disappearing.
- **The getter answers the enum too**, because a setter taking `HttpStatus` and a getter handing back `int` would be the asymmetry `paramEnum` avoids.
  `WebTest`'s client is the JDK's, so `statusCode()` there stays an int: the boundary is this framework's API, not other people's.

## 22–23 · The other servers

### 22. `spider-silk-tomcat`, with Jetty still the default

Decision 2 cashed in: `WebServer` was made four methods so a second server would be a small job, and this is what proves it.
A module rather than a class in core, following 17: a tie to one server is stated in the artifact's name.
The dependency tracks Servlet 6.0, matching core's servlet API, so the three servers stay on one specification level.

Jetty stays the default, which is the actual decision.
Tomcat wants a working directory on disk where Jetty runs diskless, and logs through JULI rather than slf4j.
The one that cost real code is that it has no graceful shutdown of its own, so decision 12's guarantee is rebuilt here by pausing the connector and draining the request pool.
Its shutdown hook and its JVM lifetime are the same story: both are things decision 1 and decision 12 get from Jetty for free and have to be assembled for Tomcat.

Two asymmetries are left visible rather than papered over.
There is no `sessions(false)`, because Tomcat offers no way out of its session manager and a method that silently did nothing would be worse than its absence.
`stopTimeout` only has something to wait on while the connector runs a thread pool, so handing `executor(...)` a virtual-thread executor turns the drain into a no-op.
That is said in the Javadoc rather than worked around, since the alternative is tracking in-flight requests ourselves, a second lifecycle model in a module that exists to have none.

What Tomcat buys is not technical: the operational knowledge an organisation already has, the runtime Spring Boot defaults to for anyone migrating off it, and a security-advisory pipeline most enterprise processes already track.
That is a real reason to want it and not a reason to make it the default, which is exactly what a seam is for.

### 23. `spider-silk-undertow`

Two implementations prove a seam works.
The third tells you what it costs, which is why this was built rather than asserted.

Undertow turned out to be the *easiest* of the three to embed, which was not the expected answer.
Its graceful shutdown is a first-class handler that counts requests, so decision 12's guarantee is a wire-up rather than the reconstruction Tomcat needed.
Because it counts requests rather than shutting a pool down, it is also the only one of the three where the drain survives being handed a virtual-thread executor.
It needs no working directory, and its logging finds slf4j by itself, so neither of Tomcat's environmental costs applies.

So the default did not move again, and the reason is worth being honest about: it is not technical.
Jetty is still the one whose lifecycle is entirely its own, and that is a real difference in how much of this project's code sits between an application and its server.
Undertow's disadvantage is reach rather than design: it is a WildFly component rather than a standalone product, so operational familiarity and tooling are thinner than Tomcat's.
That is a deployment fact, not an engineering one, and it belongs in the manual as advice rather than in core as a default.

One structural note.
Each server module carries its own copy of the acceptance tests, deliberately: they assert what *core* promises against each container in turn.
Factoring them into one parameterised suite would mean a shared module that every server module depends on: a dependency built to save duplication in tests.

## 24 · The redirect default

### 24. `redirect` defaults to 302, and only accepts a 3xx

The choice between 301 and 302 is not symmetric, and that asymmetry decides it: a 302 can be taken back, a 301 cannot.
Browsers and intermediaries cache a 301, often indefinitely, so a wrong one keeps sending visitors to the wrong place long after the code is fixed, with no way to call it back.
Every comparable default agrees: `HttpServletResponse.sendRedirect`, Javalin, Spark, Spring MVC, Express, Rails, and Django all send 302, so a framework defaulting to 301 would be surprising in the one direction that cannot be undone.

`redirect(location, HttpStatus)` is how an application says otherwise, and it takes the enum for decision 21's reason.
It rejects a status outside 3xx: a `Location` header on a 200 is not a redirect, and failing at the call beats shipping a response no client will follow.

No `redirectPermanent(...)` convenience method.
`redirect(url, HttpStatus.MOVED_PERMANENTLY)` already names the status out loud, and a second spelling would only hide which one was chosen.
That is the same argument that kept `virtualThreads()` out in decision 16.

## 25 · The Gradle plugin

### 25. `spider-silk-gradle-plugin`: packaging conventions, in an included build

The example's build file had grown a packaging block any application would copy verbatim: jte precompilation with its native-resources extension, Jib with a JRE base and a restated `targetCompatibility`, the `-Pnative` switch with the task dependency Jib's extension forgets to declare, and a `resolveDependencies` task for Dockerfile layer caching.
Code copied unchanged into every consumer is a convention plugin by definition, so it became one: `net.benelog.spidersilk`, an included build so the example applies it exactly as a published application would.

The no-reflection principle is about the runtime, and this framework already puts its magic at build time: precompiled templates, generated reflect-config.
A build-time convention plugin therefore extends the pattern rather than breaking it.
The line it holds is that only packaging every application shares goes in.
The example's `domainReflectConfig` stays out, because it is the price of that app's reflective row mapper, not a shared convention.
Everything the plugin sets lands before the build script's own blocks run, so overriding is plain `jib { }` / `graalvmNative { }` configuration, and every convention has its expanded form in the manual for a build that would rather own it.

The cost accepted: the plugin pins jte, Jib, and the GraalVM build tools, so their upgrades now arrive as plugin releases, and its DSL joins the API that freezes at 1.0.
A Maven counterpart was initially deferred, then shipped as decision 26.

## 26 · The Maven counterpart

### 26. `spider-silk-maven-parent`: a parent POM, not a Maven plugin

Maven's counterpart of a Gradle convention plugin is not a Maven plugin: a Mojo runs goals, and cannot declare other plugins' configuration.
Inheritance is where Maven puts build conventions, so decision 25's conventions ship for Maven as a parent POM.
Its `pluginManagement` entries stay inert until the child declares the plugin, which makes the declaration itself the opt-in, the way `spiderSilk { jte() }` is on the Gradle side.

The one convention that could not carry over structurally is the `-Pnative` tag switch: a profile cannot append to a value the child wrote, so the parent defaults a `spider-silk.image.tag` property to `latest`, the `native` profile flips it, and the child places the placeholder in its image name.
That is a documented convention where Gradle has an override.
The parent POM is a hand-written file published verbatim by a Gradle module, and the publication fails if the file's coordinates and the module's ever drift.
Gradle's POM DSL has no model for `pluginManagement` or profiles, and generating XML through it would only obscure a file whose whole value is being readable.

The versions diverge where upstream does: Jib's Maven plugin stops at 3.5.2 on Central while its Gradle plugin is at 3.5.4, and the parent pins what exists rather than what would be symmetric.

## 27 · The three every deployment turns on

### 27. CORS, gzip, and security headers: named on `App`, not filters and not plugins

All three are the web tier, so all three are core-eligible, and the question the issue left open was what shape they take.
A helper each, registered as an ordinary `before`/`after` filter, was the tempting answer and is the wrong one: a filter runs only once a route has matched, and what these three have to reach is precisely the answers where none did.
A CORS preflight is an `OPTIONS` nobody registered a handler for, which is decision 10b's automatic answer.
A cross-origin 404 has to carry the CORS headers, or the browser reports a CORS failure rather than the 404 that happened.
Security headers belong on an error page like any other page, and the biggest thing most applications send is a static file, which decision 9 answers before routing gets as far as a filter.
Making filters run for unmatched paths to fit them in would change what `before`/`after` mean for every application that already has one.

So each is one named method on `App` taking one inert value: `cors(Cors)`, `gzip(Gzip)`, and `securityHeaders(SecurityHeaders)`.
Each is applied in `AppServlet` between working the answer out and putting it on the wire, which is the one point every path meets.
That is the shape decision 9's `staticFiles(StaticFiles)` and decision 11's `requestLogger` already have, and it is deliberately not Javalin's bundled-plugin registry: nothing registers itself, nothing is on until it is named, and reading the three lines is reading the whole of what they do.

Compression is a transform over the sealed `WebResponse` of decision 18 rather than a servlet response wrapper, which is what decides its cases.
A body already in memory is compressed there and then, so the length it announces is the length it sends.
A `Stream` body wraps the output stream as it is written, so a large file never lands in memory whole and its `Content-Length` goes.
SSE is excluded by what it is: a stream buffered until it is worth deflating is a stream that no longer arrives.
`Raw` is excluded by definition.
It also had to agree with decision 9's validators: a compressed answer's `ETag` is marked weak, since the two bodies are the same file and not the same bytes, and `StaticFiles` already accepts the weak form back, so revalidation keeps working without an inbound rewrite.
Every compressible answer carries `Vary: Accept-Encoding` whether or not it ended up compressed, which is what `WebResponse.vary(field)` exists for: `header(name, value)` overwrites, and CORS and compression each add a field to the same header.

Gzip's stream wrapping is the one container-sensitive piece, so it is in the acceptance tests decision 22 and 23 mirror onto Tomcat and Undertow.

## 28 · The Accept header

### 28. Content negotiation: `accepts(...)` answers with a type, not a serializer

The gap was real: a handler answering HTML to a browser and JSON to a client parsed `Accept` itself, quality values and all.
The shape it closed with is what keeps it out of the reflection the framework exists without.

**`accepts(candidates...)` returns one of the strings it was handed.**
Not a parsed media-type object, and not a serializer chosen on the handler's behalf: the answer is a value the handler wrote on that line, so the branch is an ordinary `switch` and the compiler still sees every case.
The reflective half of what the other frameworks call content negotiation, picking a writer once the type is known, is decision 8's territory and stays refused.

**It never returns null.**
A caller that will take none of what is offered is a 406, the same contract as `param` answering 400 and `pathParamLong` throwing rather than handing a `null` onward.
That is decision 10b's rule, applied to one more question.
A caller that sent no `Accept` at all gets the first candidate, since the specification reads an absent header as "anything", which makes the argument list the order the *handler* prefers.

**One parser, two headers.**
`AcceptHeader` is package-private and answers `Accept-Encoding` for decision 27's `gzip()` as well, because both are the same grammar of comma-separated values with a `q=` weight.
`q=0` is a refusal rather than an absence, and is honoured even where a wildcard would otherwise have covered the type.
Ordering is by specificity where the weights tie: `text/html` before `text/*` before `*/*`.
Only `acceptedTypes()` can observe that, since `accepts` matches each candidate against the closest entry that names it.

**Asking is what declares the dependency.**
A handler that calls either method gets `Vary: Accept` on its answer without saying so, because the answer now depends on a request header and a shared cache must not hand JSON to the next browser.
That is the same bookkeeping `gzip()` does for `Accept-Encoding`, and it is on the request rather than the response so that a handler cannot ask the question and forget the header.

`acceptedTypes()` is the parsed view underneath, for the handler that has to decide something `accepts` cannot phrase.
It is empty for a request with no `Accept`: a caller that will take anything, which is not a caller that will take nothing.

## 29 · The OpenAPI export

### 29. `spider-silk-openapi`, outside core

Decision 13 left the export in the example with a condition on it: the reading was worth writing once, but not worth putting in the artifact every application depends on.
15c had just settled the same question the same way, with a module whose name carries what it is tied to, and this one is tied to a document format instead of a server.
Four decisions inside it:

- **A module, not a method on `App`.**
  Pinning `3.1.0` inside core would sign core up to track someone else's document version forever, and an application that wants no spec at all would carry the pin anyway.
  In a module the pin is a dependency an application chooses, which is what 17's split bought for the test harness.
- **Core does not change by one line.**
  That is the claim being proved rather than a convenience: `routes()` was already enough, since `{deckId}` *is* the path template and a `Route` is a record.
  A module that needed a new accessor would have been evidence that 13's minimal shape was too minimal, and it needed none.
- **`document(title, version, routes)` — a list, not the `App`.**
  Which routes a document covers is the application's call, not the reader's: an app serving HTML alongside its API passes the `/api` routes, and this module cannot tell a page from an endpoint.
  Taking the `App` would have made the guess mandatory and hidden it, so what the module takes is the list 13 exists to hand out.
  Title and version are arguments for the same reason a default would be wrong: OpenAPI requires both, and neither is this module's to name.
- **A wildcard throws rather than being skipped.**
  The example used to drop `*` routes quietly, which is fine when the filter and the reader are the same forty lines and dishonest once they are not: a document silently missing a route claims the application answers less than it does.
  Refusing it moves the filter to the call site, where it is visible, the same trade as `HttpStatus.of` throwing on a code the registry does not know.

What is *not* in the document is the other half of the decision: no request or response schema, no server list, no security scheme.
None is derivable from a route, so each would have to be declared at the registration site.
That is the door 13's deferred description parameter came through, and decision 30 opened it exactly one line wide.

## 30 · Route descriptions

### 30. An overload, not an annotation

13 deferred this with the condition written down: a description is worth a parameter on fourteen methods only when something reads it, not when it merely makes a nicer document.
29 is what made something read it.
The export turns the route list into the artifact a client generator or a Swagger UI consumes.
In that document the one field a reader looks at first, `summary`, was the one field nothing could fill, because a method and a path do not say what a route is *for*.
Three decisions:

- **A `String` between the path and the handler.**
  `get(path, description, handler)` on `App` and on `RouteGroup`, seven methods each.
  Last would read worse: the handler is a lambda or a method reference, and an argument after it pushes the closing brace away from the call.
  The parameter is what an annotation would be with the reflection taken out, which is the point rather than an accident: the text lives at the registration site either way, and the difference is whether reading it costs a classpath scan.
- **A third component on `Route`, and `""` for a route without one.**
  Not null: the guard write-up rejected a record whose component is null half the time, and this is the same rule read the other way round.
  A `Guard` needed three records because a path and a status are different things.
  A described and an undescribed route are the same thing, one of them undocumented, so one record with a documented empty string reports it honestly and a reader prints `description()` without a null check.
  `Route(method, path)` stays as a second constructor, so every existing fixture and reader compiles unchanged.
- **The framework never reads it.**
  Nothing in the dispatcher, the router index, or the duplicate check sees the string.
  `routes()` hands it back and that is all.
  A description that changed behaviour would be a configuration language growing inside a documentation field.

`spider-silk-openapi` maps a non-empty description to the operation's `summary` and omits the field entirely when there is none, rather than writing an empty string a UI would render as a blank line.
29's second bullet still holds as it was written: core did not change to let the export be a module.
It changed here for a need of its own, the route list saying what a route is for, and the export reads that the way it reads everything else, off the list.

## 31 · The pre-compressed sibling

### 31. `.br` and `.gz` siblings: `precompressed()` on `StaticFiles`, not on by default

Decision 27's `gzip()` deflates the same unchanging stylesheet again on every request that asks for it, and brotli it cannot answer at all, since the JDK ships no encoder.
A `.br` or `.gz` file a build left next to the asset closes both halves at once, and is the only way core would ever answer brotli.
Five decisions inside it:

- **Off until it is named.**
  This is the one that was genuinely open, because two rules pointed opposite ways: 27's is that nothing is on until it is named, and 9's `classpath:/public` is already served without being asked for.
  The argument for a default is that placing an `app.css.br` *is* the naming, and it is a good argument.
  It lost to what happens to an application that upgrades.
  A `.gz` left behind by a pipeline that no longer runs would start being served by a version bump, which is a content change nobody wrote, and every static request would pay up to two extra lookups to find the siblings that are usually not there.
  So it is `precompressed()`, one more method on the value 9 already hands to `staticFiles(...)`.
- **The validators come from the original, whichever body is sent.**
  The two encodings are one resource, so the `ETag` is derived from the original's modification time and length and the `Last-Modified` is the original's, with the tag marked weak on an encoded answer.
  That is the same reading 27 gave its own compressed answers, and `StaticFiles` already accepted the weak form back.
  A browser that cached the plain body and revalidates while accepting brotli keeps its 304.
  `Content-Type` comes from the original name for the same reason read the other way: a `.gz` extension is the encoding, not the type.
- **`Vary: Accept-Encoding` from `StaticFiles`, not from `Gzip`.**
  27 puts the field on every compressible answer, and this is the case it could not reach: `Gzip` stops at the first sight of a `Content-Encoding`, so the answer it must not touch is exactly the answer it would never mark.
  A root with `precompressed()` on therefore varies every answer itself, sibling found or not, and `WebResponse.vary` not repeating a field already listed is what lets both add it.
- **A stale sibling is passed over rather than declared the build's problem.**
  A `.gz` older than the file beside it is a build that did not rerun, and serving it is serving content that no longer exists: a wrong answer, not a slow one.
  Both timestamps are already read, so comparing them costs nothing.
  A time neither file reports is no evidence of staleness, and the sibling still answers.
- **Brotli over gzip, fixed.**
  Not by the client's `q=` ordering: brotli is the smaller of the two and the one core cannot produce any other way, so where both siblings exist and the client takes both, the choice is not the client's to reverse.
  Whether it takes them at all is read through `AcceptHeader`, decision 28's one parser, rather than by reading `Accept-Encoding` a second way.

Unlike 27's stream wrapping, none of this touches the output stream: it is a file's bytes and two headers.
So it is not mirrored onto the Tomcat and Undertow acceptance tests of 22 and 23.
The external-directory half that used to sit beside this in the deferred list shipped separately, as `StaticFiles.directory(path)`.

## 32 · The public surface, before 1.0

### 32. One name — `net.benelog.spidersilk` — and a pass over what is public

1.0 is the promise that what is public stays public and keeps its shape, so everything public becomes permanent on the day it is made.
Nothing had ever been read with that in mind: a type was public because a caller in another package needed it, and a method was public because making it so was the shortest way past a compiler error.
Two questions came out of that, and the second one had to wait for the first, since a package rename decides what half the answers are written in.

**The name.**
There were three of them for one thing: the group id `io.github.benelog.spidersilk`, the package root `spidersilk`, and a module name derived from the project name.
They are now one string, `net.benelog.spidersilk`, and it is the group id, the package root, the `Automatic-Module-Name` in every manifest, and the Gradle plugin id.

- **`net.benelog`, not `io.github.benelog`.**
  `io.github.<user>` is the coordinate Maven Central lends to a publisher with no domain of its own, and this project has one: the manual is served from `spider-silk.benelog.net`.
  A group id is a claim of ownership, and the domain is the thing owned.
  The GitHub handle is a name that moves if the account does.
- **`net.benelog.spidersilk`, not `net.benelog`.**
  The artifact ids already say `spider-silk-*`, so the shorter group would read fine.
  It would also put two group ids in one repository, because a Gradle plugin id has to be `net.benelog.spidersilk` whatever the jars use, and the plugin marker artifact's group *is* the plugin id.
  A domain alone also stops distinguishing anything the moment a second project publishes under it.
  Appending the project to the domain is what the multi-module projects a reader already knows do, such as `org.springframework.boot:spring-boot-*` and `org.eclipse.jetty:jetty-*`.
  It is also what makes the group id and the package root the same string, which is the whole point of the exercise.
- **The module name is the package root, hyphens read as dots.**
  It used to be `'spidersilk.' + project.name.substring('spider-silk-'.length())`, which for `spider-silk-jetty-websocket` produced `spidersilk.jetty-websocket`.
  That is not a legal Java module name, so that jar was unusable on the module path, while its package was `spidersilk.jetty.websocket` all along.
  The hyphen in a project name is the package separator it reads as, so it is substituted for one: the name a build writes on the module path and the name a source file writes in an `import` are now the same string, which is the property that made the alignment worth doing.

**What is public.**
The pass found less to close than expected, because most of the surface was already deliberate, and the useful outcome was writing down *why* in the places where the answer is "it stays".

- **`RouteGroup.resolve(String)` is private.**
  The one method that was public for no reason: nothing outside the class ever called it, and it is how a group builds the path it hands to `App`, not something a caller holding a group has a use for.
- **`WebRequest`'s constructor stays public, with the reason restated.**
  It carried `Public so a test can build a request and call a handler method directly`, which names a caller instead of a contract.
  A contract stated as "for tests" is one nobody can tell the boundaries of.
  What is true is that anything holding a servlet request and knowing what the path variables should be can build the argument a handler takes.
  `TestRequest` in `spider-silk-test` is one such caller, in another module, which is why the constructor cannot be anything narrower.
- **`raw()` stays, documented as what it costs.**
  Core itself reads through it, and `WebResponse.raw(...)` already blesses raw servlet access on the way out, so removing the one on the way in would leave a one-directional escape hatch.
  What it is missing is the warning.
  What is read through it is read behind the framework's back: `accepts` records that an answer varies by `Accept` and reading the header directly does not, and a body consumed there is a body `body()` can no longer read.
  A handler that uses it is also tied to the servlet API rather than to this one.
- **What escapes is frozen where freezing it is possible.**
  Two leaks were real.
  `queryParams(name)` handed back the live `ArrayList` out of the map cached on the request, so a caller that altered it altered what every later read of that request saw.
  `cookies()` built a fresh `LinkedHashMap` and handed it over unwrapped.
  Both are frozen now, with `Collections.unmodifiableMap` and not `Map.copyOf`, since the order the request sent them in is part of the answer.
  Where the element is mutable by nature the javadoc says so instead: a `jakarta` `Cookie` is a mutable object and `WebResponse.cookies()` hands back the ones it was given, the `byte[]` of a `Bytes` body is held rather than copied because a download's second copy is exactly the cost the byte array was avoiding, and a `Template`'s model is held because a template model takes null values that the unmodifiable copies refuse.
  A caveat a reader can act on beats a defensive copy that quietly doubles the memory of the one case that cannot afford it.
- **`AppServlet` stays non-final, and now says why.**
  A `web.xml` names a class and calls the no-argument constructor the container requires, so a subclass that builds its `App` and passes it up is the only way to deploy that way at all.
  What the subclass gets is the servlet lifecycle and nothing else: `dispatch` and `write` are private, so the two halves of decision 18's split are not an extension point.
- **`net.benelog.spidersilk.server` and `net.benelog.spidersilk.test` were read and left as they are.**
  The server package is decision 2's seam: `WebServer`, `WebServerFactory`, and the bundled `JettyServer`.
  It sitting in core while `TomcatServer` sits in a module named after its server is the asymmetry of *being the default*, not an oversight: core's server is core's, and the ones core does not bundle name what they are tied to.
  In the test package `StubServletRequest` and `TestClient`'s constructor were already package-private, and the three public types are the three a test calls.

**Not yet: japicmp or revapi.**
Binary-compatibility tooling compares a build against a baseline, and there is no released baseline to compare against until 1.0 exists.
It belongs to the release after this one, when the first frozen surface is on a repository somewhere.

## 33 · JSON too big to hold

### 33. Streamed JSON and NDJSON, on the `Stream` body already there

`WebResponse.json(list, JsonWriter.list(w))` builds every element as a tree and then one string holding all of them, so a large answer is in memory twice before a byte of it is sent.
That is the right trade for an answer that fits and the wrong one for an export.
`jsonArray(sink -> ...)` and `ndjson(sink -> ...)` write the elements as they are produced, and `req.bodyNdjson(reader)` reads a body the same way.
The decisions:

- **No new `Body` kind.**
  Both are a `WebResponse.stream(...)` body underneath, built by two static factories that supply the framing.
  A seventh member of the sealed `Body` interface would have been a change every server module, every filter, and the compression had to answer for, in exchange for nothing the existing one does not already do.
  What that inheritance costs is stated rather than fixed: the headers commit before the writer runs, a HEAD runs the writer to find the length, and gzip covers it, since `application/x-ndjson` was added to `Gzip.DEFAULT_TYPES`.
- **The framing belongs to the response, the mapping to the writer.**
  A `JsonSink` takes one value at a time and the brackets, commas, and newlines are the factory's.
  So the same hand-written `JsonWriter<T>` serves a held answer and a streamed one: this is a different way of framing output, not a different way of mapping it.
- **`JsonSink.write` throws `UncheckedIOException`, not `IOException`.**
  The values come from a database cursor, and a row callback is a `Consumer`.
  A checked exception would have made `card -> sink.write(card, CARD)` illegal there and forced a wrapper at every call site, for an exception whose only meaning is that the client is gone and the response is already committed.
  `req.body()` had set the precedent.
- **NDJSON is bulk transfer; SSE stays the live one.**
  Each line stands alone, so a consumer acts on record one without waiting for the last and a cut-off transfer leaves whole records rather than an unclosed document.
  What it does not do is flush per value or reconnect, which is decision 15a's job and the boundary between the two.
- **`bodyNdjson` is lazy, and says which line.**
  A hundred thousand records are never a list, and a rejected line answers 400 naming it: the report a large body needs, which one big array cannot give.
  The cost is that the failure happens where the stream is consumed, so it must be consumed before the response is returned.
  The javadoc says so.
- **No streaming parser in core.**
  `bodyJson()` builds the whole tree because a tree is what a hand-written `JsonReader` reads, and a pull parser would be a second JSON API to keep.
  For a single document too large to hold, the answer is NDJSON.
  Where the format is not the application's to choose, `bodyStream()` hands the bytes to a parser from another library.

**And the seam for that library was written down.**
`WebResponse.json(String)` and the new `bodyStream()`/`bodyReader()` are the whole of it, which the manual now has a page for.
Hand-written mapping is core's decision for core, not for an application with a wire format it does not own.
Worth recording is what an external binder does and does not keep.
avaje-jsonb generates an adapter per type at compile time with no reflective fallback, so a native image needs no entry.
Its field names still come from the record's components, though, so a rename changes the wire silently, which is the *stronger* promise the rejected `json(Object)` was rejected for.
No reflection was never the whole rule.
It was the mechanism by which the rule was kept.

## 34 · The contract, checked against the code

### 34. Six places the contract and the behaviour disagreed

The API states one contract for input, "a value or a 400", and six places in the code did something else.
Decision 32 read what was public.
This one read what the public things did.
Each fix is small, and each would have cost more after 1.0, because correcting a behaviour a caller has coded around is the breaking change that looks like a bug fix.

- **`Json.JsonException`, a subtype of `IllegalArgumentException`.**
  `Json`'s accessors threw the plain type, and so did `pathParam` for a variable the pattern never declared.
  An application that maps `IllegalArgumentException` to 404, as the example did, therefore answered 404 for a body that failed to parse and for a typo in a handler.
  The subtype keeps decision 8's reader contract: a reader throwing the plain type for a rule of its own is still a 400 through `bodyJson(reader)`.
  It also lets an application map parsing failures on their own.
  The `pathParam` case became `IllegalStateException`, since a mismatch between a pattern and the handler reading it is not bad input.
- **The most specific exception handler runs.**
  It used to be the first registered that matched, so `Exception.class` registered first made every later handler unreachable, silently.
  Decision 14's router throws on a route that can never run, and the same shape here would throw on a subtype registered after its supertype, which forbids an order that is perfectly readable.
  Matching by specificity resolves it with no rule to remember: every type that matches one exception lies on one inheritance chain, so of any two the one assignable to the other is the more specific.
  `isAssignableFrom` is `Class` API of the same kind as the `isInstance` already in use, not the scanning this framework refuses.
- **Registration closes at `start()`.**
  The router's maps are read by every request thread without a lock, and decision 13's claim that `routes()` is the list the dispatcher walks holds only while that list does not change underneath it.
  Registering on a running `App` throws, and `stop()` opens registration again, so a suite that reconfigures between starts still works.
- **Three strict readings.**
  `paramBoolean` read `yes` as false through `Boolean.parseBoolean`, where `paramLong` answers 400 for `x`.
  It now takes `true` and `false` and rejects the rest, and gained the required form the other typed parameters already had.
  `asLong` truncated `1.5` to 1.
  It now rejects a fraction and still reads `2.0` and `1e3` as whole.
  `AppServlet` set UTF-8 on every request, which overrode a charset the request declared.
  The default now applies only where none was.

Rejected on the way: the framework turning an uncaught `JsonException` into a 400 itself.
That would make a reader-less `bodyJson()` answer 400 with no line saying so, one implicit mapping in an API whose point is that mappings are written.
The example and the agent skill carry the explicit line instead, and specificity matching is what lets that line sit after the `IllegalArgumentException` one.

## 35 · The argument order of a body

### 35. The content type first, on every body that takes one

`bytes` takes the content type first, as `stream` already did.
It used to take it last, so the two factories that name a content type named it in opposite places.
They are the same answer in a different shape, bytes held in memory or bytes written as they go, and a reader who had used one guessed the other wrong.
`stream` is the one that cannot move: its writer is a lambda, and a lambda reads as a block only when it is the last argument.
So `bytes` moved, and the rule is now stateable in one line: the content type is the first argument of every body that takes one.

No deprecated overload was left behind.
`bytes(byte[], String)` and `bytes(String, byte[])` would both compile, which is exactly the ambiguity the change removes.
The break is mechanical: every call site is a compile error whose fix is visible in the error.
It is taken before 1.0 for the reason decision 34 gives, that the cost only grows.

## 36 · The argument order of an exception handler

### 36. The request first, in `ExceptionHandler` too

`ExceptionHandler.handle` takes `(WebRequest request, E exception)`.
It used to take the exception first, and it was the one handler interface that did.
`Handler`, `BeforeFilter`, `AfterFilter`, and `RequestLogger` all name the request first, so a reader who had written three lambdas for this framework had to look the fourth one up.
Decision 18 set the naming rule for these interfaces.
This is the argument rule that goes with it.
Every handler now takes the request first, and whatever else it is given follows.

The order it left reads like a `catch` clause, and Javalin's `(e, ctx)` is the same.
That echo is the argument for having kept it, and it loses to consistency across five interfaces of the same framework.
The interface is functional, so its shape is final at 1.0 and was settled on purpose rather than by default.

No deprecated overload was left behind, for the reason decision 35 gives.
The break is mechanical wherever the lambda touches the exception: a body calling `e.getMessage()` stops compiling until the parameters are swapped.
A lambda that uses neither parameter compiles either way, which is the one case the compiler cannot flag, and also the case where the order does not matter.
It is taken before 1.0 for the reason decision 34 gives, that the cost only grows.

## 37 · The type of an elapsed time

### 37. `RequestLogger` reports a `Duration`

`RequestLogger.log` takes a `Duration`, where it used to take a `long` of milliseconds.
It was the one place the API handed out a duration as a number.
`maxAge`, `hsts`, `stopTimeout`, and the cookie forms all take a `Duration`, so a reader had one exception to hold in mind.

The measurement is `System.nanoTime`, and a `long` of milliseconds threw away everything below a millisecond before the logger saw it.
A request that finished in 400 microseconds was reported as `0`.
A `Duration` carries what was measured, and a logger that wants the old number calls `took.toMillis()`.

Rejected on the way: reporting nanoseconds as a `long`.
It keeps the precision and loses the unit, which is the half of the problem that costs more.
`Duration` names its own unit at every call site that reads it.

The break is not mechanical, which is why it is taken now rather than after 1.0.
A lambda that passes the argument straight to a logger keeps compiling and starts printing `PT0.4S` where it printed `400`.
Decision 34's argument applies with more force for that reason, not less.

## 38 · Typed parameters beyond the named forms

### 38. `param(name, parser)`, alongside the named forms rather than in their place

`param(name, parser)`, `param(name, parser, default)`, and `pathParam(name, parser)` read any type, where the parser is a `Function<String, T>` written at the call site.
`paramLong`, `paramBoolean`, and `paramEnum` each exist as a required form and a default form, and the same again for path variables.
Every further type was two or four more overloads, and `int`, `double`, `UUID`, and `LocalDate` had none.
One seam covers them all, and it is the framework's own idiom: the mapping is a lambda, so there is still no reflection.

The named forms stay.
`req.paramLong("id")` is shorter than `req.param("id", Long::parseLong)`, and the common types are common enough to be worth the shorter spelling.
The seam covers the rest, so the overload list stops growing.

The contract is decision 8's, the one `bodyJson(reader)` has: a parser that rejects the text answers 400 naming the parameter, so a handler receives a whole value or none.
Rejecting is throwing `IllegalArgumentException` **or `DateTimeException`**.
`DateTimeParseException` is not an `IllegalArgumentException` — it descends from `DateTimeException`, which descends straight from `RuntimeException` — so an `IllegalArgumentException`-only catch would have made `LocalDate::parse`, one of the types the seam exists for, a 500 on a bad date.

Rejected on the way: catching `RuntimeException`.
The value handed to a parser is never null, since `param(name)` has already answered 400 for an absent one.
A `NullPointerException` or an `IllegalStateException` out of a parser is therefore a fault in the parser and not in the request, and a 500 is the honest answer.

## 39 · A file a handler chose

### 39. `WebResponse.file(Path)`, and the content-type table stays private

`WebResponse.file(path)` answers with the content type the name implies, the file's size as `Content-Length`, and a `Stream` body.
A handler serving an export, an attachment, or anything else outside the classpath was writing `stream(contentType, out -> Files.copy(path, out))` and working out the content type itself.
`ContentTypes.byPath` already had the table, and `StaticFiles` already built exactly this response.

**The table stays package-private.**
That was the open question, and decision 32 answers it: 1.0 is the promise that what is public keeps its shape, so a public table is a permanent one.
The table has fourteen extensions and is deliberately incomplete, and made public that incompleteness becomes the promise: `.pdf`, `.webp`, and `.mp4` each turn into a request to extend a published list.
Reached only through `file`, it is an implementation detail of a factory, and a handler that disagrees with what the name implied writes `.contentType(...)`, which was already there.
The case that pushes the other way is a blob out of a database with a filename stored beside it, where there is no `Path` to hand to `file`.
That handler names its own content type, which is one string it already has the information to write, and the alternative is a permanent public list to keep it from writing it.

**A missing file throws; it does not answer 404.**
The framework cannot tell an export that was cleaned up from a path the handler built wrong.
The handler can, so a 404 for a missing file is the line the handler writes, after its own check.
`UncheckedIOException` is what `bodyStream()` already throws for the same class of problem.

The check runs in the factory rather than in the writer, and that is the load-bearing part.
Decision 18 names the trap: a body produced after dispatch has left its `try` block can no longer reach `app.exception(...)`.
One `readAttributes` call answers both questions the factory has — the size, and whether this is a regular file at all — so a directory fails while the handler can still be told, not after the headers are committed.

Rejected on the way: a `file(contentType, path)` overload.
Decision 35 would put the content type first, `.contentType(...)` already overrides, and two ways to say the same thing is what that decision removed rather than added.

Validators and conditional requests stay with `StaticFiles`, which is not refactored onto this.
It works on a `Resource` rather than a `Path`, and it carries the `ETag`, the `Last-Modified`, and the pre-compressed sibling branch that decision 31 added.
A file a handler chose is not a static file: only the handler knows whether it can change.

## 40 · The reads that only `raw()` reached

### 40. Eight read-only delegates on `WebRequest`, and `SecurityHeaders` stops going behind the API

`isSecure()`, `remoteAddress()`, `contentType()`, `queryString()`, `scheme()`, `host()`, `headers(name)`, and `headers()` are methods on `WebRequest`.
Each was answered through `raw()` before, and each is an ordinary question about a request rather than a container detail.
`SecurityHeaders` asked `request.raw().isSecure()`, which is core reaching around its own API for something the API should have said.
The bar decision 32 sets for the public surface is a use a handler or a `RequestLogger` has today, not completeness against `HttpServletRequest`: a request logger wants the client address, a signature wants the query string as it arrived, and HSTS wants the scheme.

`raw()` stays, and stays what decision 32 made it: the escape hatch for an async context, a client certificate, or a container-specific attribute.
The eight are read-only, so nothing about the one deliberate write in this class changes.

**`host()` carries the port when it is not the scheme's default.**
That was the open question, and it is the one method here that is not a bare delegate.
`getServerName()` alone would make `scheme() + "://" + host() + path()` wrong on every development server that is not on 80, which is the case the method exists for.
Both halves come from the container rather than from the `Host` header directly, so a proxy's `X-Forwarded-Host` applies here on the same terms as it does to `scheme()`.
`header("Host")` still reads the header as sent, for a handler that wants exactly that.

`headers()` answers `Map<String, List<String>>` in the order the request sent them, and its names are therefore case-sensitive where `header(name)` is not.
That is the shape `cookies()` already has, and the alternative — a case-insensitive map — would promise a lookup semantics that the one-name `headers(name)` already provides better.

`TestRequest` gains `remoteAddress(addr)` and nothing else.
`secure()` already covered `isSecure()` and `scheme()`, and `contentType()` and `queryString()` are already stated by `header("Content-Type")` and `queryParam(name, value)`.
The host is stated as `header("Host", ...)`, which is how a real request states it, so the stub derives the name and the port from that header the way a container does rather than taking a setter of its own.

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
| Javalin-style plugin registry | A registry of things that configure themselves is how a container starts. Decision 27 is what the alternative looks like: three named methods, each taking a value that does nothing until `App` is handed it. |
| Spark's static-import DSL | Process-global mutable state: one app per JVM, no parallel tests. |
| Path-scoped `error(status, handler)`, or `RouteGroup.error(...)` | An application that answers JSON under `/api` and HTML elsewhere branches inside one handler, on `req.accepts(...)` or `req.path()`, which is one visible line. A path component on `Guard.Error` would turn decision 6's one place that renders a 404 into several, and would report a status handler as covering a pattern where it covers a status. |
| `app.ws(path, config)` in core | A WebSocket is a protocol upgrade, so it leaves servlet dispatch: the router, `before`/`after`, `error(status, ...)`, `requestLogger`, `routes()`, and `WebTest` all stop applying to it. Core would be handing out an API that core's own features silently do not cover. It also ends the no-lock-in claim that `WebServer` exists for, since `AppServlet` on another container cannot follow. `jakarta.websocket` is no escape either: its default `Configurator` instantiates endpoints reflectively. It lives in `spider-silk-jetty-websocket` instead, where the name carries the tie to Jetty — decision 15c. |
