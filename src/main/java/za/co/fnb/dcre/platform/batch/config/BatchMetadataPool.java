package za.co.fnb.dcre.platform.batch.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * SCRUM-101: the dedicated connection pool the Spring Batch {@link
 * org.springframework.batch.core.repository.JobRepository} uses when the target is
 * CockroachDB, carrying {@code multiple_active_portals_enabled} (see
 * {@link BatchMetadataUrl}) so a job RESTART survives Batch's nested
 * {@code getLastStepExecution} / {@code getJobParameters} query.
 *
 * <p><b>Metadata only.</b> The session variable is scoped to this pool's connections;
 * every business datasource keeps CockroachDB's default single-portal behaviour, which
 * was the agreed blast radius. When the target is NOT CockroachDB the pool is not
 * created at all and the caller keeps using the primary DataSource and transaction
 * manager, so plain PostgreSQL (and any Testcontainers Postgres) never sees the
 * unknown option.
 *
 * <p><b>Detection is a {@code SELECT version()} probe</b> on the primary DataSource,
 * whose banner starts with {@code CockroachDB} on CockroachDB and {@code PostgreSQL}
 * on PostgreSQL. The JDBC URL and driver cannot tell them apart (CockroachDB speaks
 * the pgwire protocol through {@code org.postgresql.Driver}) and
 * {@code DatabaseMetaData} reports a spoofed PostgreSQL identity, so the banner is the
 * only reliable signal that needs no new configuration. Any failure of the probe or of
 * the pool build degrades to the previous behaviour (primary DataSource, no option)
 * with a WARN, because a broken JobRepository at startup would break all 22 services.
 *
 * <p><b>Deliberately not a {@code DataSource} bean</b>, same reason as
 * {@link HeartbeatDatasourceConfig}: exposing a second {@code DataSource} bean would
 * trip Boot's {@code DataSourceAutoConfiguration}
 * ({@code @ConditionalOnMissingBean(DataSource.class)}) and REPLACE the primary
 * application datasource. It is registered as this wrapper type instead, which Spring
 * closes on context shutdown (inferred {@code close}). The resolved URL is logged once
 * at startup (INFO) so operators can see the option really landed.
 *
 * <p><b>Pool size 5.</b> This pool serves Batch metadata only. The concurrent claimants
 * are the job thread plus one per partition worker, and partition grids are capped at
 * {@code availableProcessors()} ({@link za.co.fnb.dcre.platform.batch.PartitionSizer}) on
 * 2-CPU stage pods; repository calls are short, self-contained transactions that never
 * nest, so 5 covers the busiest observed grid without deadlock risk while keeping the
 * per-pod connection cost to CockroachDB negligible across the fleet.
 */
final class BatchMetadataPool implements AutoCloseable {

    static final int POOL_SIZE = 5;

    private static final Logger log = LoggerFactory.getLogger(BatchMetadataPool.class);

    private final HikariDataSource pool;

    BatchMetadataPool(final DataSourceProperties properties, final DataSource primary) {
        this.pool = build(properties, primary);
    }

    DataSource dataSourceOr(final DataSource primary) {
        return pool == null ? primary : pool;
    }

    PlatformTransactionManager transactionManagerOr(final PlatformTransactionManager primary) {
        return pool == null ? primary : new DataSourceTransactionManager(pool);
    }

    @Override
    public void close() {
        if (pool != null) {
            pool.close();
        }
    }

    private static HikariDataSource build(final DataSourceProperties properties, final DataSource primary) {
        try {
            final String url = properties.determineUrl();
            final String metadataUrl = BatchMetadataUrl.forBanner(url, versionBanner(primary));
            if (metadataUrl == null || metadataUrl.equals(url)) {
                log.info("batch metadata: reusing the primary DataSource (no CockroachDB portal option needed)");
                return null;
            }
            final HikariDataSource dedicated = DataSourceBuilder.create()
                    .type(HikariDataSource.class)
                    .url(metadataUrl)
                    .username(properties.determineUsername())
                    .password(properties.determinePassword())
                    .build();
            dedicated.setPoolName("batch-metadata");
            dedicated.setMaximumPoolSize(POOL_SIZE);
            dedicated.setMinimumIdle(1);
            log.info("batch metadata: dedicated CockroachDB pool size={} url={}", POOL_SIZE, metadataUrl);
            return dedicated;
        } catch (final RuntimeException e) {
            log.warn("batch metadata: falling back to the primary DataSource, "
                    + "CockroachDB multiple-active-portals probe failed: {}", e.toString());
            return null;
        }
    }

    private static String versionBanner(final DataSource primary) {
        return new JdbcTemplate(primary).queryForObject("SELECT version()", String.class);
    }
}
