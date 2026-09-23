# Changelog

This file lists what changed in each release of Spider Silk.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the version numbers follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
The release workflow copies a version's section into its GitHub Release, so a section is written before the tag is pushed.
[RELEASING.md](RELEASING.md) has the steps.

## [Unreleased]

This release renames the public names a first-time reader guessed wrong.
Every rename is a compile error whose fix is the new name, and no deprecated alias is left behind.

### Added

- `spider-silk-core`: `req.session()` answers a `WebSession`, with `get(key)`, `get(key, type)`, `set(key, value)`, `remove(key)`, and `invalidate()`.
  Asking for it starts no session.
- `spider-silk-core`: `JsonObject.getString(key, default)`, `getLong(key, default)`, `getDouble(key, default)`, `getBoolean(key, default)`, `getObjectOrNull(key)`, and `getArrayOrNull(key)`.
  They follow the absence rule `WebRequest` follows: the plain name requires the value, a default as the last argument makes it optional, and `OrNull` answers null.
- `spider-silk-core`: `app.bodyLimits(BodyLimits)` bounds what `body()` and `bodyJson()` hold (`maxBytes`) and what one `bodyNdjson` line holds (`maxNdjsonLineBytes`), in bytes counted as they arrive, and answers 413 beyond them.
  `BodyLimits.unlimited()` lifts both, and `bodyStream()` and `bodyReader()` are never limited.

### Changed

- `spider-silk-core`: `WebResponse.json(String)` is `WebResponse.rawJson(String)`.
  `json` now takes only a `JsonValue` or a value with its `JsonWriter`, so a Java string can no longer be sent as a JSON document by mistake.
- `spider-silk-core`: `app.error(status, handler)` is `app.statusPage(status, handler)`.
- `spider-silk-core`: `app.guards()` is `app.hooks()`, and the sealed `Guard` is `Hook`.
  `Guard.Error` is `Hook.StatusPage`, and `Guard.ResponseFilter` is `Hook.EveryResponse`.
- `spider-silk-core`: `RequestCompletion.failure()` and `failed()` are `writeFailure()` and `writeFailed()`, and `exception()` is `thrown()`.
- `spider-silk-core`: `Json.JsonValue`, `Json.JsonObject`, `Json.JsonArray`, `Json.JsonPrimitive`, and `Json.JsonException` are top-level types in `net.benelog.spidersilk.json`.
  `Json.obj()` and `Json.arr()` are `Json.object()` and `Json.array()`.
- `spider-silk-core`: the `WebResponse.Stream` body is `WebResponse.Streamed`.
- `spider-silk-core`: `app.server()`, the running server, is `app.runningServer()`.
- `spider-silk-core`: a path pattern containing whitespace is rejected at registration with an `IllegalArgumentException`.
  A description passed where the path goes used to register a route nothing could reach.
- `spider-silk-core`: `pathParam` read before routing, in a `beforeRequest` filter, says that no route has matched yet, and an undeclared variable names the route's pattern.
- `spider-silk-test`: `TestRequest.sessionAttr(key, value)` is `TestRequest.session(key, value)`.
- `spider-silk-core`: `body()`, `bodyJson()`, and each `bodyNdjson` line default to a 1MB limit, so a larger body answers 413 until `app.bodyLimits(...)` raises the limit.
  `body()` reads the request through `getInputStream()` rather than `getReader()`, so `raw().getReader()` after it throws `IllegalStateException`.
- `spider-silk-core`: a path pattern that binds one variable name twice, such as `/tenants/{id}/items/{id}` or `/files/{id}/{id*}`, is rejected at registration with an `IllegalArgumentException`.
  The later value used to overwrite the earlier one; the check covers routes, route-group prefixes, filter paths, and `Cors.forPath`.
- `spider-silk-core`: `Json.parse`, and so `bodyJson`, accepts only RFC 8259 syntax.
  The numbers `01`, `+1`, `.5`, and `1.`, raw control characters inside strings, whitespace other than space, tab, LF, and CR, and non-ASCII hex digits in `\u` escapes are a `JsonException`, which `bodyJson` answers with 400.
  An integer too large for a long is reported as "Number out of range".
