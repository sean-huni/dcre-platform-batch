package za.co.fnb.dcre.platform.batch.config;

import java.util.List;

import org.jspecify.annotations.Nullable;
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
 * a {@code PreparedStatementCallback}: line 334 sets {@code setMaxRows(1)}, which makes
 * pgjdbc execute through a row-limited portal that stays SUSPENDED until the
 * {@code ResultSet} is closed. The {@code ResultSet} opens at line 337 and, while it is
 * still open, line 341 calls {@code jobExecutionDao.getJobParameters(...)}, which prepares
 * a SECOND statement on the SAME connection ({@code JdbcJobExecutionDao} lines 428-453,
 * the statement at line 450). PostgreSQL tolerates the extra portal; CockroachDB rejects
 * it with SQLSTATE {@code 0A000} ("unimplemented: multiple active portals is in preview
 * ... cannot perform operation sql.PrepareStmt while a different portal is open"), Hikari
 * marks the connection broken, and every restart of an existing job instance dies. It is
 * the only ResultSet-scoped nested query in the whole {@code repository/dao/jdbc} package.
 *
 * <p><b>The repair.</b> Read the last step execution's id, let that query close, then
 * delegate to the library's own {@code getStepExecution(long)} (lines 287-291; public,
 * NOT deprecated, {@code StepExecutionDao} line 58, since 6.0). That path is strictly
 * sequential: getJobExecutionId, then {@code jobExecutionDao.getJobExecution} (lines
 * 324-335, itself sequential), then the mapping, each JdbcTemplate call closing before
 * the next opens. {@code PortalSafeStepExecutionDaoIT} pins that sequentiality against a
 * real CockroachDB, so an upstream rewrite that nests a query inside the delegate fails
 * here instead of at a customer's job launch.
 *
 * <p><b>Why {@code LIMIT 1} and not the driver-neutral {@code setMaxRows(1)}.</b>
 * {@code setMaxRows} is the portable mechanism and stock's own (line 334), and it was
 * tried here first. It does not work: it is the very thing that opens the suspended portal.
 * With {@code setMaxRows(1)} on the id query, pgjdbc executes through a row-limited portal
 * and, inside an open transaction, defers the portal's Close to the next Sync, so the
 * portal is still open when the delegate prepares its first statement even though
 * JdbcTemplate has already closed the {@code ResultSet}. Measured on CockroachDB v26.2.3:
 * the whole suite went red with the identical "unimplemented: multiple active portals"
 * error this class exists to avoid (this IT plus both restart ITs in
 * {@code BatchJdbcConfigIT}). {@code LIMIT 1} makes the result a single complete row, so
 * the portal closes with the statement and nothing is left suspended. The narrower
 * portability cost is accepted knowingly: {@code LIMIT} is not ANSI and Oracle and SQL
 * Server reject it, but this class only ever exists on a CockroachDB or PostgreSQL
 * deployment, since a database that tolerates the second portal should be running the
 * stock DAO instead. The SQL is otherwise the stock {@code GET_LAST_STEP_EXECUTION}
 * (lines 97-103) narrowed to its id column, same join, same {@code ORDER BY}.
 *
 * <p><b>Value equivalence.</b> Both paths end in the same {@code StepExecutionRowMapper}
 * ({@code StepExecutionRowMapper.java}, lines 48-72); the only difference is the
 * {@code JobInstance} object identity (re-read instead of the caller's instance), and
 * {@code Entity.equals} is id-only. The step {@code ExecutionContext} is unaffected:
 * neither path loads it, and {@code SimpleJobExplorer.getLastStepExecution} (lines
 * 266-276) fills it afterwards, so the restart context {@code SimpleStepHandler}
 * (line 117) needs is preserved. {@code PortalSafeStepExecutionDaoIT} pins that
 * equivalence field by field.
 *
 * <p><b>Accepted cost: 7 round trips where stock does 2.</b> Stock issues the joined
 * SELECT plus the parameters SELECT. This path issues the id SELECT, then the delegate's
 * six (job-execution id, job-instance id, job instance, job parameters, job execution,
 * step execution). All seven are primary-key point lookups, and the read happens once per
 * step per job launch, but it does widen the CockroachDB SERIALIZABLE transaction that
 * {@code SimpleStepHandler} runs the restart check in. Accepted deliberately: the
 * alternative is a restart that cannot complete at all. Recorded so it is a known trade,
 * not a surprise.
 *
 * <p><b>DELETE this class</b> (and {@link PortalSafeJobRepositoryFactoryBean}) once the
 * upstream nesting is fixed; the canary test in {@code PortalSafeStepExecutionDaoIT}
 * turns red on the Batch version that fixes it.
 */
final class PortalSafeStepExecutionDao extends JdbcStepExecutionDao {

    /**
     * The id column of the stock GET_LAST_STEP_EXECUTION, same join and same ordering.
     * LIMIT 1 rather than setMaxRows(1) on purpose: see the class javadoc, a row-limited
     * portal is exactly what CockroachDB refuses to leave open across the delegate.
     */
    private static final String GET_LAST_STEP_EXECUTION_ID = """
            SELECT SE.STEP_EXECUTION_ID
            FROM %PREFIX%JOB_EXECUTION JE
                JOIN %PREFIX%STEP_EXECUTION SE ON SE.JOB_EXECUTION_ID = JE.JOB_EXECUTION_ID
            WHERE JE.JOB_INSTANCE_ID = ? AND SE.STEP_NAME = ?
            ORDER BY SE.CREATE_TIME DESC, SE.STEP_EXECUTION_ID DESC
            LIMIT 1
            """;

    @Override
    public @Nullable StepExecution getLastStepExecution(final JobInstance jobInstance, final String stepName) {
        final List<Long> ids = getJdbcTemplate().query(getQuery(GET_LAST_STEP_EXECUTION_ID),
                (rs, rowNum) -> rs.getLong(1), jobInstance.getInstanceId(), stepName);
        return ids.isEmpty() ? null : getStepExecution(ids.getFirst());
    }
}
