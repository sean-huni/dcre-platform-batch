package za.co.fnb.dcre.platform.batch.config;

import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.ConditionalOnEnabledMetricsExport;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetryResourceAttributes;
import org.springframework.boot.support.EnvironmentPostProcessorApplicationListener;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import za.co.fnb.dcre.platform.batch.config.properties.TelemetryProperties;
import za.co.fnb.dcre.platform.batch.fixture.crg.SampleApplication;
import za.co.fnb.dcre.platform.batch.telemetry.StageIdentity;

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
 * <p>So every identity assertion below reads the effect through Boot's OWN
 * {@link OpenTelemetryResourceAttributes}, which is the class
 * {@code OtlpMetricsPropertiesConfigAdapter.resourceAttributes()} uses, rather than through the
 * property keys this code happens to write.
 */
class TelemetryEnvironmentPostProcessorTest {

    private static final String NAME_KEY = TelemetryEnvironmentPostProcessor.SERVICE_NAME_KEY;
    private static final String RESOURCE_NAME_KEY =
            TelemetryEnvironmentPostProcessor.RESOURCE_SERVICE_NAME_KEY;
    private static final String INSTANCE_ID_KEY = TelemetryEnvironmentPostProcessor.INSTANCE_ID_KEY;
    private static final String EXPORT_ENABLED_KEY =
            TelemetryEnvironmentPostProcessor.EXPORT_ENABLED_KEY;

    @AfterEach
    void noTestMayLeakIntoTheJvmWideSystemProperties() {
        // Earned: the first green run of this class failed five tests with
        // `expected "dcre-crg" but was "dcre-set-by-hand"`, because StandardEnvironment
        // .getSystemProperties() returns the LIVE System.getProperties() map and one test's setup
        // leaked into every other. As an @AfterEach this is order-independent and names the guilty
        // test, where an ordinary @Test would depend on running last.
        assertThat(System.getProperty(NAME_KEY)).isNull();
        assertThat(System.getProperty(TelemetryProperties.STAGE)).isNull();
        assertThat(System.getProperty(TelemetryProperties.ENABLED)).isNull();
    }

    @Test
    void namesTheServiceFromTheApplicationPackageLeafWhenNoExplicitStageIsSet() {
        final ConfigurableEnvironment env = process(SampleApplication.class);

        assertThat(env.getProperty(NAME_KEY)).isEqualTo("dcre-crg");
        assertThat(resourceAttributes(env)).containsEntry("service.name", "dcre-crg");
    }

    @Test
    void setsTheNameThroughTheStrongestChannelToo() {
        // Precedence read from OpenTelemetryResourceAttributes bytecode: configured resource
        // attributes, then OTEL_SERVICE_NAME and OTEL_RESOURCE_ATTRIBUTES, then
        // spring.application.name, then unknown_service. Using only the weak channel would let
        // anything exporting OTEL_SERVICE_NAME to stage pods displace the validated name while
        // leaving the instance id pointing at the derived one.
        final ConfigurableEnvironment env = process(SampleApplication.class);

        assertThat(env.getProperty(RESOURCE_NAME_KEY)).isEqualTo("dcre-crg");
    }

    @Test
    void theStrongChannelOutranksTheWeakOneWhenTheyDisagree() {
        // The load-bearing half of setting both: a configured resource attribute must beat
        // spring.application.name, measured through Boot's own resolution rather than asserted.
        // Here the service has renamed ITSELF and the export still carries the validated name.
        // (The OTEL_SERVICE_NAME rung of that precedence is read from bytecode and not executed:
        // the constructor that accepts a fake system environment is package-private in Boot.)
        final ConfigurableEnvironment env = configured(NAME_KEY, "dcre-set-by-hand");

        process(env, SampleApplication.class);

        assertThat(resourceAttributes(env)).containsEntry("service.name", "dcre-crg");
    }

    @Test
    void anExplicitStageOutranksThePackageLeaf() {
        final ConfigurableEnvironment env = configured(TelemetryProperties.STAGE, "mrv");

        process(env, SampleApplication.class);

        assertThat(env.getProperty(NAME_KEY)).isEqualTo("dcre-mrv");
        assertThat(resourceAttributes(env)).containsEntry("service.name", "dcre-mrv");
    }

