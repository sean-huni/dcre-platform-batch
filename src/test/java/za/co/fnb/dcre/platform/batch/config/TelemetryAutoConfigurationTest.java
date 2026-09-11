package za.co.fnb.dcre.platform.batch.config;

import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import za.co.fnb.dcre.platform.batch.telemetry.StageIdentity;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bean half. It resolves nothing: it reads back what
 * {@link TelemetryEnvironmentPostProcessor} published, so it cannot name the service differently
 * from what is being exported. Every test therefore seeds the published keys rather than an
 * application class.
 */
class TelemetryAutoConfigurationTest {

    private static final String NAME_KEY = TelemetryEnvironmentPostProcessor.SERVICE_NAME_KEY;
    private static final String INSTANCE_KEY = TelemetryEnvironmentPostProcessor.INSTANCE_ID_KEY;

    // The OpenTelemetry bean is part of the FIXTURE, not of what is asserted here. Every consumer
    // has one, because this library puts spring-boot-starter-opentelemetry on their classpath and
    // Boot's OpenTelemetrySdkAutoConfiguration publishes an SDK whether OpenTelemetry is enabled or
    // disabled. A runner without it would model a context no consumer has, and the appender
    // installer would refuse it by name, so every case below would then fail for the wrong reason.
    // It is a no-op instance deliberately: nothing here exercises the log path, which is
    // LogExportTest's job, and that test boots a real SpringApplication because only that reads
    // logback-spring.xml at all.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(OpenTelemetry.class, OpenTelemetry::noop)
            .withConfiguration(AutoConfigurations.of(TelemetryAutoConfiguration.class));

    @Test
    void backsOffEntirelyWhenTheMarkerIsAbsent() {
        runner.withPropertyValues(NAME_KEY + "=dcre-crg", INSTANCE_KEY + "=pod-7")
              .run(ctx -> {
                  assertThat(ctx).hasNotFailed();
                  assertThat(ctx).doesNotHaveBean(StageIdentity.class);
              });
    }

    @Test
    void publishesExactlyTheIdentityThatWasPublishedToTheExporter() {
        runner.withPropertyValues("dcre.telemetry.enabled=true",
                                  NAME_KEY + "=dcre-crg", INSTANCE_KEY + "=dcre-crg-9f4c2")
              .run(ctx -> {
                  final StageIdentity identity = ctx.getBean(StageIdentity.class);
                  assertThat(identity.serviceName()).isEqualTo("dcre-crg");
                  assertThat(identity.instanceId()).isEqualTo("dcre-crg-9f4c2");
              });
    }

    @Test
    void failsStartupWhenTheServiceNameWasNeverPublished() {
        runner.withPropertyValues("dcre.telemetry.enabled=true", INSTANCE_KEY + "=pod-7")
              .run(ctx -> {
                  assertThat(ctx).hasFailed();
                  assertThat(ctx.getStartupFailure()).rootCause()
                          .isInstanceOf(IllegalStateException.class)
                          .hasMessage(TelemetryAutoConfiguration.NAME_NOT_PUBLISHED);
              });
    }

    @Test
    void failsStartupWhenTheInstanceIdWasNeverPublished() {
        // Distinct from the case above on purpose. One test per control, or neither control is
        // detectable when the other is removed.
        runner.withPropertyValues("dcre.telemetry.enabled=true", NAME_KEY + "=dcre-crg")
              .run(ctx -> {
                  assertThat(ctx).hasFailed();
                  assertThat(ctx.getStartupFailure()).rootCause()
                          .isInstanceOf(IllegalStateException.class)
                          .hasMessage(TelemetryAutoConfiguration.INSTANCE_NOT_PUBLISHED);
              });
    }

    @Test
    void failsStartupWhenTheServiceRenamedItself() {
        // spring.application.name is contributed as a DEFAULT, so a service that sets its own wins
        // for the export. Telemetry must not then claim a different name: it fails, by name.
        runner.withPropertyValues("dcre.telemetry.enabled=true",
                                  NAME_KEY + "=my-service", INSTANCE_KEY + "=pod-7")
              .run(ctx -> {
                  assertThat(ctx).hasFailed();
                  assertThat(ctx.getStartupFailure()).rootCause()
                          .isInstanceOf(IllegalArgumentException.class)
                          .hasMessageContaining("my-service");
              });
    }

    @Test
    void failsStartupOnAPerJobShapedName() {
        // mrv carries the seam tokens mrv AND mrv-account-reference. A per-job shaped name reaching
        // here must fail the service rather than quietly publish a second job label.
        runner.withPropertyValues("dcre.telemetry.enabled=true",
                                  NAME_KEY + "=dcre-mrv-account-reference", INSTANCE_KEY + "=pod-7")
              .run(ctx -> {
                  assertThat(ctx).hasFailed();
                  assertThat(ctx.getStartupFailure()).rootCause()
                          .isInstanceOf(IllegalArgumentException.class)
                          .hasMessageContaining("dcre-mrv-account-reference");
              });
    }

    @Test
    void backsOffWhenOnlyBroadcastTelemetryDataEnvVarsArePresent() {
        // Regression, caught live in-cluster 2026-07-14 on the exchange autoconfig: the
        // orchestrator exports shared DATA variables to EVERY stage pod and relaxed binding
        // canonicalizes them, which activated that autoconfig fleet-wide and crashed non-writer
        // services at startup. The marker is therefore `enabled` and never an endpoint.
        runner.withPropertyValues(NAME_KEY + "=dcre-crg", INSTANCE_KEY + "=pod-7")
              .withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                      new SystemEnvironmentPropertySource("test-env", Map.of(
                              "OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector:4318",
                              "DCRE_TELEMETRY_ENDPOINT", "http://collector:4318"))))
              .run(ctx -> {
                  assertThat(ctx).hasNotFailed();
                  assertThat(ctx).doesNotHaveBean(StageIdentity.class);
              });
    }

    @Test
    void theMarkerItselfStillActivatesFromTheEnvironment() {
        // Positive control for the test above. Without it, that back-off is equally explained by an
        // inert property source, and an absence that proves nothing reads exactly like one that does.
        runner.withPropertyValues(NAME_KEY + "=dcre-crg", INSTANCE_KEY + "=pod-7")
              .withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                      new SystemEnvironmentPropertySource("test-env",
                              Map.of("DCRE_TELEMETRY_ENABLED", "true"))))
              .run(ctx -> assertThat(ctx.getBean(StageIdentity.class).serviceName())
                      .isEqualTo("dcre-crg"));
    }

    @Test
    void isRegisteredSoEveryConsumerActuallyGetsIt() throws Exception {
        // Boot reads this exact resource. The class existing proves nothing about the fleet getting
        // it; the FILE is the registration, and a library that forgets the line ships a no-op.
        final List<String> registered = registeredAutoConfigurations();
        assertThat(registered).contains(TelemetryAutoConfiguration.class.getName());
        for (final String fqcn : registered) {
            assertThat(Class.forName(fqcn)).as(fqcn).isNotNull();
        }
    }

    @Test
    void leavesBatchJdbcConfigOutOfTheImportsFile() {
        // Membership of that file is a decision, not a default: BatchJdbcConfig is imported by the
        // services that want a persistent JDBC JobRepository and must not arrive by autoconfiguration.
        assertThat(registeredAutoConfigurations()).doesNotContain(BatchJdbcConfig.class.getName());
    }

    private static List<String> registeredAutoConfigurations() {
        final String resource =
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
        try (InputStream in = TelemetryAutoConfigurationTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        } catch (final Exception e) {
            throw new IllegalStateException("could not read " + resource, e);
        }
    }
}
