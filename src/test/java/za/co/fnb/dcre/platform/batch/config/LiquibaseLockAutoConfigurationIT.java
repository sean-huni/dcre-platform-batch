package za.co.fnb.dcre.platform.batch.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-80 wiring proof.
 *
 * <p>{@link StaleChangelogLockReleaserIT} proves the SQL. It cannot prove the part most likely to
 * be wrong: that the releaser actually RUNS, before Liquibase, in a real context. The releaser is
 * attached as a {@code BeanPostProcessor} matching the bean NAMED {@code liquibase}, which is an
 * assumption about Spring Boot's autoconfiguration rather than a fact this module controls. If
 * that name is wrong the releaser is silently never invoked and every unit test still passes.
 *
 * <p>So this boots real Liquibase against a real CockroachDB whose lock table is already held by
 * a dead pod, exactly as the mandates chaos gate left it on 2026-08-07, and requires the context
 * to start. Without the releaser this test HANGS rather than fails, which is itself the shape of
 * the production incident.
 */
class LiquibaseLockAutoConfigurationIT {

    private static final String LOCK_TABLE = "mas_databasechangeloglock";

    private static CockroachContainer crdb;

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startDb() {
        crdb = new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));
        crdb.start();
        final DriverManagerDataSource ds = new DriverManagerDataSource(
                crdb.getJdbcUrl(), crdb.getUsername(), crdb.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);
    }

    @AfterAll
    static void stopDb() {
        if (crdb != null) {
            crdb.stop();
        }
    }

    @Test
    void liquibaseStartsEvenThoughAKilledPodStillHoldsTheLock() {
        jdbc.execute("DROP TABLE IF EXISTS " + LOCK_TABLE);
        jdbc.execute("CREATE TABLE " + LOCK_TABLE + " (id INT NOT NULL PRIMARY KEY,"
                + " locked BOOLEAN NOT NULL, lockgranted TIMESTAMP, lockedby VARCHAR(255))");
        jdbc.update("INSERT INTO " + LOCK_TABLE + " (id, locked, lockgranted, lockedby)"
                + " VALUES (1, true, now() - '10 minutes'::INTERVAL,"
                + " 'man-mas-50031ee46d964deead8c890fe39d61d3-zt7tk (10.244.0.211)')");

        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("liquibase");
            assertThat(jdbc.queryForObject("SELECT locked FROM " + LOCK_TABLE + " WHERE id = 1",
                    Boolean.class))
                    .as("the dead pod's lock must have been broken before Liquibase ran; if the"
                            + " BeanPostProcessor is matching the wrong bean name this stays true"
                            + " and the context blocks on the lock instead")
                    .isFalse();
        });
    }

    /**
     * The negative half: with the feature switched off the lock survives, which proves the
     * assertion above is detecting the releaser rather than something else clearing the row.
     */
    @Test
    void theLockSurvivesWhenTheReleaserIsDisabled() {
        jdbc.execute("DROP TABLE IF EXISTS " + LOCK_TABLE);
        jdbc.execute("CREATE TABLE " + LOCK_TABLE + " (id INT NOT NULL PRIMARY KEY,"
                + " locked BOOLEAN NOT NULL, lockgranted TIMESTAMP, lockedby VARCHAR(255))");
        jdbc.update("INSERT INTO " + LOCK_TABLE + " (id, locked, lockgranted, lockedby)"
                + " VALUES (1, true, now() - '10 minutes'::INTERVAL, 'dead-pod')");

        runner().withPropertyValues("dcre.liquibase.release-stale-locks=false")
                .withPropertyValues("spring.liquibase.enabled=false")
                .run(context -> assertThat(jdbc.queryForObject(
                        "SELECT locked FROM " + LOCK_TABLE + " WHERE id = 1", Boolean.class))
                        .as("nothing else in the context clears this row")
                        .isTrue());
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        LiquibaseLockAutoConfiguration.class, LiquibaseAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=" + crdb.getJdbcUrl(),
                        "spring.datasource.username=" + crdb.getUsername(),
                        "spring.datasource.password=" + crdb.getPassword(),
                        "spring.datasource.driver-class-name=org.postgresql.Driver",
                        "spring.liquibase.database-change-log-table=mas_databasechangelog",
                        "spring.liquibase.database-change-log-lock-table=" + LOCK_TABLE,
                        "spring.liquibase.change-log=classpath:db/changelog/a80-test-changelog.xml");
    }
}
