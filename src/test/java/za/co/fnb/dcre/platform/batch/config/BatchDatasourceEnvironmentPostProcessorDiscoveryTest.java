package za.co.fnb.dcre.platform.batch.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-90: end-to-end proof that {@link BatchDatasourceEnvironmentPostProcessor} is DISCOVERED via
 * {@code META-INF/spring.factories} during a real {@code SpringApplication} bootstrap and
 * contributes the fleet READ COMMITTED default to the environment. The unit test proves the EPP
 * logic; this proves the registration/auto-discovery that every stage service relies on. No
 * container: only the {@code Environment} is inspected (no datasource autoconfig is triggered).
 */
class BatchDatasourceEnvironmentPostProcessorDiscoveryTest {

    @Test
    void readCommittedDefaultIsAutoDiscoveredIntoEnvironment() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(EmptyApp.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off")
                .run()) {
            final ConfigurableEnvironment environment = context.getEnvironment();
            assertThat(environment.getProperty("spring.datasource.hikari.transaction-isolation"))
                    .isEqualTo("TRANSACTION_READ_COMMITTED");
        }
    }

    @Configuration
    static class EmptyApp {
    }
}
