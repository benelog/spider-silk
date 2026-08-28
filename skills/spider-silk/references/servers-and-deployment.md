# Servers and deployment

Contents: [Jetty tuning](#jettyserver) · [Virtual threads](#virtual-threads) · [Tomcat](#tomcat) · [Undertow](#undertow) · [Choosing](#choosing-a-server) · [External container](#an-external-servlet-container) · [Container images](#container-images) · [Native image](#graalvm-native-image)

## JettyServer

`app.start(port)` runs embedded Jetty with graceful shutdown (a JVM shutdown hook, five seconds' drain) already in place.
Everything usually worth tuning is a method on `net.benelog.spidersilk.server.JettyServer`, and customizers reach the real Jetty objects:

```java
new JettyServer(app)
        .port(8443)
        .host("127.0.0.1")
        .contextPath("/app")
        .sessions(false)
        .threadPool(new QueuedThreadPool(200, 8))
        .multipart(new MultipartConfigElement(tmp, 10_485_760L, 10_485_760L, 1_048_576))
        .stopTimeout(Duration.ofSeconds(20))
        .shutdownHook(false)                    // something else owns the lifecycle
        .customizeHttpConfiguration(http -> http.setSendServerVersion(false))
        .customizeContext(context -> context.addFilter(MyFilter.class, "/*", null))
        .customizeServer(server -> server.setDumpBeforeStop(true))
        .start();
```

To keep `app.start(port)` as the entry point while configuring the server, replace the factory:

```java
app.server((a, port) -> new JettyServer(a).port(port).sessions(false))
   .start(9000);
```

`WebServer` is four methods (`start`, `stop`, `join`, `port`), so a server of your own is a small job.
`app.start(0)` binds an OS-picked free port; `app.port()` reads it back.

## Virtual threads

A thread pool setting, not a framework feature — worth it only when handlers block (I/O, database):

```java
QueuedThreadPool pool = new QueuedThreadPool();
pool.setVirtualThreadsExecutor(VirtualThreads.getDefaultVirtualThreadsExecutor());
app.server((a, port) -> new JettyServer(a).port(port).threadPool(pool)).start(8080);
```

A `synchronized` block around the blocking call pins the carrier thread and takes the benefit back.
On Tomcat and Undertow the same is `executor(Executors.newVirtualThreadPerTaskExecutor())`.

## Tomcat

`spider-silk-tomcat` (optionally `exclude group: 'org.eclipse.jetty.ee10'`):

```java
new App()
        .get("/hello/{name}", req -> WebResponse.text("Hello " + req.pathParam("name")))
        .server((app, port) -> new TomcatServer(app).port(port))
        .start(8080);

new TomcatServer(app)
        .port(8443).host("127.0.0.1").contextPath("/app")
        .baseDir(Path.of("/var/tmp/tomcat"))    // default: temp dir, deleted on stop
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .multipart(...).stopTimeout(Duration.ofSeconds(20)).shutdownHook(false)
        .customizeConnector(c -> c.setProperty("maxThreads", "400"))
        .customizeContext(ctx -> ctx.addParameter("mode", "production"))
        .customizeTomcat(t -> t.getHost().setAutoDeploy(false))
        .start();
```

Tomcat's own quirks: no `sessions(false)` (always on); the graceful drain waits on the default `ThreadPoolExecutor`, so `executor(...)` makes `stopTimeout` a no-op; logging is JULI, so route it with `jul-to-slf4j`.

## Undertow

`spider-silk-undertow` (same optional Jetty exclusion):

```java
new UndertowServer(app)
        .port(8443).host("127.0.0.1").contextPath("/app")
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .multipart(...).stopTimeout(Duration.ofSeconds(20)).shutdownHook(false)
        .customizeDeployment(d -> d.setDefaultEncoding("UTF-8"))
        .customizeBuilder(b -> b.setIoThreads(4).setWorkerThreads(64))
        .start();
```

Undertow's drain counts requests rather than threads, so it survives the virtual-thread executor; no `sessions(false)` here either; logging is JBoss Logging, which finds slf4j on its own.

## Choosing a server

Everything core provides works the same on all three, and `WebTest` starts no server at all, so switching is one line at the factory.

- **Jetty (default)**: costs nothing to keep — no disk, its own lifecycle, slf4j directly.
- **Tomcat**: for surroundings that already assume it (ops knowledge, JMX dashboards, Spring Boot migrations, enterprise CVE tracking).
- **Undertow**: lightest to embed, and the only one whose graceful drain is first-class (works with virtual threads); thinner ecosystem.

## An external servlet container

`AppServlet` is a plain servlet, so a container you did not start needs no server module at all:

```java
ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
context.addServlet(new ServletHolder(new AppServlet(app)), "/*");
```

Exclude the bundled Jetty when deploying this way:

```groovy
implementation('net.benelog.spidersilk:spider-silk-core') {
    exclude group: 'org.eclipse.jetty.ee10'
}
```

## Container images

The Gradle plugin (see setup.md) brings Jib with `eclipse-temurin:21-jre`, port 8080, and layered caching already set; the build states only the image name:

```groovy
plugins { id 'net.benelog.spidersilk' }
jib { to { image = 'ghcr.io/example/my-app' } }
```

```bash
gradle jibBuildTar      # writes build/jib-image.tar, no Docker daemon needed
gradle jib              # pushes straight to the registry
gradle jibDockerBuild   # loads into a local daemon
```

Precompile templates for production images (`spiderSilk { jte() }`): a JRE base suffices only when nothing compiles at runtime.
Where a platform demands a Dockerfile, mirror Jib's layering by hand: copy build scripts first, warm the dependency cache (`resolveDependencies` task on Gradle, `mvn dependency:go-offline` on Maven), then copy dependency jars, resources, and loose classes as separate `COPY` steps, least volatile first.
The framework repo's `example-flashcard/Dockerfile` is a worked example.

## GraalVM native image

The Gradle plugin and Maven parent configure the native build: `--no-fallback`, mostly static (`--static-nolibc`), reachability metadata on, so the binary runs on `distroless/base`.

```bash
gradle nativeCompile                # the binary
gradle -Pnative jibBuildTar         # containerize it, tagged :native
mvn -Pnative package jib:buildTar   # Maven equivalent
```

Requirements that follow from "no compiler at runtime": jte templates must be precompiled, and reflective libraries the app itself uses (e.g. Spring JDBC row mapping) need reachability metadata — the manual's native-image chapter (<https://spider-silk.benelog.net>) walks through generating it.
The framework's own code needs no reflection configuration.
