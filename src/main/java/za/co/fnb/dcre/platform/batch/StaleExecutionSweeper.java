package za.co.fnb.dcre.platform.batch;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * A-39a: a killed pod strands BATCH_JOB_EXECUTION in STARTED and the relaunch
 * throws JobExecutionAlreadyRunning. Each service self-heals its OWN metadata
 * (single-writer, R-04) by abandoning stale STARTED executions at startup,
 * before the job launches. AGT never touches service schemas.
 */
public final class StaleExecutionSweeper {

    private StaleExecutionSweeper() {
    }

    public static int abandonStale(DataSource batchMeta, String tablePrefix, int olderThanSeconds) {
        String exec = """
                UPDATE %sJOB_EXECUTION
                SET STATUS='ABANDONED', EXIT_CODE='ABANDONED', END_TIME=now(),
                    EXIT_MESSAGE='abandoned by StaleExecutionSweeper (A-39a)'
                WHERE STATUS='STARTED' AND START_TIME < now() - INTERVAL '%d seconds'
                """.formatted(tablePrefix, olderThanSeconds);
        String step = """
                UPDATE %sSTEP_EXECUTION
                SET STATUS='ABANDONED', END_TIME=now()
                WHERE STATUS='STARTED' AND START_TIME < now() - INTERVAL '%d seconds'
                """.formatted(tablePrefix, olderThanSeconds);
        try (Connection c = batchMeta.getConnection()) {
            try (PreparedStatement p = c.prepareStatement(step)) {
                p.executeUpdate();
            }
            try (PreparedStatement p = c.prepareStatement(exec)) {
                return p.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException("stale-execution sweep failed", e);
        }
    }
}
