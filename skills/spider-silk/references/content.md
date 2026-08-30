# Content: JSON, templates, static files, SSE, WebSocket, OpenAPI

Contents: [JSON](#json) · [Large answers and NDJSON](#answers-too-large-to-hold) · [Binding with another library](#binding-json-with-another-library) · [Templates](#templates) · [Static files](#static-files) · [SSE](#server-sent-events) · [WebSocket](#websocket) · [OpenAPI export](#openapi-export)

## JSON

No reflection, so no automatic serialization: the wire format is code you write with `net.benelog.spidersilk.json.Json`.
Build trees inline, or name the mapping as a `JsonWriter<T>` / `JsonReader<T>` lambda so it is reused:

```java
import net.benelog.spidersilk.json.*;

static final JsonWriter<Deck> DECK = deck -> Json.obj()
        .put("id", deck.id())
        .put("name", deck.name());
static final JsonWriter<List<Deck>> DECKS = JsonWriter.list(DECK);

record NewDeck(String name) { }
static final JsonReader<NewDeck> NEW_DECK =
        json -> new NewDeck(json.asObject().getString("name"));

app.get("/api/decks", req -> WebResponse.json(deckService.decks(), DECKS));
app.post("/api/decks", req -> {
    Deck deck = deckService.create(req.bodyJson(NEW_DECK).name());   // missing key -> 400
    return WebResponse.json(deck, DECK).status(HttpStatus.CREATED);
});
```

- `getString`/`getLong`/... throw `IllegalArgumentException` on a missing key or wrong type, and `req.bodyJson(reader)` turns that into a 400: a handler gets a whole value or none.
- For keys allowed to be absent: `optString`/`optLong`/`optDouble`/`optBoolean` answer a default, for a missing key and an explicit JSON `null` alike.
- A parsed array reads with for-each: `for (Json.JsonValue v : json.asArray())`.
- `JsonCodec<T>` is writer and reader at once for types that travel both ways: `JsonCodec.of(writer, reader)`, `JsonCodec.list(codec)`.
- The conventional home for an app's wire format is one `Codecs` class of static writer/reader lambdas.

### Answers too large to hold

`WebResponse.json(list, DECKS)` builds every element as a tree and then one string holding all of them, so a hundred thousand rows sit in memory twice before the client sees the first one.
That is the right trade for an answer that fits and the wrong one for an export.
Two response kinds write the elements as they are produced instead:

```java
// An ordinary JSON array, written a value at a time: the brackets and commas belong to the response
app.get("/api/decks/{deckId}/cards", req -> {
    long deckId = req.pathParamLong("deckId");           // look the deck up here, not inside the writer
    return WebResponse.jsonArray(sink -> service.eachCard(deckId, card -> sink.write(card, CARD)));
});

// One complete JSON value per line, application/x-ndjson
app.get("/api/decks/{deckId}/cards.ndjson", req -> {
    long deckId = req.pathParamLong("deckId");
    return WebResponse.ndjson(sink -> service.eachCard(deckId, card -> sink.write(card, CARD)));
});

// Reading NDJSON back, lazily: a hundred thousand records are never a list
app.post("/api/decks/{deckId}/cards.ndjson", req -> {
    int imported = service.importCards(req.pathParamLong("deckId"), req.bodyNdjson(CARD_DRAFT));
    return WebResponse.json(Json.obj().put("imported", imported));
});
```

- `sink.write(value, writer)` takes the same hand-written `JsonWriter<T>` as everything else: a different way of framing the output, not a different way of mapping it.
  It throws `UncheckedIOException` rather than a checked `IOException`, so `card -> sink.write(card, CARD)` is an ordinary `Consumer` and fits a row callback over a database cursor.
- Both are a `stream` body underneath, so the headers commit before the writer runs.
  Anything that can fail in a way the client should hear about — a missing deck, an unauthorized caller — must be settled *before* the response is returned; a failure inside the writer can no longer change the status.
- `req.bodyNdjson(reader)` is a lazy `Stream<T>`.
  A line that is not valid JSON, or that the reader rejects, answers 400 naming the line — but only where the stream is consumed, so consume it before returning the response, ideally inside one transaction so a bad line rolls the import back.
- Prefer NDJSON over an array for bulk data: each line stands alone, so a consumer acts on record one without waiting for the last, and a cut-off transfer leaves whole records rather than an unclosed document.
  It is bulk transfer, not live events — [SSE](#server-sent-events) is the one that flushes per event and reconnects.
- There is no streaming *parser*: `bodyJson()` builds the whole tree, because a tree is what a hand-written `JsonReader` reads.
  For a single document too large to hold, the answer is NDJSON.

### Binding JSON with another library

Hand-written mapping is core's decision for core, not for the application.
An app with three hundred DTOs, or a wire format generated from a schema it does not own, has a reason to bind with a library — so when the user asks for Jackson, Gson, or avaje-jsonb, wire it through the seam rather than refusing:

```java
// Out: a document something else already serialized
return WebResponse.json(MAPPER.writeValueAsString(decks));

// In: the body, unread. bodyStream() is bytes (Jackson), bodyReader() is characters (Gson)
NewDeck body = MAPPER.readValue(req.bodyStream(), NewDeck.class);
NewDeck body = GSON.fromJson(req.bodyReader(), NewDeck.class);
```

- Never route a library's output through `JsonWriter<T>`: it returns a `Json.JsonValue`, so the document would be re-parsed the moment the library finished writing it.
- A body reads as bytes or as characters, never both.
  After `body()`, `bodyJson()`, `bodyNdjson()`, or `param()` — all of which read characters — `bodyStream()` throws `IllegalStateException`, and the reverse holds too.
- Core has not been told the body is JSON, so the library's own failures are the application's to answer: map them with `app.exception(JsonProcessingException.class, ...)` or throw `HttpException(HttpStatus.BAD_REQUEST, ...)`.
- Under a [native image](servers-and-deployment.md#graalvm-native-image), Jackson and Gson need a reflection entry per bound type; avaje-jsonb generates an adapter per type at compile time and needs none.

## Templates

`WebResponse.template(name, model)` renders a page; the name carries no extension — the engine appends its own.
Out of the box an `App` renders with jte over `classpath:/jte`, appending `.jte`; the model `Map` is passed to jte's parameters by name and `${}` output is HTML-escaped.

```java
app.get("/decks/{deckId}", req ->
        WebResponse.template("deck", Map.of("deck", service.deck(req.pathParamLong("deckId")))));
// renders classpath:/jte/deck.jte

app.templates(new JteTemplates("templates").suffix(".html"));   // a root or suffix of your own
```

Rendering happens while exception handling still applies, so a template that throws reaches `app.exception(...)`.

### jte development vs. production

- Development — resolve from the source directory and hot-recompile on change:
  `app.templates(new JteTemplates(new DirectoryCodeResolver(Path.of("src/main/resources/jte"))));`
- Production — precompile at build time (the Gradle plugin's `spiderSilk { jte() }` writes the jte plugin block for you), then:
  `app.templates(new JteTemplates(TemplateEngine.createPrecompiled(ContentType.Html)));`
  No JDK needed at runtime; a template that does not compile fails the build.
  A GraalVM native image requires this mode.

### Other engines

Each is a module of its own; `templates(renderer)` swaps it in.
All append their suffix (never checked for, so keep extensions out of template names), and all escape by default.

```java
app.templates(new FreeMarkerTemplates("freemarker"));   // classpath:/freemarker/deck.ftlh, ${x?no_esc} opts out
app.templates(new HandlebarsTemplates("hbs"));          // classpath:/hbs/deck.hbs, {{{x}}} opts out
app.templates(new ThymeleafTemplates("thymeleaf"));     // classpath:/thymeleaf/deck.html, th:utext opts out
```

Each also has a constructor taking the engine's own configured object (`Configuration`, `Handlebars`, `TemplateEngine`) for helpers, dialects, or file-system loading — leave the engine-side suffix empty, since the renderer appends its own.
Any other engine is one lambda: `app.templates((template, model, out) -> mustache.compile(template + ".mustache").execute(out, model));`

## Static files

`classpath:/public/*` is served at the root without being asked; routes are matched first, so a route can shadow a file.
Every answer carries `ETag` and `Last-Modified` (reloads come back 304), with `Cache-Control: no-cache` by default.

```java
app.staticFiles("/assets");                             // a different classpath root

app.staticFiles(new StaticFiles("/public")
        .hostedPath("/assets")                          // classpath:/public/* at /assets/*
        .maxAge(Duration.ofDays(365))                   // only for fingerprinted names
        .precompressed());                              // app.css.br / app.css.gz answer app.css

app.staticFiles(
        new StaticFiles("/public"),
        StaticFiles.directory(Path.of("/srv/uploads"))  // a directory on disk, path-traversal guarded
                .hostedPath("/uploads"));               // several roots read in order

app.staticFiles();                                      // serves nothing at all
```

`precompressed()` serves a `.br` or `.gz` sibling a build left next to the asset (brotli preferred), skipping siblings older than the file; `gzip()` still deflates what has no sibling.

## Server-Sent Events

`WebResponse.sse(...)` answers `text/event-stream`, one flushed event per call.
It is an ordinary GET route, so filters, `app.routes()`, and the request logger all cover it.

```java
app.get("/decks/{deckId}/events", req -> {
    long deckId = req.pathParamLong("deckId");
    return WebResponse.sse(stream -> {
        while (stream.isOpen()) {
            stream.id(String.valueOf(revision))
                  .send("due", Json.obj().put("count", service.due(deckId)).toJson());
            Thread.sleep(1000);
        }
    });
});
```

- `send(data)` unnamed event, `send(event, data)` named, `id(...)` labels for `Last-Event-ID` resume, `comment(text)` is a heartbeat.
- The request holds its thread for the stream's lifetime — the virtual-thread executor (servers-and-deployment.md) is what makes many streams cheap.
- A client that went away surfaces as `SseStream.Closed` thrown from the write; `app.stop()` closes open streams first.
- Jetty idles out a quiet connection after 30 seconds: `stream.comment("ping")` on a timer keeps it alive.

## WebSocket

`spider-silk-jetty-websocket` (Jetty-only, deliberately) maps endpoints beside the routes via the server factory:

```java
import net.benelog.spidersilk.jetty.websocket.*;

new App()
        .get("/", req -> WebResponse.text("hi"))
        .server((app, port) -> new JettyServer(app).port(port)
                .customizeServer(new WebSockets()
                        .at("/echo", (request, response) -> new EchoSocket())
                        .idleTimeout(Duration.ofMinutes(5))))
        .start(8080);

final class EchoSocket implements WebSocketHandler {
    @Override public void onText(Session session, String message) {
        session.sendText(message, Callback.NOOP);       // async; a real Callback notices failures
    }
}
```

- `WebSocketHandler` methods (`onOpen`, `onText`, `onBinary`, `onClose`, `onError`) all have do-nothing defaults; the factory builds one handler per connection, and callbacks are never concurrent per connection.
- Returning `null` from the factory refuses the upgrade with 403; the factory sees the full HTTP request (auth, subprotocols).
- Paths are Jetty's spec syntax (`/rooms/*`, `*.ws`), not `{name}` — read the path in the factory for variables.
- An upgrade leaves servlet dispatch: no filters, no `error(...)`, no request logger, not in `app.routes()`, and `WebTest` cannot reach it.
  Prefer SSE when server-push over plain HTTP is enough.

## OpenAPI export

`spider-silk-openapi` turns `app.routes()` into an OpenAPI 3.1 document — a pure function, no reflection, no server needed:

```java
import net.benelog.spidersilk.openapi.OpenApi;

app.get("/openapi.json", req -> WebResponse.json(
        OpenApi.document("Flashcard API", "1.0.0", app.routes())));
```

- Narrow the list yourself when HTML pages sit beside the API: `app.routes().stream().filter(r -> r.path().startsWith("/api")).toList()`.
- A route whose pattern contains `*` throws `IllegalArgumentException` (no OpenAPI template for it) — filter it out visibly.
- Route descriptions become each operation's `summary`; `{name}` segments become required string path parameters; each operation carries a `200 OK`.
- Schemas, servers, and security are absent on purpose: none is derivable from a route.
