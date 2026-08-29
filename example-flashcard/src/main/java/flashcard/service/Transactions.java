package flashcard.service;

import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transaction boundaries calling TransactionTemplate directly, without AOP.
 * The code a service wraps in write()/read() is exactly the transaction scope.
 */
public class Transactions {

    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;

    /** For the common case where reads and writes go to the same DataSource. */
    public Transactions(DataSource dataSource) {
        this(dataSource, dataSource);
    }

    public Transactions(DataSource writeDataSource, DataSource readDataSource) {
        PlatformTransactionManager writeManager = new JdbcTransactionManager(writeDataSource);
        PlatformTransactionManager readManager = readDataSource == writeDataSource
                ? writeManager
                : new JdbcTransactionManager(readDataSource);
        this.writeTx = new TransactionTemplate(writeManager);
        this.readTx = new TransactionTemplate(readManager);
        this.readTx.setReadOnly(true);
    }

    /** A write transaction. Rolls back when a runtime exception escapes. */
    public <T> T write(Supplier<T> action) {
        return writeTx.execute(status -> action.get());
    }

    public void writeVoid(Runnable action) {
        writeTx.executeWithoutResult(status -> action.run());
    }

    /** A read-only transaction. Groups multiple reads into one snapshot. */
    public <T> T read(Supplier<T> action) {
        return readTx.execute(status -> action.get());
    }

    /** The same, for a read whose result goes somewhere else — a response stream. */
    public void readVoid(Runnable action) {
        readTx.executeWithoutResult(status -> action.run());
    }
}
