package za.co.fnb.dcre.platform.batch.telemetry;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.resources.Resource;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.logging.OpenTelemetryLoggingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import za.co.fnb.dcre.platform.batch.config.TelemetryAutoConfiguration;
import za.co.fnb.dcre.platform.batch.config.properties.TelemetryProperties;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The log signal, proved end to end inside the JVM: the appender is ATTACHED, and a record a service
 * writes actually REACHES the SDK carrying the same resource identity as that service's metrics.
 *
 * <p><strong>Attachment alone is the failure this class exists to catch.</strong>
 * {@code OpenTelemetryAppender.install(OpenTelemetry)} walks the logger context and hands each
 * attached appender its {@code OpenTelemetry}; an appender that never receives one buffers a bounded
 * number of events and then drops everything, silently, forever. So a configuration that attaches
 * without installing is indistinguishable from a working log pipeline by inspection, by startup
 * logs, and by any assertion about configuration. Only a record arriving at an exporter separates
 * them, which is why {@link #aLogRecordActuallyReachesTheSdkAndCarriesTheStagesResourceIdentity}
 * exists beside the attachment case rather than instead of it.
 *
 * <p><strong>Why this boots a real {@code SpringApplication} instead of using
 * {@code ApplicationContextRunner}</strong>, which is this module's idiom everywhere else. The
 * {@code -spring} suffix is Boot's, not logback's: plain logback only ever looks for
 * {@code logback-test.xml} and {@code logback.xml}, and {@code logback-spring.xml} is found solely
 * by {@code LogbackLoggingSystem}, which {@code LoggingApplicationListener} drives from
 * {@code ApplicationStartingEvent}. An {@code ApplicationContextRunner} fires no such event, so a
 * test built on it would pass or fail on something other than the file this library ships. The
 * running thing is the authority.
 *
 * <p>Boot's own {@code OpenTelemetrySdkAutoConfiguration} and
 * {@code OpenTelemetryLoggingAutoConfiguration} are imported rather than hand-rolled, so the
 * {@code Resource} under test is the one production builds, derived from the very properties
 * {@code TelemetryEnvironmentPostProcessor} publishes. Only the exporter is substituted, which is
 * the one hop that would otherwise need a collector.
 */
class LogExportTest {

    /** Distinctive enough that no framework line can satisfy the assertion by accident. */
    private static final String PROBE = "dcre-log-export-probe-8f13";

    private static final String STAGE_TOKEN = "crg";

    private static final String SERVICE_NAME = "dcre-" + STAGE_TOKEN;

    private static final CollectingLogRecordExporter EXPORTER = new CollectingLogRecordExporter();

    @Test
    void theOpenTelemetryAppenderIsAttachedWhenTelemetryIsEnabled() {
        try (ConfigurableApplicationContext ignored = boot()) {
            final ch.qos.logback.classic.Logger root =
                    (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            boolean attached = false;
            for (Iterator<Appender<ILoggingEvent>> it = root.iteratorForAppenders(); it.hasNext(); ) {
                if (it.next().getClass().getName().contains("OpenTelemetryAppender")) {
                    attached = true;
                }
            }
            assertThat(attached)
                    .as("the OTLP logback appender must be attached, or the dependency is inert")
                    .isTrue();
        }
    }

    @Test
    void aLogRecordActuallyReachesTheSdkAndCarriesTheStagesResourceIdentity() {
        EXPORTER.clear();
        try (ConfigurableApplicationContext context = boot()) {
            LoggerFactory.getLogger(LogExportTest.class).info(PROBE);
            // BatchLogRecordProcessor is asynchronous, so an assertion without this flush would be
            // a race that usually passes. Flushing through the SDK's own handle keeps it
            // deterministic without a sleep.
            context.getBean(SdkLoggerProvider.class).forceFlush().join(10, TimeUnit.SECONDS);

            final List<LogRecordData> delivered = EXPORTER.containing(PROBE);
            assertThat(delivered)
                    .as("no record reached the SDK. Attaching the appender WITHOUT calling "
                        + "OpenTelemetryAppender.install(openTelemetry) leaves it attached and drops "
                        + "every record silently, which reads as a configured log pipeline that "
                        + "ships nothing")
                    .isNotEmpty();

            final Resource resource = delivered.getFirst().getResource();
            assertThat(resource.getAttribute(AttributeKey.stringKey("service.name")))
                    .as("a log line, a counter and a span from one file arrival must join on the "
                        + "SAME service.name, or the Logs board cannot be filtered to the stage "
                        + "whose metric spiked")
                    .isEqualTo(SERVICE_NAME);
            assertThat(resource.getAttribute(AttributeKey.stringKey("service.instance.id")))
                    .as("service.instance.id is what separates one replica's records from another's,"
                        + " exactly as it separates their metric series")
                    .isNotBlank();
        }
    }

    /**
     * The refusal is a control, so it gets a run of its own. Without this case
     * {@code NO_OPEN_TELEMETRY} is a message no execution ever produces, which is the shape a
     * constant drifts in: declared, plausible, and never read.
     *
     * <p>Asserted on a LITERAL substring rather than on the constant. Building the expectation by
     * calling the producer would pass for any message body at all, including an empty one, which is
     * the tautology {@code TemporalityIT} already red-proofed itself against. The constant is
     * package-private to {@code ...batch.config} in any case, and this test deliberately lives beside
     * the log signal it guards.
     */
    @Test
    void aContextThatAsksForTelemetryWithNoSdkIsRefusedRatherThanLeftDroppingRecords() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TelemetryAutoConfiguration.class))
                .withPropertyValues(TelemetryProperties.ENABLED + "=true",
                                    "spring.application.name=" + SERVICE_NAME,
                                    "management.opentelemetry.resource-attributes.service.instance.id"
                                    + "=" + SERVICE_NAME + "-9f4c2")
                .run(context -> {
                    assertThat(context).hasFailed();
                    // On the startup failure ITSELF, not rootCause(). A SmartInitializingSingleton
                    // throws out of preInstantiateSingletons unwrapped, so there is no cause to
                    // unwrap and rootCause() fails with a misleading message. Measured 2026-09-11.
                    // Same shape as TemporalityIT's BeanFactoryPostProcessor guard; the identity
                    // failures in TelemetryAutoConfigurationTest DO wrap, because those are thrown
                    // during bean creation.
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("OpenTelemetryAppender.install(...) cannot be "
                                                  + "called")
                            .hasMessageContaining("ships nothing");
                });
    }

    /**
     * A real {@code SpringApplication}, because only that fires the event that makes Boot read
     * {@code logback-spring.xml}. Command-line arguments rather than default properties: defaults
     * sit at the bottom of the precedence order beside the very source this library contributes,
     * and a fixture that can be outranked by the code under test proves nothing.
     */
    private static ConfigurableApplicationContext boot() {
        final SpringApplication application = new SpringApplication(Fixture.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        return application.run("--" + TelemetryProperties.ENABLED + "=true",
                               "--" + TelemetryProperties.STAGE + "=" + STAGE_TOKEN);
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({OpenTelemetrySdkAutoConfiguration.class,
                              OpenTelemetryLoggingAutoConfiguration.class,
                              TelemetryAutoConfiguration.class})
    static class Fixture {

        /**
         * The only substitution. Boot's {@code OpenTelemetryLoggingAutoConfiguration} wires whatever
         * {@code LogRecordExporter} beans exist into its processor, so replacing the OTLP exporter
         * with a collecting one leaves every other hop, including the {@code Resource}, exactly as
         * a stage pod builds it.
         */
        @Bean
        LogRecordExporter dcreTestLogRecordExporter() {
            return EXPORTER;
        }
    }

    /** Records what the SDK handed the exporter, which is the only evidence that anything shipped. */
    private static final class CollectingLogRecordExporter implements LogRecordExporter {

        private final List<LogRecordData> records = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(final Collection<LogRecordData> logs) {
            records.addAll(logs);
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
            records.clear();
        }

        List<LogRecordData> containing(final String body) {
            final List<LogRecordData> matching = new ArrayList<>();
            for (final LogRecordData record : records) {
                if (record.getBodyValue() != null
                    && record.getBodyValue().asString().contains(body)) {
                    matching.add(record);
                }
            }
            return matching;
        }
    }
}
