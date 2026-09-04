# Testing with WebTest and TestRequest

Both live in `spider-silk-test` (`net.benelog.spidersilk.test`), a module of its own so the production jar carries no test code:

```groovy
testImplementation 'net.benelog.spidersilk:spider-silk-test:0.1.0-SNAPSHOT'
```

## End to end: WebTest

`WebTest.test(app, client -> ...)` starts the app on a free port, hands you a client that keeps cookies across calls, and stops the app afterwards, including when the body throws:

```java
@Test
void createsADeck() {
    WebTest.test(app, client -> {
        var created = client.postForm("/decks", Map.of("name", "English"));
        assertThat(created.statusCode()).isEqualTo(302);   // redirects are not followed
        assertThat(client.get("/api/decks").body()).contains("English");
    });
}
```

- Client methods: `get`, `post`, `put`, `patch`, `delete`, `head`, `options`, plus `postForm(path, map)` and `postJson(path, json)`.
- All return the JDK's raw `HttpResponse<String>`, so assertions stay in whatever library the project already uses (AssertJ, JUnit, anything).
- Redirects are not followed — assert the 302/303 and the `Location`, then request the target if needed.
- Cookies persist across calls within one `test` block, so login-then-act flows work naturally.
- `send(builder -> ...)` escapes to a hand-built request; `send(builder -> ..., bodyHandler)` reads the body another way — as bytes when the answer is compressed and decoding as text would destroy it.
- Under the hood it is just `app.start(0)` plus a cookie-keeping JDK `HttpClient`, so no port ever needs configuring and tests never collide.
- WebSocket endpoints are outside the harness (the upgrade leaves servlet dispatch); test those against a real Jetty on a free port with the JDK's `java.net.http.WebSocket` client.

## The handler alone: TestRequest

When the handler is under test rather than the routing that reaches it, build the `WebRequest` and call the method — no port, no container, no mock library:

```java
@Test
void createDeckRespondsWith201() {
    WebResponse response = controller.createDeck(TestRequest.post("/api/decks")
            .jsonBody(Json.obj().put("name", "Spanish"))
            .build());

    assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
    assertThat(deckService.getDeck(idFrom(response)).name()).isEqualTo("Spanish");
}
```

Builder methods: `queryParam`, `formParam`, `pathParam`, `header`, `cookie`, `body`/`jsonBody`, `file`, `secure()`, `remoteAddress(addr)`, `sessionAttr`; `build()` hands back a `WebRequest`.
The host a handler reads with `req.host()` is stated as a header: `header("Host", "shop.example.com:8080")`.

- `jsonBody` takes a `Json.JsonValue` tree, a value with the `JsonWriter` the application already declares, or raw text.
  Raw text is the form for a body no writer would produce, such as the malformed one a 400 test sends.
- Path variables are supplied, not matched, since no route is involved: `pathParam("deckId", "3")` states what the router would have resolved.
- Everything a handler can tell apart still holds: query and form parameters of the same name stay separate, header lookup ignores case, and `req.file(...)` on a request with no upload answers 400 exactly as production would.
- `file` twice with one name is the field that carries several files (`req.files(name)`), an empty file name is the input a browser sent with nothing chosen (`req.fileOrNull(name)` answers null), and `UploadedFile.writeTo(path)` writes the bytes out here as it does behind a container.

## Asserting on the response value

`WebResponse` is a value, so assertions read it back directly — `status()`, `header(name)`, `headers()`, `cookies()`, and `body()`, a sealed type you can cast or switch on:

```java
assertThat(response.header("Location")).isEqualTo("/api/decks/1");
assertThat(((WebResponse.Text) response.body()).content()).isEqualTo("{\"id\":1}");
```

## Picking between the two

- `WebTest` proves the wiring: routing, filters, error handlers, content negotiation, cookies, sessions, gzip — the whole request path.
- `TestRequest` proves one handler's logic fast, with the service layer real or stubbed by hand (no DI container means constructors take test doubles directly).
- A typical suite uses `TestRequest` for handler branches and a few `WebTest` flows for the paths users actually walk.
