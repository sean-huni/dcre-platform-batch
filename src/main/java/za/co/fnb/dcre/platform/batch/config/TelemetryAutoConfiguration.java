package za.co.fnb.dcre.platform.batch.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import za.co.fnb.dcre.platform.batch.config.properties.TelemetryProperties;
import za.co.fnb.dcre.platform.batch.telemetry.StageIdentity;

/**
 * Publishes the stage's telemetry identity as a bean, for code that needs it (Task 4's flush
 * listener). Backs off unless {@code dcre.telemetry.enabled=true}, following the same rule as
 * {@link ExchangeAutoConfiguration}: no environment variable the orchestrator exports canonicalises
 * to that key, so this cannot activate by accident on a service that carries no telemetry config.
 *
 * <p><strong>It resolves nothing.</strong> It READS BACK exactly what
 * {@link TelemetryEnvironmentPostProcessor} published, so the bean cannot name the service
 * differently from what is actually being exported. Deriving it a second time, from the bean factory,
 * was the previous shape and was wrong twice over: the published values are DEFAULTS added last, so
 * a service that sets its own {@code spring.application.name} would have won for the export while
 * the bean still said {@code dcre-<leaf>}, and no fixture could express that disagreement because
 * both halves derived the same value independently.
 *
 * <p>So a service that renames itself and asks for telemetry FAILS AT STARTUP, by name. That is the
 * intended answer rather than a gap: {@code spring.application.name} is the channel a service is
 * most likely to change, the fleet-wide name is validated against {@code dcre-[a-z]+}, and a stage
 * publishing under a name nobody can map back to a service is the defect this whole task exists to
 * remove. The escape hatch is {@code dcre.telemetry.stage}, which the post-processor honours.
 *
 * <p>There is no {@code MeterFilter} here any more. Common tags become OTLP DATAPOINT attributes,
 * never resource attributes, so they never set {@code job} or {@code instance}; they duplicated the
 * identity onto every series, and once the divergence above was possible they could have put
 * {@code job="dcre-set-by-hand"} beside {@code service_name="dcre-crg"} on the same series. Their
 * stated justification named a Prometheus scrape path this fleet does not have: no build file in
 * the tree carries {@code micrometer-registry-prometheus}, and these are one-shot Job processes
 * with nothing to scrape them.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = TelemetryProperties.PREFIX, name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TelemetryProperties.class)
public class TelemetryAutoConfiguration {

    static final String NAME_NOT_PUBLISHED =
            "dcre.telemetry.enabled=true but " + TelemetryEnvironmentPostProcessor.SERVICE_NAME_KEY
            + " is not set. TelemetryEnvironmentPostProcessor publishes it from the application's "
            + "package leaf; if it did not run, this context was not started by SpringApplication. "
            + "Set " + TelemetryProperties.STAGE + " explicitly, or start the service normally";

    static final String INSTANCE_NOT_PUBLISHED =
            "dcre.telemetry.enabled=true but " + TelemetryEnvironmentPostProcessor.INSTANCE_ID_KEY
            + " is not set, so every replica of this service would be written into ONE series and "
            + "each new pod's counters would read as a counter reset";

    @Bean
    StageIdentity stageIdentity(final Environment environment) {
        final String serviceName =
                environment.getProperty(TelemetryEnvironmentPostProcessor.SERVICE_NAME_KEY);
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalStateException(NAME_NOT_PUBLISHED);
        }
        final String instanceId =
                environment.getProperty(TelemetryEnvironmentPostProcessor.INSTANCE_ID_KEY);
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalStateException(INSTANCE_NOT_PUBLISHED);
        }
        // The canonical constructor, not of(): the name is already prefixed. It still validates
        // both components, so a service that renamed itself fails here by name.
        return new StageIdentity(serviceName, instanceId);
    }
}