    @Test
    void publishesTheInstanceIdAsAnOpenTelemetryResourceAttribute() {
        final ConfigurableEnvironment env = process(SampleApplication.class);

        final String published = env.getProperty(INSTANCE_ID_KEY);
        assertThat(published).isNotBlank();
        // The VALUE, not merely its presence: it must survive Boot's map binding unchanged and land
        // as a RESOURCE attribute, not merely exist as a property key that looks right.
        assertThat(resourceAttributes(env)).containsEntry("service.instance.id", published);
    }

    @Test
    void neverPublishesTheOpenTelemetryDefaultServiceName() {
        // unknown_service is what the fleet published before this, identically, from all 30 services.
        assertThat(resourceAttributes(process(SampleApplication.class)))
                .doesNotContainEntry("service.name", "unknown_service");
    }

    @Test
    void exportStaysOffWhenTheMarkerIsAbsent() {
        assertThat(process(SampleApplication.class).getProperty(EXPORT_ENABLED_KEY))
                .isEqualTo("false");
    }

    @Test
    void exportStaysOffWhenTheMarkerIsFalse() {
        final ConfigurableEnvironment env = configured(TelemetryProperties.ENABLED, "false");

        process(env, SampleApplication.class);

        assertThat(env.getProperty(EXPORT_ENABLED_KEY)).isEqualTo("false");
    }

    @Test
    void exportStaysOffWhenTheMarkerIsTheEMPTYSTRING() {
        // The C1 case. An ordinary Kubernetes `env:` entry or a valueless ConfigMap key produces "".
        // Deferred to a ${dcre.telemetry.enabled:false} placeholder this resolves to "", and Boot's
        // gate then decides on whether that converts to a Boolean, defaulting to TRUE. Resolved in
        // Java it is total: Boolean.parseBoolean("") is false.
        final ConfigurableEnvironment env = configured(TelemetryProperties.ENABLED, "");

        process(env, SampleApplication.class);

        assertThat(env.getProperty(EXPORT_ENABLED_KEY)).isEqualTo("false");
    }

    @Test
    void exportStaysOffOnAGarbageMarkerValue() {
        final ConfigurableEnvironment env = configured(TelemetryProperties.ENABLED, "yes-please");

        process(env, SampleApplication.class);

        assertThat(env.getProperty(EXPORT_ENABLED_KEY)).isEqualTo("false");
    }

    @Test
    void exportTurnsOnWhenTheMarkerIsTrue() {
        final ConfigurableEnvironment env = configured(TelemetryProperties.ENABLED, "true");

        process(env, SampleApplication.class);

        assertThat(env.getProperty(EXPORT_ENABLED_KEY)).isEqualTo("true");
    }

    @Test
    void exportTurnsOnWhenTheMarkerIsUppercaseTRUE() {
        final ConfigurableEnvironment env = configured(TelemetryProperties.ENABLED, "TRUE");

        process(env, SampleApplication.class);

        assertThat(env.getProperty(EXPORT_ENABLED_KEY)).isEqualTo("true");
    }

