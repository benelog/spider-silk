<p align="center">
  <img src="notes/logo.svg" alt="Spider Silk" width="160">
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/net.benelog.spidersilk/spider-silk-core"><img src="https://img.shields.io/maven-central/v/net.benelog.spidersilk/spider-silk-core?style=flat-square&labelColor=2b303b&color=e2603f&label=Maven%20Central" alt="Maven Central"></a>
  <img src="https://img.shields.io/badge/Java-21-e2603f?style=flat-square&labelColor=2b303b" alt="Java 21">
  <img src="https://img.shields.io/badge/Jakarta%20Servlet-6.0-8a93a6?style=flat-square&labelColor=2b303b" alt="Jakarta Servlet 6.0">
  <img src="https://img.shields.io/badge/jte-3.2.4-8a93a6?style=flat-square&labelColor=2b303b" alt="jte 3.2.4">
  <img src="https://img.shields.io/badge/reflection-none-e2603f?style=flat-square&labelColor=2b303b" alt="No reflection">
  <a href="https://spider-silk.benelog.net"><img src="https://img.shields.io/badge/docs-manual-8a93a6?style=flat-square&labelColor=2b303b" alt="Manual"></a>
  <a href="https://spider-silk.benelog.net/ko/"><img src="https://img.shields.io/badge/docs-%ED%95%9C%EA%B5%AD%EC%96%B4%20%EB%A7%A4%EB%89%B4%EC%96%BC-8a93a6?style=flat-square&labelColor=2b303b" alt="한국어 매뉴얼"></a>
</p>

# Spider Silk

Thin call stack, strong signature.

Spider Silk is a web framework built on the Jakarta Servlet API.

## Why Spider Silk

- **Fast startup.**
  There is no annotation scanning and no proxy generation, so an application starts fast in an autoscaled container, a serverless function, a test run, and a development-server restart alike.
- **Code traceable from its source.**
  A person and an AI coding agent alike follow the code a request passes through without running it.
  - **A simple API.**
    A handler is `WebResponse handle(WebRequest request)`, registered as a lambda or a method reference.
  - **No reflection.**
    The framework's own code reflects over nothing, so what runs is what the code says, and a GraalVM native image needs no reflection config for it.
  - **A thin call stack.**
    Two stack frames stand between `HttpServlet.service` and a handler, and nothing the framework calls into is more than four frames deep.
  - **A small default stack.**
    One dependency brings embedded Jetty and jte, and jte compiles templates to Java, so rendering takes no reflection either.
- **Open to the ecosystem.**
  Tomcat and Undertow, and FreeMarker, Handlebars, and Thymeleaf, come as optional modules, and switching to one is a single line.
- **Tooling around it.**
  A test harness, an OpenAPI export, a Gradle plugin and a Maven parent for packaging, and an agent skill for AI coding agents come with the framework.

**The manual explains each of these in detail: [spider-silk.benelog.net](https://spider-silk.benelog.net)** ([한국어 매뉴얼](https://spider-silk.benelog.net/ko/)), for every released version.

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
The [manual](https://spider-silk.benelog.net) covers the rest: the modules, templates, testing, deployment, and the agent skill.

## AI coding agents

[`skills/spider-silk/`](skills/spider-silk/SKILL.md) is an [Agent Skill](https://agentskills.io) that guides a coding agent to use the framework as intended.
The repository is a plugin marketplace for both Claude Code and Codex.
Either marketplace installs the skill from the latest release's tag, so the skill describes the API of the jars on Maven Central.

Claude Code installs it with two commands inside a session:

```
/plugin marketplace add benelog/spider-silk
/plugin install spider-silk@spider-silk
```

Codex installs it with two commands in a shell:

```bash
codex plugin marketplace add benelog/spider-silk
codex plugin add spider-silk@spider-silk
```

A new release reaches an installed skill through `/plugin marketplace update spider-silk` in Claude Code, and through `codex plugin marketplace upgrade spider-silk` followed by `codex plugin add spider-silk@spider-silk` in Codex.
[The manual](https://spider-silk.benelog.net/agent-skill.html) covers auto-update, a project-level copy, and Cursor and GitHub Copilot.

## Further reading

- [The manual](https://spider-silk.benelog.net) and [the Korean manual (한국어 매뉴얼)](https://spider-silk.benelog.net/ko/): every feature in detail, for every released version.
- [notes/positioning.md](notes/positioning.md): Spider Silk next to Javalin, Spark, Helidon SE, and Spring Boot, and what it trades away.
- [notes/decisions.md](notes/decisions.md): the reasoning behind each piece, and what was rejected.
- [CHANGELOG.md](CHANGELOG.md): what changed in each release.
- [The issue tracker](https://github.com/benelog/spider-silk/issues): what was deferred, with the condition that would make it worth doing.
