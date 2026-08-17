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

A very thin web framework on top of the Jakarta Servlet API.

Two core principles:

- **No reflection.** There is no annotation scanning, no proxies, no automatic
  binding. Routes are registered as lambdas, and type conversion happens only
  through explicit methods such as `pathParamLong`. What runs is exactly what
  you see in the code, stack traces stay short, and startup is fast.
- **Better RESTful API support than raw servlets.** Per-method routing with
  path variables, typed parameter extraction, exception-to-status mapping,
  automatic 405 (Method Not Allowed) responses, and a reflection-free JSON
  builder/parser.

The template engine is [jte](https://jte.gg). jte also compiles templates to
Java code, which fits the framework's character.

## Modules

| Module | Contents | Dependencies |
|---|---|---|
| `spider-silk-core` | The framework itself | `jakarta.servlet-api` (compileOnly), `gg.jte:jte` |
| `example-flashcard` | Example: a flashcard study app | core, Jetty (embedded), spring-jdbc, H2 |

## At a Glance

```java
App app = new App()
        .templates(new JteTemplates("jte"))     // classpath:/jte/*.jte
        .staticFiles("/public");                // serves classpath:/public/* statically

// Server-side rendering
app.get("/decks/{deckId}", ctx -> {
    long deckId = ctx.pathParamLong("deckId");  // non-numeric input becomes a 400
    ctx.render("deck.jte", model);
});

// JSON API — you state in code what goes out (no automatic serialization)
app.get("/api/decks", ctx -> ctx.json(
        Json.arr().add(Json.obj().put("id", 1L).put("name", "English"))));

app.post("/api/decks", ctx -> {
    String name = ctx.bodyJson().asObject().getString("name");
    ctx.status(201).json(Json.obj().put("name", name));
});

// Exception-to-response mapping
app.exception(IllegalArgumentException.class,
        (e, ctx) -> ctx.status(404).text(e.getMessage()));
```

Deploy to any servlet container with `AppServlet`:

```java
ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
context.addServlet(new ServletHolder(new AppServlet(app)), "/*");
```

### What WebContext provides

- Path variables: `pathParam`, `pathParamLong`, `pathParamEnum`
- Parameters: `param` (400 when missing), `param(name, default)`, `paramLong`, `paramBoolean`, `paramEnum`
- Body: `body()`, `bodyJson()`, multipart upload via `file(name)`
- Session: `sessionAttr(key)` / `sessionAttr(key, value)` / `removeSessionAttr`
- Flash: `flash(key, value)` → read exactly once with `flashed(key)` on the request after a redirect
- Response: `status`, `header`, `redirect`, `html`, `text`, `json`, `bytes`, `attachment`, `render`

### The scope of "no reflection"

The principle applies to the **framework core**: routing, parameter extraction,
and JSON handling use no reflection anywhere. The example project's choice of
spring-jdbc's `DataClassRowMapper` and the transaction internals do use
reflection — that is the example's choice, and switching to repositories that
handle JDBC directly would remove even that.

## Example: Flashcard

The same features as ch07-jdbc-plus from `spring-jdbc-book` (decks, cards,
tags, spaced-repetition study, smart decks, CSV import/export, statistics)
built without Spring Boot.

- **DB**: repositories use `NamedParameterJdbcTemplate` directly. The executed
  SQL is visible in the code.
- **DI**: no container — `FlashcardContext` wires everything by calling
  constructors directly, playing the role of Spring's ApplicationContext by hand.
- **Transactions**: instead of AOP, services call `Transactions.write()/read()`,
  a thin wrapper around `TransactionTemplate`. The wrapped block is exactly the
  transaction scope.
- As a bonus, a JSON API (`/api/decks`, `/api/decks/{id}/cards`) sits on the
  same service layer to show the framework's REST support.

### Run

```bash
gradle :example-flashcard:run
# http://localhost:8080
```

The database is an H2 file (`~/db/spider-silk/flashcard`), so data survives
restarts.

### Tests

```bash
gradle test
```

Repository and service tests run against in-memory H2 without mocking.
The rollback test in `DeckServiceTest` shows the `TransactionTemplate`
boundary actually working.

## Deployment Notes

- jte compiles templates at runtime by default (requires a JDK). For production,
  precompile with the [jte Gradle plugin](https://jte.gg/pre-compiling/) and
  pass the engine through the `JteTemplates(TemplateEngine)` constructor.
- `AppServlet` is a standard servlet, so it runs on any container (Tomcat and
  others), not just Jetty.
