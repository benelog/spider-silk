# Plan

The work that falls out of [docs/positioning.md](docs/positioning.md), with status.
Priorities are by *when a user hits the gap*, not by effort.

Status: **done** · **next** (agreed, ready to build) · **open** (needs a design decision first) · **rejected** (a decision, not a backlog item)

## Progress

| # | Item | Priority | Status |
|---|---|---|---|
| 1 | Embedded Jetty, `start`/`stop`/`join`/`port` | P0 | ✅ done |
| 2 | `WebServer` / `WebServerFactory` seam for other servers | P0 | ✅ done |
| 3 | Jetty settings + `customizeServer`/`Context`/`HttpConfiguration` | P0 | ✅ done |
| 4 | Path-scoped `before`/`after` with trailing-`*` patterns | P0 | ✅ done |
| 5 | `path(prefix, group -> ...)` route groups, nestable | P0 | ✅ done |
| 6 | `error(status, handler)` + `req.errorMessage()` | P0 | ✅ done |
| 7 | `WebTest.test(app, client -> ...)` harness | P0 | ✅ done |
| 8 | `JsonCodec<T>` seam | P1 | ✅ done |
| 9 | Static files: cache headers, hosted path | P1 | ✅ done |
| 10a | Cookies and repeated parameters | P1 | ✅ done |
| 10b | `formParam` distinct from query, HEAD/OPTIONS | P1 | ✅ done |
| 11 | Request logging hook | P1 | ✅ done |
| 12 | Graceful shutdown on by default | P1 | ✅ done |
| 13 | Route introspection → overview page, OpenAPI export | P2 | ✅ done |
| 14 | Router indexed by method and first segment | P2 | ✅ done |
| 15a | SSE: event framing over the servlet response | P2 | ✅ done |
| 15b | WebSocket in core | P2 | ❌ rejected |
| 16 | Virtual threads | P2 | ✅ done |
| 17 | Split `spider-silk-test` out of core | — | ✅ done |
| 18 | `WebContext` split into `WebRequest` + sealed `WebResponse` | — | ✅ done |

19 of 20 done: all of P0, all of P1, all of P2, and both structural items.
The twentieth is 15b, which is a decision rather than a gap.
What was one entry — "WebSocket / SSE" — split once the two halves were asked the same question and gave opposite answers: SSE is HTTP and rides through `AppServlet`, WebSocket is a protocol upgrade and does not.

## P0 — done

- [x] **1. Embedded Jetty and lifecycle.**
      `App.start(port)` binds and returns; Jetty's threads are non-daemon, so the JVM stays up without `join()`.
      `start(0)` plus `port()` is what makes tests portable.
- [x] **2. Server seam.**
      `WebServer` is four methods; `App.server(factory)` swaps the implementation.
      Deliberately *not* `ServiceLoader` — that is reflection, and discovery-by-classpath is the thing this framework avoids.
- [x] **3. Jetty configuration.**
      Port, host, context path, sessions, thread pool, and multipart as methods; three customizers for everything else.
      Sessions default on, because `req.flash`/`req.sessionAttr` need them.
- [x] **4. Path-scoped filters.**
      `before(path, filter)`; a final `*` matches the prefix and everything under it.
      A before-filter that returns a response ends the request — a guard that turned the caller away must not be followed by the handler answering anyway — and returning `null` continues to the route.
      Item 18 turned that into a signature rather than a convention: the halt used to be inferred from a `bodyWritten` flag on the context, and it is now the difference between returning a value and returning nothing.
      Deliberate edge: an answer of `WebResponse.empty(401)` still halts, since it *is* an answer; what does not halt is a filter that only reads.
      `throw new HttpException(...)` is the status-only rejection path, and `error(401, ...)` renders it.
- [x] **5. Route groups.**
      `app.path("/api", api -> ...)`, nestable, with the group passed as an argument.
      Spark's static-import equivalent keeps the prefix in process-global state, which rules out two apps per JVM.
- [x] **6. Status-code error handlers.**
      One place to render a 404 or 500, whatever set the status.
      A response that already carries a body is left alone, which the sealed `WebResponse.Body` says outright rather than by wrapping the servlet response.
- [x] **7. Test harness.**
      `WebTest.test(app, client -> ...)`: port 0, cookies kept, server stopped in a `finally`.
      Returns raw `HttpResponse`, so the project's own assertion library stays in charge.

