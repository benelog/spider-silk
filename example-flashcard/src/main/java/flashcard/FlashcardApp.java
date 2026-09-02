package flashcard;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import jakarta.servlet.MultipartConfigElement;

import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.resolve.DirectoryCodeResolver;

import org.h2.jdbcx.JdbcConnectionPool;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import net.benelog.spidersilk.App;
import net.benelog.spidersilk.Route;
import net.benelog.spidersilk.Cors;
import net.benelog.spidersilk.HttpStatus;
import net.benelog.spidersilk.JteTemplates;
import net.benelog.spidersilk.SecurityHeaders;
import net.benelog.spidersilk.TemplateRenderer;
import net.benelog.spidersilk.WebResponse;
import net.benelog.spidersilk.json.Json;
import net.benelog.spidersilk.openapi.OpenApi;
import net.benelog.spidersilk.server.JettyServer;

import flashcard.service.CsvFormatException;
import flashcard.web.ApiController;
import flashcard.web.DeckController;
import flashcard.web.SmartDeckController;
import flashcard.web.StudyController;

/**
 * Application startup: builds the object graph via FlashcardContext,
 * configures the App, and runs the embedded server.
 */
public class FlashcardApp {

    private static final long MAX_UPLOAD_BYTES = 10 * 1024 * 1024;

    public static void main(String[] args) throws Exception {
        DataSource dataSource = JdbcConnectionPool.create(
                "jdbc:h2:~/db/spider-silk/flashcard;AUTO_SERVER=TRUE", "sa", "");
        initSchema(dataSource);

        App app = createApp(dataSource)
                .templates(templates(args))
                // Everything else runs on the defaults; only the CSV upload limit is tuned.
                .server((a, port) -> new JettyServer(a).port(port).multipart(uploadLimits()))
                .start(8080);
        System.out.println("Flashcard: http://localhost:" + app.port());
        app.join();
    }

    /**
     * jte's two modes, chosen at startup.
     *
     * <p>Production (no flag) renders the classes the build's {@code generateJte}
     * task compiled from the templates, so the jar and the native image carry no
     * template sources and need no JDK. {@code --dev} reads the .jte files
     * straight from the source tree instead: a template whose file changed is
     * recompiled on its next render, so an edit shows up on browser refresh.
     * Run it as {@code ./gradlew :example-flashcard:run --args=--dev}, whose
     * working directory makes the relative path below resolve.
     */
    static TemplateRenderer templates(String[] args) {
        if (Arrays.asList(args).contains("--dev")) {
            return new JteTemplates(
                    new DirectoryCodeResolver(Path.of("src/main/resources/jte")));
        }
        return new JteTemplates(TemplateEngine.createPrecompiled(ContentType.Html));
    }

