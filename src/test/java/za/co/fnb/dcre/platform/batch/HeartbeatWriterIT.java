package za.co.fnb.dcre.platform.batch;

import java.sql.Timestamp;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * SCRUM-85 Red -> Green: proves {@link HeartbeatWriter} writes a liveness heartbeat
 * into {@code launch_intent} ONLY while a Batch job of this pod runs, and no-ops
 * cleanly otherwise, against a real CockroachDB.
 *
 * <p>The jobName/pod source is a constructor param (production reads env in
 * {@code HeartbeatDatasourceConfig}), so these tests set identity directly and never
 * depend on real environment variables. {@code tick()} is invoked directly for a
 * deterministic, timing-flake-free assertion of the update behaviour; a separate
 * reflection assertion locks the {@code @Scheduled} placeholder wiring.
 *
 * <p>Red baseline (stub {@code tick()} no-op): {@link #runningJobAdvancesHeartbeat}
 * and {@link #missingIntentWarnsOnceAndDoesNotThrow} fail.
 */
class HeartbeatWriterIT {

    static final CockroachContainer CRDB =
            new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));

    static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        CRDB.start();
        jdbc = new JdbcTemplate(DataSourceBuilder.create()
                .type(SimpleDriverDataSource.class)
                .driverClassName("org.postgresql.Driver")
                .url(CRDB.getJdbcUrl()).username(CRDB.getUsername()).password(CRDB.getPassword())
                .build());
        jdbc.execute("""
                CREATE TABLE launch_intent (
                    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
                    job_name VARCHAR(63) NOT NULL UNIQUE,
                    heartbeat_at TIMESTAMPTZ,
                    owner_pod VARCHAR(63)
                )""");
    }

    private String seedIntent() {
        final String jobName = "crr-" + UUID.randomUUID();
        jdbc.update("INSERT INTO launch_intent (job_name) VALUES (?)", jobName);
        return jobName;
    }

    private Timestamp heartbeatOf(final String jobName) {
        return jdbc.queryForObject(
                "SELECT heartbeat_at FROM launch_intent WHERE job_name = ?", Timestamp.class, jobName);
    }

    @Test
    void runningJobAdvancesHeartbeat() throws Exception {
        final String jobName = seedIntent();
        final HeartbeatWriter writer = new HeartbeatWriter(jdbc, jobName, "pod-alpha");

        writer.beforeJob(execution());
        writer.tick();
        final Timestamp first = heartbeatOf(jobName);
        assertThat(first).as("first heartbeat is written while running").isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT owner_pod FROM launch_intent WHERE job_name = ?", String.class, jobName))
                .isEqualTo("pod-alpha");

        Thread.sleep(10);
        writer.tick();
        assertThat(heartbeatOf(jobName)).as("heartbeat advances on the next interval").isAfter(first);
    }

    @Test
    void absentJobNameIsNoOp() {
        final String jobName = seedIntent();
        final HeartbeatWriter writer = new HeartbeatWriter(jdbc, null, "pod-alpha");

        writer.beforeJob(execution());
        assertThatCode(writer::tick).doesNotThrowAnyException();
        assertThat(heartbeatOf(jobName)).as("no intent is touched when JOB_NAME is unset").isNull();
    }

    @Test
    void notRunningIsNoOp() {
        final String jobName = seedIntent();
        final HeartbeatWriter writer = new HeartbeatWriter(jdbc, jobName, "pod-alpha");

        // no beforeJob -> not running
        writer.tick();
        assertThat(heartbeatOf(jobName)).as("no heartbeat when no job of this pod runs").isNull();

        // afterJob clears the running flag again
        writer.beforeJob(execution());
        writer.afterJob(execution());
        writer.tick();
        assertThat(heartbeatOf(jobName)).as("no heartbeat once the job finished").isNull();
    }

    @Test
    void missingIntentWarnsOnceAndDoesNotThrow() {
        final HeartbeatWriter writer = new HeartbeatWriter(jdbc, "crr-no-such-intent", "pod-alpha");
        final ListAppender<ILoggingEvent> appender = attachAppender();

        writer.beforeJob(execution());
        assertThatCode(writer::tick).doesNotThrowAnyException();
        assertThatCode(writer::tick).doesNotThrowAnyException();

        final long warnings = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("crr-no-such-intent"))
                .count();
        assertThat(warnings).as("0-rows warns exactly once, never throws").isEqualTo(1);
    }

    @Test
    void dbFailureWarnsOncePerErrorClassAndDoesNotThrow() {
        final JdbcTemplate broken = new JdbcTemplate(DataSourceBuilder.create()
                .type(SimpleDriverDataSource.class)
                .driverClassName("org.postgresql.Driver")
                .url("jdbc:postgresql://127.0.0.1:1/agt_ops?connectTimeout=1&sslmode=disable")
                .username("root").password("").build());
        final HeartbeatWriter writer = new HeartbeatWriter(broken, "crr-db-down", "pod-alpha");
        final ListAppender<ILoggingEvent> appender = attachAppender();

        writer.beforeJob(execution());
        assertThatCode(writer::tick).doesNotThrowAnyException();
        assertThatCode(writer::tick).doesNotThrowAnyException();

        final long warnings = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("dcre.agtops-db-url"))
                .count();
        assertThat(warnings)
                .as("a DB-connection failure warns once per error class, names the URL, never throws")
                .isEqualTo(1);
    }

    @Test
    void refCountKeepsHeartbeatWhileASecondJobRuns() {
        final String jobName = seedIntent();
        final HeartbeatWriter writer = new HeartbeatWriter(jdbc, jobName, "pod-alpha");

        writer.beforeJob(execution());   // job 1 of this JVM starts
        writer.beforeJob(execution());   // job 2 of this JVM starts
        writer.afterJob(execution());    // job 1 ends; job 2 still running
        writer.tick();
        final Timestamp whileSecondRuns = heartbeatOf(jobName);
        assertThat(whileSecondRuns)
                .as("heartbeat continues while a second job of the pod still runs").isNotNull();

        writer.afterJob(execution());    // job 2 ends; no job of this pod runs
        writer.tick();
        assertThat(heartbeatOf(jobName))
                .as("heartbeat stops once the last job finishes").isEqualTo(whileSecondRuns);
    }

    @Test
    void scheduledIntervalPlaceholderIsWired() throws Exception {
        final Scheduled scheduled = HeartbeatWriter.class.getMethod("tick").getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString()).isEqualTo("${dcre.batch.heartbeat-seconds:10}000");
    }

    private static ListAppender<ILoggingEvent> attachAppender() {
        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        final ch.qos.logback.classic.Logger logger = context.getLogger(HeartbeatWriter.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    // beforeJob/afterJob toggle a running flag and never read the execution, so a
    // null argument is sufficient to exercise the lifecycle deterministically.
    private static JobExecution execution() {
        return null;
    }
}