## P1 — the first week

- [x] **9. Static file caching.**
      `StaticFiles` now sends `Content-Length`, `ETag`, and `Last-Modified`, and answers `If-None-Match` / `If-Modified-Since` with a bodyless 304.
      The default `Cache-Control` is `no-cache` — cache, but revalidate — with `maxAge(Duration)` for fingerprinted names and `cacheControl(String)` as the escape hatch.
      `hostedPath` moves the files under a prefix.
      Directories are refused, and routes still win over files.

      Deliberately left out: pre-compressed variants (`.gz`/`.br`) and an external-directory location.
      Both are real, neither is needed until someone deploys behind something that is not already doing it.

- [x] **8. `JsonCodec<T>` seam.**

      ```java
      @FunctionalInterface
      interface JsonWriter<T> { Json.JsonValue write(T value); }

      @FunctionalInterface
      interface JsonReader<T> { T read(Json.JsonValue json); }

      interface JsonCodec<T> extends JsonWriter<T>, JsonReader<T> { }
      ```

      plus `WebResponse.json(value, writer)` and `req.bodyJson(reader)`.
      Codecs stay hand-written, so the mapping is still visible; they just stopped being re-inlined in every handler.
      The four decisions:

      - **Two interfaces, not one with two methods.**
        Most codecs are write-only — `DeckSummary` and `CardWithTags` never come back in — and a combined interface makes those fill `read` with `UnsupportedOperationException`.
        Split, each half is a SAM and therefore a lambda: `JsonWriter<DeckSummary> w = s -> Json.obj().put("id", s.id())...`.
        A two-method interface cannot be written as a lambda at all — which is also why `JsonCodec.of(writer, reader)` exists: a codec is the one shape that would otherwise need an anonymous class.
      - **Codecs live in the web layer**, a `Codecs` holder beside the controllers, not on the record.
        A codec on `Deck` would make `flashcard.domain` import `spidersilk.json.Json` — the domain would depend on the web framework to state its own wire format.
        The wire format belongs to the tier that serves it.
        Core ships the interfaces only; where codecs sit is a convention the example demonstrates.
      - **Collections compose**: `JsonWriter.list(writer)`, `JsonReader.list(reader)`, and `JsonCodec.list(codec)` return the `List<T>` form.
        Function composition, no reflection, and it collapsed the hand-rolled array loops in `ApiController.listDecks` and `listCards` — both handlers are now one line.
      - **`read` throws, and `req.bodyJson(reader)` turns `IllegalArgumentException` into a 400.**
        The same contract as `pathParamLong`: it returns the value or it rejects the request.
        No `Result` or `Validator` type in core — business rules stay in the service layer, where they already throw.
        `Json`'s own accessors already throw `IllegalArgumentException` on a missing key or a wrong type, so a reader gets the rejection for free by simply reading.

- [x] **10a. Cookies and repeated parameters.**
      `cookie(name)` / `cookies()` to read; `cookie(name, value)`, `cookie(name, value, maxAge)`, and `cookie(Cookie)` to set; `removeCookie(name)`.
      The two- and three-argument forms default to `Path=/`, `HttpOnly`, and `SameSite=Lax`, since a cookie worth setting from the server is usually one a script has no business reading.
      `params(name)` returns every value of a repeated parameter and an empty list when there is none — an unchecked checkbox group is an answer, not a 400.

- [x] **10b. The rest of the request API.**
      `queryParam`/`queryParams` read the query string, parsed here rather than through the servlet API, which merges it with the form body.
      `formParam`/`formParams` are what is left once the query values are taken out of the merged list — subtracted by count, not by position, so a name that appears in both places still splits correctly.
      HEAD runs the GET route and drops the body, so the headers — `Content-Length` included — are the ones the GET would have sent; this servlet overrides `service`, so `HttpServlet`'s own HEAD machinery never runs and the container is not relied on.
      Since item 18 the length is usually just the body's own: a `Text`, `Bytes`, or rendered `Template` knows its size without being produced, and only a `Stream` or a `Raw` body still runs through a counting wrapper.
      OPTIONS answers from `Router.allowedMethods` plus the HEAD and OPTIONS this servlet adds itself, and `head`/`options` register a route when the automatic answer is not the right one.

