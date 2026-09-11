package za.co.fnb.dcre.platform.batch.config;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import za.co.fnb.dcre.platform.batch.config.properties.TelemetryProperties;
import za.co.fnb.dcre.platform.batch.telemetry.StageIdentity;

import java.util.List;

/**
 * Publishes the stage's telemetry identity as a bean, and stamps it onto every meter.
 *
 * <p>Backs off unless {@code dcre.telemetry.enabled=true}, following the same rule as
 * {@link ExchangeAutoConfiguration}: no environment variable the orchestrator exports canonicalises
 * to that key, so this cannot activate by accident on a service that carries no telemetry config.
 *
 * <p><strong>This is not where {@code job} and {@code instance} come from.</strong> Those are
 * RESOURCE attributes, contributed as properties before any registry exists by
 * {@link TelemetryEnvironmentPostProcessor}, because {@code OtlpMeterRegistry} builds its resource
 * once in its constructor and no {@code MeterFilter} can reach it. This class publishes the same
 * identity as a bean for code that needs it (Task 4's flush listener) and applies it as common
 * meter tags.
 *
 * <p>The stage token defaults to the PACKAGE LEAF of the class annotated
 * {@code @SpringBootApplication}, not to the token a service hands its seam listener. That listener
 * token is per-JOB: one service constructs it twice with different values and another passes a
 * variable at the construction site, so deriving {@code service.name} from it would give those two
 * services two {@code job} labels each. {@link TelemetryProperties#stage()} remains an explicit
 * override and outranks the leaf when set. When neither yields a token the context FAILS TO START,
 * because a service publishing under a guessed name is worse than one publishing nothing.
 *
 * <p>The rule itself lives in {@link StageIdentityResolver}, shared with the post-processor, so the
 * two halves cannot answer differently.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "dcre.telemetry", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TelemetryProperties.class)
public class TelemetryAutoConfiguration {

    @Bean
    StageIdentity stageIdentity(final TelemetryProperties properties,
                                final ListableBeanFactory beans,
                                final Environment environment) {
        return StageIdentityResolver.resolveOrThrow(
                properties.stage(),
                StageIdentityResolver.candidatesFrom(beans),
                StageIdentityResolver.instanceId(environment));
    }

    /**
     * Common tags, which become OTLP DATAPOINT attributes rather than resource attributes. They do
     * not set {@code job} or {@code instance}; the post-processor does that. They are kept so a
     * meter carries its own stage identity wherever it is read directly, for example through a
     * Prometheus scrape of the actuator endpoint rather than through the collector.
     */
    @Bean
    MeterFilter stageIdentityFilter(final StageIdentity identity) {
        return MeterFilter.commonTags(List.of(
                Tag.of("service.name", identity.serviceName()),
                Tag.of("service.instance.id", identity.instanceId())));
    }
}
