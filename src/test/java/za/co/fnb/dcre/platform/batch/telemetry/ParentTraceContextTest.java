package za.co.fnb.dcre.platform.batch.telemetry;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import za.co.fnb.dcre.platform.batch.config.TelemetryAutoConfiguration;
import za.co.fnb.dcre.platform.batch.config.properties.TelemetryProperties;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CONSUMER half of the trace context, without which the whole trace feature is inert.
 *
 * <p>AGT puts a W3C {@code traceparent} on every stage pod. Nothing read it. Measured across all 65
 * jars on this module's resolved runtime classpath on 2026-09-11, the literal {@code traceparent}
 * appears in exactly ONE class, {@code io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator},
 * which is a CARRIER-based API: you hand it headers and it never touches the process environment.
 * The SDK's autoconfigure implementation, which does read environment variables, is not on this
 * classpath at all, only its service-provider interface, and Boot's own
 * {@code OpenTelemetryEnvironmentVariableEnvironmentPostProcessor} maps eight {@code OTEL_*}
 * variables of which {@code TRACEPARENT} is not one. So the variable arrived, was ignored, and every
 * stage pod started a fresh root trace while both halves looked correctly wired.
 *
 * <p><strong>This asserts the JOIN, not the parse.</strong> A test that the propagator returned a
 * context would pass over a process in which nothing ever makes that context current, which is
 * exactly the shape of the defect being closed. So a real {@code SpringApplication} is booted and a
 * span is created inside an {@link ApplicationRunner}, because that is the same hook Spring Batch's
 * own {@code JobLauncherApplicationRunner} uses, and the span's trace id is compared with the one
 * injected.
 *
 * <p>The mechanism that turns that span into a job span is Spring Batch's
 * {@code BatchObservabilityBeanPostProcessor}, confirmed present in
 * {@code spring-batch-core-6.0.4.jar} on 2026-09-11. It is not exercised here: a real job needs a
 * repository and a database, and the claim under test is about the ambient context the runner phase
 * runs under, which is what that post-processor's spans will hang from.
 */
class ParentTraceContextTest {

    private static final String INJECTED_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private static final String INJECTED_SPAN_ID = "00f067aa0ba902b7";

    private static final String INJECTED = "00-" + INJECTED_TRACE_ID + "-" + INJECTED_SPAN_ID + "-01";

    private static final RecordingSpanExporter EXPORTED = new RecordingSpanExporter();

    private static final List<SpanContext> AMBIENT_AT_RUNNER_TIME = new CopyOnWriteArrayList<>();

    @BeforeEach
    void reset() {
        EXPORTED.clear();
        AMBIENT_AT_RUNNER_TIME.clear();
    }

    @Test
    void aSpanCreatedInTheRunnerPhaseJoinsTheInjectedTrace() {
        try (ConfigurableApplicationContext ignored = boot(INJECTED)) {
            assertThat(AMBIENT_AT_RUNNER_TIME)
                    .as("the runner never ran, so nothing below is evidence of anything")
                    .hasSize(1);
            assertThat(AMBIENT_AT_RUNNER_TIME.getFirst().getTraceId())
                    .as("the ambient context during the runner phase must be the arrival's, because "
                        + "that is what a Batch job span will hang from")
                    .isEqualTo(INJECTED_TRACE_ID);

            final SpanData span = onlySpan();
            assertThat(span.getTraceId())
                    .as("a span created where the job runs must JOIN the arrival's trace; a "
                        + "traceparent that is received and ignored produces a fresh root trace and "
                        + "looks identical from inside the pod")
                    .isEqualTo(INJECTED_TRACE_ID);
            assertThat(span.getParentSpanId())
                    .as("the arrival span must be the parent, or the stage hangs off nothing")
                    .isEqualTo(INJECTED_SPAN_ID);
            assertThat(span.getSpanContext().isSampled())
                    .as("the sampled flag must travel, or the child decides sampling on its own and "
                        + "half a DAG goes missing")
                    .isTrue();
        }
    }

