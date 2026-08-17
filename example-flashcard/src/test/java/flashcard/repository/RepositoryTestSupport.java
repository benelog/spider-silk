package flashcard.repository;

import java.util.UUID;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcConnectionPool;

import flashcard.FlashcardApp;
import flashcard.service.Transactions;

/** Loads the schema into an isolated in-memory H2 database per test class. */
public abstract class RepositoryTestSupport {

    protected final DataSource dataSource = JdbcConnectionPool.create(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    protected final Transactions tx = new Transactions(dataSource);

    protected RepositoryTestSupport() {
        try {
            FlashcardApp.initSchema(dataSource);
        } catch (Exception e) {
            throw new IllegalStateException("Schema initialization failed", e);
        }
    }
}
