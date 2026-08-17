package flashcard;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.MultipartConfigElement;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import spidersilk.App;
import spidersilk.AppServlet;
import spidersilk.JteTemplates;
import spidersilk.Route;
import spidersilk.StaticFiles;
import spidersilk.WebRequest;
import spidersilk.WebResponse;
import spidersilk.json.Json;
import spidersilk.json.JsonCodec;
import spidersilk.json.JsonReader;
import spidersilk.json.JsonWriter;
import spidersilk.server.JettyServer;
import spidersilk.server.WebServer;
import spidersilk.test.TestRequest;

import flashcard.web.ApiController;
import flashcard.web.DeckController;
import flashcard.web.OpenApi;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The README's Java blocks, kept where the compiler can see them.
 *
 * <p>Not a test — there are no assertions to run and nothing here is executed.
 * It exists so that {@code compileTestJava} fails the build when a snippet in
 * the README names a method the framework no longer has. Only symbols the
 * README leaves undefined on purpose (a {@code model}, a {@code logger}, a
 * service) are supplied as stubs below; every Spider Silk call is verbatim.
 */
@SuppressWarnings("unused")
class ReadmeSnippets {

    // ---- symbols the README leaves to the reader ----

    private Map<String, Object> model = Map.of();
    private ApiController api;
    private ApiController controller;
    private FlashcardContext context;
    private Logger logger;
    private DeckLike deckService;
    private DueService service;
    private String revision = "41";

    private WebResponse requireApiKey(WebRequest req) {
        return null;
    }

    private String requestId() {
        return "id";
    }

    interface Logger {
        void info(String message, Object... args);
    }

    interface DeckLike {
        List<Deck> decks();

        Deck create(String name);
    }

    interface DueService {
        long due(long deckId);
    }

    record Deck(long id, String name) {
    }

    record NewDeck(String name) {
    }

    static final class MyUndertowServer implements WebServer {

        MyUndertowServer(App app, int port) {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void join() {
        }

        @Override
        public int port() {
            return 0;
        }
    }

    // ---- block 2: At a Glance ----

    void atAGlance() {
        App app = new App()
                .templates(new JteTemplates("jte"))     // classpath:/jte/*.jte
                .staticFiles("/public");                // serves classpath:/public/* statically

        // Server-side rendering
        app.get("/decks/{deckId}", req -> {
            long deckId = req.pathParamLong("deckId");  // non-numeric input becomes a 400
            return WebResponse.render("deck.jte", model);
        });

        // JSON API — you state in code what goes out (no automatic serialization)
        app.get("/api/decks", req -> WebResponse.json(
                Json.arr().add(Json.obj().put("id", 1L).put("name", "English"))));

        app.post("/api/decks", req -> {
            String name = req.bodyJson().asObject().getString("name");
            return WebResponse.json(Json.obj().put("name", name)).status(201);
        });

        // Routes sharing a prefix — the group is an argument, not ambient state
        app.path("/api/decks", group -> {
            group.before(req -> requireApiKey(req));    // covers /api/decks and everything under it
            group.get("", api::listDecks);              // GET  /api/decks
            group.get("/{deckId}", api::listCards);     // GET  /api/decks/{deckId}
        });

        // Exception-to-response mapping
        app.exception(IllegalArgumentException.class,
                (e, req) -> WebResponse.text(e.getMessage()).status(404));

        // One place for a styled error page, whatever produced the status
        app.error(404, req -> WebResponse.render("not-found.jte", Map.of("path", req.path())));
    }

    // ---- blocks 3, 5, 7: the three shapes a handler comes in ----

    void threeShapes(App app) {
        app.get("/openapi.json", req -> WebResponse.json(OpenApi.document(app.routes())));

        app.get("/stats", context.statsAction());

        DeckController decks = context.deckController();
        app.get("/decks/{deckId}", decks::showDeck);
        app.post("/decks/{deckId}/rename", decks::renameDeck);
    }

    // ---- blocks 8, 9: filters ----

    void filters(App app) {
        app.before("/admin/*", req -> req.sessionAttr("user") == null
                ? WebResponse.redirect("/login")    // answers here, so the route handler never runs
                : null);                            // carry on

        app.after((req, res) -> res.header("X-Request-Id", requestId()));
    }

    // ---- blocks 10, 11: JSON writers and readers ----

