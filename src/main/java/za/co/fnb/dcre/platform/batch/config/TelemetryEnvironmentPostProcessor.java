package za.co.fnb.dcre.platform.batch.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import za.co.fnb.dcre.platform.batch.config.properties.TelemetryProperties;
import za.co.fnb.dcre.platform.batch.telemetry.StageIdentity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Contributes the stage's telemetry identity as PROPERTIES, before any meter registry is built.
 *
 * <p>[!CONVENTION-OVERRIDE] This library's family pattern for contributing configuration is an
 * OPT-IN classpath resource: {@code dcre-exchange-layout.yml}, which a writer service imports with
 * {@code spring.config.import} and which nothing else ever sees. This class is the first thing here
 * that writes into EVERY consumer's Environment whether they asked or not, and it falsifies the
 * README's line that Spring Boot is {@code compileOnly} so the library stays "a pure convention
 * carrier". Both the README and that line are corrected in the same commit. The deviation is taken
 * deliberately: opt-in cannot work here, because the value being contributed is the service's own
 * identity and the failure mode of forgetting to opt in is silent. A service that omitted the import
 * would publish as {@code unknown_service}, look healthy, and collapse into one shared series with
 * every other service that forgot. Canon: 12FactorApp Alignment, https://12factor.net/, config from
 * the environment. The escape hatch is that everything here is a DEFAULT added last, so any service
 * can override any of it.
 *
 * <p>This has to be a post-processor and cannot be a {@code MeterFilter}. {@code OtlpMeterRegistry}
 * holds {@code private final io.opentelemetry.proto.resource.v1.Resource resource}, built once in
 * its constructor from {@code OtlpConfig.resourceAttributes()}, and a filter only ever sees a
 * {@code Meter.Id} afterwards. Common tags become DATAPOINT attributes; Prometheus derives
 * {@code job} from the RESOURCE attribute {@code service.name} and {@code instance} from
 * {@code service.instance.id}.
 *
 * <p>Four properties are contributed, in one property source added LAST so a service that
 * configures any of them explicitly still wins:
 *
 * <ul>
 * <li>{@code management.opentelemetry.resource-attributes.service.name}, the STRONGEST channel.
 *     Read from the bytecode of {@code OpenTelemetryResourceAttributes}, the precedence is
 *     configured resource attributes, then {@code OTEL_SERVICE_NAME} and
 *     {@code OTEL_RESOURCE_ATTRIBUTES}, then {@code spring.application.name}, then the literal
 *     {@code unknown_service}. Setting only the weak channel would let anything exporting
 *     {@code OTEL_SERVICE_NAME} to stage pods silently displace the validated name while leaving
 *     the instance id pointing at the derived one.</li>
 * <li>{@code spring.application.name}, the same value again, because it is what a human reads in
 *     logs and what other Boot machinery keys on. It carries the full validated
 *     {@link StageIdentity#serviceName()}, for example {@code dcre-crg}, not the bare package leaf:
 *     the leaf is where the value comes FROM, and {@code dcre-<leaf>} is the fleet-wide name every
 *     other part of this design validates.</li>
 * <li>{@code management.opentelemetry.resource-attributes.service.instance.id}, so each replica is
 *     its own series instead of every execution collapsing into one.</li>
 * <li>{@code management.otlp.metrics.export.enabled}, because identity being right is not the same
 *     as export being wanted. Boot's own OTLP export activates on the JAR being present, which no
 *     property marker of ours can refuse, so the marker is made to control it here instead.</li>
 * </ul>
 *
 * <p><strong>The export flag is resolved in Java and never deferred to a placeholder.</strong> A
 * value of {@code ${dcre.telemetry.enabled:false}} fails OPEN. Boot's gate is
 * {@code @ConditionalOnEnabledMetricsExport("otlp")}, whose condition reads
 * {@code management.otlp.metrics.export.enabled} and defaults to {@code true}; because this library
 * ALWAYS contributes that key, the outcome turns entirely on whether the resolved value converts to
 * a Boolean. A marker set to the empty string, which is what an ordinary Kubernetes {@code env:}
 * entry or a valueless ConfigMap key produces, would resolve the placeholder to {@code ""} and take
 * that default. {@link Boolean#parseBoolean} is total: every string that is not case-insensitively
 * {@code "true"}, including null, empty and garbage, is {@code false}.
 *
 * <p>Setting the name UNCONDITIONALLY is the point rather than an oversight. An export that happens
 * without the marker now publishes under the correct name instead of collapsing 30 services into one
 * {@code unknown_service} series, which turns a fleet-wide defect into correct default behaviour
 * rather than merely suppressing it.
 *
 * <p>Ordered last so {@code ConfigDataEnvironmentPostProcessor} (order
 * {@code Integer.MIN_VALUE + 10}) has already loaded {@code application.yml} and an explicitly
 * configured {@code dcre.telemetry.stage} is visible.
 */
public class TelemetryEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** This library's publication contract. {@link TelemetryAutoConfiguration} reads these back. */
    static final String SERVICE_NAME_KEY = "spring.application.name";

    static final String RESOURCE_SERVICE_NAME_KEY =
            "management.opentelemetry.resource-attributes.service.name";

    static final String INSTANCE_ID_KEY =
            "management.opentelemetry.resource-attributes.service.instance.id";

    static final String EXPORT_ENABLED_KEY = "management.otlp.metrics.export.enabled";

    static final String PROPERTY_SOURCE_NAME = "dcre-telemetry-defaults";

    @Override
    public void postProcessEnvironment(final ConfigurableEnvironment environment,
                                       final SpringApplication application) {
        final Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put(EXPORT_ENABLED_KEY, String.valueOf(
                Boolean.parseBoolean(environment.getProperty(TelemetryProperties.ENABLED))));

        final Optional<StageIdentity> identity = StageIdentityResolver.resolveQuietly(
                environment.getProperty(TelemetryProperties.STAGE),
                StageIdentityResolver.candidatesFrom(application),
                System.getenv("HOSTNAME"));
        identity.ifPresent(stage -> {
            defaults.put(RESOURCE_SERVICE_NAME_KEY, stage.serviceName());
            defaults.put(SERVICE_NAME_KEY, stage.serviceName());
            defaults.put(INSTANCE_ID_KEY, stage.instanceId());
        });

        environment.getPropertySources()
                .addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
