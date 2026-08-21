<p align="center">
  <img src="docs/logo.svg" alt="Spider Silk" width="160">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-e2603f?style=flat-square&labelColor=2b303b" alt="Java 21">
  <img src="https://img.shields.io/badge/Jakarta%20Servlet-6.0-8a93a6?style=flat-square&labelColor=2b303b" alt="Jakarta Servlet 6.0">
  <img src="https://img.shields.io/badge/jte-3.2.4-8a93a6?style=flat-square&labelColor=2b303b" alt="jte 3.2.4">
  <img src="https://img.shields.io/badge/reflection-none-e2603f?style=flat-square&labelColor=2b303b" alt="No reflection">
</p>

# Spider Silk

A thin web framework on top of the Jakarta Servlet API.

Three core principles:

- **No reflection.**
    * There is no annotation scanning, no proxies, no automatic binding.
    * What runs is exactly what you see in the code, stack traces stay short, and startup is fast.
* **The API is intuitively simple.**
    * A handler is a function from a request to a response: `WebResponse handle(WebRequest request)`.
    * `WebResponse` as the return type matches the intuition directly: a handler takes a request in and hands a response back.
* **Better RESTful API support than raw servlets.**
    * Per-method routing with path variables, typed parameter extraction, exception-to-status mapping.

**Full documentation: [spider-silk.benelog.net](https://spider-silk.benelog.net)**

## Quick Start

Java 21 or later is required.
The current version is `0.1.0-SNAPSHOT`, published to GitHub Packages.

> GitHub Packages requires authentication even for a public repository.
> Create a [personal access token (classic)](https://github.com/settings/tokens) with the `read:packages` scope and use it as the password.

### build.gradle

```groovy
repositories {
    mavenCentral()
    maven {
        url = uri('https://maven.pkg.github.com/benelog/spider-silk')
        credentials {
            username = project.findProperty('gpr.user') ?: System.getenv('GITHUB_ACTOR')
            password = project.findProperty('gpr.token') ?: System.getenv('GITHUB_TOKEN')
        }
    }
}

dependencies {
    implementation 'io.github.benelog.spidersilk:spider-silk-core:0.1.0-SNAPSHOT'
    testImplementation 'io.github.benelog.spidersilk:spider-silk-test:0.1.0-SNAPSHOT'
}
```

Keep the token out of the build file by putting it in `~/.gradle/gradle.properties`:

```properties
gpr.user=your-github-username
gpr.token=ghp_yourPersonalAccessToken
```

### pom.xml

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/benelog/spider-silk</url>
    <snapshots>
      <enabled>true</enabled>
    </snapshots>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>io.github.benelog.spidersilk</groupId>
    <artifactId>spider-silk-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </dependency>
  <dependency>
    <groupId>io.github.benelog.spidersilk</groupId>
    <artifactId>spider-silk-test</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
  </dependency>
</dependencies>
```

The matching credentials go in `~/.m2/settings.xml`, under the same `id`:

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>your-github-username</username>
      <password>ghp_yourPersonalAccessToken</password>
    </server>
  </servers>
</settings>
```

### Hello, world

```java
import spidersilk.App;
import spidersilk.WebResponse;

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

Routing groups, filters, error handlers, JSON codecs, SSE, templates, static files, route introspection, the test harness, and server tuning are all in the [documentation](https://spider-silk.benelog.net).

Embedded Jetty comes along with `spider-silk-core`, so nothing else is needed to serve a request.

## Modules

| Module | Contents | Dependencies |
|---|---|---|
| `spider-silk-core` | The framework itself | `gg.jte:jte`, embedded Jetty (`jetty-ee10-servlet`) |
| `spider-silk-test` | The `WebTest` harness and `TestRequest`, for test scope | core, and otherwise the JDK only (the servlet API compile-time only, as in core) |
| `spider-silk-tomcat` | `TomcatServer`, for running the same app on an embedded Tomcat instead | core, `tomcat-embed-core` |
| `spider-silk-undertow` | `UndertowServer`, the same for an embedded Undertow | core, `undertow-servlet` |
| `example-flashcard` | Example: a flashcard study app | core, spring-jdbc, H2 |

## Further reading

Where Spider Silk sits next to Javalin, Spark, Helidon SE, and Spring Boot, and what that comparison says should change: [docs/positioning.md](docs/positioning.md).
Why each piece has the shape it has, item by item, with the rejected list: [docs/decisions.md](docs/decisions.md).
What was deliberately deferred, and what would make it worth doing: [PLAN.md](PLAN.md).
