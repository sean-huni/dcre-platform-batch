package za.co.fnb.dcre.platform.batch.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

import za.co.fnb.dcre.platform.batch.config.properties.TelemetryProperties;
import za.co.fnb.dcre.platform.batch.telemetry.StageIdentity;

import java.util.Map;

/**
 * Publishes the stage's telemetry identity as a bean, and refuses a telemetry configuration that
 * would publish silence. Backs off unless {@code dcre.telemetry.enabled=true}, following the same
 * rule as
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

    /**
     * Boot's own key for the OTLP aggregation temporality, bound into
     * {@code OtlpMetricsProperties} under prefix {@code management.otlp.metrics.export}. Declared
     * here rather than beside the post-processor's keys because this is the unit that WRITES it;
     * the post-processor's constants are the four it publishes for every consumer, and this is not
     * one of them.
     */
    static final String TEMPORALITY_KEY = "management.otlp.metrics.export.aggregation-temporality";

    /** The only value that reports. One home for the fact: the default and the guard share it. */
    static final String CUMULATIVE = "cumulative";

    static final String TEMPORALITY_PROPERTY_SOURCE = "dcre-telemetry-temporality";

    static String notCumulative(final String configured) {
        return TEMPORALITY_KEY + " is '" + configured + "'. Only " + CUMULATIVE + " reports through "
               + "this stack: with delta the receiver accepts the payload with HTTP 200 and an empty "
               + "partialSuccess and stores none of it, so the service would run, look healthy and "
               + "publish silence, which is the one failure shape no dashboard can show. "
               + "Measured 2026-09-11";
    }

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

    /**
     * Pins cumulative temporality and REFUSES anything else at startup.
     *
     * <p>INHERITED MEASUREMENT (Task 1 spike, 2026-09-11), not re-measured here: with
     * {@code aggregation-temporality=delta} an orderly exit publishes NOTHING. The receiver accepts
     * the payload with HTTP 200 and an empty {@code partialSuccess} and stores none of it, so no
     * error appears in the application, in the collector or in a panel. A service configured that
     * way looks healthy and is invisible. The spike isolated it to the receiver rather than to
     * Micrometer by hand-posting a delta payload.
     *
     * <p><strong>The refusal is the control; the contributed default is only a pin.</strong> Boot
     * 4.1.0 already defaults to cumulative, read from the constructor bytecode of
     * {@code OtlpMetricsProperties} in {@code spring-boot-micrometer-metrics-4.1.0.jar}, so
     * contributing it buys nothing today and holds the value if that default ever moves. What closes
     * the defect is refusing an explicit delta, because the realistic override channel is an
     * environment variable nobody remembers setting and its consequence is silence rather than an
     * error.
     *
     * <p>Blank is treated as UNSET rather than refused, which is this library's one deviation from
     * its own fail-closed habit and is deliberate. An ordinary Kubernetes {@code env:} entry or a
     * valueless ConfigMap key produces {@code ""}, and binding {@code ""} to
     * {@code AggregationTemporality} was measured to yield NO value, so Boot falls back to its own
     * cumulative default and the service reports correctly. Refusing it would crash a working
     * service; a guard stricter than the thing it guards is a defect rather than caution.
     *
     * <p><strong>Why not the sibling mechanism.</strong>
     * {@link TelemetryEnvironmentPostProcessor} already contributes defaults conditionally on this
     * same marker, resolved in Java, so "it had to be conditional" is NOT why this lives here.
     * The reason is blast radius: an {@code EnvironmentPostProcessor} runs for every consumer that
     * merely has this jar on its classpath, so a THROWING guard there would be able to fail a
     * service that asked for no telemetry at all. Behind a conditional bean it can only fail a
     * service that asked for telemetry and then configured it to publish silence. The contribution
     * alone could have gone in the sibling; the refusal could not, and splitting the pin from the
     * guard would put one fact in two units.
     *
     * <p>Resolved through the {@link Binder}, which is the SAME reader Boot uses to bind
     * {@code OtlpMetricsProperties}, so the guard and the thing it guards cannot disagree by
     * construction. {@code Environment.getProperty} is relaxed only because
     * {@code ConfigurationPropertySources.attach} has added the {@code configurationProperties}
     * source, which every {@code SpringApplication} does; measured 2026-09-11, in a bare
     * {@code StandardEnvironment} it is an EXACT-match lookup that reads straight past
     * {@code aggregationTemporality}, {@code aggregation_temporality} and the upper-case dotted
     * form, finds this class's own contributed default, and passes. That was not a live defect,
     * because production always has the attached source, and depending on it implicitly is still
     * the wrong shape for a control.
     *
     * <p>{@code static}, so the enclosing configuration class is not instantiated early to build a
     * {@code BeanFactoryPostProcessor}. The contributed source is added LAST, so any service that
     * sets the key explicitly still wins the lookup, and is then refused by name if it chose delta.
     * The default arrives too late for a {@code @ConditionalOnProperty} on it, which nothing in Boot
     * has, and comfortably before {@code OtlpMetricsProperties} is bound, which happens at bean
     * creation.
     *
     * <p>A refusal throws during {@code refresh()}, before {@code ApplicationStartedEvent}, so
     * {@code RunnerPhaseGate} has not been reached and {@link za.co.fnb.dcre.platform.batch.ExitCodeMain}
     * reserves exit {@code 78}. AGT classifies that as {@code TECH_CONFIG_FAILED}, which is the
     * correct bucket: this is a configuration fault, not a job verdict.
     */
    @Bean
    static BeanFactoryPostProcessor cumulativeTemporalityPinAndGuard(
            final ConfigurableEnvironment environment) {
        return beanFactory -> {
            environment.getPropertySources().addLast(new MapPropertySource(
                    TEMPORALITY_PROPERTY_SOURCE, Map.of(TEMPORALITY_KEY, CUMULATIVE)));
            // The same constant is the contributed default AND the fallback here, so the two can
            // never drift; the guard therefore still bites if the contribution is ever removed.
            final String configured =
                    Binder.get(environment).bind(TEMPORALITY_KEY, String.class).orElse(CUMULATIVE);
            if (!configured.isBlank() && !CUMULATIVE.equalsIgnoreCase(configured)) {
                throw new IllegalStateException(notCumulative(configured));
            }
        };
    }
}
