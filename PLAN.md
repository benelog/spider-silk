# Plan

The work that falls out of [docs/positioning.md](docs/positioning.md), with
status. Priorities are by *when a user hits the gap*, not by effort.

Status: **done** · **next** (agreed, ready to build) · **open** (needs a design
decision first) · **rejected** (a decision, not a backlog item)

## Progress

| # | Item | Priority | Status |
|---|---|---|---|
| 1 | Embedded Jetty, `start`/`stop`/`join`/`port` | P0 | ✅ done |
| 2 | `WebServer` / `WebServerFactory` seam for other servers | P0 | ✅ done |
| 3 | Jetty settings + `customizeServer`/`Context`/`HttpConfiguration` | P0 | ✅ done |
| 4 | Path-scoped `before`/`after` with trailing-`*` patterns | P0 | ✅ done |
| 5 | `path(prefix, group -> ...)` route groups, nestable | P0 | ✅ done |
| 6 | `error(status, handler)` + `ctx.errorMessage()` | P0 | ✅ done |
| 7 | `WebTest.test(app, client -> ...)` harness | P0 | ✅ done |
| 8 | `JsonCodec<T>` seam | P1 | ✅ done |
| 9 | Static files: cache headers, hosted path | P1 | ✅ done |
| 10a | Cookies and repeated parameters | P1 | ✅ done |
| 10b | `formParam` distinct from query, HEAD/OPTIONS | P1 | ✅ done |
| 11 | Request logging hook | P1 | ✅ done |
| 12 | Graceful shutdown on by default | P1 | ✅ done |
| 13 | Route introspection → overview page, OpenAPI export | P2 | ✅ done |
| 14 | Router indexed by method and first segment | P2 | ✅ done |
| 15 | WebSocket / SSE | P2 | ⬜ open |
| 16 | Virtual threads | P2 | ✅ done |
| 17 | Split `spider-silk-test` out of core | — | ✅ done |

17 of 18 done: all of P0, all of P1, three of P2, and the structural split.
The one that remains open is WebSocket/SSE.

## P0 — done

- [x] **1. Embedded Jetty and lifecycle.** `App.start(port)` binds and returns;
      Jetty's threads are non-daemon, so the JVM stays up without `join()`.
      `start(0)` plus `port()` is what makes tests portable.
- [x] **2. Server seam.** `WebServer` is four methods; `App.server(factory)`
      swaps the implementation. Deliberately *not* `ServiceLoader` — that is
      reflection, and discovery-by-classpath is the thing this framework avoids.
- [x] **3. Jetty configuration.** Port, host, context path, sessions, thread
      pool, and multipart as methods; three customizers for everything else.
      Sessions default on, because `ctx.flash`/`ctx.sessionAttr` need them.
- [x] **4. Path-scoped filters.** `before(path, handler)`; a final `*` matches
      the prefix and everything under it. A before-filter that writes a response
      ends the request — a guard that turned the caller away must not be followed
      by the handler answering anyway. Deliberate edge: setting a status *without*
      writing does **not** halt, because "only what you wrote happens" is the
      rule everywhere else, and widening the halt to any 4xx status would break a
      handler that sets 404 and then renders a page. `throw new HttpException(...)`
      is the status-only rejection path.
- [x] **5. Route groups.** `app.path("/api", api -> ...)`, nestable, with the
      group passed as an argument. Spark's static-import equivalent keeps the
      prefix in process-global state, which rules out two apps per JVM.
- [x] **6. Status-code error handlers.** One place to render a 404 or 500,
      whatever set the status. A handler that already wrote a body is left alone,
      tracked by `WebContext` rather than by wrapping the servlet response.
- [x] **7. Test harness.** `WebTest.test(app, client -> ...)`: port 0, cookies
      kept, server stopped in a `finally`. Returns raw `HttpResponse`, so the
      project's own assertion library stays in charge.

## P1 — the first week

- [x] **9. Static file caching.** `StaticFiles` now sends `Content-Length`,
      `ETag`, and `Last-Modified`, and answers `If-None-Match` /
      `If-Modified-Since` with a bodyless 304. The default `Cache-Control` is
      `no-cache` — cache, but revalidate — with `maxAge(Duration)` for
      fingerprinted names and `cacheControl(String)` as the escape hatch.
      `hostedPath` moves the files under a prefix. Directories are refused, and
      routes still win over files.

      Deliberately left out: pre-compressed variants (`.gz`/`.br`) and an
      external-directory location. Both are real, neither is needed until
      someone deploys behind something that is not already doing it.