    /**
     * The negative control the ruling asked for by name. A malformed value must mean a FRESH trace
     * and never a dead pod: a stage that refuses to start because a telemetry header was mangled has
     * turned an observability defect into an outage.
     */
    @Test
    void aMalformedTraceparentStartsAFreshValidTraceRatherThanFailingTheService() {
        try (ConfigurableApplicationContext context = boot("not-a-traceparent")) {
            assertThat(context.isActive()).as("the context must have started").isTrue();
            assertThat(AMBIENT_AT_RUNNER_TIME).hasSize(1);
            assertThat(AMBIENT_AT_RUNNER_TIME.getFirst().isValid())
                    .as("no parent to adopt, so the runner phase runs under no ambient span")
                    .isFalse();

            final SpanData span = onlySpan();
            assertThat(span.getSpanContext().isValid())
                    .as("a fresh trace must still be a VALID trace")
                    .isTrue();
            assertThat(span.getTraceId())
                    .as("and it must not somehow be the injected one")
                    .isNotEqualTo(INJECTED_TRACE_ID);
        }
    }

    @Test
    void noTraceparentAtAllAlsoStartsAFreshValidTrace() {
        // One control per case. Sharing the malformed one would leave the ordinary case, which is
        // every clock window AGT launches, untested.
        try (ConfigurableApplicationContext context = boot(null)) {
            assertThat(context.isActive()).isTrue();
            assertThat(AMBIENT_AT_RUNNER_TIME).hasSize(1);
            assertThat(AMBIENT_AT_RUNNER_TIME.getFirst().isValid()).isFalse();
            assertThat(onlySpan().getSpanContext().isValid()).isTrue();
        }
    }

    private static SpanData onlySpan() {
        final List<SpanData> spans = EXPORTED.spans();
        assertThat(spans).as("the runner must have produced exactly one span").hasSize(1);
        return spans.getFirst();
    }

    /**
     * A real {@code SpringApplication}, because the claim is about the window between
     * {@code ApplicationStartedEvent} and the runners, which no {@code ApplicationContextRunner} has.
     * The variable is seeded through a {@code SystemEnvironmentPropertySource}, which is the source a
     * Kubernetes {@code env:} entry lands in.
     */
    private static ConfigurableApplicationContext boot(final String traceparent) {
        final SpringApplication application = new SpringApplication(Fixture.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        if (traceparent != null) {
            application.addInitializers(context -> context.getEnvironment().getPropertySources()
                    .addFirst(new SystemEnvironmentPropertySource("test-env",
                            Map.of("TRACEPARENT", traceparent))));
        }
        return application.run("--" + TelemetryProperties.ENABLED + "=true",
                               "--" + TelemetryProperties.STAGE + "=crg");
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(TelemetryAutoConfiguration.class)
    static class Fixture {

        @Bean
        io.opentelemetry.api.OpenTelemetry openTelemetry() {
            // Only so TelemetryAutoConfiguration's log-appender installer has something to install.
            return io.opentelemetry.api.OpenTelemetry.noop();
        }

        @Bean
        SdkTracerProvider tracerProvider() {
            return SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(EXPORTED))
                    .build();
        }

        /**
         * Stands in for {@code JobLauncherApplicationRunner}. Records the ambient context as well as
         * creating a span: the context is the direct evidence that the parent was established, and
         * the span is the evidence that it is actually inherited by work.
         */
        @Bean
        ApplicationRunner probe(final SdkTracerProvider tracerProvider) {
            return new ApplicationRunner() {
                @Override
                public void run(final ApplicationArguments args) {
                    AMBIENT_AT_RUNNER_TIME.add(Span.current().getSpanContext());
                    tracerProvider.get("test").spanBuilder("job").startSpan().end();
                }
            };
        }
    }

    /** Holds what the SDK ended, which is the only evidence a span existed and where it hung. */
    private static final class RecordingSpanExporter implements SpanExporter {

        private final List<SpanData> spans = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(final Collection<SpanData> batch) {
            spans.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }

        void clear() {
            spans.clear();
        }

        List<SpanData> spans() {
            return spans;
        }
    }
}
