<p align="center">
  <img src="notes/logo.svg" alt="Spider Silk" width="160">
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/net.benelog.spidersilk/spider-silk-core"><img src="https://img.shields.io/maven-central/v/net.benelog.spidersilk/spider-silk-core?style=flat-square&labelColor=2b303b&color=e2603f&label=Maven%20Central" alt="Maven Central"></a>
  <img src="https://img.shields.io/badge/Java-21-e2603f?style=flat-square&labelColor=2b303b" alt="Java 21">
  <img src="https://img.shields.io/badge/Jakarta%20Servlet-6.0-8a93a6?style=flat-square&labelColor=2b303b" alt="Jakarta Servlet 6.0">
  <img src="https://img.shields.io/badge/jte-3.2.4-8a93a6?style=flat-square&labelColor=2b303b" alt="jte 3.2.4">
  <img src="https://img.shields.io/badge/reflection-none-e2603f?style=flat-square&labelColor=2b303b" alt="No reflection">
</p>

# Spider Silk

Thin call stack, strong signature.

Spider Silk is a web framework built on the Jakarta Servlet API.

Thin here is a measurement rather than a metaphor.
Two stack frames stand between `HttpServlet.service` and a handler, and a stack trace taken inside the handler shows both.

```
java.lang.Throwable: where a handler stands
    at com.example.decks.DeckRoutes.one(DeckRoutes.java:15)  // the handler
    at net.benelog.spidersilk.AppServlet.dispatch(AppServlet.java:221)  // Spider Silk
    at net.benelog.spidersilk.AppServlet.service(AppServlet.java:128)  // Spider Silk
    at jakarta.servlet.http.HttpServlet.service(HttpServlet.java)  // the Servlet API
    at org.eclipse.jetty.ee10.servlet.ServletHolder.handle(ServletHolder.java)  // Jetty, from here down
    at org.eclipse.jetty.ee10.servlet.ServletHandler$ChainEnd.doFilter(ServletHandler.java)
    at org.eclipse.jetty.ee10.servlet.ServletHandler$MappedServlet.handle(ServletHandler.java)
    at org.eclipse.jetty.ee10.servlet.ServletChannel.dispatch(ServletChannel.java)
    at org.eclipse.jetty.ee10.servlet.ServletChannel.handle(ServletChannel.java)
    ... 12 more Jetty frames
    at java.base/java.lang.Thread.run(Thread.java)
```

Seventeen frames of Jetty follow, and the trace shows five of them.
Swapping in Tomcat or Undertow changes that part of the stack and not the two frames above it.
No filter an application registers adds to them either, because each has answered, or declined to, before the handler is called.
A filter or an exception handler stands one frame deeper, a streamed body two, and nothing the framework calls into goes deeper than four.
The signature is the other half: a handler answers by returning a `WebResponse`, so a branch that forgets to answer is a compile error rather than a blank response.
Spider silk is thin and holds, which is what the name is for.

Three core principles:

- **No reflection.**
    * There is no annotation scanning, no proxies, and no automatic binding.
    * What runs is what the code says, stack traces stay short, and startup stays fast.
- **The API is intuitively simple.**
    * A handler is a function from a request to a response: `WebResponse handle(WebRequest request)`.
    * That signature is the whole model.
- **Better RESTful API support than raw servlets.**
    * Routing is per method, paths carry variables, parameters are extracted with a declared type, and exceptions map to status codes.

**Full documentation: [spider-silk.benelog.net](https://spider-silk.benelog.net)**, also [in Korean](https://spider-silk.benelog.net/ko/), with every released version alongside.

## Quick Start

Spider Silk requires Java 21 or later.
The current version is `1.1.0`, published to Maven Central.

### build.gradle

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'net.benelog.spidersilk:spider-silk-core:1.1.0'
    testImplementation 'net.benelog.spidersilk:spider-silk-test:1.1.0'
}
```

### pom.xml

```xml
<dependencies>
  <dependency>
    <groupId>net.benelog.spidersilk</groupId>
    <artifactId>spider-silk-core</artifactId>
    <version>1.1.0</version>
  </dependency>
  <dependency>
    <groupId>net.benelog.spidersilk</groupId>
    <artifactId>spider-silk-test</artifactId>
    <version>1.1.0</version>
    <scope>test</scope>
  </dependency>
</dependencies>
```

### Hello, world

```java
import net.benelog.spidersilk.App;
import net.benelog.spidersilk.WebResponse;

public class Main {

    public static void main(String[] args) {
        App app = new App();
        app.get("/hello/{name}", req -> WebResponse.text("Hello, " + req.pathParam("name")));
        app.start(8080);
    }
}
```

```bash
$ curl localhost:8080/hello/silk
Hello, silk
```

`spider-silk-core` brings embedded Jetty with it, so nothing else is needed to serve a request.

Routing groups, filters, error handlers, JSON codecs, SSE, templates, static files, route introspection, the test harness, and server tuning are all in the [documentation](https://spider-silk.benelog.net).

## Modules

| Module | Contents | Dependencies |
|---|---|---|
| `spider-silk-core` | The framework itself | `gg.jte:jte`, embedded Jetty (`jetty-ee10-servlet`) |
| `spider-silk-test` | The `WebTest` harness and `TestRequest`, for test scope | core, and otherwise the JDK only (the servlet API compile-time only, as in core) |
| `spider-silk-tomcat` | `TomcatServer`, for running the same app on an embedded Tomcat instead | core, `tomcat-embed-core` |
| `spider-silk-undertow` | `UndertowServer`, the same for an embedded Undertow | core, `undertow-servlet` |
| `spider-silk-freemarker` | `FreeMarkerTemplates`, for rendering FreeMarker templates | core, `freemarker` |
| `spider-silk-handlebars` | `HandlebarsTemplates`, the same for Handlebars | core, `handlebars` |
| `spider-silk-thymeleaf` | `ThymeleafTemplates`, the same for Thymeleaf | core, `thymeleaf` |
| `spider-silk-jetty-websocket` | `WebSockets`, for WebSocket endpoints alongside the routes, on Jetty | core, `jetty-websocket-jetty-server` |
| `spider-silk-openapi` | `OpenApi`, for the route list as an OpenAPI 3.1 document | core only |
| `example-flashcard` | Example: a flashcard study app | core, spring-jdbc, H2 |

## AI coding agents

The repository ships an [Agent Skill](https://agentskills.io) that teaches coding agents the framework: [`skills/spider-silk/`](skills/spider-silk/SKILL.md).
Claude Code installs it as a plugin:

```
/plugin marketplace add benelog/spider-silk
/plugin install spider-silk@spider-silk
```

Codex, Cursor, and GitHub Copilot read the same directory once it is copied into their skill locations.
[The documentation](https://spider-silk.benelog.net/agent-skill.html) has the paths.

## Further reading

[notes/positioning.md](notes/positioning.md) places Spider Silk next to Javalin, Spark, Helidon SE, and Spring Boot, and states what it trades away to get there.
[notes/decisions.md](notes/decisions.md) gives the reasoning behind each piece, item by item, together with the list of what was rejected.
[CHANGELOG.md](CHANGELOG.md) lists what changed in each release.
[The issue tracker](https://github.com/benelog/spider-silk/issues) records what was deliberately deferred, one issue per item, with the condition that would make it worth doing.
