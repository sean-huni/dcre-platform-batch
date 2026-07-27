package za.co.fnb.dcre.platform.batch.config;

import java.util.List;

import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.repository.dao.jdbc.JdbcStepExecutionDao;
import org.springframework.batch.core.step.StepExecution;

/**
 * SCRUM-101: the stock {@link JdbcStepExecutionDao} with a {@code getLastStepExecution}
 * that never opens a second portal on one connection, so a job RESTART works on
 * CockroachDB.
 *
 * <p><b>The library defect, spring-batch-core 6.0.4.</b>
 * {@code JdbcStepExecutionDao.getLastStepExecution} (lines 331-358) runs its query inside
 * a {@code PreparedStatementCallback}: the {@code ResultSet} opens at line 337 and, while
 * it is still open, line 341 calls {@code jobExecutionDao.getJobParameters(...)}, which
 * prepares a SECOND statement on the SAME connection ({@code JdbcJobExecutionDao} lines
 * 428-453, the statement at line 450). PostgreSQL tolerates the extra portal; CockroachDB
 * rejects it ("unimplemented: multiple active portals is in preview"), Hikari marks the
 * connection broken, and every restart of an existing job instance dies. It is the only
 * ResultSet-scoped nested query in the whole {@code repository/dao/jdbc} package.
 *
 * <p><b>The repair.</b> Read the last step execution's id, let that query close, then
 * delegate to the library's own {@code getStepExecution(long)} (lines 286-291; public,
 * NOT deprecated, {@code StepExecutionDao} line 58, since 6.0). That path is strictly
 * sequential: getJobExecutionId, then {@code jobExecutionDao.getJobExecution} (lines
 * 324-335, itself sequential), then the mapping, each JdbcTemplate call closing before
 * the next opens.
 *
 * <p><b>Value equivalence.</b> Both paths end in the same {@code StepExecutionRowMapper}
 * (lines 47-73); the only difference is the {@code JobInstance} object identity (re-read
 * instead of the caller's instance), and {@code Entity.equals} is id-only. The step
 * {@code ExecutionContext} is unaffected: neither path loads it, and
 * {@code SimpleJobExplorer.getLastStepExecution} (lines 266-273) fills it afterwards, so
 * the restart context {@code SimpleStepHandler} (line 117) needs is preserved.
 * {@code PortalSafeStepExecutionDaoIT} pins that equivalence field by field.
 *
 * <p><b>DELETE this class</b> (and {@link PortalSafeJobRepositoryFactoryBean}) once the
 * upstream nesting is fixed; the canary test in {@code PortalSafeStepExecutionDaoIT}
 * turns red on the Batch version that fixes it.
 */
final class PortalSafeStepExecutionDao extends JdbcStepExecutionDao {

    /** The id column of the stock GET_LAST_STEP_EXECUTION, same join and same ordering. */
    private static final String GET_LAST_STEP_EXECUTION_ID = """
            SELECT SE.STEP_EXECUTION_ID
            FROM %PREFIX%JOB_EXECUTION JE
                JOIN %PREFIX%STEP_EXECUTION SE ON SE.JOB_EXECUTION_ID = JE.JOB_EXECUTION_ID
            WHERE JE.JOB_INSTANCE_ID = ? AND SE.STEP_NAME = ?
            ORDER BY SE.CREATE_TIME DESC, SE.STEP_EXECUTION_ID DESC
            LIMIT 1
            """;

    @Override
    public StepExecution getLastStepExecution(final JobInstance jobInstance, final String stepName) {
        final List<Long> ids = getJdbcTemplate().query(getQuery(GET_LAST_STEP_EXECUTION_ID),
                (rs, rowNum) -> rs.getLong(1), jobInstance.getInstanceId(), stepName);
        return ids.isEmpty() ? null : getStepExecution(ids.getFirst());
    }
}
