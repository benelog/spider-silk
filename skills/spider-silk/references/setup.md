# Setup: dependencies, modules, build integration

Spider Silk requires Java 21 or later.
Releases are published to Maven Central, so neither Gradle nor Maven needs a repository beyond the default or any credentials.
The snippets below write `1.1.0`, the release this skill was written against.
SKILL.md has the rule for checking it before it reaches a build file.
The group id, the package root, and the `Automatic-Module-Name` are all `net.benelog.spidersilk`.

## Gradle

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'net.benelog.spidersilk:spider-silk-core:1.1.0'
    testImplementation 'net.benelog.spidersilk:spider-silk-test:1.1.0'
}
```

## Maven

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

## Modules

| Artifact | What it adds | Note |
|---|---|---|
| `spider-silk-core` | The framework: routing, request/response, JSON, jte templates, static files, SSE, embedded Jetty | The only required module |
| `spider-silk-test` | `WebTest` harness and `TestRequest` | Test scope only |
| `spider-silk-tomcat` | `TomcatServer` (embedded Tomcat instead of Jetty) | May `exclude group: 'org.eclipse.jetty.ee10'` |
| `spider-silk-undertow` | `UndertowServer` (embedded Undertow) | Same optional Jetty exclusion |
| `spider-silk-freemarker` | `FreeMarkerTemplates` | May `exclude group: 'gg.jte'` |
| `spider-silk-handlebars` | `HandlebarsTemplates` | Same optional jte exclusion |
| `spider-silk-thymeleaf` | `ThymeleafTemplates` | Same optional jte exclusion |
| `spider-silk-jetty-websocket` | `WebSockets` endpoint mapping | Jetty-only by design |
| `spider-silk-openapi` | `OpenApi.document(...)` over `app.routes()` | Depends on core only |

The exclusions are optional; they only keep an unused server or engine off the classpath.
Each module's classes live in a subpackage of the core root: `net.benelog.spidersilk.tomcat`, `net.benelog.spidersilk.undertow`, `net.benelog.spidersilk.freemarker`, `net.benelog.spidersilk.jetty.websocket`, `net.benelog.spidersilk.openapi`.

## The Gradle plugin (packaging conventions)

The `net.benelog.spidersilk` plugin carries the packaging block an application would otherwise restate: it applies `application`, Jib, and the GraalVM native plugin, with Jib on `eclipse-temurin:21-jre` exposing port 8080 and the native binary built `--no-fallback --static-nolibc`.

```groovy
plugins {
    id 'net.benelog.spidersilk' version '1.1.0'
}

spiderSilk {
    jte()   // precompiled templates; leave out for FreeMarker, Handlebars, or Thymeleaf
}

application {
    mainClass = 'com.example.Main'
}

jib {
    to { image = 'ghcr.io/example/my-app' }
}
```

The plugin is published to Maven Central, not the Gradle Plugin Portal, so `settings.gradle` adds Central to the plugin repositories:

```groovy
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
```

`spiderSilk { jte() }` sets up jte precompilation (templates under `src/main/resources/jte` become classes at build time) plus jte's native-image resources extension.
`-Pnative` re-aims the Jib tasks at the GraalVM binary on a `distroless/base` image, tagged `:native`.

## The Maven parent (same conventions by inheritance)

```xml
<parent>
  <groupId>net.benelog.spidersilk</groupId>
  <artifactId>spider-silk-maven-parent</artifactId>
  <version>1.1.0</version>
</parent>
```

The parent resolves from Maven Central like the jars, so it needs no repository declaration.
Everything sits in the parent's `pluginManagement` and is inert until the child declares the plugin: declaring `jib-maven-plugin`, `jte-maven-plugin`, or `native-maven-plugin` is the opt-in.
The main class is declared once, in the `maven-jar-plugin` manifest; Jib and the native plugin read it from there.
`mvn -Pnative package jib:buildTar` ships the GraalVM binary instead of the jar.
