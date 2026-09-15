# Changelog

This file lists what changed in each release of Spider Silk.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the version numbers follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
The release workflow copies a version's section into its GitHub Release, so a section is written before the tag is pushed.
[RELEASING.md](RELEASING.md) has the steps.

## [Unreleased]

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

[Unreleased]: https://github.com/benelog/spider-silk/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/benelog/spider-silk/releases/tag/v1.0.0