- [x] **8. `JsonCodec<T>` seam.**

      ```java
      @FunctionalInterface
      interface JsonWriter<T> { Json.JsonValue write(T value); }

      @FunctionalInterface
      interface JsonReader<T> { T read(Json.JsonValue json); }

      interface JsonCodec<T> extends JsonWriter<T>, JsonReader<T> { }
      ```

      plus `ctx.json(value, writer)` and `ctx.bodyJson(reader)`. Codecs stay
      hand-written, so the mapping is still visible; they just stopped being
      re-inlined in every handler. The four decisions:

      - **Two interfaces, not one with two methods.** Most codecs are write-only
        — `DeckSummary` and `CardWithTags` never come back in — and a combined
        interface makes those fill `read` with `UnsupportedOperationException`.
        Split, each half is a SAM and therefore a lambda:
        `JsonWriter<DeckSummary> w = s -> Json.obj().put("id", s.id())...`.
        A two-method interface cannot be written as a lambda at all — which is
        also why `JsonCodec.of(writer, reader)` exists: a codec is the one shape
        that would otherwise need an anonymous class.
      - **Codecs live in the web layer**, a `Codecs` holder beside the
        controllers, not on the record. A codec on `Deck` would make
        `flashcard.domain` import `spidersilk.json.Json` — the domain would
        depend on the web framework to state its own wire format. The wire
        format belongs to the tier that serves it. Core ships the interfaces
        only; where codecs sit is a convention the example demonstrates.
      - **Collections compose**: `JsonWriter.list(writer)`,
        `JsonReader.list(reader)`, and `JsonCodec.list(codec)` return the
        `List<T>` form. Function composition, no reflection, and it collapsed
        the hand-rolled array loops in `ApiController.listDecks` and
        `listCards` — both handlers are now one line.
      - **`read` throws, and `ctx.bodyJson(reader)` turns
        `IllegalArgumentException` into a 400.** The same contract as
        `pathParamLong`: it returns the value or it rejects the request. No
        `Result` or `Validator` type in core — business rules stay in the
        service layer, where they already throw. `Json`'s own accessors already
        throw `IllegalArgumentException` on a missing key or a wrong type, so a
        reader gets the rejection for free by simply reading.

- [x] **10a. Cookies and repeated parameters.** `cookie(name)` / `cookies()` to
      read; `cookie(name, value)`, `cookie(name, value, maxAge)`, and
      `cookie(Cookie)` to set; `removeCookie(name)`. The two- and three-argument
      forms default to `Path=/`, `HttpOnly`, and `SameSite=Lax`, since a cookie
      worth setting from the server is usually one a script has no business
      reading. `params(name)` returns every value of a repeated parameter and an
      empty list when there is none — an unchecked checkbox group is an answer,
      not a 400.

- [x] **10b. The rest of the request API.** `queryParam`/`queryParams` read the
      query string, parsed here rather than through the servlet API, which
      merges it with the form body. `formParam`/`formParams` are what is left
      once the query values are taken out of the merged list — subtracted by
      count, not by position, so a name that appears in both places still splits
      correctly. HEAD runs the GET route through a response that counts the body
      and throws it away, so the headers — `Content-Length` included — are the
      ones the GET would have sent; this servlet overrides `service`, so
      `HttpServlet`'s own HEAD machinery never runs and the container is not
      relied on. OPTIONS answers from `Router.allowedMethods` plus the HEAD and
      OPTIONS this servlet adds itself, and `head`/`options` register a route
      when the automatic answer is not the right one.

- [x] **11. Request logging hook.** `app.requestLogger((ctx, millis) -> ...)`.
      One lambda, no logging framework in core. It runs in a `finally` around
      the whole dispatch, *after* the error handler has had its turn, so the
      status it reports is the one that was sent rather than the one the router
      set. A logger that throws goes to the servlet log: the response is already
      out by then, and a broken logger must not become a broken response.

- [x] **12. Graceful shutdown.** `stopTimeout` (five seconds) and
      `shutdownHook` (on), both with a method to turn them off. The interaction
      the note warned about turned out to be worse than "make the default
      modest": a stop timeout of *any* size made `stop()` wait out the whole
      timeout for idle keep-alive connections and then **throw** a
      `TimeoutException` — the project's own suite went from 0.75s to 6s and
      five tests failed. The fix is `ServerConnector.setShutdownIdleTimeout`
      (100ms): the drain then waits for requests in flight only, which is what
      it was ever supposed to mean, and five seconds costs an idle stop nothing.
      The hook is Jetty's own `setStopAtShutdown` rather than a thread of our
      own — one hook per JVM, deregistered on stop, so a suite that starts a
      server per test does not accumulate them.

