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
| 8 | `JsonCodec<T>` seam | P1 | ⬜ open |
| 9 | Static files: cache headers, hosted path | P1 | ✅ done |
| 10a | Cookies and repeated parameters | P1 | ✅ done |
| 10b | `formParam` distinct from query, HEAD/OPTIONS | P1 | ✅ done |
| 11 | Request logging hook | P1 | ✅ done |
| 12 | Graceful shutdown on by default | P1 | ✅ done |
| 13 | Route introspection → overview page, OpenAPI export | P2 | ⬜ open |
| 14 | Router indexed by method and first segment | P2 | ✅ done |
| 15 | WebSocket / SSE | P2 | ⬜ open |
| 16 | Virtual threads | P2 | ✅ done |
| 17 | Split `spider-silk-test` out of core | — | ⬜ open |

14 of 18 done: all of P0, all of P1 except the JsonCodec seam, and two of P2.

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

- [ ] **8. `JsonCodec<T>` seam** *(open — needs a design decision)*

      ```java
      interface JsonCodec<T> {
          Json.JsonValue write(T value);
          T read(Json.JsonValue json);
      }
      ```

      plus `ctx.json(value, codec)` and `ctx.bodyJson(codec)`. Codecs stay
      hand-written, so the mapping is still visible; they just stop being
      re-inlined in every handler. **Open questions:** where codecs live (next to
      the record? a `Codecs` holder?), whether collections get
      `JsonCodec.list(codec)`, and whether `read` returning a partly-built object
      needs a validation story. Settle these before writing code — this is the
      API most likely to be regretted.

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

- [ ] **13. Route introspection** *(open — the differentiator)*. Routes are an
      explicit list, so `app.routes()` can expose them with **no reflection at
      all**, and from that a route-overview page and a static OpenAPI export come
      almost free. Javalin needs a plugin for this. **Open questions:** what a
      route exposes beyond method and pattern (a description? response types?)
      without dragging annotations back in. Note that item 14 grouped the routes
      into buckets — still an explicit list, just not one list, so this is
      unaffected beyond needing to walk the index.

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

- [ ] **17. Split `spider-silk-test`** *(open)*. `spidersilk.test` currently
      ships inside core. It is two JDK-only classes, so the cost is small, but a
      separate module would keep the production jar honest. Worth doing if the
      harness grows, or when core is first published.

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
