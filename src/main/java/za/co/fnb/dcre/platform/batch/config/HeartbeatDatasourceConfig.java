package za.co.fnb.dcre.platform.batch.config;

import java.net.InetAddress;
import java.net.UnknownHostException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.scheduling.annotation.EnableScheduling;

import za.co.fnb.dcre.platform.batch.HeartbeatWriter;

/**
 * SCRUM-85 (R-47, M12 piece 2): the @Import-able unit that gives a stage / M-family
 * service a heartbeat writer against {@code agt_ops.launch_intent}. Bundles a SECOND,
 * read-write datasource pointed at {@code agt_ops}, {@code @EnableScheduling}, and the
 * {@link HeartbeatWriter} bean.
 *
 * <p><b>Usage: {@code @Import} only, never component-scanned</b> (identical caveat to
 * {@link BatchJdbcConfig}). {@code @Import(HeartbeatDatasourceConfig.class)} on each
 * service; it must NOT fall under a service's {@code @ComponentScan} base package, and
 * it is deliberately NOT registered in
 * {@code META-INF/spring/...AutoConfiguration.imports}. Each service additionally
 * registers the {@link HeartbeatWriter} bean as a listener on its Job
 * ({@code new JobBuilder(...).listener(heartbeatWriter)}) - a {@code JobExecutionListener}
 * bean is not auto-applied to jobs in Batch 6, and the writer only heartbeats between
 * {@code beforeJob} and {@code afterJob}.
 *
 * <p><b>The agt_ops datasource is built inline (not a bean)</b>, cloning rpt's
 * {@code OpsLiquibaseConfig} pattern: exposing a standalone {@code DataSource} bean would
 * trip Boot's {@code DataSourceAutoConfiguration}
 * ({@code @ConditionalOnMissingBean(DataSource.class)}) and REPLACE the primary
 * application datasource. It is a deliberately non-pooling {@link SimpleDriverDataSource}:
 * one tiny UPDATE per {@code dcre.batch.heartbeat-seconds} needs no pool, and a pooled one
 * would idle unclosed for the life of the context. The URL default is an infrastructure
 * address (dev CRDB {@code agt_ops}), not a table prefix, so a masking default is safe
 * here (clean-clone boots with no {@code .env}, 12FactorApp); every deployed context
 * overrides {@code dcre.agtops-db-url}. The resolved URL/user is logged once at startup
 * (INFO) so operators can see where heartbeats go and spot a wired-but-wrong target.
 *
 * <p><b>Dedicated agt_ops credentials.</b> The connection uses
 * {@code dcre.agtops-db-user} / {@code dcre.agtops-db-password} (default {@code root} /
 * blank for the dev clean-clone), NOT the primary {@code spring.datasource.*} creds, so a
 * future tenant-role-scoped primary datasource can still be granted a distinct agt_ops
 * UPDATE credential.
 *
 * <p><b>{@code dcre.batch.heartbeat-seconds}</b> (default 10) drives the writer's
 * {@code @Scheduled} interval directly via a placeholder, so it is not carried on the
 * typed {@code BatchProperties} record (a {@code fixedDelayString} must read a raw
 * placeholder; a bound-but-ignored record field would mislead).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class HeartbeatDatasourceConfig {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatDatasourceConfig.class);

    @Bean
    HeartbeatWriter heartbeatWriter(
            @Value("${dcre.agtops-db-url:jdbc:postgresql://localhost:26257/agt_ops?sslmode=disable}") final String url,
            @Value("${dcre.agtops-db-user:root}") final String user,
            @Value("${dcre.agtops-db-password:}") final String password,
            @Value("${JOB_NAME:}") final String jobName,
            @Value("${HOSTNAME:}") final String hostname) {
        final String resolvedJob = blankToNull(jobName);
        final String pod = resolvePod(hostname);
        log.info("heartbeat: agt_ops liveness target url={} user={} job_name={} owner_pod={}",
                url, user, resolvedJob == null ? "<unset: heartbeat disabled>" : resolvedJob, pod);
        final DataSource agtOps = DataSourceBuilder.create()
                .type(SimpleDriverDataSource.class)
                .driverClassName("org.postgresql.Driver")
                .url(url).username(user).password(password).build();
        return new HeartbeatWriter(new JdbcTemplate(agtOps), resolvedJob, pod);
    }

    private static String blankToNull(final String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private static String resolvePod(final String hostname) {
        if (hostname != null && !hostname.isBlank()) {
            return hostname;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (final UnknownHostException e) {
            return "unknown";
        }
    }
}
