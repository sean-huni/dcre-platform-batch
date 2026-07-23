package za.co.fnb.dcre.platform.batch.config;

import javax.sql.DataSource;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JdbcJobRepositoryFactoryBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import za.co.fnb.dcre.platform.batch.config.properties.BatchProperties;

/**
 * SCRUM-84 (R-47, M12 piece 1): a shared, persistent JDBC {@link JobRepository}
 * for every DCRE stage + M-family service, replacing the Boot 4.1 / Batch 6
 * default {@code ResourcelessJobRepository}.
 *
 * <p><b>The flaw.</b> Spring Batch 6 flipped {@code DefaultBatchConfiguration}
 * to return {@code ResourcelessJobRepository} (in-memory) and Boot 4.1's
 * {@code BatchAutoConfiguration} provides only that; {@code spring.batch.jdbc.*}
 * is no longer bound. Every service therefore ran non-persistent batch metadata,
 * so the provisioned {@code <SVC>_BATCH_*} tables stayed empty.
 *
 * <p><b>The fix.</b> {@code @EnableBatchProcessing} makes Boot's
 * {@code BatchAutoConfiguration} back off wholesale (it is
 * {@code @ConditionalOnMissingBean(annotation = EnableBatchProcessing.class)}),
 * and the explicit {@code jobRepository} bean below makes Batch's own
 * {@code BatchRegistrar} skip its default Resourceless registration (it checks
 * {@code containsBeanDefinition("jobRepository")}). The registrar still supplies
 * the {@code JobOperator}, wired to this repository. No schema change: the
 * {@code <SVC>_BATCH_*} tables are byte-equal to Batch 6.0.4's canonical
 * Postgres schema (prefix + EXIT_MESSAGE widened to TEXT for CockroachDB).
 *
 * <p><b>Property-resolution path (verified against Batch 6.0.4 bytecode).</b>
 * The annotation route {@code @EnableJdbcJobRepository(tablePrefix = "${...}")}
 * is NOT used: {@code BatchRegistrar.registerJdbcJobRepository} consumes the
 * attribute as a raw annotation constant via
 * {@code builder.addPropertyValue("tablePrefix", annotation.tablePrefix())}, so
 * a {@code ${dcre.batch.table-prefix}} placeholder would resolve only if the
 * {@code PropertySourcesPlaceholderConfigurer} happens to visit a
 * registrar-registered bean-definition property, a version-fragile ordering
 * dependency. Instead this config builds the repository explicitly via
 * {@link JdbcJobRepositoryFactoryBean} and injects the prefix from the typed
 * {@link BatchProperties} ({@code dcre.batch.table-prefix}), which resolves
 * deterministically at bean construction.
 *
 * <p><b>Usage: {@code @Import} only, never component-scanned.</b>
 * {@code @Import(BatchJdbcConfig.class)} on each service, and it must NOT fall
 * under a service's {@code @ComponentScan} base package: a scan covering
 * {@code za.co.fnb.dcre.platform.batch} would auto-activate
 * {@code @EnableBatchProcessing} unintentionally. It is deliberately NOT
 * registered in {@code META-INF/spring/...AutoConfiguration.imports} either: as
 * an {@code @AutoConfiguration} it could double-apply {@code @EnableBatchProcessing}
 * alongside a service's own batch config. Each service supplies only its own
 * {@code dcre.batch.table-prefix}; the autoconfigured primary {@code DataSource}
 * and {@code transactionManager} are reused as-is.
 *
 * <p><b>CRDB isolation.</b> The framework create/restart transaction runs at
 * {@code ISOLATION_READ_COMMITTED}, not Batch's {@code SERIALIZABLE} default: a
 * {@code 40001} retry-serializable abort at job launch is outside the step
 * tasklet, so it is NOT caught by {@code CrdbRetryExceptionHandler}. The
 * {@code JOB_INST_UN} unique constraint still prevents duplicate instances, so
 * dropping to READ_COMMITTED removes the contention surface without weakening the
 * single-instance guarantee.
 *
 * <p><b>Fleet READ COMMITTED + fail-fast (SCRUM-90).</b> Beyond create/restart, EVERY pooled
 * connection now runs at READ COMMITTED, shipped fleet-wide from
 * {@code classpath:dcre-batch-datasource.yml} by {@link BatchDatasourceEnvironmentPostProcessor}:
 * the persistent JobRepository writes {@code <SVC>_BATCH_*} metadata inside the chunk-commit
 * transaction, so a {@code 40001} can surface AT COMMIT under SERIALIZABLE, outside the tasklet
 * retry handler. The {@link ReadCommittedStartupAssertion} bean below opens a real transaction at
 * startup and refuses to start any service whose primary pool is not actually READ COMMITTED, so
 * no service silently ships at SERIALIZABLE. Opt out only in tests that do not exercise isolation,
 * via {@code dcre.batch.assert-read-committed=false}.
 */
@Configuration(proxyBeanMethods = false)
@EnableBatchProcessing
@EnableConfigurationProperties(BatchProperties.class)
public class BatchJdbcConfig {

    @Bean
    JobRepository jobRepository(final DataSource dataSource,
                                final PlatformTransactionManager transactionManager,
                                final BatchProperties properties) throws Exception {
        final JdbcJobRepositoryFactoryBean factory = new JdbcJobRepositoryFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTransactionManager(transactionManager);
        factory.setTablePrefix(properties.tablePrefix());
        factory.setIsolationLevelForCreate("ISOLATION_READ_COMMITTED");
        factory.afterPropertiesSet();
        return factory.getObject();
    }

    @Bean
    @ConditionalOnProperty(prefix = "dcre.batch", name = "assert-read-committed",
            havingValue = "true", matchIfMissing = true)
    ReadCommittedStartupAssertion readCommittedStartupAssertion(final DataSource dataSource) {
        return new ReadCommittedStartupAssertion(dataSource);
    }
}