    static final JsonWriter<Deck> DECK = deck -> Json.obj()
            .put("id", deck.id())
            .put("name", deck.name());

    static final JsonWriter<List<Deck>> DECKS = JsonWriter.list(DECK);

    static final JsonReader<NewDeck> NEW_DECK =
            json -> new NewDeck(json.asObject().getString("name"));

    void jsonSeam(App app) {
        app.get("/api/decks", req -> WebResponse.json(deckService.decks(), DECKS));

        app.post("/api/decks", req -> {
            Deck deck = deckService.create(req.bodyJson(NEW_DECK).name());   // no key -> 400
            return WebResponse.json(deck, DECK).status(201);
        });

        JsonCodec<Deck> codec = JsonCodec.of(DECK, json -> new Deck(0, ""));
        JsonCodec<List<Deck>> listCodec = JsonCodec.list(codec);
    }

    // ---- blocks 12, 13: cookies, query vs form ----

    WebResponse cookiesAndParams(WebRequest req, String page, String value) {
        String theme = req.cookie("theme");                 // null when absent
        List<String> tags = req.params("tag");              // ?tag=java&tag=web, or a checkbox group

        String queryPage = req.queryParam("page");     // query string only, null when absent
        String name = req.formParam("name");           // form body only
        List<String> formTags = req.formParams("tag");

        return WebResponse.html(page)
                .cookie("theme", "dark")                    // session cookie
                .cookie("token", value, Duration.ofDays(7)) // survives a browser restart
                .removeCookie("stale");
    }

    // ---- block 14: Server-Sent Events ----

    void sse(App app) {
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
    }

    // ---- blocks 15, 16: request logging, static files ----

    void loggingAndAssets(App app) {
        app.requestLogger((req, res, millis) -> logger.info("{} {} -> {} ({}ms)",
                req.method(), req.path(), res.status(), millis));

        app.staticFiles(new StaticFiles("/public")
                .hostedPath("/assets")              // classpath:/public/* at /assets/*
                .maxAge(Duration.ofDays(365)));     // only when the name carries a content hash
    }

    // ---- blocks 17, 18: route introspection ----

    void introspection(App app) {
        app.get("/_routes",
                req -> WebResponse.render("routes.jte", Map.of("routes", app.routes())));

        Json.JsonObject paths = Json.obj();
        for (Route route : app.routes()) {
            paths.put(route.path(), Json.obj().put(route.method().toLowerCase(Locale.ROOT),
                    Json.obj()));
        }
    }

    // ---- blocks 20, 21, 22, 23: the server ----

    void server(App app, String tmp) {
        new JettyServer(app)
                .port(8443)
                .host("127.0.0.1")
                .contextPath("/app")
                .sessions(false)
                .threadPool(new QueuedThreadPool(200, 8))
                .multipart(new MultipartConfigElement(tmp, 10_485_760L, 10_485_760L, 1_048_576))
                .stopTimeout(Duration.ofSeconds(20))    // longer drain for slow requests
                .shutdownHook(false)                    // something else owns the lifecycle
                .customizeHttpConfiguration(http -> http.setSendServerVersion(false))
                .customizeContext(context -> context.addFilter(MyFilter.class, "/*", null))
                .customizeServer(server -> server.setDumpBeforeStop(true));

        QueuedThreadPool pool = new QueuedThreadPool();
        pool.setVirtualThreadsExecutor(VirtualThreads.getDefaultVirtualThreadsExecutor());

        app.server((a, port) -> new JettyServer(a).port(port).threadPool(pool));

        app.server((a, port) -> new JettyServer(a).port(port).sessions(false));
        app.server((a, port) -> new MyUndertowServer(a, port));   // implements WebServer

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.addServlet(new ServletHolder(new AppServlet(app)), "/*");
    }

    abstract static class MyFilter implements jakarta.servlet.Filter {
    }

    // ---- block 24: asserting on a returned response ----

    void assertOnTheAnswer() throws Exception {
        WebResponse response = controller.createDeck(TestRequest.post("/api/decks")
                .jsonBody("{\"name\": \"Spanish\"}")
                .build());

        assertEquals(201, response.status());
        assertEquals("/api/decks/1", response.header("Location"));
        assertEquals("{\"id\":1}", ((WebResponse.Text) response.body()).content());
    }
}
