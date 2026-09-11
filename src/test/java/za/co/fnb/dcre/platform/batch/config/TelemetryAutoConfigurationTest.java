package za.co.fnb.dcre.platform.batch.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import za.co.fnb.dcre.crg.CrgApplication;
import za.co.fnb.dcre.mrv.MrvApplication;
import za.co.fnb.dcre.platform.batch.telemetry.StageIdentity;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The identity half of the fleet's telemetry: this autoconfiguration is the ONE place all 28 stage
 * services get {@code service.name} and {@code service.instance.id} from, so a defect here is a
 * defect everywhere at once.
 */
class TelemetryAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TelemetryAutoConfiguration.class));

    @Test
    void backsOffEntirelyWhenTheMarkerIsAbsent() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(StageIdentity.class);
            assertThat(ctx).doesNotHaveBean(MeterFilter.class);
        });
    }

    @Test
    void publishesIdentityWhenEnabled() {
        runner.withPropertyValues("dcre.telemetry.enabled=true", "dcre.telemetry.stage=crg")
              .run(ctx -> assertThat(ctx.getBean(StageIdentity.class).serviceName()).isEqualTo("dcre-crg"));
    }

    @Test
    void failsStartupRatherThanPublishingAnUnknownStage() {
        runner.withPropertyValues("dcre.telemetry.enabled=true")
              .run(ctx -> {
                  assertThat(ctx).hasFailed();
                  assertThat(ctx.getStartupFailure()).rootCause()
                          .isInstanceOf(IllegalStateException.class)
                          .hasMessageContaining("dcre.telemetry.stage");
              });
    }

    @Test
    void derivesTheStageFromTheApplicationPackageLeafWhenNoPropertyIsSet() {
        // The measured default across the fleet: 30 Boot applications at za.co.fnb.dcre.<svc>, so
        // 30 distinct lowercase leaves, one per deployable, read from the application class rather
        // than hand-typed anywhere.
        runner.withUserConfiguration(OneApplicationBean.class)
              .withPropertyValues("dcre.telemetry.enabled=true")
              .run(ctx -> assertThat(ctx.getBean(StageIdentity.class).serviceName()).isEqualTo("dcre-crg"));
    }

    @Test
    void anExplicitStagePropertyOutranksThePackageLeaf() {
        // Precedence proven rather than argued. The property is the escape hatch; without this test
        // it would be a value nothing reads, which is a comment wearing the costume of config.
        runner.withUserConfiguration(OneApplicationBean.class)
              .withPropertyValues("dcre.telemetry.enabled=true", "dcre.telemetry.stage=mrv")
              .run(ctx -> assertThat(ctx.getBean(StageIdentity.class).serviceName()).isEqualTo("dcre-mrv"));
    }

    @Test
    void failsStartupWhenTwoApplicationBeansDisagreeOnTheStage() {
        // Two service names is two job labels, which is the exact defect this wiring exists to
        // prevent. Picking one silently would be the defect arriving through the fix.
        runner.withUserConfiguration(TwoApplicationBeans.class)
              .withPropertyValues("dcre.telemetry.enabled=true")
              .run(ctx -> {
                  assertThat(ctx).hasFailed();
                  assertThat(ctx.getStartupFailure()).rootCause()
                          .isInstanceOf(IllegalStateException.class)
                          .hasMessageContaining("crg")
                          .hasMessageContaining("mrv");
              });
    }

    @Test
    void refusesAPerJobStageTokenRatherThanPublishingASecondJobLabel() {
        // mrv carries the seam tokens mrv AND mrv-account-reference. Wiring the per-job one here
        // must fail the service at startup, not quietly publish a second job label.
        runner.withUserConfiguration(OneApplicationBean.class)
              .withPropertyValues("dcre.telemetry.enabled=true",
                                  "dcre.telemetry.stage=mrv-account-reference")
              .run(ctx -> {
                  assertThat(ctx).hasFailed();
                  assertThat(ctx.getStartupFailure()).rootCause()
                          .isInstanceOf(IllegalArgumentException.class)
                          .hasMessageContaining("dcre-mrv-account-reference");
              });
    }

    @Test
    void stampsBothIdentityLabelsOntoEveryMeter() {
        // Prometheus derives job from service.name and instance from service.instance.id. A stage
        // that sets the first and not the second writes every execution into ONE series and each
        // crossing reads as a counter reset.
        runner.withPropertyValues("dcre.telemetry.enabled=true", "dcre.telemetry.stage=crg")
              .run(ctx -> {
                  final SimpleMeterRegistry registry = new SimpleMeterRegistry();
                  registry.config().meterFilter(ctx.getBean(MeterFilter.class));
                  final Counter counter = registry.counter("dcre.stage.runs");
                  assertThat(counter.getId().getTag("service.name")).isEqualTo("dcre-crg");
                  assertThat(counter.getId().getTag("service.instance.id")).isNotBlank();
              });
    }

    @Test
    void backsOffWhenOnlyBroadcastTelemetryDataEnvVarsArePresent() {
        // Regression, caught live in-cluster 2026-07-14 on the exchange autoconfig: the
        // orchestrator exports shared DATA variables to EVERY stage pod and relaxed binding
        // canonicalizes them, which activated that autoconfig fleet-wide and crashed non-writer
        // services at startup. The marker is therefore `enabled` and never an endpoint.
        runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("test-env", Map.of(
                                "OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector:4318",
                                "DCRE_TELEMETRY_ENDPOINT", "http://collector:4318"))))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(StageIdentity.class);
                    assertThat(ctx).doesNotHaveBean(MeterFilter.class);
                });
    }

    @Test
    void theMarkerItselfStillActivatesFromTheEnvironment() {
        // Positive control for the test above. Without it, that back-off is equally explained by an
        // inert property source, and an absence that proves nothing reads exactly like one that does.
        runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("test-env", Map.of(
                                "DCRE_TELEMETRY_ENABLED", "true",
                                "DCRE_TELEMETRY_STAGE", "crg"))))
                .run(ctx -> assertThat(ctx.getBean(StageIdentity.class).serviceName()).isEqualTo("dcre-crg"));
    }

    @Test
    void isRegisteredSoThe28ConsumersActuallyGetIt() throws Exception {
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

    /**
     * The application class is declared through a {@code @Bean} FACTORY METHOD on purpose.
     * ConfigurationClassPostProcessor skips a bean definition that has a factory method, so the
     * fixture is registered with its {@code @SpringBootApplication} metadata intact and visible to
     * {@code getBeanNamesForAnnotation}, while its {@code @EnableAutoConfiguration} is not honoured
     * and cannot drag Boot's whole autoconfiguration set into this runner.
     */
    @Configuration(proxyBeanMethods = false)
    static class OneApplicationBean {

        @Bean
        CrgApplication crgApplication() {
            return new CrgApplication();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoApplicationBeans {

        @Bean
        CrgApplication crgApplication() {
            return new CrgApplication();
        }

        @Bean
        MrvApplication mrvApplication() {
            return new MrvApplication();
        }
    }
}
