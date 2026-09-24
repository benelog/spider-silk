package flashcard;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.MultipartConfigElement;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import net.benelog.spidersilk.App;
import net.benelog.spidersilk.AppServlet;
import net.benelog.spidersilk.HttpStatus;
import net.benelog.spidersilk.Route;
import net.benelog.spidersilk.StaticFiles;
import net.benelog.spidersilk.WebRequest;
import net.benelog.spidersilk.WebResponse;
import net.benelog.spidersilk.json.Json;
import net.benelog.spidersilk.json.JsonCodec;
import net.benelog.spidersilk.json.JsonObject;
import net.benelog.spidersilk.json.JsonReader;
import net.benelog.spidersilk.json.JsonWriter;
import net.benelog.spidersilk.openapi.OpenApi;
import net.benelog.spidersilk.server.JettyServer;
import net.benelog.spidersilk.server.WebServer;
import net.benelog.spidersilk.test.TestRequest;

import flashcard.web.ApiController;
import flashcard.web.DeckController;
import flashcard.web.StatsAction;

import static org.assertj.core.api.Assertions.assertThat;

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
    private StatsAction statsAction;
    private DeckController deckController;
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

    record User(String name) {
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
        App app = new App();    // jte over classpath:/jte, and classpath:/public served at /

        // Server-side rendering
        app.get("/decks/{deckId}", req -> {
            long deckId = req.pathParamLong("deckId");  // non-numeric input becomes a 400
            return WebResponse.template("deck", model);
        });

        // JSON API — you state in code what goes out (no automatic serialization)
        app.get("/api/decks", req -> WebResponse.json(
                Json.array().add(Json.object().put("id", 1L).put("name", "English"))));

        app.post("/api/decks", req -> {
            String name = req.bodyJson().asObject().getString("name");
            return WebResponse.json(Json.object().put("name", name)).status(HttpStatus.CREATED);
        });

        // Routes sharing a prefix — the group is an argument, not ambient state
        app.path("/api/decks", group -> {
            group.beforeRoute(req -> requireApiKey(req));    // guards matched routes under /api/decks
            group.get("", api::listDecks);                   // GET  /api/decks
            group.get("/{deckId}", api::listCards);          // GET  /api/decks/{deckId}
        });

        // Exception-to-response mapping
        app.exception(IllegalArgumentException.class,
                (req, e) -> WebResponse.text(e.getMessage()).status(HttpStatus.NOT_FOUND));

        // One place for a styled error page, whatever produced the status
        app.statusPage(HttpStatus.NOT_FOUND, req -> WebResponse.template("not-found", Map.of("path", req.path())));
    }

    // ---- blocks 3, 5, 7: the three shapes a handler comes in ----

    void threeShapes(App app) {
        app.get("/openapi.json", req -> WebResponse.json(
                OpenApi.document("Flashcard API", "1.0.0", app.routes())));

        app.get("/stats", statsAction);

        app.get("/decks/{deckId}", deckController::showDeck);
        app.post("/decks/{deckId}/rename", deckController::renameDeck);
    }

    // ---- blocks 8, 9: filters ----

    void filters(App app) {
        app.beforeRoute("/admin/*", req -> req.session().get("user") == null
                ? WebResponse.redirect("/login")    // answers here, so the route handler never runs
                : null);                            // carry on

        app.afterRoute("/api/*", (req, res) -> res.header("Cache-Control", "no-store"));

        app.responseFilter((req, res) -> res.header("X-Request-Id", requestId()));
    }

    // ---- blocks 10, 11: JSON writers and readers ----

    static final JsonWriter<Deck> DECK = deck -> Json.object()
            .put("id", deck.id())
            .put("name", deck.name());

    static final JsonWriter<List<Deck>> DECKS = JsonWriter.list(DECK);

    static final JsonReader<NewDeck> NEW_DECK =
            json -> new NewDeck(json.asObject().getString("name"));

    void jsonSeam(App app) {
        app.get("/api/decks", req -> WebResponse.json(deckService.decks(), DECKS));

        app.post("/api/decks", req -> {
            Deck deck = deckService.create(req.bodyJson(NEW_DECK).name());   // no key -> 400
            return WebResponse.json(deck, DECK).status(HttpStatus.CREATED);
        });

        JsonCodec<Deck> codec = JsonCodec.of(DECK, json -> new Deck(0, ""));
        JsonCodec<List<Deck>> listCodec = JsonCodec.list(codec);
    }

    // ---- blocks 12, 13: cookies, query vs form ----

    WebResponse cookiesAndParams(WebRequest req, String page, String value) {
        String theme = req.cookie("theme");                 // null when absent
        List<String> tags = req.params("tag");              // ?tag=java&tag=web, or a checkbox group

        String queryPage = req.queryParamOrNull("page");    // query string only, null when absent
        String name = req.formParam("name");                // form body only, 400 when absent
        List<String> formTags = req.formParams("tag");

        String search = req.paramOrNull("q");                         // null when absent
        LocalDate due = req.formParam("due", LocalDate::parse);       // 400 when the form carries none
        int pageNumber = req.queryParam("page", Integer::parseInt, 1); // default covers absence only

        return WebResponse.html(page)
                .cookie("theme", "dark")                    // session cookie
                .cookie("token", value, Duration.ofDays(7)) // survives a browser restart
                .removeCookie("stale");
    }

    // ---- sessions ----

    void sessions(WebRequest req, User user) {
        req.session().set("user", user);                 // creates the session if there is none yet
        User read = req.session().get("user", User.class);  // null when absent
        User same = req.session().get("user");              // the same read, cast by the caller
        req.session().remove("user");                    // creates no session to remove from
        req.session().invalidate();
    }

    // ---- block 14: Server-Sent Events ----

    void sse(App app) {
        app.get("/decks/{deckId}/events", req -> {
            long deckId = req.pathParamLong("deckId");
            return WebResponse.sse(stream -> {
                while (stream.isOpen()) {
                    stream.id(String.valueOf(revision))
                          .send("due", Json.object().put("count", service.due(deckId)).toJson());
                    Thread.sleep(1000);
                }
            });
        });
    }

    // ---- blocks 15, 16: request logging, static files ----

    void loggingAndAssets(App app) {
        app.requestLogger((req, completion) -> logger.info("{} {} -> {} ({}ms)",
                req.method(), req.path(), completion.statusCode(), completion.took().toMillis()));

        app.staticFiles(new StaticFiles("/public")
                .hostedPath("/assets")              // classpath:/public/* at /assets/*
                .maxAge(Duration.ofDays(365)));     // only when the name carries a content hash

        app.staticFiles(
                new StaticFiles("/public"),                        // classpath:/public/* at /*
                StaticFiles.directory(Path.of("/srv/uploads"))     // /srv/uploads/* at /uploads/*
                        .hostedPath("/uploads"));
    }

    // ---- blocks 17, 18: route introspection ----

    void introspection(App app) {
        app.get("/_routes",
                req -> WebResponse.template("routes", Map.of("routes", app.routes())));

        JsonObject paths = Json.object();
        for (Route route : app.routes()) {
            paths.put(route.path(), Json.object().put(route.method().toLowerCase(Locale.ROOT),
                    Json.object()));
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
        ServletHolder holder = new ServletHolder(new AppServlet(app));
        holder.setInitOrder(0);                           // initialize while the context starts
        context.addServlet(holder, "/*");
    }

    abstract static class MyFilter implements jakarta.servlet.Filter {
    }

    // ---- block 24: asserting on a returned response ----

    void assertOnTheAnswer() throws Exception {
        WebResponse response = controller.createDeck(TestRequest.post("/api/decks")
                .jsonBody(Json.object().put("name", "Spanish"))
                .build());

        assertThat(response.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.header("Location")).isEqualTo("/api/decks/1");
        assertThat(((WebResponse.Text) response.body()).content()).isEqualTo("{\"id\":1}");
    }
}
