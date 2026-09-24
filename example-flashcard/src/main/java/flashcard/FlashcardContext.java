package flashcard;

import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import jakarta.servlet.MultipartConfigElement;

import net.benelog.spidersilk.App;
import net.benelog.spidersilk.Cors;
import net.benelog.spidersilk.HttpStatus;
import net.benelog.spidersilk.Route;
import net.benelog.spidersilk.SecurityHeaders;
import net.benelog.spidersilk.TemplateRenderer;
import net.benelog.spidersilk.WebResponse;
import net.benelog.spidersilk.json.JsonException;
import net.benelog.spidersilk.openapi.OpenApi;
import net.benelog.spidersilk.server.JettyServer;

import flashcard.repository.CardRepository;
import flashcard.repository.DeckRepository;
import flashcard.repository.ReviewLogRepository;
import flashcard.repository.ReviewStateRepository;
import flashcard.repository.SmartDeckRepository;
import flashcard.repository.TagRepository;
import flashcard.service.CardService;
import flashcard.service.CsvFormatException;
import flashcard.service.DeckService;
import flashcard.service.SmartDeckService;
import flashcard.service.StatsService;
import flashcard.service.StudyService;
import flashcard.service.Transactions;
import flashcard.web.ApiController;
import flashcard.web.DeckController;
import flashcard.web.HomeAction;
import flashcard.web.SmartDeckController;
import flashcard.web.StatsAction;
import flashcard.web.StudyController;

/**
 * A hand-written counterpart of Spring's ApplicationContext: the constructor
 * wires the whole object graph by calling constructors directly, without a
 * DI container. The dependency graph is visible right here in the code, and
 * so is the App those handlers are served by: its configuration, its route
 * table, and the server it runs on. FlashcardApp picks the database and the
 * templates and hands them in.
 */
public class FlashcardContext {

    private static final long MAX_UPLOAD_BYTES = 10 * 1024 * 1024;

    private final HomeAction homeAction;
    private final DeckController deckController;
    private final StudyController studyController;
    private final SmartDeckController smartDeckController;
    private final StatsAction statsAction;
    private final ApiController apiController;
    // Null leaves the App on its default, jte over classpath:/jte.
    private final TemplateRenderer templates;

    /** A context on the default templates, as the tests run it. */
    public FlashcardContext(DataSource dataSource) {
        this(dataSource, null);
    }

    public FlashcardContext(DataSource dataSource, TemplateRenderer templates) {
        this.templates = templates;
        Transactions tx = new Transactions(dataSource);

        CardRepository cardRepository = new CardRepository(dataSource);
        DeckRepository deckRepository = new DeckRepository(dataSource);
        TagRepository tagRepository = new TagRepository(dataSource);
        ReviewStateRepository reviewStateRepository = new ReviewStateRepository(dataSource);
        ReviewLogRepository reviewLogRepository = new ReviewLogRepository(dataSource);
        SmartDeckRepository smartDeckRepository = new SmartDeckRepository(dataSource);

        CardService cardService = new CardService(cardRepository, tagRepository, tx);
        DeckService deckService = new DeckService(deckRepository, cardRepository,
                cardService, tx);
        SmartDeckService smartDeckService = new SmartDeckService(smartDeckRepository,
                cardRepository, tx);
        StatsService statsService = new StatsService(reviewLogRepository, deckRepository, tx);
        StudyService studyService = new StudyService(cardRepository, reviewStateRepository,
                reviewLogRepository, deckService, smartDeckService, tx);

        this.homeAction = new HomeAction(deckService, studyService, smartDeckService);
        this.deckController = new DeckController(deckService, cardService);
        this.studyController = new StudyController(studyService, smartDeckService);
        this.smartDeckController = new SmartDeckController(smartDeckService);
        this.statsAction = new StatsAction(statsService);
        this.apiController = new ApiController(deckService, cardService);
    }

    /** CSV uploads are capped at 10MB, buffered in memory up to 1MB. */
    private static MultipartConfigElement uploadLimits() {
        return new MultipartConfigElement(System.getProperty("java.io.tmpdir"),
                MAX_UPLOAD_BYTES, MAX_UPLOAD_BYTES, 1024 * 1024);
    }

    /**
     * The App with its response-wide concerns, exception handlers, and routes.
     * Tests take it as it is; {@link #start(int)} adds the server that main runs.
     */
    App createApp() {
        // Static files are left at their default, classpath:/public served at the root.
        App app = new App();
        if (templates != null) {
            app.templates(templates);
        }

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
        app.exception(JsonException.class,
                (req, e) -> WebResponse.text(e.getMessage()).status(HttpStatus.BAD_REQUEST));

        registerRoutes(app);
        return app;
    }

    /** Starts {@link #createApp()} on Jetty, the server main runs. */
    public App start(int port) {
        return createApp()
                // Everything else runs on the defaults; only the CSV upload limit is tuned.
                .server((app, pt) -> new JettyServer(app).port(pt).multipart(uploadLimits()))
                .start(port);
    }

    /**
     * Every route this application answers, in one readable list.
     *
     * <p>There is no {@code Controller} interface and no scanning: routing is a
     * list of statements, so {@link App#routes()} reports exactly what is written
     * here. A handler reaches the table in one of three shapes.
     */
    private void registerRoutes(App app) {
        // 1. A class with a single handler implements Handler and registers as itself.
        app.get("/", homeAction);
        app.get("/stats", statsAction);

        // 2. A class with several handlers keeps them as public methods, registered
        //    by method reference. The prefix is repeated here rather than hidden in
        //    the class, so the path and the method that answers it sit on one line.
        app.post("/decks", deckController::createDeck);
        app.get("/decks/{deckId}", deckController::showDeck);
        app.post("/decks/{deckId}/rename", deckController::renameDeck);
        app.post("/decks/{deckId}/delete", deckController::deleteDeck);
        app.post("/decks/{deckId}/cards", deckController::addCard);
        app.get("/decks/{deckId}/cards/{cardId}/edit", deckController::editCardForm);
        app.post("/decks/{deckId}/cards/{cardId}/edit", deckController::editCard);
        app.post("/decks/{deckId}/cards/{cardId}/delete", deckController::deleteCard);
        app.get("/decks/{deckId}/export.csv", deckController::exportCsv);
        app.post("/decks/{deckId}/import", deckController::importCsv);

        app.post("/study/deck/{deckId}", studyController::startDeckStudy);
        app.post("/study/today", studyController::startTodayStudy);
        app.post("/study/smart/{smartDeckId}", studyController::startSmartStudy);
        app.post("/study/preset/{condition}", studyController::startPresetStudy);
        app.get("/study", studyController::showStudy);
        app.post("/study/answer", studyController::answer);
        app.post("/study/retry", studyController::retry);
        app.post("/study/finish", studyController::finish);

        app.post("/smart-decks", smartDeckController::create);
        app.post("/smart-decks/{id}/delete", smartDeckController::delete);

        // The /api routes carry a description, because they are the ones read by
        // something that is not this application: the description becomes the
        // summary in /openapi.json. The pages above go without, since a path and
        // a page are read together and the extra argument would say nothing new.
        app.path("/api/decks", group -> {
            group.get("", "List every deck with its card count", apiController::listDecks);
            group.post("", "Create a deck", apiController::createDeck);
            group.get("/{deckId}/cards", "List the cards of one deck", apiController::listCards);
            group.get("/{deckId}/cards.ndjson", "Export the cards of one deck as NDJSON",
                    apiController::exportCards);
            group.post("/{deckId}/cards.ndjson", "Import cards into one deck from NDJSON",
                    apiController::importCards);
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
}