## P2 — what decides whether this is more than a teaching framework

- [x] **13. Route introspection** *(the differentiator)*. `app.routes()` returns
      `List<Route>` — `record Route(String method, String path)` — with **no
      reflection at all**: it is the same list the dispatcher walks, read back as
      data. Javalin needs a plugin for this. The four decisions:

      - **Method and path, and nothing else.** The handler is left out: it is a
        lambda, so the only name it has is what reflection would dig out of its
        synthetic class. `PathPattern` stays package-private too — the exposed
        data is plain strings, not a matching engine.
      - **No description, no response types.** The decisive fact is that
        `PathPattern`'s `{deckId}` *is* OpenAPI's path-template syntax verbatim,
        so `/api/decks/{deckId}/cards` drops into a `paths:` key untranslated —
        the minimal shape already yields a valid document. Adding a description
        would mean a parameter on all seven registration methods on both `App`
        and `RouteGroup`, and documentation attached at the registration site is
        an annotation with the reflection taken out. Deferring costs nothing:
        those overloads are purely additive if the need ever proves real.
      - **The overview page and the OpenAPI export are not in core.** Core hands
        out the list and stops. An OpenAPI document is a spec format, not the
        web tier, and its version drift is not a web framework's to own —
        CLAUDE.md's rule. `flashcard.web.RoutesController` is the demonstration:
        `/_routes` renders `routes.jte`, `/openapi.json` builds a 3.1 document
        with the `Json` builder, and together they are about forty lines. If the
        export earns its keep, it becomes a fourth module, the way
        `spider-silk-test` did.
      - **Routes only** — not filters, not error handlers. A second public
        record for "which guard covers this path" is nice-to-have, and additive
        later.

      Item 14's buckets lose registration order, so `Router` keeps a flat
      registration-order list beside the index; `routes()` is an immutable
      snapshot of it, taken per call. The automatic HEAD and OPTIONS answers do
      **not** appear — `routes()` lists what was registered, which is the honest
      answer for a framework whose pitch is that only what you register runs.
      A `*` route has no OpenAPI equivalent, so the example's export skips it.

- [x] **14. Router indexing.** Routes are grouped by method, then by first
      literal segment, with a bucket for the patterns that can match any first
      segment — one starting with a variable, or a bare `*`. A lookup merges the
      two candidate lists **by registration index**, so the tie-break is exactly
      what it was when `find` scanned everything: `/study/today` registered
      before `/study/{mode}` still wins, and so does `/{page}` registered before
      `/decks`. `allowedMethods` runs off the same index.

- [ ] **15. WebSocket / SSE** *(open)*. Jetty can do both. **Open question:**
      whether this belongs in core at all, given that the servlet-deployable
      story (`AppServlet` on Tomcat) cannot follow it there.

- [x] **16. Virtual threads.** Documented, not wrapped:
      `QueuedThreadPool.setVirtualThreadsExecutor(VirtualThreads.getDefault...)`
      passed to `threadPool(...)`, with a test asserting the handler really does
      run on a virtual thread. No `virtualThreads()` method — it would be two
      lines of Jetty's own API behind a name that hides which knob was turned,
      and the choice is not ours to make: it pays off only when handlers block,
      and `synchronized` around the blocking call takes the benefit back.

## Structural

- [x] **17. Split `spider-silk-test`.** `spidersilk.test` is its own module,
      depending on core and otherwise on the JDK alone; core's jar now carries
      no test code at all. Core's own tests are a consumer of it like anyone
      else — `testImplementation project(':spider-silk-test')`. That looks
      circular and is not: the arrow runs core's *test* source set → the harness
      → core's *main* source set, which Gradle resolves without complaint.

## Rejected — decisions, with the reason

These are closed. If one is reopened, it is a change to what the framework is.

| Idea | Why not |
|---|---|
| `ctx.json(Object)`, `ctx.bodyAsClass(Foo.class)` | Reflection. The whole point is that the wire format changes only when someone edits it. |
| Annotation-driven routing | Reflection, plus scanning. |
| A DI container | Not the web tier, and `FlashcardContext` shows the alternative. |
| `ServiceLoader`-based server discovery | Classpath-driven binding is the magic this framework exists without. |
| Javalin-style plugin registry | A registry of things that configure themselves is how a container starts. |
| Spark's static-import DSL | Process-global mutable state: one app per JVM, no parallel tests. |
