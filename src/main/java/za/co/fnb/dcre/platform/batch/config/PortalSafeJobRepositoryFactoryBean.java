package za.co.fnb.dcre.platform.batch.config;

import java.sql.Types;
import java.util.Locale;

import org.springframework.batch.core.repository.dao.jdbc.JdbcStepExecutionDao;
import org.springframework.batch.core.repository.support.JdbcJobRepositoryFactoryBean;
import org.springframework.batch.infrastructure.support.DatabaseType;

/**
 * SCRUM-101: the stock {@link JdbcJobRepositoryFactoryBean} with one substitution, the
 * step-execution DAO. Every other DAO, the {@code SimpleJobRepository} it assembles, and
 * all of the factory's configuration are untouched.
 *
 * <p>{@code createStepExecutionDao()} is protected with a covariant
 * {@link JdbcStepExecutionDao} return (spring-batch-core 6.0.4,
 * {@code JobRepositoryFactoryBean} lines 363-373) and every field it reads is protected
 * (lines 94-121), so the substitution is a supported extension point rather than a copy
 * of the factory. Rationale for the DAO itself: {@link PortalSafeStepExecutionDao}.
 *
 * <p><b>Upgrade fragility, accepted deliberately.</b> {@code JobRepositoryFactoryBean},
 * the deprecated superclass that declares those protected members, is scheduled for
 * removal in Batch 6.2 or later. That is acceptable precisely because it breaks at
 * COMPILE time, loudly, on the version bump that removes it: there is no silent
 * regression path back to the nested read.
 */
final class PortalSafeJobRepositoryFactoryBean extends JdbcJobRepositoryFactoryBean {

    @Override
    protected JdbcStepExecutionDao createStepExecutionDao() {
        final JdbcStepExecutionDao dao = new PortalSafeStepExecutionDao();
        dao.setJdbcTemplate(jdbcOperations);
        dao.setStepExecutionIncrementer(
                incrementerFactory.getIncrementer(databaseType, tablePrefix + stepExecutionIncrementerName));
        dao.setTablePrefix(tablePrefix);
        dao.setClobTypeToUse(clobTypeToUse());
        dao.setExitMessageLength(maxVarCharLengthForExitMessage);
        return dao;
    }

    /** Mirrors the private {@code JobRepositoryFactoryBean.determineClobTypeToUse} (lines 387-398). */
    private int clobTypeToUse() {
        if (clobType != null) {
            return clobType;
        }
        return DatabaseType.SYBASE == DatabaseType.valueOf(databaseType.toUpperCase(Locale.ROOT))
                ? Types.LONGVARCHAR
                : Types.CLOB;
    }
}
