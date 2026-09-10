package za.co.fnb.dcre.platform.batch.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-80 (SCRUM-107), found by the mandates chaos gate on 2026-08-07.
 *
 * <p>SIGKILL of a service pod DURING its Liquibase migration leaves the changelog lock row set,
 * owned by a pod that no longer exists. Liquibase has no owner-liveness check and no TTL, so the
 * relaunched pod waits on that lock forever:
 *
 * <pre>
 * mas_databasechangeloglock: locked=t lockedby='man-mas-...-zt7tk (10.244.0.211)'  &lt;- SIGKILLed
 * relaunched pod ...-l9dtm : "Waiting for changelog lock...." every 10s, indefinitely
 * </pre>
 *
 * <p>This is the worst failure shape available. The pod stays in {@code Running}, so AGT's
 * OutcomeWatcher never sees a terminal condition, no relaunch budget is consumed, nothing is
 * logged as an error and no alert fires. The DAG simply never completes. A crash would have been
 * better.
 *
 * <p>All 24 changelog-lock tables across dcre_man, dcre_col and agt_ops are exposed, so the fix
 * lives in platform-batch's autoconfiguration where all 22 services inherit it.
 *
 * <p>The TTL is the whole design decision. Breaking a lock a LIVE pod still holds would let two
 * Liquibase runs proceed at once, so the threshold has to sit well above a real migration.
 * Measured on this fleet: a service's whole startup, migration included, completes in about 7
 * seconds, so the 60s default is roughly an order of magnitude of headroom.
 */
class StaleChangelogLockReleaserIT {

    private static final String LOCK_TABLE = "mas_databasechangeloglock";

    private static CockroachContainer crdb;

    private static DataSource dataSource;

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startDb() {
        crdb = new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));
        crdb.start();
        final DriverManagerDataSource ds = new DriverManagerDataSource(
                crdb.getJdbcUrl(), crdb.getUsername(), crdb.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        dataSource = ds;
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void stopDb() {
        if (crdb != null) {
            crdb.stop();
        }
    }

    @BeforeEach
    void freshLockTable() {
        jdbc.execute("DROP TABLE IF EXISTS " + LOCK_TABLE);
        jdbc.execute("CREATE TABLE " + LOCK_TABLE + " (id INT NOT NULL PRIMARY KEY,"
                + " locked BOOLEAN NOT NULL, lockgranted TIMESTAMP, lockedby VARCHAR(255))");
    }

    /** The exact live incident: a dead pod's lock, older than the TTL, must be broken. */
    @Test
    void aLockHeldByAPodThatDiedIsReleased() {
        lockRow(Duration.ofMinutes(10), "man-mas-50031ee46d964deead8c890fe39d61d3-zt7tk (10.244.0.211)");

        final int released = releaser(Duration.ofSeconds(60)).releaseStaleLocks();

        assertThat(released)
                .as("the SIGKILLed pod's lock must be broken, or its replacement waits forever"
                        + " while AGT sees a healthy Running pod and never intervenes")
                .isEqualTo(1);
        assertThat(locked()).isFalse();
    }

    /**
     * The other half, and the one that matters more: a lock a LIVE pod is still holding must
     * survive. A releaser that clears every lock would let two Liquibase runs migrate the same
     * database at once, which is a worse defect than the hang it fixes.
     */
    @Test
    void aFreshLockIsLeftAloneBecauseItsOwnerMayStillBeMigrating() {
        lockRow(Duration.ofSeconds(5), "man-mas-live (10.244.0.99)");

        final int released = releaser(Duration.ofSeconds(60)).releaseStaleLocks();

        assertThat(released)
                .as("5s old against a 60s TTL: this pod is very likely mid-migration")
                .isZero();
        assertThat(locked()).isTrue();
    }

    /** An unlocked table is the normal case and must not be touched or reported as a break. */
    @Test
    void anUnlockedTableIsUntouched() {
        jdbc.update("INSERT INTO " + LOCK_TABLE + " (id, locked) VALUES (1, false)");

        assertThat(releaser(Duration.ofSeconds(60)).releaseStaleLocks()).isZero();
        assertThat(locked()).isFalse();
    }

    /**
     * A lock row with a NULL lockgranted cannot be aged, and must fail CLOSED (left alone) rather
     * than be treated as infinitely old. Guessing the other way breaks a live migration on the
     * strength of a missing timestamp.
     */
    @Test
    void aLockWithNoGrantTimestampIsNotBroken() {
        jdbc.update("INSERT INTO " + LOCK_TABLE + " (id, locked, lockgranted, lockedby)"
                + " VALUES (1, true, NULL, 'unknown')");

        assertThat(releaser(Duration.ofSeconds(60)).releaseStaleLocks()).isZero();
        assertThat(locked()).isTrue();
    }

    /**
     * Before the lock table exists (first ever boot) there is nothing to release, and probing must
     * not throw: it runs BEFORE Liquibase, which is what creates the table.
     */
    @Test
    void anAbsentLockTableIsNotAnError() {
        jdbc.execute("DROP TABLE IF EXISTS " + LOCK_TABLE);

        assertThat(releaser(Duration.ofSeconds(60)).releaseStaleLocks())
                .as("the releaser runs before Liquibase creates this table, so absence is normal")
                .isZero();
    }

    /**
     * Ages are compared IN THE DATABASE. The pod's clock and CockroachDB's clock are different
     * clocks, and a skewed pod could otherwise break every lock or none.
     */
    @Test
    void theAgeComparisonHappensInTheDatabaseNotInTheJvm() {
        lockRow(Duration.ofSeconds(90), "man-mas-old (10.244.0.7)");

        assertThat(releaser(Duration.ofSeconds(60)).releaseStaleLocks()).isEqualTo(1);
    }

    private StaleChangelogLockReleaser releaser(final Duration ttl) {
        return new StaleChangelogLockReleaser(dataSource, LOCK_TABLE, ttl);
    }

    private void lockRow(final Duration age, final String owner) {
        jdbc.update("INSERT INTO " + LOCK_TABLE + " (id, locked, lockgranted, lockedby)"
                + " VALUES (1, true, now() - ?::INTERVAL, ?)", age.toSeconds() + " seconds", owner);
    }

    private boolean locked() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT locked FROM " + LOCK_TABLE + " WHERE id = 1", Boolean.class));
    }
}
