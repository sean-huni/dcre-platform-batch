package za.co.fnb.dcre.platform.batch.config;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import za.co.fnb.dcre.platform.batch.config.properties.BatchProperties;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-84 Red -> Green: proves {@link BatchJdbcConfig} yields a PERSISTENT JDBC
 * {@link JobRepository} (not the Boot 4.1 / Batch 6 default
 * {@code ResourcelessJobRepository}) writing the {@code IT_BATCH_} prefixed
 * metadata tables against a real CockroachDB.
 *
 * <p>Red baseline (import removed): Boot autoconfig supplies
 * {@code ResourcelessJobRepository}; assertion (a) fails, execution ids stay a
 * constant {@code 1}, and {@code IT_BATCH_JOB_INSTANCE} stays empty.
 */
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class BatchJdbcConfigIT {

    /** Partitions in the CTV shape: {@code dcre.ctv.max-partitions} defaults to 5. */
    private static final int PARTITIONS = 5;

    static {
        ItBatchMetadataDb.ensureStarted();
    }

    @DynamicPropertySource
    static void props(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ItBatchMetadataDb::url);
        registry.add("spring.datasource.username", ItBatchMetadataDb::username);
        registry.add("spring.datasource.password", ItBatchMetadataDb::password);
        registry.add("dcre.batch.table-prefix", () -> ItBatchMetadataDb.TABLE_PREFIX);
    }

    @Autowired
    JobRepository jobRepository;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    Job itJob;

    @Autowired
    Job restartItJob;

    @Autowired
    Job partitionedItJob;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void resolvesJdbcRepositoryWithMonotonicPersistentExecutions() throws Exception {
        // (a) NOT the in-memory default
        assertThat(jobRepository).isNotInstanceOf(ResourcelessJobRepository.class);
        assertThat(jobRepository.getClass().getName()).doesNotContain("Resourceless");

        // (b) two runs -> monotonic execution ids (Resourceless returns a constant 1)
        final JobExecution first = jobOperator.start(itJob, runParams("1"));
        final JobExecution second = jobOperator.start(itJob, runParams("2"));
        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getId()).isGreaterThan(first.getId());

        // (c) rows landed in the prefixed metadata table
        final Integer instances = jdbc.queryForObject(
                "SELECT count(*) FROM IT_BATCH_JOB_INSTANCE", Integer.class);
        assertThat(instances).isGreaterThanOrEqualTo(2);
    }

    private JobParameters runParams(final String run) {
        return new JobParametersBuilder().addString("run.id", run, true).toJobParameters();
    }

    /**
     * SCRUM-101 Red -> Green. Restarting an existing job instance makes Batch 6.0.4's
     * {@code JdbcStepExecutionDao.getLastStepExecution} run a nested
     * {@code getJobParameters} query while its own {@code ResultSet} is still open.
     * CockroachDB rejects the second portal ("unimplemented: multiple active portals
     * is in preview"), Hikari evicts the broken connection, and the restart dies.
     *
     * <p>Red baseline (with the stock DAO): the restart launch fails with a
     * {@code TransactionSystemException: JDBC rollback failed} whose logged cause is the
     * portal error raised from {@code JdbcStepExecutionDao.getLastStepExecution:341} via
     * {@code JdbcJobExecutionDao.getJobParameters:450}.
     */
    @Test
    void restartsAFailedInstanceWithoutTrippingMultipleActivePortals() throws Exception {
        final JobParameters params =
                new JobParametersBuilder().addString("restart.id", "portals", true).toJobParameters();

        final JobExecution failed = jobOperator.start(restartItJob, params);
        assertThat(failed.getStatus()).as("first attempt fails on purpose").isEqualTo(BatchStatus.FAILED);

        final JobExecution restarted = jobOperator.start(restartItJob, params);
        assertThat(restarted.getAllFailureExceptions()).isEmpty();
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    /**
     * SCRUM-101 Red -> Green in the shape that actually died, CTV's 5-way partitioned
     * step. {@code SimpleStepExecutionSplitter} (line 137) calls
     * {@code getLastStepExecution} ONCE PER PARTITION, so a partitioned restart runs the
     * defective nested read five times over, on five different step names.
     *
     * <p>Red baseline (with the stock DAO): the restart never reaches the splitter, it
     * already dies on the master step's own lookup, and no partition is re-created.
     */
    @Test
    void restartsAPartitionedInstanceInTheCtvShape() throws Exception {
        final JobParameters params =
                new JobParametersBuilder().addString("restart.id", "partitions", true).toJobParameters();

        final JobExecution failed = jobOperator.start(partitionedItJob, params);
        assertThat(failed.getStatus()).as("every partition fails on purpose").isEqualTo(BatchStatus.FAILED);
        assertThat(stepExecutions("partitionWorkerStep:partition")).isEqualTo(PARTITIONS);

        final JobExecution restarted = jobOperator.start(partitionedItJob, params);
        assertThat(restarted.getAllFailureExceptions()).isEmpty();
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepExecutions("partitionWorkerStep:partition")).isEqualTo(2 * PARTITIONS);
    }

    private int stepExecutions(final String stepNamePrefix) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM IT_BATCH_STEP_EXECUTION WHERE STEP_NAME LIKE ?",
                Integer.class, stepNamePrefix + "%");
    }

    @Test
    void failsClosedWhenPrefixAbsent() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropsOnly.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("dcre.batch.table-prefix");
                });
    }

    @Test
    void failsClosedWhenPrefixIsBareBatchDefault() {
        new ApplicationContextRunner()
                .withPropertyValues("dcre.batch.table-prefix=BATCH_")
                .withUserConfiguration(PropsOnly.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("dcre.batch.table-prefix");
                });
    }

    @Configuration
    @EnableConfigurationProperties(BatchProperties.class)
    static class PropsOnly {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(BatchJdbcConfig.class)
    static class ItApp {

        @Bean
        Job itJob(final JobRepository jobRepository, final PlatformTransactionManager transactionManager) {
            return new JobBuilder("itJob", jobRepository)
                    .start(new StepBuilder("itStep", jobRepository)
                            .tasklet((contribution, chunkContext) -> RepeatStatus.FINISHED, transactionManager)
                            .build())
                    .build();
        }

        // Fails its first execution so the second launch of the same instance takes the
        // RESTART path, where getLastStepExecution reads a prior step-execution row.
        @Bean
        Job restartItJob(final JobRepository jobRepository, final PlatformTransactionManager transactionManager) {
            final AtomicBoolean firstAttempt = new AtomicBoolean(true);
            return new JobBuilder("restartItJob", jobRepository)
                    .start(new StepBuilder("restartItStep", jobRepository)
                            .tasklet((contribution, chunkContext) -> {
                                if (firstAttempt.getAndSet(false)) {
                                    throw new IllegalStateException("planned first-attempt failure");
                                }
                                return RepeatStatus.FINISHED;
                            }, transactionManager)
                            .build())
                    .build();
        }

        // CTV's shape (CtvJobConfig.validationStep): a master step fanning a worker step
        // across 5 partitions named partition0..partition4 on virtual threads. Every
        // partition fails its first execution, so the second launch restarts all five.
        @Bean
        Job partitionedItJob(final JobRepository jobRepository, final PlatformTransactionManager transactionManager) {
            final AtomicInteger attempts = new AtomicInteger();
            final Step worker = new StepBuilder("partitionWorkerStep", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        if (attempts.incrementAndGet() <= PARTITIONS) {
                            throw new IllegalStateException("planned first-attempt partition failure");
                        }
                        return RepeatStatus.FINISHED;
                    }, transactionManager)
                    .build();
            return new JobBuilder("partitionedItJob", jobRepository)
                    .start(new StepBuilder("partitionMasterStep", jobRepository)
                            .partitioner("partitionWorkerStep", fixedGrid())
                            .step(worker)
                            .gridSize(PARTITIONS)
                            .taskExecutor(new VirtualThreadTaskExecutor("it-part-"))
                            .build())
                    .build();
        }

        private static Partitioner fixedGrid() {
            return gridSize -> {
                final Map<String, ExecutionContext> partitions = new LinkedHashMap<>();
                for (int i = 0; i < gridSize; i++) {
                    final ExecutionContext context = new ExecutionContext();
                    context.putInt("partitionIndex", i);
                    partitions.put("partition" + i, context);
                }
                return partitions;
            };
        }
    }
}
