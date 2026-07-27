package za.co.fnb.dcre.platform.batch.config;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JdbcJobRepositoryFactoryBean;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * SCRUM-101 Red -> Green for {@link PortalSafeStepExecutionDao}, on ONE shared pool with
 * ONE transaction manager and no CockroachDB preview flag anywhere.
 *
 * <p>Two guards. The CANARY asserts the library defect is still present in the pinned
 * spring-batch-core: it turns red on the version that fixes the nested read, which is the
 * signal to DELETE the override. The PINNING test asserts the override's result is field
 * for field the step execution that was persisted, so a future Batch release quietly
 * changing {@code getStepExecution(long)} fails loudly instead of silently losing restart
 * context.
 *
 * <p>Red baseline for the pinning test (factory swapped back to the stock
 * {@link JdbcJobRepositoryFactoryBean}): the read dies on "unimplemented: multiple active
 * portals is in preview" before a single field can be compared.
 */
class PortalSafeStepExecutionDaoIT {

    private static final LocalDateTime STARTED_AT = LocalDateTime.of(2026, 7, 27, 1, 2, 3, 123_456_000);
    private static final LocalDateTime ENDED_AT = LocalDateTime.of(2026, 7, 27, 1, 4, 5, 654_321_000);

    private static final HikariDataSource POOL = pool();

    @AfterAll
    static void closePool() {
        POOL.close();
    }

    @Test
    void stockDaoStillTripsMultipleActivePortalsOnThisBatchVersion() throws Exception {
        final JobRepository repo = repository(new JdbcJobRepositoryFactoryBean());
        final Seeded seeded = seed(repo, "canary");

        assertThatThrownBy(() -> repo.getLastStepExecution(seeded.instance(), "portalSafeStep"))
                .isInstanceOf(RuntimeException.class)
                .satisfies(thrown -> assertThat(stackOf(thrown)).contains("rollback failed"));
    }

    @Test
    void portalSafeReadReturnsThePersistedStepExecutionFieldForField() throws Exception {
        final JobRepository repo = repository(new PortalSafeJobRepositoryFactoryBean());
        final Seeded seeded = seed(repo, "pinned");
        final StepExecution expected = seeded.step();

        final StepExecution last = repo.getLastStepExecution(seeded.instance(), "portalSafeStep");

        assertThat(last).isNotNull();
        assertThat(last.getId()).isEqualTo(expected.getId());
        assertThat(last.getStepName()).isEqualTo("portalSafeStep");
        assertThat(last.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(last.getExitStatus()).isEqualTo(expected.getExitStatus());
        assertThat(last.getVersion()).isEqualTo(expected.getVersion());

        assertThat(last.getReadCount()).isEqualTo(expected.getReadCount());
        assertThat(last.getWriteCount()).isEqualTo(expected.getWriteCount());
        assertThat(last.getCommitCount()).isEqualTo(expected.getCommitCount());
        assertThat(last.getRollbackCount()).isEqualTo(expected.getRollbackCount());
        assertThat(last.getFilterCount()).isEqualTo(expected.getFilterCount());
        assertThat(last.getReadSkipCount()).isEqualTo(expected.getReadSkipCount());
        assertThat(last.getWriteSkipCount()).isEqualTo(expected.getWriteSkipCount());
        assertThat(last.getProcessSkipCount()).isEqualTo(expected.getProcessSkipCount());

        assertThat(last.getStartTime()).isEqualTo(STARTED_AT);
        assertThat(last.getEndTime()).isEqualTo(ENDED_AT);
        // clock-derived, and a CockroachDB TIMESTAMP holds microseconds
        assertThat(last.getCreateTime()).isCloseTo(expected.getCreateTime(), within(1, ChronoUnit.MICROS));
        assertThat(last.getLastUpdated()).isCloseTo(expected.getLastUpdated(), within(1, ChronoUnit.MICROS));

        // the restart-critical payload SimpleStepHandler (line 117) copies forward
        assertThat(last.getExecutionContext().isEmpty()).isFalse();
        assertThat(last.getExecutionContext().getString("resume.key")).isEqualTo("resume-value");

        assertThat(last.getJobExecutionId()).isEqualTo(expected.getJobExecutionId());
        final JobParameters reread = last.getJobExecution().getJobParameters();
        assertThat(reread).isEqualTo(seeded.params());
        assertThat(reread.getParameter("probe.window").value()).isEqualTo(42L);
        assertThat(reread.getParameter("probe.window").identifying()).isTrue();
        assertThat(reread.getParameter("probe.note").value()).isEqualTo("non-identifying");
        assertThat(reread.getParameter("probe.note").identifying()).isFalse();

        final JobInstance instance = last.getJobExecution().getJobInstance();
        assertThat(instance).isEqualTo(seeded.instance());
        assertThat(instance.getJobName()).isEqualTo(seeded.instance().getJobName());
    }

    private record Seeded(JobInstance instance, StepExecution step, JobParameters params) {
    }

    private Seeded seed(final JobRepository repo, final String tag) {
        final JobParameters params = new JobParametersBuilder()
                .addString("probe.id", tag + "-" + UUID.randomUUID(), true)
                .addLong("probe.window", 42L, true)
                .addString("probe.note", "non-identifying", false)
                .toJobParameters();
        final JobInstance instance = repo.createJobInstance("portalSafeJob-" + tag, params);
        final JobExecution jobExecution = repo.createJobExecution(instance, params, new ExecutionContext());
        final StepExecution step = repo.createStepExecution("portalSafeStep", jobExecution);
        step.setStartTime(STARTED_AT);
        step.setEndTime(ENDED_AT);
        step.setStatus(BatchStatus.FAILED);
        step.setExitStatus(new ExitStatus("FAILED", "seeded exit description"));
        step.setReadCount(7);
        step.setWriteCount(5);
        step.setCommitCount(3);
        step.setRollbackCount(1);
        step.setFilterCount(2);
        step.setReadSkipCount(11);
        step.setWriteSkipCount(13);
        step.setProcessSkipCount(17);
        step.getExecutionContext().putString("resume.key", "resume-value");
        repo.updateExecutionContext(step);
        repo.update(step);
        return new Seeded(instance, step, params);
    }

    private static JobRepository repository(final JdbcJobRepositoryFactoryBean factory) throws Exception {
        factory.setDataSource(POOL);
        factory.setTransactionManager(new DataSourceTransactionManager(POOL));
        factory.setTablePrefix(ItBatchMetadataDb.TABLE_PREFIX);
        factory.setIsolationLevelForCreate("ISOLATION_READ_COMMITTED");
        factory.afterPropertiesSet();
        return factory.getObject();
    }

    private static HikariDataSource pool() {
        ItBatchMetadataDb.ensureStarted();
        final HikariDataSource ds = DataSourceBuilder.create().type(HikariDataSource.class)
                .url(ItBatchMetadataDb.url())
                .username(ItBatchMetadataDb.username())
                .password(ItBatchMetadataDb.password())
                .build();
        ds.setPoolName("portal-safe-it");
        ds.setMaximumPoolSize(3);
        return ds;
    }

    private static String stackOf(final Throwable thrown) {
        final StringWriter writer = new StringWriter();
        thrown.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
