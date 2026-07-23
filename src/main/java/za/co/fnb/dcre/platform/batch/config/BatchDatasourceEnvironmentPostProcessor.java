package za.co.fnb.dcre.platform.batch.config;

import java.io.IOException;
import java.util.List;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * SCRUM-90 (M12): contributes the fleet-wide CockroachDB READ COMMITTED default for the batch
 * datasource to every service that depends on platform-batch, from ONE shared resource
 * ({@code classpath:dcre-batch-datasource.yml}).
 *
 * <p>Registered in {@code META-INF/spring.factories} under
 * {@code org.springframework.boot.EnvironmentPostProcessor}; Boot discovers it during environment
 * preparation for every {@code SpringApplication} whose classpath carries platform-batch. All 13
 * stage services {@code @Import(BatchJdbcConfig.class)}, so all 13 carry the jar and inherit the
 * default. The loaded property source is added LAST (lowest precedence): it is a DEFAULT a service
 * may still override, while {@link ReadCommittedStartupAssertion} fails startup for any pool that
 * is not actually READ COMMITTED.
 *
 * <p><b>Why an EnvironmentPostProcessor, not a per-service {@code spring.config.import} line.</b>
 * Only the 3 writer services import {@code classpath:dcre-exchange-layout.yml} and the non-writer
 * services carry no {@code spring.config.import} block at all, so a config-import line is not a
 * single source of truth. This mirrors the existing platform-batch pattern of shipping
 * cross-cutting behavior via {@code META-INF/spring} discovery ({@code ExchangeAutoConfiguration})
 * and binds through Boot's native Hikari wiring with no reflection or pool mutation.
 */
public class BatchDatasourceEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String RESOURCE = "dcre-batch-datasource.yml";

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    @Override
    public void postProcessEnvironment(final ConfigurableEnvironment environment,
                                       final SpringApplication application) {
        final Resource resource = new ClassPathResource(RESOURCE);
        if (!resource.exists()) {
            return;
        }
        try {
            final List<PropertySource<?>> sources = loader.load(RESOURCE, resource);
            for (final PropertySource<?> source : sources) {
                environment.getPropertySources().addLast(source);
            }
        } catch (final IOException e) {
            throw new IllegalStateException(
                    "failed to load %s for the fleet READ COMMITTED batch default".formatted(RESOURCE), e);
        }
    }
}
