package flashcard;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;

import javax.sql.DataSource;

import jakarta.servlet.MultipartConfigElement;

import org.h2.jdbcx.JdbcConnectionPool;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import spidersilk.App;
import spidersilk.JteTemplates;
import spidersilk.server.JettyServer;

import flashcard.service.CsvFormatException;

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
        app.exception(CsvFormatException.class, (e, ctx) -> {
            ctx.flash("error", e.getMessage());
            ctx.redirect("/");
        });
        app.exception(IllegalArgumentException.class,
                (e, ctx) -> ctx.status(404).text(e.getMessage()));

        context.controllers().forEach(controller -> controller.register(app));
        return app;
    }

    /** CSV uploads are capped at 10MB, buffered in memory up to 1MB. */
    static MultipartConfigElement uploadLimits() {
        return new MultipartConfigElement(System.getProperty("java.io.tmpdir"),
                MAX_UPLOAD_BYTES, MAX_UPLOAD_BYTES, 1024 * 1024);
    }
}
