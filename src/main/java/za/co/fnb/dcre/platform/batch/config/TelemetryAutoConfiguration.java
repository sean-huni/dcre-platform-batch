package za.co.fnb.dcre.platform.batch.config;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.ClassUtils;

import za.co.fnb.dcre.platform.batch.config.properties.TelemetryProperties;
import za.co.fnb.dcre.platform.batch.telemetry.StageIdentity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Publishes the stage's telemetry identity and stamps it onto every meter.
 *
 * <p>Backs off unless {@code dcre.telemetry.enabled=true}, following the same rule as
 * {@link ExchangeAutoConfiguration}: no environment variable the orchestrator exports canonicalises
 * to that key, so this cannot activate by accident on a service that carries no telemetry config.
 *
 * <p>The stage token defaults to the PACKAGE LEAF of the class annotated
 * {@code @SpringBootApplication}, not to anything hand-typed and not to the token a service hands
 * its seam listener. That listener token is per-JOB: one service constructs it twice with different
 * values and another passes a variable at the construction site, so deriving {@code service.name}
 * from it would give those two services two {@code job} labels each, which is the defect this
 * wiring exists to prevent. {@link TelemetryProperties#stage()} remains as an explicit override and
 * outranks the leaf when it is set, so a service that must publish under another name can say so
 * once, visibly, in its own config.
 *
 * <p>When neither yields a token the context FAILS TO START. A service publishing under a guessed
 * name is worse than one publishing nothing, because the series looks healthy and names no stage.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "dcre.telemetry", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TelemetryProperties.class)
public class TelemetryAutoConfiguration {

    @Bean
    StageIdentity stageIdentity(final TelemetryProperties properties, final ListableBeanFactory beans) {
        return StageIdentity.of(resolveStageToken(properties, beans), System.getenv("HOSTNAME"));
    }

    /**
     * Prometheus derives {@code job} from {@code service.name} and {@code instance} from
     * {@code service.instance.id}. Both are stamped as common tags so every meter the service
     * registers carries them, whoever registered it.
     */
    @Bean
    MeterFilter stageIdentityFilter(final StageIdentity identity) {
        return MeterFilter.commonTags(List.of(
                Tag.of("service.name", identity.serviceName()),
                Tag.of("service.instance.id", identity.instanceId())));
    }

    static String resolveStageToken(final TelemetryProperties properties, final ListableBeanFactory beans) {
        final String configured = properties.stage();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        final Set<String> leaves = applicationPackageLeaves(beans);
        if (leaves.size() == 1) {
            return leaves.iterator().next();
        }
        if (leaves.isEmpty()) {
            throw new IllegalStateException(
                    "dcre.telemetry.enabled=true but no stage token could be resolved: this context "
                    + "holds no bean annotated @SpringBootApplication to take a package leaf from. "
                    + "Set dcre.telemetry.stage explicitly. Publishing under a guessed name is worse "
                    + "than publishing nothing, because the series looks healthy and names no stage");
        }
        throw new IllegalStateException(
                "dcre.telemetry.enabled=true resolved more than one application package leaf "
                + leaves + ": a service with two service.name values publishes two job labels, which "
                + "is the defect this wiring exists to prevent. Set dcre.telemetry.stage explicitly");
    }

    private static Set<String> applicationPackageLeaves(final ListableBeanFactory beans) {
        final Set<String> leaves = new LinkedHashSet<>();
        for (final String name : beans.getBeanNamesForAnnotation(SpringBootApplication.class)) {
            final Class<?> type = beans.getType(name);
            if (type == null) {
                continue;
            }
            final String pkg = ClassUtils.getUserClass(type).getPackageName();
            final String leaf = pkg.substring(pkg.lastIndexOf('.') + 1);
            if (!leaf.isBlank()) {
                leaves.add(leaf);
            }
        }
        return leaves;
    }
}
