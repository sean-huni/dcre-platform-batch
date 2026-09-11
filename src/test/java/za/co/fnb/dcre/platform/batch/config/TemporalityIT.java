package za.co.fnb.dcre.platform.batch.config;

import io.micrometer.registry.otlp.AggregationTemporality;
import org.junit.jupiter.api.Test;
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
 * <p>Measured 2026-09-11: with delta temporality an orderly exit publishes NOTHING. The receiver
 * accepts the payload with HTTP 200 and an empty {@code partialSuccess} and stores none of it, so no
 * error appears anywhere: not in the application, not in the collector, not in a panel. A service
 * configured that way looks healthy and is invisible. That was isolated to the receiver rather than
 * to Micrometer by hand-posting a delta payload.
 *
 * <p><strong>The guard is the control; the contributed default is a pin.</strong> Boot 4.1.0 already
 * defaults to cumulative: {@code OtlpMetricsProperties}' constructor assigns
 * {@code AggregationTemporality.CUMULATIVE}, read from the bytecode of
 * {@code spring-boot-micrometer-metrics-4.1.0.jar} on 2026-09-11. So contributing the default buys
 * nothing TODAY and pins the value against that default moving; what closes the defect is refusing
 * an explicit delta. Both are asserted separately, so removing either is detectable.
 *
 * <p>This class sits in the {@code config} package rather than the {@code telemetry} package the
 * brief named, so it can assert the EXACT message rather than a substring. The alternative was
 * making a library-internal key and message public purely for a test.
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

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
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
        // Measured 2026-09-11 that Environment.getProperty resolves the underscore form of a
        // hyphenated key, so the guard does not need the Binder to see it. Asserting it here means
        // a future Spring that stops doing so fails this test instead of silently reopening the hole.
        runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("systemEnvironment", Map.of(
                                "MANAGEMENT_OTLP_METRICS_EXPORT_AGGREGATION_TEMPORALITY", "delta"))))
              .run(ctx -> refused(ctx, "delta"));
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
     * <p>Asserted on the startup failure ITSELF, not on {@code rootCause()}: a
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
                .hasMessage(TelemetryAutoConfiguration.notCumulative(value));
    }

    private static OtlpMetricsProperties bindOtlpProperties(
            final org.springframework.context.ApplicationContext context) {
        return Binder.get(context.getEnvironment())
                .bind("management.otlp.metrics.export", Bindable.of(OtlpMetricsProperties.class))
                .orElseGet(OtlpMetricsProperties::new);
    }
}
