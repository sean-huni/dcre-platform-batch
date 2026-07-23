package za.co.fnb.dcre.platform.batch.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-90 Red -> Green for {@link ReadCommittedStartupAssertion}, the fleet fail-fast guard.
 *
 * <p>Against a real CockroachDB (the same image the persistent-JobRepository IT uses), a genuine
 * Hikari pool is built at a chosen isolation and the assertion bean is exercised through a real
 * context refresh:
 * <ul>
 *   <li>GREEN: pool at {@code TRANSACTION_READ_COMMITTED} -> {@code SHOW transaction_isolation}
 *       returns {@code read committed} -> context starts.</li>
 *   <li>RED: pool at {@code TRANSACTION_SERIALIZABLE} -> the bean throws in
 *       {@code afterSingletonsInstantiated} -> context refresh fails with an
 *       {@link IllegalStateException} that names the fix.</li>
 * </ul>
 * The container is shared and never stopped (established platform-batch Testcontainers pattern;
 * see {@code BatchJdbcConfigIT}).
 */
class ReadCommittedStartupAssertionIT {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static {
        CRDB.start();
        enableReadCommitted();
    }

    static void enableReadCommitted() {
        try (Connection c = DriverManager.getConnection(CRDB.getJdbcUrl(), CRDB.getUsername(), CRDB.getPassword());
             Statement s = c.createStatement()) {
            s.execute("SET CLUSTER SETTING sql.txn.read_committed_isolation.enabled = true");
        } catch (final Exception e) {
            // v26.2 ships READ COMMITTED enabled by default; the GREEN assertion is the source of truth.
        }
    }

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(AssertionConfig.class);

    @Test
    void greenContextStartsWhenPoolIsReadCommitted() {
        runner.withPropertyValues("test.tx-isolation=TRANSACTION_READ_COMMITTED")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void redStartupFailsWhenPoolIsSerializable() {
        runner.withPropertyValues("test.tx-isolation=TRANSACTION_SERIALIZABLE")
                .run(context -> {
                    assertThat(context).hasFailed();
                    // The SmartInitializingSingleton throw propagates as-is out of context refresh,
                    // so the startup failure IS the IllegalStateException (no wrapping cause).
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("READ COMMITTED")
                            .hasMessageContaining("spring.datasource.hikari.transaction-isolation");
                });
    }

    @Configuration
    static class AssertionConfig {

        @Bean(destroyMethod = "close")
        HikariDataSource dataSource(@Value("${test.tx-isolation}") final String isolation) {
            final HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(CRDB.getJdbcUrl());
            ds.setUsername(CRDB.getUsername());
            ds.setPassword(CRDB.getPassword());
            ds.setTransactionIsolation(isolation);
            ds.setMaximumPoolSize(2);
            return ds;
        }

        @Bean
        ReadCommittedStartupAssertion readCommittedStartupAssertion(final DataSource dataSource) {
            return new ReadCommittedStartupAssertion(dataSource);
        }
    }
}
