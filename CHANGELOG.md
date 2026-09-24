# Changelog

This file lists what changed in each release of Spider Silk.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the version numbers follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
The release workflow copies a version's section into its GitHub Release, so a section is written before the tag is pushed.
[RELEASING.md](RELEASING.md) has the steps.

## [Unreleased]

This release renames the public names a first-time reader guessed wrong.
Every rename is a compile error whose fix is the new name, and no deprecated alias is left behind.

### Added

- `spider-silk-core`: `AppServlet.NO_MULTIPART_PARAMETER`, the servlet init parameter a server sets when it registers the servlet with no multipart configuration.
  `JettyServer.multipart(null)` sets it, and a parameter read of a multipart request then answers from the query string, as on Tomcat and Undertow.
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
- `spider-silk-core`: `RequestCompletion.thrown()` and `writeFailure()` are a `Throwable`, since an `Error` is reported through them too.
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
- `spider-silk-core`: `Json.parse` reads an integer outside the range of a long as a number, which `asDouble` answers and `asLong` rejects.
  It used to be a `JsonException` reporting "Number out of range".
- `spider-silk-core`: `attachment(name)` writes a name outside printable ASCII, or one holding a quote or a backslash, as RFC 6266's `filename*` beside an escaped ASCII fallback.
  A control character in the name throws `IllegalArgumentException`.
- `spider-silk-core`: `SseStream.id(...)` and `send(event, data)` throw `IllegalArgumentException` for a line break in the id or the event name, and `id(...)` for a NUL, before anything is written.
- `spider-silk-core`: with named origins, `Cors` adds `Vary: Origin` to every answer on a covered path, whether the request carried an allowed `Origin`, another one, or none.
- `spider-silk-undertow`: `UndertowServer` installs a servlet exception handler that closes the connection when a failure arrives after the response has started.
  Replacing it through `customizeDeployment(...)` gives up that behavior.

### Removed

- `spider-silk-core`: `WebRequest.sessionAttr`, `setSessionAttr`, `removeSessionAttr`, and `invalidateSession`, replaced by `req.session()`.
- `spider-silk-core`: `JsonObject.optString`, `optLong`, `optDouble`, `optBoolean`, `optObject`, and `optArray`, replaced by the getters above.

### Fixed

- `spider-silk-core`: a query string whose escapes are not UTF-8, such as `?a=%FF`, answers 400 from `queryParam`, `param`, and `formParam` on every server.
  `queryParam` answered U+FFFD, and `param` a 400 blaming a form body on Jetty and Tomcat and U+FFFD on Undertow.
- `spider-silk-core`: `paramLong` and `pathParamLong` take an optional sign and ASCII digits only, where full-width and other digits read as numbers.
- `spider-silk-core`: `file(name)` and `fileOrNull(name)` find the first file past an empty file input of the same name, as `files(name)` does.
- `spider-silk-core`: a status page that throws is reported as `completion.thrown()`, where its 500 reported nothing thrown.
- `spider-silk-core`: under `app.gzip()`, a 304 carries the `Vary` and the weak `ETag` the compressed 200 carried.
- `spider-silk-core`: `JsonReader.list` holds the nulls its element reader answers, where `List.copyOf` threw and answered 500; `JsonReader` takes a nullable type argument.
- `spider-silk-tomcat`: a multipart form may carry 1000 parts and 8KB of headers per part, as on Jetty, where Tomcat's own 50 parts and 512 bytes answered 413.
- `spider-silk-undertow`: a path with an encoded slash answers 400, as on Jetty and Tomcat, where `%2F` reached a path variable undecoded.
- `spider-silk-undertow`: a multipart form over Undertow's 1000 parts answers 400 instead of 500.
- `spider-silk-test`: `TestRequest` reports the charset its `Content-Type` declares, so a body in an unknown charset answers 415 as on a server, and parses a `Cookie` header into cookies.
- `spider-silk-core`: `redirect(location)` percent-encodes as UTF-8 each character a header cannot carry, anything outside ASCII, a space, or a control character.
  Jetty replaced such a character with a space, Undertow kept its low byte, and Tomcat sent the 302 with no `Location`.
- `spider-silk-core`: `cookie(...)` throws `IllegalArgumentException` for a value RFC 6265 does not allow, inside the handler.
  The container refused it only while writing, a bare 500 that no exception handler saw.