    @Test
    void bootsOwnExportGateFailsOpenOnAnEmptyValue() {
        // MEASURED, not read from the converter contract, because the whole C1 defect turns on it.
        // Same annotation and same property Boot uses to gate OTLP export. An empty value must be
        // shown to MATCH, which is what makes deferring the decision to a placeholder unsafe.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OtlpExportGateProbe.class))
                .withPropertyValues(EXPORT_ENABLED_KEY + "=")
                .run(ctx -> assertThat(ctx).hasBean("otlpExportWouldBeOn"));

        // Control: the same probe with the value this library now writes.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OtlpExportGateProbe.class))
                .withPropertyValues(EXPORT_ENABLED_KEY + "=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean("otlpExportWouldBeOn"));
    }

    @Test
    void doesNotOverwriteAServiceThatNamesItself() {
        // Contributed as DEFAULTS, added last, so explicit config always outranks them.
        final ConfigurableEnvironment env = configured(NAME_KEY, "dcre-set-by-hand");

        process(env, SampleApplication.class);

        assertThat(env.getProperty(NAME_KEY)).isEqualTo("dcre-set-by-hand");
    }

    @Test
    void contributesNoIdentityAndDoesNotFailWhenNoApplicationClassIsResolvable() {
        // This runs for EVERY Boot application that imports the library, including ones with no
        // fleet-shaped package. Failing here would break them all at startup; the autoconfiguration
        // is where an unresolvable stage is fatal, and only when the marker asks for telemetry.
        final ConfigurableEnvironment env = new StandardEnvironment();

        process(env, String.class);

        assertThat(env.getProperty(NAME_KEY)).isNull();
        assertThat(env.getProperty(INSTANCE_ID_KEY)).isNull();
        // The gate is independent of identity and must still be contributed.
        assertThat(env.getProperty(EXPORT_ENABLED_KEY)).isEqualTo("false");
    }

    @Test
    void failsOnAPerJobStageTokenRatherThanPublishingASecondJobLabel() {
        final ConfigurableEnvironment env =
                configured(TelemetryProperties.STAGE, "mrv-account-reference");

        process(env, za.co.fnb.dcre.platform.batch.fixture.mrv.SampleApplication.class);

        // Tolerant by design: it publishes nothing rather than publishing a second job label, and
        // TelemetryAutoConfiguration fails the service at startup when the marker is on.
        assertThat(env.getProperty(NAME_KEY)).isNull();
        assertThat(resourceAttributes(env)).doesNotContainKey("service.instance.id");
    }

    @Test
    void isDiscoveredThroughSpringFactoriesByBootsOwnListener() {
        // Real discovery through META-INF/spring.factories and Boot's real ordering, so this fails
        // if the registration file is missing, misnamed, or keyed on the deprecated interface.
        final ConfigurableEnvironment env = new StandardEnvironment();
        final SpringApplication application = new SpringApplication(SampleApplication.class);

        new EnvironmentPostProcessorApplicationListener().onApplicationEvent(
                new ApplicationEnvironmentPreparedEvent(
                        new DefaultBootstrapContext(), application, new String[0], env));

        assertThat(env.getProperty(NAME_KEY)).isEqualTo("dcre-crg");
    }

    @Test
    void theBeanAgreesWithWhatWasActuallyPublishedToTheExporter() {
        // One fact, two halves: the post-processor writes the resource attributes the exporter uses,
        // and the autoconfiguration publishes the bean carrying the same identity. Nothing in this
        // library consumes that bean yet: Task 4 turned out to need no flush and so no consumer.
        // The bean reads the published values back rather than re-deriving them, and this proves it.
        final ConfigurableEnvironment published = process(SampleApplication.class);
        final Map<String, String> exported = resourceAttributes(published);

        new ApplicationContextRunner()
                // Fixture: every consumer carries an OpenTelemetry bean, so the appender installer
                // in TelemetryAutoConfiguration can run. Without it this context is refused by name
                // and the identity assertion below would never be reached.
                .withBean(OpenTelemetry.class, OpenTelemetry::noop)
                .withConfiguration(AutoConfigurations.of(TelemetryAutoConfiguration.class))
                .withPropertyValues(
                        TelemetryProperties.ENABLED + "=true",
                        NAME_KEY + "=" + published.getProperty(NAME_KEY),
                        INSTANCE_ID_KEY + "=" + published.getProperty(INSTANCE_ID_KEY))
                .run(ctx -> {
                    final StageIdentity bean = ctx.getBean(StageIdentity.class);
                    assertThat(bean.serviceName()).isEqualTo(exported.get("service.name"));
                    assertThat(bean.instanceId()).isEqualTo(exported.get("service.instance.id"));
                });
    }

    /** Boot's real OTLP export gate, on a trivial bean, so its behaviour can be measured. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnEnabledMetricsExport("otlp")
    static class OtlpExportGateProbe {

        @Bean
        String otlpExportWouldBeOn() {
            return "on";
        }
    }

    /**
     * Explicit configuration, as its own highest-precedence property source.
     *
     * <p>NOT {@code environment.getSystemProperties().put(...)}: that returns the LIVE
     * {@code System.getProperties()} map, which is the leak the {@code @AfterEach} above guards.
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

    private static Map<String, String> configuredResourceAttributes(
            final ConfigurableEnvironment environment) {
        return Binder.get(environment)
                .bind("management.opentelemetry.resource-attributes",
                        Bindable.mapOf(String.class, String.class))
                .orElseGet(Map::of);
    }

    /** What Boot actually hands the OTLP registry, via the class its config adapter uses. */
    private static Map<String, String> resourceAttributes(final ConfigurableEnvironment environment) {
        final Map<String, String> resolved = new LinkedHashMap<>();
        new OpenTelemetryResourceAttributes(environment, configuredResourceAttributes(environment))
                .applyTo(resolved::put);
        return resolved;
    }
}
