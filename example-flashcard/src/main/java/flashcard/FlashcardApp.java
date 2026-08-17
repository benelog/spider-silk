package flashcard;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;

import javax.sql.DataSource;

import jakarta.servlet.MultipartConfigElement;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.h2.jdbcx.JdbcConnectionPool;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import steelspider.App;
import steelspider.AppServlet;
import steelspider.JteTemplates;

import flashcard.service.CsvFormatException;

/**
 * Application startup: builds the object graph via FlashcardContext,
 * configures the App, and runs the Jetty server.
 */
public class FlashcardApp {

    public static void main(String[] args) throws Exception {
        DataSource dataSource = JdbcConnectionPool.create(
                "jdbc:h2:~/db/steel-spider/flashcard;AUTO_SERVER=TRUE", "sa", "");
        initSchema(dataSource);

        Server server = createServer(createApp(dataSource), 8080);
        server.start();
        System.out.println("Flashcard: http://localhost:8080");
        server.join();
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

    static Server createServer(App app, int port) {
        Server server = new Server(port);
        // Study sessions and flash messages use the HTTP session,
        // so the SESSIONS option is required
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        ServletHolder holder = new ServletHolder(new AppServlet(app));
        // Multipart config for CSV uploads (10MB max)
        holder.getRegistration().setMultipartConfig(new MultipartConfigElement(
                System.getProperty("java.io.tmpdir"), 10 * 1024 * 1024, 10 * 1024 * 1024,
                1024 * 1024));
        context.addServlet(holder, "/*");

        server.setHandler(context);
        return server;
    }
}
