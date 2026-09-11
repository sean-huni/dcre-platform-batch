package za.co.fnb.dcre.platform.batch.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingConnectionDetails;
import org.springframework.boot.opentelemetry.autoconfigure.logging.otlp.OtlpLoggingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.logging.otlp.OtlpLoggingConnectionDetails;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two environment variables that decide whether a stage's LOGS and TRACES can leave the pod at
 * all, proved to be the names Boot actually reads.
 *
 * <p><strong>This test exists because a name nobody reads is the defect that has already shipped on
 * this plan.</strong> An invented or stale variable binds a property no code consults, Boot does not
 * complain about a property nobody asked for, and the pod starts, looks correctly wired and exports
 * nothing. The orchestrator is in a different repository and cannot be on this classpath, so the
 * cross-repo half of the contract stays hand-maintained; what CAN be proved here, and is, is that
 * these exact spellings open Boot's own gates and that the obvious wrong choice does not.
 *
 * <p>The names were derived from the artifacts rather than chosen. Read from the bytecode of
 * {@code spring-boot-opentelemetry-4.1.0.jar} and
 * {@code spring-boot-micrometer-tracing-opentelemetry-4.1.0.jar} on 2026-09-11:
 * {@code OtlpLoggingProperties} is {@code @ConfigurationProperties("management.opentelemetry.logging.export.otlp")}
 * and {@code OtlpTracingProperties} is
 * {@code @ConfigurationProperties("management.opentelemetry.tracing.export.otlp")}; in each jar the
 * connection-details bean carries {@code @ConditionalOnProperty} on that prefix plus
 * {@code .endpoint}, and the exporter configuration is {@code @ConditionalOnBean} of those
 * connection details. So the endpoint property is not merely where the URL is read, it is the switch.
 *
 * <p>The gate is asserted by the EFFECT, the presence of the connection-details bean and the URL it
 * answers, never by reading the property back. A property that binds and a gate that opens are two
 * different claims, and only the second one makes a record leave the process.
 */
class OtlpEndpointVariableTest {

    /**
     * Relaxed-binding environment form of
     * {@code management.opentelemetry.logging.export.otlp.endpoint}. Asserted rather than assumed:
     * the cases below set exactly this string and nothing else.
     */
    private static final String LOGS_VAR = "MANAGEMENT_OPENTELEMETRY_LOGGING_EXPORT_OTLP_ENDPOINT";

    /** Relaxed-binding environment form of {@code management.opentelemetry.tracing.export.otlp.endpoint}. */
    private static final String TRACES_VAR = "MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT";

    /**
     * {@code management.otlp.logging.endpoint}, which reads like the right name and is not. Its
     * metadata in the same jar carries {@code deprecation.level=error} since {@code 4.0.0} with
     * {@code replacement=management.opentelemetry.logging.export.otlp.endpoint}, and no condition
     * anywhere reads it.
     */
    private static final String DEPRECATED_LOGS_VAR = "MANAGEMENT_OTLP_LOGGING_ENDPOINT";

    private static final String LOGS_URL = "http://collector.stub.test:4318/v1/logs";

    private static final String TRACES_URL = "http://collector.stub.test:4318/v1/traces";

    private final ApplicationContextRunner logs = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OtlpLoggingAutoConfiguration.class));

    private final ApplicationContextRunner traces = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OtlpTracingAutoConfiguration.class));

    @Test
    void theLogsEndpointVariableOpensBootsOwnOtlpLoggingGate() {
        logs.withInitializer(environment(LOGS_VAR, LOGS_URL)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OtlpLoggingConnectionDetails.class);
            assertThat(context.getBean(OtlpLoggingConnectionDetails.class).getUrl(
                    org.springframework.boot.opentelemetry.autoconfigure.logging.otlp.Transport.HTTP))
                    .isEqualTo(LOGS_URL);
        });
    }

    @Test
    void theTracesEndpointVariableOpensBootsOwnOtlpTracingGate() {
        traces.withInitializer(environment(TRACES_VAR, TRACES_URL)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OtlpTracingConnectionDetails.class);
            assertThat(context.getBean(OtlpTracingConnectionDetails.class).getUrl(
                    org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp
                            .Transport.HTTP))
                    .isEqualTo(TRACES_URL);
        });
    }

    /**
     * The negative control. Without it, a passing case above is equally explained by a gate that is
     * open for everyone, and this is also the measured statement of what the fleet did before this
     * variable existed: records reached the logger provider and stopped.
     */
    @Test
    void withoutTheVariableTheLoggingGateStaysShutSoNothingCanLeaveTheProcess() {
        logs.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OtlpLoggingConnectionDetails.class);
        });
    }

    @Test
    void withoutTheVariableTheTracingGateStaysShutToo() {
        // One control per gate. Sharing one would leave the other's absence undetectable.
        traces.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OtlpTracingConnectionDetails.class);
        });
    }

    /**
     * The discriminator that makes the derivation worth something. The deprecated name is a
     * plausible choice, it is still documented in the same metadata file, and setting it changes
     * nothing at all: the gate stays shut and the service looks exactly as it does when correctly
     * configured. This is the shape of the defect the whole test class exists to prevent.
     */
    @Test
    void theDeprecatedLoggingEndpointNameDoesNotOpenTheGateAndFailsSilently() {
        logs.withInitializer(environment(DEPRECATED_LOGS_VAR, LOGS_URL)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context)
                    .as("management.otlp.logging.endpoint was replaced in Boot 4.0.0 and no "
                        + "condition reads it; choosing it would export nothing and raise nothing")
                    .doesNotHaveBean(OtlpLoggingConnectionDetails.class);
        });
    }

    /**
     * A {@code SystemEnvironmentPropertySource}, not {@code withPropertyValues}, because the claim
     * under test is about the ENVIRONMENT VARIABLE spelling. That source is the one that maps
     * {@code management.opentelemetry.logging.export.otlp.endpoint} onto the upper-case underscored
     * form, and it is the source a Kubernetes {@code env:} entry actually lands in.
     */
    private static org.springframework.context.ApplicationContextInitializer<
            org.springframework.context.ConfigurableApplicationContext> environment(
            final String variable, final String value) {
        return context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("systemEnvironment", Map.of(variable, value)));
    }
}
