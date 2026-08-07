package za.co.fnb.dcre.platform.batch.config;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.time.Duration;

/**
 * A-80: breaks a Liquibase changelog lock left behind by a killed pod, BEFORE Liquibase runs.
 *
 * <p>Lives in platform-batch so all 22 DCRE services inherit it: every one of them migrates on
 * startup and every one runs as a Kubernetes Job that can be SIGKILLed mid-migration. See
 * {@link StaleChangelogLockReleaser} for the failure this exists to prevent.
 *
 * <p>Ordered before Liquibase's own autoconfiguration (by NAME, so platform-batch needs no
 * liquibase dependency) and applied through a
 * {@link BeanPostProcessor}, because {@code SpringLiquibase} performs its update inside
 * {@code afterPropertiesSet()}: the lock has to be cleared before initialisation, not after.
 *
 * <p>No compile-time dependency on Liquibase. The bean is matched by NAME and the lock table is
 * read from the environment, so platform-batch does not have to carry liquibase-core.
 */
@AutoConfiguration(beforeName = "org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration")
@ConditionalOnClass(name = "liquibase.integration.spring.SpringLiquibase")
@ConditionalOnProperty(name = "dcre.liquibase.release-stale-locks", havingValue = "true",
        matchIfMissing = true)
public class LiquibaseLockAutoConfiguration {

    /** Spring Boot's bean name for the SpringLiquibase instance. */
    private static final String LIQUIBASE_BEAN = "liquibase";

    /** Liquibase's own default, used when a service does not name its lock table. */
    private static final String DEFAULT_LOCK_TABLE = "DATABASECHANGELOGLOCK";

    /**
     * Whole-service startup on this fleet, migration included, measures about 7 seconds, so 60s
     * is roughly an order of magnitude of headroom over a real migration. Raise it for a service
     * with a genuinely long migration: breaking the lock of a pod that is still working would let
     * two Liquibase runs share one database, which is worse than the wait.
     */
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    /**
     * Static, so the post-processor is created without forcing early initialisation of the
     * configuration class or the beans it would otherwise pull in.
     */
    @Bean
    static BeanPostProcessor dcreStaleLiquibaseLockReleaser(final Environment environment,
            final org.springframework.beans.factory.ObjectProvider<DataSource> dataSources) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(final Object bean, final String beanName) {
                if (!LIQUIBASE_BEAN.equals(beanName)) {
                    return bean;
                }
                final DataSource dataSource = dataSources.getIfAvailable();
                if (dataSource == null) {
                    return bean;
                }
                new StaleChangelogLockReleaser(dataSource, lockTable(environment), ttl(environment))
                        .releaseStaleLocks();
                return bean;
            }
        };
    }

    private static String lockTable(final Environment environment) {
        return environment.getProperty("spring.liquibase.database-change-log-lock-table",
                DEFAULT_LOCK_TABLE);
    }

    private static Duration ttl(final Environment environment) {
        return environment.getProperty("dcre.liquibase.lock-ttl", Duration.class, DEFAULT_TTL);
    }
}
