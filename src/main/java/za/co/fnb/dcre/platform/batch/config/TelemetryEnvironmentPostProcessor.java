package za.co.fnb.dcre.platform.batch.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import za.co.fnb.dcre.platform.batch.telemetry.StageIdentity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Contributes the stage's telemetry identity as PROPERTIES, before any meter registry is built.
 *
 * <p>This has to be a post-processor and cannot be a {@code MeterFilter}. {@code OtlpMeterRegistry}
 * holds {@code private final io.opentelemetry.proto.resource.v1.Resource resource}, built once in
 * its constructor from {@code OtlpConfig.resourceAttributes()}, and a filter only ever sees a
 * {@code Meter.Id} afterwards. Common tags become DATAPOINT attributes; Prometheus derives
 * {@code job} from the RESOURCE attribute {@code service.name} and {@code instance} from
 * {@code service.instance.id}. Verified against {@code micrometer-registry-otlp} 1.17.0 and
 * {@code OpenTelemetryResourceAttributes} in {@code spring-boot-opentelemetry} 4.1.0, whose only
 * sources for {@code service.name} are the configured resource attributes, {@code OTEL_SERVICE_NAME},
 * {@code spring.application.name} and finally the literal {@code unknown_service}.
 *
 * <p>Three properties are contributed, as DEFAULTS added LAST so a service that configures any of
 * them explicitly still wins:
 *
 * <ul>
 * <li>{@code spring.application.name}, which Boot turns into the {@code service.name} resource
 *     attribute. It carries the full validated {@link StageIdentity#serviceName()}, for example
 *     {@code dcre-crg}, and not the bare package leaf: the leaf is where the value comes FROM, and
 *     {@code dcre-<leaf>} is the fleet-wide name every other part of this design validates.</li>
 * <li>{@code management.opentelemetry.resource-attributes.service.instance.id}, so each replica is
 *     its own series instead of every execution collapsing into one.</li>
 * <li>{@code management.otlp.metrics.export.enabled}, defaulted to
 *     {@code ${dcre.telemetry.enabled:false}}, because identity being right is not the same as
 *     export being wanted. Boot's own OTLP export activates on the JAR being present, which no
 *     property marker of ours can refuse, so the marker is made to control it here instead.</li>
 * </ul>
 *
 * <p>Setting the name UNCONDITIONALLY is the point rather than an oversight. An export that happens
 * without the marker now publishes under the correct name instead of collapsing 30 services into one
 * {@code unknown_service} series, which turns a fleet-wide defect into correct default behaviour
 * rather than merely suppressing it.
 *
 * <p>Ordered last so {@code ConfigDataEnvironmentPostProcessor} has already loaded
 * {@code application.yml} and an explicitly configured {@code dcre.telemetry.stage} is visible.
 */
public class TelemetryEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "dcre-telemetry-defaults";
    static final String EXPORT_ENABLED_KEY = "management.otlp.metrics.export.enabled";
    static final String EXPORT_ENABLED_DEFAULT = "${dcre.telemetry.enabled:false}";

    @Override
    public void postProcessEnvironment(final ConfigurableEnvironment environment,
                                       final SpringApplication application) {
        final Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put(EXPORT_ENABLED_KEY, EXPORT_ENABLED_DEFAULT);

        final Optional<StageIdentity> identity = StageIdentityResolver.resolveQuietly(
                environment.getProperty("dcre.telemetry.stage"),
                StageIdentityResolver.candidatesFrom(application),
                System.getenv("HOSTNAME"));
        identity.ifPresent(stage -> {
            defaults.put("spring.application.name", stage.serviceName());
            defaults.put(StageIdentityResolver.INSTANCE_ID_KEY, stage.instanceId());
        });

        environment.getPropertySources()
                .addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
