package za.co.fnb.dcre.platform.batch.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;

/**
 * A-80: releases a Liquibase changelog lock left behind by a pod that no longer exists.
 *
 * <p>Every DCRE service migrates on startup, and every service runs as a Kubernetes Job that can
 * be killed at any instant, including during that migration. Liquibase's lock has no owner
 * liveness check and no expiry, so a SIGKILL mid-migration leaves {@code locked = true} owned by
 * a dead pod and the replacement waits on it indefinitely.
 *
 * <p>The failure mode is the reason this is not merely an inconvenience: the waiting pod stays
 * {@code Running}, so AGT's OutcomeWatcher never observes a terminal condition, no relaunch
 * budget is spent, no error is logged and no alert fires. The stage simply never finishes.
 * Observed live on 2026-08-07: {@code man-mas} killed mid-migration, replacement pod printing
 * "Waiting for changelog lock...." every 10 seconds while the DAG sat in DAG_RUNNING.
 *
 * <p>Breaking a lock is not free: if the holder is alive and still migrating, breaking it lets
 * two Liquibase runs work on one database. So this only breaks locks OLDER than a TTL, ages are
 * compared by the DATABASE clock rather than the pod's, and a lock with no grant timestamp is
 * left alone rather than treated as infinitely old. Whole-service startup on this fleet
 * (migration included) measures about 7 seconds, so the 60s default carries roughly an order of
 * magnitude of headroom.
 */
public class StaleChangelogLockReleaser {

    private static final Logger log = LoggerFactory.getLogger(StaleChangelogLockReleaser.class);

    /** CockroachDB / PostgreSQL: undefined table. Normal before the first migration. */
    private static final String UNDEFINED_TABLE = "42P01";

    private final DataSource dataSource;

    private final String lockTable;

    private final Duration ttl;

    public StaleChangelogLockReleaser(final DataSource dataSource, final String lockTable,
            final Duration ttl) {
        this.dataSource = dataSource;
        this.lockTable = lockTable;
        this.ttl = ttl;
    }

    /**
     * @return how many stale locks were broken, 0 when there was nothing to do.
     */
    public int releaseStaleLocks() {
        try {
            // now() and lockgranted are BOTH the database's clock, so pod clock skew cannot
            // widen or collapse the age. lockgranted IS NOT NULL fails closed on a row whose
            // age is unknown.
            final int released = new JdbcTemplate(dataSource).update(
                    "UPDATE " + lockTable + " SET locked = false, lockgranted = NULL, lockedby = NULL"
                            + " WHERE locked = true AND lockgranted IS NOT NULL"
                            + " AND lockgranted < now() - ?::INTERVAL",
                    ttl.toSeconds() + " seconds");
            if (released > 0) {
                log.warn("released {} stale Liquibase lock(s) in {} (held longer than {}). The"
                        + " previous owner was killed mid-migration; without this its replacement"
                        + " would wait on the lock forever while looking healthy.",
                        released, lockTable, ttl);
            }
            return released;
        } catch (final RuntimeException e) {
            if (isMissingTable(e)) {
                return 0; // first boot: Liquibase has not created the lock table yet
            }
            // Never block startup on this: the worst case without it is the wait Liquibase
            // would have done anyway, and a broken probe must not become a new outage.
            log.warn("could not check {} for stale Liquibase locks; continuing to Liquibase,"
                    + " which will wait on the lock if one is genuinely held", lockTable, e);
            return 0;
        }
    }

    private static boolean isMissingTable(final Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql && UNDEFINED_TABLE.equals(sql.getSQLState())) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
