package za.co.fnb.dcre.platform.batch.config;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-90: proves the shared {@code dcre-batch-datasource.yml} resource loads and that
 * {@link BatchDatasourceEnvironmentPostProcessor} contributes the fleet READ COMMITTED default as a
 * LOW-precedence (overridable) property source. Fast unit test, no container.
 */
class BatchDatasourceEnvironmentPostProcessorTest {

    private static final String KEY = "spring.datasource.hikari.transaction-isolation";

    private final BatchDatasourceEnvironmentPostProcessor processor =
            new BatchDatasourceEnvironmentPostProcessor();

    @Test
    void contributesReadCommittedDefaultFromSharedResource() {
        final StandardEnvironment environment = new StandardEnvironment();

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(KEY)).isEqualTo("TRANSACTION_READ_COMMITTED");
    }

    @Test
    void defaultIsLowPrecedenceSoAServiceCanOverride() {
        final StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "service", Map.of(KEY, "TRANSACTION_SERIALIZABLE")));

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(KEY)).isEqualTo("TRANSACTION_SERIALIZABLE");
    }
}
