package za.co.fnb.dcre.platform.batch.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * SCRUM-90 (M12, fugu 5.7): fail-fast guarantee that the primary batch {@link DataSource} actually
 * runs at CockroachDB READ COMMITTED. Declared as a {@code @Bean} in {@link BatchJdbcConfig}, so
 * every service that {@code @Import(BatchJdbcConfig.class)} (all 13 stage services) gets it.
 *
 * <p>The check runs during context refresh ({@link SmartInitializingSingleton}), before any Batch
 * job launches. It opens a REAL transaction ({@code autoCommit=false}) and reads
 * {@code SHOW transaction_isolation}: a multi-statement autocommit call could report a session
 * default and mislead, whereas the value inside an explicit transaction is the isolation a chunk
 * commit will actually use. If it is not {@code read committed} the bean throws and startup fails,
 * so no service can silently ship at SERIALIZABLE (the M12 40001-at-commit hazard). The message
 * names the fix so the operator does not have to trace the mechanism.
 */
public class ReadCommittedStartupAssertion implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ReadCommittedStartupAssertion.class);
    private static final String EXPECTED = "read committed";
    private static final String SHOW_ISOLATION = "SHOW transaction_isolation";

    private final DataSource dataSource;

    public ReadCommittedStartupAssertion(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterSingletonsInstantiated() {
        final String actual = currentTransactionIsolation();
        if (!EXPECTED.equalsIgnoreCase(actual)) {
            throw new IllegalStateException((
                    "DCRE batch datasource must run at READ COMMITTED but the primary pool reported "
                    + "transaction_isolation='%s'. Fix: set "
                    + "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED "
                    + "(shipped fleet-wide by platform-batch dcre-batch-datasource.yml).")
                    .formatted(actual));
        }
        log.info("batch datasource isolation verified: transaction_isolation={}", actual);
    }

    private String currentTransactionIsolation() {
        try (final Connection connection = dataSource.getConnection()) {
            final boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (final Statement statement = connection.createStatement();
                 final ResultSet resultSet = statement.executeQuery(SHOW_ISOLATION)) {
                final String value = resultSet.next() ? resultSet.getString(1) : null;
                connection.rollback();
                return value;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (final SQLException e) {
            throw new IllegalStateException("failed to verify batch datasource transaction isolation", e);
        }
    }
}
