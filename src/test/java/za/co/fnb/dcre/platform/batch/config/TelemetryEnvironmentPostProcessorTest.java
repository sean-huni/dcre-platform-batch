package za.co.fnb.dcre.platform.batch.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetryResourceAttributes;
import org.springframework.boot.support.EnvironmentPostProcessorApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import za.co.fnb.dcre.platform.batch.telemetry.StageIdentity;

import za.co.fnb.dcre.crg.CrgApplication;
import za.co.fnb.dcre.mrv.MrvApplication;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The identity has to be contributed as PROPERTIES, before any registry bean exists.
 *
 * <p>A {@code MeterFilter} provably cannot do it: {@code OtlpMeterRegistry} holds
 * {@code private final io.opentelemetry.proto.resource.v1.Resource resource}, built once at
 * construction from {@code OtlpConfig.resourceAttributes()}, while a filter operates on a
 * {@code Meter.Id} afterwards. Common tags become DATAPOINT attributes; Prometheus derives
 * {@code job} and {@code instance} from the RESOURCE.
 *
 * <p>So every assertion below reads the effect through Boot's OWN
 * {@link OpenTelemetryResourceAttributes}, which is the class
 * {@code OtlpMetricsPropertiesConfigAdapter.resourceAttributes()} uses, rather than through the
 * property keys this code happens to write.
 */
class TelemetryEnvironmentPostProcessorTest {

    private static final String INSTANCE_ID_KEY =
            "management.opentelemetry.resource-attributes.service.instance.id";
    private static final String EXPORT_ENABLED_KEY = "management.otlp.metrics.export.enabled";

    @Test
    void namesTheServiceFromTheApplicationPackageLeafWhenNoExplicitStageIsSet() {
        final ConfigurableEnvironment env = process(CrgApplication.class);

        assertThat(env.getProperty("spring.application.name")).isEqualTo("dcre-crg");
        assertThat(resourceAttributes(env)).containsEntry("service.name", "dcre-crg");
    }

    @Test
    void anExplicitStageOutranksThePackageLeaf() {
        final ConfigurableEnvironment env = configured("dcre.telemetry.stage", "mrv");

        process(env, CrgApplication.class);

        assertThat(env.getProperty("spring.application.name")).isEqualTo("dcre-mrv");
        assertThat(resourceAttributes(env)).containsEntry("service.name", "dcre-mrv");
    }

    @Test
    void publishesTheInstanceIdAsAnOpenTelemetryResourceAttribute() {
        final ConfigurableEnvironment env = process(CrgApplication.class);

        final String published = env.getProperty(INSTANCE_ID_KEY);
        assertThat(published).isNotBlank();
        // The whole point: it must survive Boot's own binding and land as a RESOURCE attribute,
        // not merely exist as a property key that looks right.
        assertThat(resourceAttributes(env)).containsEntry("service.instance.id", published);
    }

    @Test
    void neverPublishesTheOpenTelemetryDefaultServiceName() {
        // unknown_service is what the fleet publishes today, identically, from all 30 services.
        assertThat(resourceAttributes(process(CrgApplication.class)))
                .doesNotContainEntry("service.name", "unknown_service");
    }

    @Test
    void exportStaysOffWhenTheMarkerIsAbsent() {
        assertThat(process(CrgApplication.class).getProperty(EXPORT_ENABLED_KEY)).isEqualTo("false");
    }

    @Test
    void exportStaysOffWhenTheMarkerIsFalse() {
        final ConfigurableEnvironment env = configured("dcre.telemetry.enabled", "false");

        process(env, CrgApplication.class);

        assertThat(env.getProperty(EXPORT_ENABLED_KEY)).isEqualTo("false");
    }

    @Test
    void exportTurnsOnWhenTheMarkerIsTrue() {
        final ConfigurableEnvironment env = configured("dcre.telemetry.enabled", "true");

        process(env, CrgApplication.class);

        assertThat(env.getProperty(EXPORT_ENABLED_KEY)).isEqualTo("true");
    }

    @Test
    void defersToAServiceThatNamesItself() {
        // Contributed as DEFAULTS, added last, so explicit config always outranks them.
        final ConfigurableEnvironment env = configured("spring.application.name", "dcre-set-by-hand");

        process(env, CrgApplication.class);

        assertThat(env.getProperty("spring.application.name")).isEqualTo("dcre-set-by-hand");
    }

