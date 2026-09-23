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

Thin is a measurement: two stack frames stand between `HttpServlet.service` and a handler.

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

- Tomcat or Undertow changes only the container frames below.
- Filters add no frames, since they finish before the handler runs.
- Nothing the framework calls into is more than four frames deep.
- Strong is the signature: a handler returns a `WebResponse`, so a branch that forgets to answer does not compile.

Spider silk is thin and holds, which is what the name is for.

Three principles:

- **No reflection**: no annotation scanning, no proxies, no automatic binding.
  What runs is what the code says, and startup stays fast.
- **One simple model**: a handler is `WebResponse handle(WebRequest request)`.
- **Better REST support than raw servlets**: per-method routing, path variables, typed parameters, and exceptions mapped to status codes.

**Documentation: [spider-silk.benelog.net](https://spider-silk.benelog.net)** ([Korean](https://spider-silk.benelog.net/ko/)), for every released version.

## Quick Start

Requires Java 21 or later.
The current version on Maven Central is `1.1.0`.

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

`spider-silk-core` includes embedded Jetty, so nothing else is needed.
Everything else is in the [documentation](https://spider-silk.benelog.net).

## Modules

### Core

Every application uses these two.

| Module | Contents | Dependencies |
|---|---|---|
| `spider-silk-core` | The framework, with jte templates and embedded Jetty | `gg.jte:jte`, `jetty-ee10-servlet` |
| `spider-silk-test` | `WebTest` and `TestRequest`, for test scope | core, and the JDK only |

### Extensions

Optional: add one only when you want what it names instead of, or beside, the core default.

| Module | Use it when you want | Dependencies |
|---|---|---|
| **Servers** (core's default is Jetty) | | |
| `spider-silk-tomcat` | `TomcatServer`: embedded Tomcat | core, `tomcat-embed-core` |
| `spider-silk-undertow` | `UndertowServer`: embedded Undertow | core, `undertow-servlet` |
| **Templates** (core's default is jte) | | |
| `spider-silk-freemarker` | `FreeMarkerTemplates`: FreeMarker | core, `freemarker` |
| `spider-silk-handlebars` | `HandlebarsTemplates`: Handlebars | core, `handlebars` |
| `spider-silk-thymeleaf` | `ThymeleafTemplates`: Thymeleaf | core, `thymeleaf` |
| **Features** | | |
| `spider-silk-jetty-websocket` | `WebSockets`: WebSocket endpoints beside the routes, on Jetty | core, `jetty-websocket-jetty-server` |
| `spider-silk-openapi` | `OpenApi`: the route list as an OpenAPI 3.1 document | core only |

### Build

Optional packaging conventions: precompiled jte, a Jib image, and a native build.

| Module | Build tool |
|---|---|
| `spider-silk-gradle-plugin` | Gradle, as the plugin `net.benelog.spidersilk` |
| `spider-silk-maven-parent` | Maven, as a parent POM |

### Example

`example-flashcard` is a flashcard study app built on core, spring-jdbc, and H2.

## AI coding agents

[`skills/spider-silk/`](skills/spider-silk/SKILL.md) is an [Agent Skill](https://agentskills.io) that teaches coding agents the framework.
Claude Code installs it as a plugin:

```
/plugin marketplace add benelog/spider-silk
/plugin install spider-silk@spider-silk
```

Codex, Cursor, and GitHub Copilot read the same directory from their skill locations, [listed here](https://spider-silk.benelog.net/agent-skill.html).

## Further reading

- [notes/positioning.md](notes/positioning.md): Spider Silk next to Javalin, Spark, Helidon SE, and Spring Boot, and what it trades away.
- [notes/decisions.md](notes/decisions.md): the reasoning behind each piece, and what was rejected.
- [CHANGELOG.md](CHANGELOG.md): what changed in each release.
- [The issue tracker](https://github.com/benelog/spider-silk/issues): what was deferred, with the condition that would make it worth doing.
