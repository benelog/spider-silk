package flashcard;

import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.resolve.DirectoryCodeResolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Arrays;
import javax.sql.DataSource;
import net.benelog.spidersilk.App;
import net.benelog.spidersilk.JteTemplates;
import net.benelog.spidersilk.TemplateRenderer;
import org.h2.jdbcx.JdbcConnectionPool;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Application startup: FlashcardContext builds the whole App, and this class
 * only starts it.
 */
public class FlashcardApp {

    public static void main(String[] args) throws Exception {
        App app = new FlashcardContext(fileDatabase(), templates(args))
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
    private static TemplateRenderer templates(String[] args) {
        if (Arrays.asList(args).contains("--dev")) {
            return new JteTemplates(
                new DirectoryCodeResolver(Path.of("src/main/resources/jte")));
        }
        return new JteTemplates(TemplateEngine.createPrecompiled(ContentType.Html));
    }


    /** The database main runs on: an H2 file, with the schema applied. */
    static DataSource fileDatabase() throws Exception {
        DataSource dataSource = JdbcConnectionPool.create(
            "jdbc:h2:~/db/spider-silk/flashcard;AUTO_SERVER=TRUE", "sa", "");
        initSchema(dataSource);
        return dataSource;
    }

    public static void initSchema(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                new ClassPathResource("schema.sql"), StandardCharsets.UTF_8));
        }
    }
}