- `spider-silk-undertow`: `UndertowServer` installs a servlet exception handler that closes the connection when a failure arrives after the response has started.
  Replacing it through `customizeDeployment(...)` gives up that behavior.

### Removed

- `spider-silk-core`: `WebRequest.sessionAttr`, `setSessionAttr`, `removeSessionAttr`, and `invalidateSession`, replaced by `req.session()`.
- `spider-silk-core`: `JsonObject.optString`, `optLong`, `optDouble`, `optBoolean`, `optObject`, and `optArray`, replaced by the getters above.

### Fixed

- `spider-silk-core`: `JsonValue.asLong()` and `getLong` convert a decimal or exponent number from its digits rather than the nearest double.
  `9007199254740993.0` reads exactly, and `1.0000000000000001` is rejected as a fraction.
- `spider-silk-core`: a gzip-compressed stream whose writer fails before writing anything answers 500, not a 200 with an empty gzip body.
- `spider-silk-core`: a response body that fails after the response is committed aborts the transfer on Jetty, Tomcat, and Undertow, so the client sees an incomplete response instead of a truncated one that looks complete.
  `RequestCompletion.writeFailure()` still reports the original failure, and the logged status stays 200.
- `spider-silk-core`: a static file served off the classpath is opened only when its body is written, so a response filter that replaces the response or throws no longer leaves the file open.
  A file served out of a jar no longer holds a descriptor on the jar per request, and a directory entry inside a jar answers 404.

## [1.1.0] - 2026-09-17

### Added

- `spider-silk-core`: `WebRequest.route()` reports the route that answered the request, as `app.routes()` lists it, from `beforeRoute` through the request logger.
  A tracing span or a metric groups by the pattern `/api/decks/{deckId}` without re-matching the path against the routing table.
- `spider-silk-core`: `RequestCompletion.exception()` and `threw()` report what a handler, a filter, or a template threw, whether an exception handler answered it or the framework's 500 did.
  A logger records failures from there instead of registering a catch-all `exception(...)` handler.

### Changed

- `spider-silk-core`: an `HttpException` is answered by its status and `error(status, ...)` without passing through an exception handler registered for a broader type.
  Only a handler for `HttpException` itself or a subtype of it catches one.
  A catch-all for `RuntimeException` or `Exception` therefore no longer turns a deliberate 404 into its own answer.

## [1.0.0] - 2026-09-16

The first release, and the first published to Maven Central.
From this version on, a change to a public signature follows Semantic Versioning.

### Added

- `spider-silk-core`: routing per method with path variables, route groups, and `/files/{path*}` tail variables.
- `spider-silk-core`: `WebRequest` and the immutable, sealed `WebResponse`, with typed parameter extraction through parsers.
- `spider-silk-core`: before, after, and response filters, status-code error handlers, and exception handlers.
- `spider-silk-core`: the `Json` API and the `JsonCodec<T>` seam, streamed JSON and NDJSON, jte templates, static files with pre-compressed siblings, and Server-Sent Events.
- `spider-silk-core`: sessions and flash messages, CORS, gzip, security headers, request logging, and route introspection through `app.routes()`.
- `spider-silk-core`: embedded Jetty with virtual threads and graceful shutdown, behind the `WebServer` seam.
- `spider-silk-test`: the `WebTest` harness and `TestRequest`.
- `spider-silk-tomcat` and `spider-silk-undertow`: the same application on an embedded Tomcat or Undertow.
- `spider-silk-freemarker`, `spider-silk-handlebars`, and `spider-silk-thymeleaf`: template engines besides jte.
- `spider-silk-jetty-websocket`: WebSocket endpoints beside the routes, on Jetty.
- `spider-silk-openapi`: the route list as an OpenAPI 3.1 document.
- `spider-silk-gradle-plugin` (plugin id `net.benelog.spidersilk`) and `spider-silk-maven-parent`: packaging conventions for precompiled jte, Jib on a JRE base, and GraalVM native images.
- JSpecify `@NullMarked` on every published package, checked by NullAway at compile time.

[Unreleased]: https://github.com/benelog/spider-silk/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/benelog/spider-silk/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/benelog/spider-silk/releases/tag/v1.0.0
