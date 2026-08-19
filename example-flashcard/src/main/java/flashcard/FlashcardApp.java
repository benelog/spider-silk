package flashcard;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Map;

import javax.sql.DataSource;

import jakarta.servlet.MultipartConfigElement;

import org.h2.jdbcx.JdbcConnectionPool;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import spidersilk.App;
import spidersilk.HttpStatus;
import spidersilk.JteTemplates;
import spidersilk.WebResponse;
import spidersilk.server.JettyServer;

import flashcard.service.CsvFormatException;
import flashcard.web.ApiController;
import flashcard.web.DeckController;
import flashcard.web.OpenApi;
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
                // Everything else runs on the defaults; only the CSV upload limit is tuned.
                .server((a, port) -> new JettyServer(a).port(port).multipart(uploadLimits()))
                .start(8080);
        System.out.println("Flashcard: http://localhost:" + app.port());
        app.join();
    }

    public static void initSchema(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ClassPathResource("schema.sql"), StandardCharsets.UTF_8));
        }
    }

    static App createApp(DataSource dataSource) {
        FlashcardContext context = new FlashcardContext(dataSource);

        App app = new App()
                .templates(new JteTemplates("jte"))
                .staticFiles("/public");

        // CSV format error: this handler runs after the transaction rolled back.
        app.exception(CsvFormatException.class, (e, req) -> {
            req.flash("error", e.getMessage());
            return WebResponse.redirect("/");
        });
        app.exception(IllegalArgumentException.class,
                (e, req) -> WebResponse.text(e.getMessage()).status(HttpStatus.NOT_FOUND));

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

        ApiController api = context.apiController();
        app.path("/api/decks", group -> {
            group.get("", api::listDecks);
            group.post("", api::createDeck);
            group.get("/{deckId}/cards", api::listCards);
        });

        // 3. A lambda, for a handler with no state worth a class of its own. Both of
        //    these read app.routes() per request, so they list the routes above.
        app.get("/_routes",
                req -> WebResponse.template("routes.jte", Map.of("routes", app.routes())));
        app.get("/openapi.json", req -> WebResponse.json(OpenApi.document(app.routes())));
    }

    /** CSV uploads are capped at 10MB, buffered in memory up to 1MB. */
    static MultipartConfigElement uploadLimits() {
        return new MultipartConfigElement(System.getProperty("java.io.tmpdir"),
                MAX_UPLOAD_BYTES, MAX_UPLOAD_BYTES, 1024 * 1024);
    }
}
