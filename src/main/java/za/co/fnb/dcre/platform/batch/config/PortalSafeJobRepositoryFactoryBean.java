package za.co.fnb.dcre.platform.batch.config;

import org.springframework.batch.core.repository.dao.jdbc.JdbcStepExecutionDao;
import org.springframework.batch.core.repository.support.JdbcJobRepositoryFactoryBean;

/**
 * SCRUM-101: the stock {@link JdbcJobRepositoryFactoryBean} with one substitution, the
 * step-execution DAO. Every other DAO, the {@code SimpleJobRepository} it assembles, and
 * all of the factory's configuration are untouched.
 *
 * <p>{@code createStepExecutionDao()} is protected with a covariant
 * {@link JdbcStepExecutionDao} return (spring-batch-core 6.0.4,
 * {@code JobRepositoryFactoryBean} lines 364-373) and every field it reads is protected
 * (lines 95-121), so the substitution is a supported extension point rather than a copy
 * of the factory. Rationale for the DAO itself: {@link PortalSafeStepExecutionDao}.
 *
 * <p><b>Nothing private is reimplemented.</b> The stock method also calls the PRIVATE
 * {@code determineClobTypeToUse} (lines 387-399). Rather than copy it, this override asks
 * the library for a stock DAO first and reads the answer back off it through the public
 * {@code AbstractJdbcBatchMetadataDao.getClobTypeToUse()} (line 105). There is therefore
 * no second implementation that can silently drift from the library's own, at the cost of
 * one throwaway DAO per application context.
 *
 * <p><b>Upgrade fragility, accepted deliberately.</b> {@code JobRepositoryFactoryBean},
 * the deprecated superclass that declares those protected members
 * ({@code @Deprecated(since = "6.0", forRemoval = true)}, line 75), is scheduled for
 * removal in Batch 6.2 or later.
 *
 * <p>Note what that does NOT mean. This module pins its own {@code springBatchVersion}
 * ({@code gradle.properties}) and takes spring-batch-core {@code compileOnly}, so it always
 * compiles against that pin no matter what a consuming service resolves: a fleet-wide
 * Spring Batch bump produces NO compile failure here. The override simply keeps running
 * against whatever version the CONSUMER puts on the runtime classpath, and that is the
 * real risk. A later Batch that removes the superclass fails at the consumer's context refresh
 * ({@code NoClassDefFoundError}), and a later Batch that fixes the nesting leaves this
 * override silently redundant. The guard is therefore a test, not the compiler:
 * {@code PortalSafeOverrideGuardTest} fails the build the moment the pin above moves off
 * the version these two classes were read line by line against, forcing a re-verification
 * against the new sources; {@code PortalSafeStepExecutionDaoIT}'s canary then says whether
 * the override can be deleted.
 */
final class PortalSafeJobRepositoryFactoryBean extends JdbcJobRepositoryFactoryBean {

    @Override
    protected JdbcStepExecutionDao createStepExecutionDao() {
        final JdbcStepExecutionDao stock = super.createStepExecutionDao();
        final JdbcStepExecutionDao dao = new PortalSafeStepExecutionDao();
        dao.setJdbcTemplate(jdbcOperations);
        dao.setStepExecutionIncrementer(
                incrementerFactory.getIncrementer(databaseType, tablePrefix + stepExecutionIncrementerName));
        dao.setTablePrefix(tablePrefix);
        dao.setClobTypeToUse(stock.getClobTypeToUse());
        dao.setExitMessageLength(maxVarCharLengthForExitMessage);
        return dao;
    }
}
