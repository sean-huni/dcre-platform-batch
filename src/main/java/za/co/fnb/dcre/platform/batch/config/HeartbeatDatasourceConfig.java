package za.co.fnb.dcre.platform.batch.config;

import java.net.InetAddress;
import java.net.UnknownHostException;

import javax.sql.DataSource;

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
 * here; every deployed context overrides {@code dcre.agtops-db-url}.
 *
 * <p><b>{@code dcre.batch.heartbeat-seconds}</b> (default 10) drives the writer's
 * {@code @Scheduled} interval directly via a placeholder, so it is not carried on the
 * typed {@code BatchProperties} record (a {@code fixedDelayString} must read a raw
 * placeholder; a bound-but-ignored record field would mislead).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class HeartbeatDatasourceConfig {

    @Bean
    HeartbeatWriter heartbeatWriter(
            @Value("${dcre.agtops-db-url:jdbc:postgresql://localhost:26257/agt_ops?sslmode=disable}") final String url,
            @Value("${spring.datasource.username:root}") final String user,
            @Value("${spring.datasource.password:}") final String password,
            @Value("${JOB_NAME:}") final String jobName,
            @Value("${HOSTNAME:}") final String hostname) {
        final DataSource agtOps = DataSourceBuilder.create()
                .type(SimpleDriverDataSource.class)
                .driverClassName("org.postgresql.Driver")
                .url(url).username(user).password(password).build();
        return new HeartbeatWriter(new JdbcTemplate(agtOps), blankToNull(jobName), resolvePod(hostname));
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
