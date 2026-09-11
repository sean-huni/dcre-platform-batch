package za.co.fnb.dcre.platform.batch.config;

import io.micrometer.registry.otlp.AggregationTemporality;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import za.co.fnb.dcre.platform.batch.config.properties.TelemetryProperties;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cumulative is pinned, and delta is REFUSED at startup rather than tolerated.
 *
 * <p>INHERITED MEASUREMENT (Task 1 spike, 2026-09-11), not re-measured by this test: with delta
 * temporality an orderly exit publishes NOTHING. The receiver accepts the payload with HTTP 200 and
 * an empty {@code partialSuccess} and stores none of it, so no error appears anywhere: not in the
 * application, not in the collector, not in a panel. A service configured that way looks healthy
 * and is invisible. The spike isolated it to the receiver by hand-posting a delta payload.
 * Everything this class asserts about Boot and Micrometer, by contrast, is measured here.
 *
 * <p><strong>The guard is the control; the contributed default is a pin.</strong> Boot 4.1.0 already
 * defaults to cumulative: {@code OtlpMetricsProperties}' constructor assigns
 * {@code AggregationTemporality.CUMULATIVE}, read from the bytecode of
 * {@code spring-boot-micrometer-metrics-4.1.0.jar} on 2026-09-11. So contributing the default buys
 * nothing TODAY and pins the value against that default moving; what closes the defect is refusing
 * an explicit delta. Both are asserted separately, so removing either is detectable.
 *
 * <p>This class sits in the {@code config} package rather than the {@code telemetry} package the
 * brief named. NOT for the reason first given: a literal substring assertion is strictly stronger
 * on the text and needs no package move. The move earns its place by pinning the failure to THIS
 * guard, through the package-private key and message, rather than accepting any startup failure.
 * That matters because the brief's own fixture would have failed for an unrelated identity reason
 * and a bare {@code hasFailed()} would have passed on it.
 *
 * <p>Every fixture seeds the published identity keys, because {@link TelemetryAutoConfiguration}
 * reads them back and fails startup without them. A context that failed for the WRONG reason would
 * satisfy {@code hasFailed()} and prove nothing, so the passing cases here double as the control
 * that the fixture starts at all.
 */
class TemporalityIT {

    private static final String NAME_KEY = TelemetryEnvironmentPostProcessor.SERVICE_NAME_KEY;
    private static final String INSTANCE_KEY = TelemetryEnvironmentPostProcessor.INSTANCE_ID_KEY;
    private static final String TEMPORALITY = TelemetryAutoConfiguration.TEMPORALITY_KEY;

    // Fixture, not subject. Every consumer carries an OpenTelemetry bean, because this library puts
    // spring-boot-starter-opentelemetry on their classpath and Boot's OpenTelemetrySdkAutoConfiguration
    // publishes an SDK whether OpenTelemetry is enabled or disabled. Without it the appender
    // installer refuses this context by name, and every temporality case below would then pass or
    // fail on the wrong failure: exactly the hazard this class's own javadoc already names.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(OpenTelemetry.class, OpenTelemetry::noop)
            .withConfiguration(AutoConfigurations.of(TelemetryAutoConfiguration.class))
            .withPropertyValues(TelemetryProperties.ENABLED + "=true",
                                NAME_KEY + "=dcre-crg",
                                INSTANCE_KEY + "=dcre-crg-9f4c2");

    @Test
    void temporalityIsCumulativeByDefaultBecauseDeltaPublishesNothing() {
        runner.run(ctx -> assertThat(ctx.getEnvironment().getProperty(TEMPORALITY))
                .isEqualTo(TelemetryAutoConfiguration.CUMULATIVE));
    }

    @Test
    void theContributedDefaultIsWhatBootsOwnOtlpPropertiesBindTo() {
        // The property key existing proves only that this library wrote a string it chose itself.
        // This reads the value back through the type Boot binds and Micrometer consumes, so a key
        // path that Boot does not read, or a value its converter rejects, fails here.
        // Stated gap: because Boot's own default is already CUMULATIVE, this test stays GREEN if
        // the contributed default is removed entirely. The test above is what detects that.
        runner.run(ctx -> assertThat(bindOtlpProperties(ctx).getAggregationTemporality())
                .isEqualTo(AggregationTemporality.CUMULATIVE));
    }

    @Test
    void anExplicitDeltaSettingFailsStartupRatherThanPublishingSilenceForever() {
        runner.withPropertyValues(TEMPORALITY + "=delta").run(ctx -> refused(ctx, "delta"));
    }

