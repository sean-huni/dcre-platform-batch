package za.co.fnb.dcre.platform.batch.config;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
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
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;

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

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static {
        CRDB.start();
        applyBatchSchema();
    }

    static void applyBatchSchema() {
        try (InputStream in = BatchJdbcConfigIT.class.getResourceAsStream("/batch-metadata-it.sql");
             Connection c = DriverManager.getConnection(CRDB.getJdbcUrl(), CRDB.getUsername(), CRDB.getPassword())) {
            final String script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (final String raw : script.split(";")) {
                final String stmt = Arrays.stream(raw.split("\n"))
                        .filter(line -> !line.trim().startsWith("--"))
                        .reduce("", (a, b) -> a + "\n" + b).trim();
                if (!stmt.isEmpty()) {
                    try (Statement s = c.createStatement()) {
                        s.execute(stmt);
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to apply IT_BATCH_ schema", e);
        }
    }

    @DynamicPropertySource
    static void props(final DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CRDB::getJdbcUrl);
        registry.add("spring.datasource.username", CRDB::getUsername);
        registry.add("spring.datasource.password", CRDB::getPassword);
        registry.add("dcre.batch.table-prefix", () -> "IT_BATCH_");
    }

    @Autowired
    JobRepository jobRepository;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    Job itJob;

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
    }
}