    @Test
    void contributesNoIdentityAndDoesNotFailWhenNoApplicationClassIsResolvable() {
        // This runs for EVERY Boot application that imports the library, including ones with no
        // fleet-shaped package. Failing here would break them all at startup; the autoconfiguration
        // is where an unresolvable stage is fatal, and only when the marker asks for telemetry.
        final ConfigurableEnvironment env = new StandardEnvironment();

        process(env, String.class);

        assertThat(env.getProperty("spring.application.name")).isNull();
        assertThat(env.getProperty(INSTANCE_ID_KEY)).isNull();
        // The gate is independent of identity and must still be contributed.
        assertThat(env.getProperty(EXPORT_ENABLED_KEY)).isEqualTo("false");
    }

    @Test
    void failsOnAPerJobStageTokenRatherThanPublishingASecondJobLabel() {
        final ConfigurableEnvironment env = configured("dcre.telemetry.stage", "mrv-account-reference");

        process(env, MrvApplication.class);

        // Tolerant by design: it publishes nothing rather than publishing a second job label, and
        // TelemetryAutoConfiguration fails the service at startup when the marker is on.
        assertThat(env.getProperty("spring.application.name")).isNull();
        assertThat(resourceAttributes(env)).doesNotContainKey("service.instance.id");
    }

    @Test
    void isDiscoveredThroughSpringFactoriesByBootsOwnListener() {
        // Real discovery through META-INF/spring.factories and Boot's real ordering, so this fails
        // if the registration file is missing, misnamed, or keyed on the deprecated interface.
        final ConfigurableEnvironment env = new StandardEnvironment();
        final SpringApplication application = new SpringApplication(CrgApplication.class);

        new EnvironmentPostProcessorApplicationListener().onApplicationEvent(
                new ApplicationEnvironmentPreparedEvent(
                        new DefaultBootstrapContext(), application, new String[0], env));

        assertThat(env.getProperty("spring.application.name")).isEqualTo("dcre-crg");
    }


    @Test
    void theBeanAgreesWithWhatWasActuallyPublishedToTheExporter() {
        // One fact, two halves: the post-processor writes the resource attributes the exporter uses,
        // the autoconfiguration publishes the bean Task 4 consumes. If they can disagree, the bean is
        // a lie about what the fleet is publishing. They diverge most easily on the instance id,
        // because StageIdentity.of mints a fresh local-<uuid> whenever HOSTNAME is unset, which is
        // every developer laptop and every one of these tests.
        final ConfigurableEnvironment published = process(CrgApplication.class);
        final Map<String, String> exported = resourceAttributes(published);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TelemetryAutoConfiguration.class))
                .withUserConfiguration(TelemetryAutoConfigurationTest.OneApplicationBean.class)
                .withPropertyValues(
                        "dcre.telemetry.enabled=true",
                        INSTANCE_ID_KEY + "=" + published.getProperty(INSTANCE_ID_KEY))
                .run(ctx -> {
                    final StageIdentity bean = ctx.getBean(StageIdentity.class);
                    assertThat(bean.serviceName()).isEqualTo(exported.get("service.name"));
                    assertThat(bean.instanceId()).isEqualTo(exported.get("service.instance.id"));
                });
    }

    @Test
    void leavesNoTestConfigurationInTheJvmWideSystemProperties() {
        // The guard for the harness bug above: if any test in this class starts mutating
        // System.getProperties() again, this fails instead of a neighbouring test failing.
        assertThat(System.getProperty("spring.application.name")).isNull();
        assertThat(System.getProperty("dcre.telemetry.stage")).isNull();
        assertThat(System.getProperty("dcre.telemetry.enabled")).isNull();
    }

    /**
     * Explicit configuration, as its own highest-precedence property source.
     *
     * <p>NOT {@code environment.getSystemProperties().put(...)}: that returns the LIVE
     * {@code System.getProperties()} map, so one test setting {@code spring.application.name} leaked
     * into every other test in the class and four of them failed against a value they never set.
     * The environment is part of the fixture.
     */
    private static ConfigurableEnvironment configured(final String key, final String value) {
        final ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources()
                .addFirst(new MapPropertySource("explicit-config", Map.of(key, value)));
        return environment;
    }

    private static ConfigurableEnvironment process(final Class<?>... sources) {
        return process(new StandardEnvironment(), sources);
    }

    private static ConfigurableEnvironment process(final ConfigurableEnvironment environment,
                                                   final Class<?>... sources) {
        new TelemetryEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication(sources));
        return environment;
    }

    /** What Boot actually hands the OTLP registry, via the class its config adapter uses. */
    private static Map<String, String> resourceAttributes(final ConfigurableEnvironment environment) {
        final Map<String, String> configured = Binder.get(environment)
                .bind("management.opentelemetry.resource-attributes",
                        Bindable.mapOf(String.class, String.class))
                .orElseGet(Map::of);
        final Map<String, String> resolved = new LinkedHashMap<>();
        new OpenTelemetryResourceAttributes(environment, configured).applyTo(resolved::put);
        return resolved;
    }
}