    public static void initSchema(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ClassPathResource("schema.sql"), StandardCharsets.UTF_8));
        }
    }

    static App createApp(DataSource dataSource) {
        FlashcardContext context = new FlashcardContext(dataSource);

        // Templates and static files are left at their defaults:
        // jte over classpath:/jte, and classpath:/public served at the root.
        App app = new App();

        // The three response-wide concerns, each a value App is handed. Nothing
        // registers itself and nothing is on until it is named here.
        app.securityHeaders(SecurityHeaders.defaults()
                // This application loads nothing but its own stylesheet, so
                // default-src 'self' holds. style-src is the exception: stats.jte
                // sizes its chart bars with a style attribute, and a height
                // computed per row cannot come out of a file. HSTS stays off,
                // because main serves this over http://localhost.
                .contentSecurityPolicy("default-src 'self'; style-src 'self' 'unsafe-inline'"));

        // Pages, the stylesheet, the JSON API, and the CSV export are all text.
        app.gzip();

        // The OpenAPI document is the one thing here meant to be read by
        // something that is not this application — a Swagger UI or a client
        // generator, served from somewhere else. The rest of /api creates decks,
        // and opening that to any origin would be a worse example than none.
        app.cors(Cors.anyOrigin().forPath("/openapi.json"));

        // CSV format error: this handler runs after the transaction rolled back.
        app.exception(CsvFormatException.class, (req, e) -> {
            req.flash("error", e.getMessage());
            return WebResponse.redirect("/");
        });
        app.exception(IllegalArgumentException.class,
                (req, e) -> WebResponse.text(e.getMessage()).status(HttpStatus.NOT_FOUND));
        // A body that failed to parse is a 400, not one of the 404s above. The
        // more specific type wins whatever the order, so this line may sit here.
        app.exception(Json.JsonException.class,
                (req, e) -> WebResponse.text(e.getMessage()).status(HttpStatus.BAD_REQUEST));

        registerRoutes(app, context);
        return app;
    }

    /**
     * Every route this application answers, in one readable list.
     *
     * <p>There is no {@code Controller} interface and no scanning: routing is a
     * list of statements, so {@link App#routes()} reports exactly what is written
     * here. A handler reaches the table in one of three shapes.
     */
    static void registerRoutes(App app, FlashcardContext context) {
        // 1. A class with a single handler implements Handler and registers as itself.
        app.get("/", context.homeAction());
        app.get("/stats", context.statsAction());

        // 2. A class with several handlers keeps them as public methods, registered
        //    by method reference. The prefix is repeated here rather than hidden in
        //    the class, so the path and the method that answers it sit on one line.
        DeckController decks = context.deckController();
        app.post("/decks", decks::createDeck);
        app.get("/decks/{deckId}", decks::showDeck);
        app.post("/decks/{deckId}/rename", decks::renameDeck);
        app.post("/decks/{deckId}/delete", decks::deleteDeck);
        app.post("/decks/{deckId}/cards", decks::addCard);
        app.get("/decks/{deckId}/cards/{cardId}/edit", decks::editCardForm);
        app.post("/decks/{deckId}/cards/{cardId}/edit", decks::editCard);
        app.post("/decks/{deckId}/cards/{cardId}/delete", decks::deleteCard);
        app.get("/decks/{deckId}/export.csv", decks::exportCsv);
        app.post("/decks/{deckId}/import", decks::importCsv);

        StudyController study = context.studyController();
        app.post("/study/deck/{deckId}", study::startDeckStudy);
        app.post("/study/today", study::startTodayStudy);
        app.post("/study/smart/{smartDeckId}", study::startSmartStudy);
        app.post("/study/preset/{condition}", study::startPresetStudy);
        app.get("/study", study::showStudy);
        app.post("/study/answer", study::answer);
        app.post("/study/retry", study::retry);
        app.post("/study/finish", study::finish);

        SmartDeckController smartDecks = context.smartDeckController();
        app.post("/smart-decks", smartDecks::create);
        app.post("/smart-decks/{id}/delete", smartDecks::delete);

        // The /api routes carry a description, because they are the ones read by
        // something that is not this application: the description becomes the
        // summary in /openapi.json. The pages above go without, since a path and
        // a page are read together and the extra argument would say nothing new.
        ApiController api = context.apiController();
        app.path("/api/decks", group -> {
            group.get("", "List every deck with its card count", api::listDecks);
            group.post("", "Create a deck", api::createDeck);
            group.get("/{deckId}/cards", "List the cards of one deck", api::listCards);
            group.get("/{deckId}/cards.ndjson", "Export the cards of one deck as NDJSON",
                    api::exportCards);
            group.post("/{deckId}/cards.ndjson", "Import cards into one deck from NDJSON",
                    api::importCards);
        });

        // 3. A lambda, for a handler with no state worth a class of its own. Both of
        //    these read app.routes() per request, so they list the routes above.
        app.get("/_routes",
                req -> WebResponse.template("routes", Map.of("routes", app.routes())));
        app.get("/openapi.json", req -> WebResponse.json(
                OpenApi.document("Flashcard API", "1.0.0", documentedRoutes(app))));
    }

    /**
     * Which routes the OpenAPI document covers: the /api ones, since the rest of
     * this app serves HTML, and no wildcard, which has no path template. Both are
     * this application's calls to make, which is why spider-silk-openapi takes a
     * list rather than the App.
     */
    private static List<Route> documentedRoutes(App app) {
        return app.routes().stream()
                .filter(route -> route.path().startsWith("/api") && !route.path().contains("*"))
                .toList();
    }

    /** CSV uploads are capped at 10MB, buffered in memory up to 1MB. */
    static MultipartConfigElement uploadLimits() {
        return new MultipartConfigElement(System.getProperty("java.io.tmpdir"),
                MAX_UPLOAD_BYTES, MAX_UPLOAD_BYTES, 1024 * 1024);
    }
}