- `spider-silk-core`: the 500 that replaces a response which failed while written carries the CORS and security headers, so a browser no longer reports a CORS failure in its place.
- `spider-silk-core`: `Accept`, `Accept-Encoding`, and `Access-Control-Request-Headers` sent on several lines are read as the lines joined, as RFC 9110 reads a list field, where only the first line was read.
- `spider-silk-core`: after `bodyNdjson` or `bodyReader()`, any other reader of the body throws `IllegalStateException`, as the `bodyStream()` javadoc said.
  `bodyStream()` after `bodyNdjson` answered the rest of the body without the part the NDJSON reader had buffered.
- `spider-silk-core`: under `app.gzip()`, a HEAD for a streamed body, such as a static file, runs no writer and carries no `Content-Length`, where it opened and compressed the whole file.
- `spider-silk-core`: a stamped static file in a directory root rewritten while served, at the same length with the stamp kept, gets a new `ETag`, where it kept the old one and answered the old one with 304.
- `spider-silk-core`: `Json` writes a lone surrogate as an escape, where it reached the client as `?`.
- `spider-silk-undertow`: a failed `start()` undeploys the app, so the `App` accepts registrations again, and `contextPath("")` is the root, as on Jetty and Tomcat.
- `spider-silk-freemarker`: `?url` works, with UTF-8 as the charset, where it threw for every template.
- `spider-silk-openapi`: two routes that OpenAPI would read as one path throw `IllegalArgumentException`, where one operation was silently dropped or the document named one path twice.
- `spider-silk-core`: `JsonValue.asLong()` and `getLong` refuse a decimal with more than 19 significant digits from its digit count.
  A million-digit fraction, which fits in a 1MB body, used to hold the request thread for about 17 seconds before its 400.
- `spider-silk-core`: an `Error` such as `AssertionError` or `StackOverflowError`, thrown by a handler, a filter, an exception handler, a status page, or a response writer, answers the framework's 500 with the usual headers and reaches the request logger.
  The container used to answer it with its own page, without the security or CORS headers, and the logger heard of nothing, or of a 200 that succeeded.
- `spider-silk-core`: `app.stop()` and the JVM shutdown hook close an SSE stream whose client stopped reading without waiting for its blocked write.
  That write held the stop until the connector's idle timeout, about 30 seconds on Jetty and 60 on Tomcat, and it now ends with the stop timeout.
- `spider-silk-core`: `body()`, `bodyJson()`, and `bodyNdjson(...)` answer a body that ends before its `Content-Length` with 400 instead of 500, and refuse it again on a later read.
- `spider-silk-tomcat`: `stop()` no longer shuts down an executor passed to `executor(...)`, which refused every request after a restart.
  It waits within the stop timeout for the requests running on it, and leaves it running.
- `spider-silk-tomcat`: the stop no longer adds Tomcat's two-second `unloadDelay` after the drain has already waited the stop timeout.
- `spider-silk-core`: a multipart upload the container refuses answers 413 for a size limit and 400 for a body that will not parse, through `file`, `fileOrNull`, and `files`.
  Jetty used to answer an oversized part as a missing file, null, or an empty list, and Tomcat as a 500.
- `spider-silk-core`: `param`, `params`, and `formParam` answer a form body the container cannot parse with 400, and a multipart one as `file` does, instead of a 500.
- `spider-silk-core`: `body()`, `bodyJson()`, `bodyReader()`, and `bodyNdjson(...)` answer a charset the JVM cannot decode with 415, before a byte is read, instead of a 500.
- `spider-silk-core`: `JsonArray.get(index)` throws `JsonException` for an index outside the array, so a reader given a short array answers 400 instead of 500.
- `spider-silk-core`: a static file whose modification time is a reproducible build's stamp, one before 2000 such as the 1970-01-01T00:00:01Z Jib writes, is tagged by a CRC-32 of its content and carries no `Last-Modified`.
  A same-length edit used to keep its `ETag` across releases, and browsers kept the old file on a 304.
- `spider-silk-core`: a JVM shutdown closes open SSE streams through a hook of the application's own, so Ctrl-C and SIGTERM no longer wait out the stop timeout for them.
- `spider-silk-tomcat`: `stopTimeout` drains requests in flight on Tomcat's own thread pool, which the drain used to skip, dropping a request after Tomcat's two-second `unloadDelay`.
- `spider-silk-openapi`: a path key gets a leading slash and loses a trailing one, the way the router reads the pattern, so `get("decks")` beside `post("/decks/")` is the one item `/decks`.
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