- [x] **11. Request logging hook.**
      `app.requestLogger((req, res, millis) -> ...)`.
      One lambda, no logging framework in core.
      It runs in a `finally` around the whole dispatch, *after* the error handler has had its turn, so the status it reports is the one that was sent rather than the one the router set.
      A logger that throws goes to the servlet log: the response is already out by then, and a broken logger must not become a broken response.

- [x] **12. Graceful shutdown.**
      `stopTimeout` (five seconds) and `shutdownHook` (on), both with a method to turn them off.
      The interaction the note warned about turned out to be worse than "make the default modest": a stop timeout of *any* size made `stop()` wait out the whole timeout for idle keep-alive connections and then **throw** a `TimeoutException` — the project's own suite went from 0.75s to 6s and five tests failed.
      The fix is `ServerConnector.setShutdownIdleTimeout` (100ms): the drain then waits for requests in flight only, which is what it was ever supposed to mean, and five seconds costs an idle stop nothing.
      The hook is Jetty's own `setStopAtShutdown` rather than a thread of our own — one hook per JVM, deregistered on stop, so a suite that starts a server per test does not accumulate them.

## P2 — what decides whether this is more than a teaching framework

- [x] **13. Route introspection** *(the differentiator)*.
      `app.routes()` returns `List<Route>` — `record Route(String method, String path)` — with **no reflection at all**: it is the same list the dispatcher walks, read back as data.
      Javalin needs a plugin for this.
      The four decisions:

      - **Method and path, and nothing else.**
        The handler is left out: it is a lambda, so the only name it has is what reflection would dig out of its synthetic class.
        `PathPattern` stays package-private too — the exposed data is plain strings, not a matching engine.
      - **No description, no response types.**
        The decisive fact is that `PathPattern`'s `{deckId}` *is* OpenAPI's path-template syntax verbatim, so `/api/decks/{deckId}/cards` drops into a `paths:` key untranslated — the minimal shape already yields a valid document.
        Adding a description would mean a parameter on all seven registration methods on both `App` and `RouteGroup`, and documentation attached at the registration site is an annotation with the reflection taken out.
        Deferring costs nothing: those overloads are purely additive if the need ever proves real.
      - **The overview page and the OpenAPI export are not in core.**
        Core hands out the list and stops.
        An OpenAPI document is a spec format, not the web tier, and its version drift is not a web framework's to own — CLAUDE.md's rule.
        The example is the demonstration: `/_routes` renders `routes.jte` and `/openapi.json` builds a 3.1 document through `flashcard.web.OpenApi`, together about forty lines.
        Both are registered as lambdas over `app` itself, which is why they list the routes registered after them.
        If the export earns its keep, it becomes a fourth module, the way `spider-silk-test` did.
      - **Routes only** — not filters, not error handlers.
        A second public record for "which guard covers this path" is nice-to-have, and additive later.

      Item 14's buckets lose registration order, so `Router` keeps a flat registration-order list beside the index; `routes()` is an immutable snapshot of it, taken per call.
      The automatic HEAD and OPTIONS answers do **not** appear — `routes()` lists what was registered, which is the honest answer for a framework whose pitch is that only what you register runs.
      A `*` route has no OpenAPI equivalent, so the example's export skips it.

- [x] **14. Router indexing.**
      Routes are grouped by method, then by first literal segment, with a bucket for the patterns that can match any first segment — one starting with a variable, or a bare `*`.
      A lookup merges the two candidate lists **by registration index**, so the tie-break is exactly what it was when `find` scanned everything: `/study/today` registered before `/study/{mode}` still wins, and so does `/{page}` registered before `/decks`.
      `allowedMethods` runs off the same index.

