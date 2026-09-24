package flashcard;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.UUID;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcConnectionPool;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * The H2 databases this application runs on, each created with schema.sql
 * already applied: a file for main, and an isolated in-memory one for a test.
 */
public final class FlashcardDatabase {

    private FlashcardDatabase() {
    }

    /** The database main runs on: an H2 file under ~/db/spider-silk. */
    public static DataSource file() {
        return initialized(JdbcConnectionPool.create(
            "jdbc:h2:~/db/spider-silk/flashcard;AUTO_SERVER=TRUE", "sa", ""));
    }

    /** A fresh in-memory database, shared by nobody else, as a test wants it. */
    public static DataSource inMemory() {
        return initialized(JdbcConnectionPool.create(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
    }

    private static DataSource initialized(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                new ClassPathResource("schema.sql"), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Schema initialization failed", e);
        }
        return dataSource;
    }
}