    @Test
    void anEnvironmentVariableSettingDeltaAlsoFailsStartup() {
        // The channel the whole guard exists for: an environment variable nobody remembers setting.
        // SystemEnvironmentPropertySource resolves the underscore form of a hyphenated key by
        // itself, so this one channel would survive even a non-relaxed reader; the three spellings
        // in the test below would not, which is why the guard reads through the Binder.
        runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("systemEnvironment", Map.of(
                                "MANAGEMENT_OTLP_METRICS_EXPORT_AGGREGATION_TEMPORALITY", "delta"))))
              .run(ctx -> refused(ctx, "delta"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "management.otlp.metrics.export.aggregationTemporality",
            "management.otlp.metrics.export.aggregation_temporality",
            "MANAGEMENT.OTLP.METRICS.EXPORT.AGGREGATION-TEMPORALITY"})
    void everyRelaxedSpellingIsRefusedBecauseBootBindsThemAllToDelta(final String spelling) {
        // Boot's binder canonicalises all three of these to the same ConfigurationPropertyName
        // and binds DELTA from any of them, so each is a real way to configure silence.
        //
        // NOT a defect that was found and fixed, and the first draft of this comment said it was.
        // Measured 2026-09-11: these already passed before the guard moved to the Binder, because
        // ConfigurationPropertySources.attach adds the configurationProperties source and every
        // SpringApplication attaches it. The bare StandardEnvironment that showed a hole is not a
        // shape production has. What the Binder buys is that the guard no longer DEPENDS on someone
        // else having attached that source: see theGuardDoesNotDependOnTheAttachedSource below.
        runner.withPropertyValues(spelling + "=delta").run(ctx -> refused(ctx, "delta"));
    }

    @Test
    void anExplicitCumulativeSettingStartsNormallyWhateverItsCase() {
        // The discriminator. Without it, a guard that refused EVERY explicit value would pass every
        // failing test above, and the two hypotheses would be indistinguishable.
        runner.withPropertyValues(TEMPORALITY + "=CUMULATIVE")
              .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void aGarbageValueFailsWithThisLibrarysMessageRatherThanABindException() {
        // Boot refuses this too, later and less legibly: measured 2026-09-11, binding it to
        // AggregationTemporality throws BindException at bean creation. Failing here names the key,
        // the value and the consequence instead.
        runner.withPropertyValues(TEMPORALITY + "=not-a-temporality")
              .run(ctx -> refused(ctx, "not-a-temporality"));
    }

    @Test
    void anEmptyValueIsTreatedAsUNSETBecauseThatIsWhatBootsBinderDoes() {
        // NOT fail-closed here, deliberately, and the exception to this library's own habit. An
        // ordinary Kubernetes `env:` entry or a valueless ConfigMap key produces "", which is the C1
        // shape that already bit this fleet. Measured 2026-09-11: binding "" to
        // AggregationTemporality yields no value, so Boot falls back to its own CUMULATIVE default
        // and the service reports correctly. Refusing it would crash a working service, and a guard
        // that is stricter than the thing it guards is a defect, not caution.
        runner.withPropertyValues(TEMPORALITY + "=").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(bindOtlpProperties(ctx).getAggregationTemporality())
                    .isEqualTo(AggregationTemporality.CUMULATIVE);
        });
    }

    @Test
    void theGuardDoesNotDependOnTheAttachedSource() {
        // Removing configurationProperties models a context that never went through
        // SpringApplication.prepareEnvironment. Environment.getProperty is then an EXACT-match
        // lookup which reads past the camelCase spelling, finds this library's OWN contributed
        // cumulative default and passes; the Binder canonicalises and refuses. This is the test
        // that goes red if the guard is moved back to Environment.getProperty.
        runner.withPropertyValues(
                        "management.otlp.metrics.export.aggregationTemporality=delta")
              .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                      .remove("configurationProperties"))
              .run(ctx -> refused(ctx, "delta"));
    }

    @Test
    void theGuardIsInertOnAServiceThatAsksForNoTelemetry() {
        // Blast radius. The 30 consumers that carry no telemetry config must not be startable or
        // unstartable on the strength of a key they never set.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TelemetryAutoConfiguration.class))
                .withPropertyValues(TEMPORALITY + "=delta")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    // Asserting a VALUE, not merely the absence of a failure: a hasNotFailed() on
                    // its own passes just as happily when the autoconfiguration has been deleted.
                    // The named source being absent is what says this code ran and declined.
                    assertThat(ctx.getEnvironment().getPropertySources()
                            .contains(TelemetryAutoConfiguration.TEMPORALITY_PROPERTY_SOURCE))
                            .isFalse();
                });
    }

    /**
     * The guard's failure, asserted by its exact named message rather than by "startup failed".
     *
     * <p>The literal substring is the load-bearing half and the round-trip through
     * {@code notCumulative} only pins that no OTHER text crept in. Asserted on the startup failure
     * ITSELF, not on {@code rootCause()}: a
     * {@code BeanFactoryPostProcessor} throwing propagates unwrapped out of {@code refresh()}, so
     * there is no cause to unwrap and {@code rootCause()} fails with a misleading message. The
     * sibling identity failures in {@link TelemetryAutoConfigurationTest} DO wrap, because those
     * are thrown during bean creation. Measured 2026-09-11.
     */
    private static void refused(final org.springframework.boot.test.context.assertj
            .AssertableApplicationContext ctx, final String value) {
        assertThat(ctx).hasFailed();
        assertThat(ctx.getStartupFailure())
                .isInstanceOf(IllegalStateException.class)
                // A LITERAL, because hasMessage(notCumulative(value)) builds its expectation by
                // calling the producer and is therefore a tautology: red-proofed 2026-09-11, a
                // message body replaced with "x" left all tests green. This line is what fails then.
                .hasMessageContaining(TelemetryAutoConfiguration.TEMPORALITY_KEY + " is '" + value
                                      + "'. Only cumulative reports through this stack")
                .hasMessage(TelemetryAutoConfiguration.notCumulative(value));
    }

    private static OtlpMetricsProperties bindOtlpProperties(
            final org.springframework.context.ApplicationContext context) {
        return Binder.get(context.getEnvironment())
                .bind("management.otlp.metrics.export", Bindable.of(OtlpMetricsProperties.class))
                .orElseGet(OtlpMetricsProperties::new);
    }
}