- [x] **15a. SSE.**
      `WebResponse.sse(stream -> ...)` hands the handler an `SseStream` — `send(data)`, `send(event, data)`, `id(...)`, `comment(...)`, `isOpen()`, `close()` — and writes `text/event-stream` frames, flushed one event at a time.
      `text/event-stream` is plain HTTP: a content type, one `data:` line per event, a blank line to end it, and a flush.
      It goes through `AppServlet` unchanged, so a deployment on Tomcat gets it too — which is the whole reason this half is in and 15b is out.
      Core adds the framing and nothing else; no new dependency.
      The five decisions:

      - **An SSE endpoint is a `get` route**, not a registration of its own.
        `routes()` lists it, filters cover it, `requestLogger` reports it when the stream ends — which is exactly the argument that sinks 15b, so the implementation had to keep it true.
      - **The events are strings.**
        `WebResponse.json(value, writer)`'s counterpart is `stream.send(writer.write(value).toJson())`, not an overload that takes a codec — the mapping stays visible at the call site, the same rule as everywhere else.
      - **The stream is blocking and holds its thread.**
        One open stream is one Jetty thread, which is the honest servlet answer and the one `AppServlet` can keep on Tomcat; `AsyncContext` would be a second dispatch model in core for a feature that has yet to prove it needs one.
        The recipe for many streams is item 16's: a virtual-thread executor on the pool, where a parked thread costs almost nothing.
      - **Ending a stream is never an error.**
        A write to a stream that has gone throws `SseStream.Closed`, which the servlet catches by that one type and finishes the request normally.
        A dedicated type rather than `UncheckedIOException`, so an IO failure in the handler's own code still reaches the exception handlers; and a write *after* `close()` throws the same `Closed` rather than an `IllegalStateException`, because otherwise every graceful shutdown would log a handler failure for each open stream.
        `SseWriter` (named `SseHandler` until item 18's naming rule) is declared `throws Exception` for the `Thread.sleep` that every SSE loop contains — the same reason `Handler` throws.
      - **Graceful shutdown is resolved, not documented away.**
        An open stream is a request in flight that never finishes, so item 12's five-second `stopTimeout` would wait it out and then report a failure to drain — exactly the failure `setShutdownIdleTimeout` fixed for idle keep-alive connections, except that this connection really is busy.
        So `App` keeps a registry of open streams and closes them as the first statement of `stop()`, before Jetty is asked to drain anything; the test asserts the stop takes under three seconds and that the handler ended.
        The writes are synchronized on the stream, because that close comes from another thread and must not cut a frame in half.

      Two deliberate edges: a HEAD of an SSE route answers with the headers and never runs the handler, since a stream with the body thrown away would never end; and the heartbeat stays the application's, `stream.comment("ping")` on a timer, because Jetty's own 30-second connector idle timeout is a number core has no business choosing for anyone.

- **15b. WebSocket in core** — *rejected*, see the table below.
      Jetty can do it, and `customizeContext` plus `JakartaWebSocketServletContainerInitializer` is the recipe for an application that needs it: the WebSocket dependency then sits in that application's build file, not in core's.
      If the recipe turns out to be worth wrapping, it becomes a `spider-silk-ws` module the way `spider-silk-test` did, where being Jetty-only is stated in the module's name rather than hidden in core.

- [x] **16. Virtual threads.**
      Documented, not wrapped: `QueuedThreadPool.setVirtualThreadsExecutor(VirtualThreads.getDefault...)` passed to `threadPool(...)`, with a test asserting the handler really does run on a virtual thread.
      No `virtualThreads()` method — it would be two lines of Jetty's own API behind a name that hides which knob was turned, and the choice is not ours to make: it pays off only when handlers block, and `synchronized` around the blocking call takes the benefit back.

## Structural

- [x] **17. Split `spider-silk-test`.**
      `spidersilk.test` is its own module, depending on core and otherwise on the JDK alone; core's jar now carries no test code at all.
      Core's own tests are a consumer of it like anyone else — `testImplementation project(':spider-silk-test')`.
      That looks circular and is not: the arrow runs core's *test* source set → the harness → core's *main* source set, which Gradle resolves without complaint.

- [x] **18. `WebContext` split into `WebRequest` and a sealed `WebResponse`.**
      `WebContext` had grown to 552 lines under nine section comments, because "the request, the response, the session, and every way of answering" is four responsibilities wearing one name.
      Splitting it along the HTTP metaphor was the obvious half; the other half was making `Handler` *return* the answer:

      ```java
      WebResponse handle(WebRequest request) throws Exception
      ```

      The compiler now checks that every branch answers, and answering twice stopped being expressible.
      The five decisions:

      - **`WebRequest`/`WebResponse`, not `HttpRequest`/`HttpResponse`.**
        `WebTest`'s own documented idiom asserts on `java.net.http.HttpResponse<String>`, so a `spidersilk.HttpResponse` would collide with the JDK inside this framework's own test style, and `HttpServletRequest` is one import away in the other direction.
        `Web*` is also what the codebase already calls things — `WebServer`, `WebServerFactory`, `WebTest`.
      - **An envelope around a sealed `Body`, not a sealed response.**
        Status, headers, and cookies are the same for every kind of answer, so they live once on `WebResponse`; what differs is the body, and *that* is the sealed type — `Empty`, `Text`, `Bytes`, `Template`, `Stream`, `Sse`, `Raw`.
        A `switch` over the kinds needs no default case, and the `with`-style methods stay type-stable, which is what lets an `AfterFilter` be `WebResponse -> WebResponse` at all.
        Sealing the response itself would have meant reimplementing `status`/`header`/`cookie` on seven records.
      - **Templates are rendered during dispatch, not while writing.**
        This is the trap the return-based model sets: a body produced after dispatch has left its `try` block can no longer reach `app.exception(...)`, and `FlashcardApp` maps `IllegalArgumentException` there.
        So a `Template` is materialized into `Text` inside dispatch, which keeps the exception routing and hands HEAD an exact `Content-Length` for free.
        `Stream`, `Sse`, and `Raw` genuinely cannot be materialized, and their failures land in the servlet log with a best-effort 500 — the honest limit, since the headers are already committed by then.
      - **The filters changed shape with the handler.**
        `BeforeFilter` returns a response or `null` to continue; `AfterFilter` takes the response and returns a replacement or `null` to keep it.
        `null` rather than `Optional` because an observational filter stays a one-liner either way and `Optional.empty()` reads worse in the common case.
        After-filters run only on a route that completed normally — not after a before-filter answered, not on an exception handler's output — which is what the javadoc already claimed and is now written down.
      - **`bodyWritten` is gone, and `StaticFiles` produces a response like anything else.**
        "Did anyone answer yet" used to be a mutable flag the servlet sniffed; it is now `body() instanceof Empty`.
        `StaticFiles.resolve` returns a streamed `WebResponse` instead of writing the servlet response itself, so a static hit flows through the request logger like every other answer.

      - **`…Handler` answers a request; `…Writer` fills a body.**
        Splitting the response into kinds produced a second family of lambdas, and calling them all `Handler` made `RawHandler` read as a relative of `Handler` — a reviewer's first guess was that one extended the other.
        So the suffix now carries the distinction: `Handler` and `ExceptionHandler` return a `WebResponse`, while `StreamWriter`, `SseWriter`, and `ServletWriter` return nothing and fill the body of a response already decided.
        That renamed `RawHandler` to `ServletWriter` and `SseHandler` to `SseWriter`, and their method from `handle` to `write`.
        `Handler` itself stayed: it is the central type, `App.error(int, Handler)` uses it too so `RouteHandler` would be inaccurate, and it is what Javalin and Helidon call the same thing.

      What it cost: `AppServlet` split into a dispatch half and a write half, and the example app's controller tests stopped needing `MockHttpServletResponse` entirely — they call the handler and assert on the value it returned.
      Two behaviours changed on purpose: `redirect` sets a `Location` header rather than calling `sendRedirect`, and cookies moved to the response while the session and flash stayed on the request, since a session outlives the response and cannot be a value returned from one.

## Rejected — decisions, with the reason

These are closed.
If one is reopened, it is a change to what the framework is.

| Idea | Why not |
|---|---|
| `WebResponse.json(Object)`, `req.bodyAsClass(Foo.class)` | Reflection. The whole point is that the wire format changes only when someone edits it. |
| Annotation-driven routing | Reflection, plus scanning. |
| A DI container | Not the web tier, and `FlashcardContext` shows the alternative. |
| `ServiceLoader`-based server discovery | Classpath-driven binding is the magic this framework exists without. |
| Javalin-style plugin registry | A registry of things that configure themselves is how a container starts. |
| Spark's static-import DSL | Process-global mutable state: one app per JVM, no parallel tests. |
| `app.ws(path, config)` in core | A WebSocket is a protocol upgrade, so it leaves servlet dispatch: the router, `before`/`after`, `error(status, ...)`, `requestLogger`, `routes()`, and `WebTest` all stop applying to it. Core would be handing out an API that core's own features silently do not cover. It also ends the no-lock-in claim — `WebServer` is four methods precisely so Jetty is replaceable, and `AppServlet` on Tomcat cannot follow. `jakarta.websocket` is not the way out either: its default `Configurator` instantiates endpoints reflectively. Recipe now, `spider-silk-ws` module if it earns one. |
