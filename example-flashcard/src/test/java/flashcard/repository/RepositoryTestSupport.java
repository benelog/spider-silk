package flashcard.repository;

import javax.sql.DataSource;

import flashcard.FlashcardDatabase;
import flashcard.service.Transactions;

/** Loads the schema into an isolated in-memory H2 database per test class. */
public abstract class RepositoryTestSupport {

    protected final DataSource dataSource = FlashcardDatabase.inMemory();
    protected final Transactions tx = new Transactions(dataSource);
}
