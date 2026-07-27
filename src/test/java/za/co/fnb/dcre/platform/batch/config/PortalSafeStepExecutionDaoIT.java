package za.co.fnb.dcre.platform.batch.config;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
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
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.within;

/**
 * SCRUM-101 Red -> Green for {@link PortalSafeStepExecutionDao}, on ONE shared pool with
 * ONE transaction manager and no CockroachDB preview flag anywhere.
 *
 * <p>Three guards. The CANARY asserts the library defect is still present in the pinned
 * spring-batch-core. The DELEGATE guard asserts the library method the repair leans on is
 * still portal-free. The PINNING test asserts the override's result is field for field the
 * step execution that was persisted, so a future Batch release quietly changing
 * {@code getStepExecution(long)} fails loudly instead of silently losing restart context.
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

    /**
     * CANARY. Asserts the defect {@link PortalSafeStepExecutionDao} exists to work around is
     * STILL in the pinned spring-batch-core: the stock {@code getLastStepExecution} prepares
     * the job-parameters read while its own row-limited portal is still suspended, and
     * CockroachDB refuses with SQLSTATE {@code 0A000}.
     *
     * <p><b>Red here means DELETE the override.</b> When this goes red because no
     * {@code 0A000} is raised any more, upstream has fixed the nesting: delete
     * {@link PortalSafeStepExecutionDao}, delete {@link PortalSafeJobRepositoryFactoryBean},
     * point {@code BatchJdbcConfig} back at the stock {@link JdbcJobRepositoryFactoryBean},
     * and delete this test. Red for any other reason means the harness stopped reaching the
     * defect and the assertion below, not the override, needs revisiting.
     */
    @Test
    @DisplayName("CANARY: the stock DAO still trips CockroachDB's multiple-active-portals refusal")
    void stockDaoStillTripsMultipleActivePortalsOnThisBatchVersion() throws Exception {
        final JobRepository repo = repository(new JdbcJobRepositoryFactoryBean());
        final Seeded seeded = seed(repo, "canary");

        final Throwable thrown = catchThrowable(() -> repo.getLastStepExecution(seeded.instance(), "portalSafeStep"));

        assertThat(thrown)
                .as("""
                        the stock nested read succeeded, so upstream has fixed it: DELETE \
                        PortalSafeStepExecutionDao and PortalSafeJobRepositoryFactoryBean, point \
                        BatchJdbcConfig back at the stock JdbcJobRepositoryFactoryBean, and delete \
                        this test.""")
                .isNotNull();
        final Optional<SQLException> portal = PortalSignal.find(thrown);
        assertThat(portal)
                .as("no SQLSTATE %s anywhere in:%n%s", PortalSignal.SQL_STATE, PortalSignal.messages(thrown))
                .isPresent();
        assertThat(portal.orElseThrow().getMessage())
                .contains("multiple active portals")
                .contains("cannot perform operation sql.PrepareStmt while a different portal is open");
        assertThat(PortalSignal.messages(thrown))
                .as("the refused statement must be the nested job-parameters read")
                .contains("JOB_EXECUTION_PARAMS");
    }

    /**
     * DELEGATE GUARD. The repair's correctness rests on the library's own
     * {@code JdbcStepExecutionDao.getStepExecution(long)} staying strictly sequential; its
     * body could be rewritten upstream to nest a query inside an open ResultSet with no
     * compile error here. This exercises exactly that delegate against real CockroachDB,
     * inside the repository's own transaction, so the read and any nested read share one
     * connection. A second portal is then refused by the database and this test fails. The
     * canary above proves, on this same pool and transaction shape, that the refusal really
     * does fire when a nested read is present.
     */
    @Test
    @DisplayName("DELEGATE: getStepExecution(long) opens no second portal on CockroachDB")
    void delegateGetStepExecutionOpensNoSecondPortal() throws Exception {
        final JobRepository repo = repository(new PortalSafeJobRepositoryFactoryBean());
        final Seeded seeded = seed(repo, "delegate");

        final StepExecution read = repo.getStepExecution(seeded.step().getId());

        assertThat(read).isNotNull();
        assertThat(read.getId()).isEqualTo(seeded.step().getId());
        assertThat(read.getStepName()).isEqualTo("portalSafeStep");
        assertThat(read.getExecutionContext().getString("resume.key")).isEqualTo("resume-value");
    }

    @Test
    @DisplayName("PINNING: the portal-safe read returns the persisted step execution field for field")
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
}
