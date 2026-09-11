package za.co.fnb.dcre.platform.batch.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.util.ClassUtils;

import za.co.fnb.dcre.platform.batch.config.properties.TelemetryProperties;
import za.co.fnb.dcre.platform.batch.telemetry.StageIdentity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The rule that turns a set of candidate application classes into a validated {@link StageIdentity}.
 *
 * <p>Resolution happens ONCE, in {@link TelemetryEnvironmentPostProcessor}, before any bean exists.
 * {@link TelemetryAutoConfiguration} does not resolve anything: it reads back what was published, so
 * the bean cannot name the service differently from what is actually being exported.
 *
 * <p>An earlier version resolved a second time from the bean factory. That was removed for two
 * reasons. It was a second home for one fact, with no fixture able to express a disagreement. And
 * {@code ListableBeanFactory.getBeanNamesForAnnotation} passes {@code allowFactoryBeanInit=true},
 * so it walks every bean definition in a consumer's context and may instantiate FactoryBeans
 * mid-refresh. This library itself ships a {@code FactoryBean} subclass, so consumers demonstrably
 * have them, and that path had never executed inside a real Boot context anywhere.
 */
final class StageIdentityResolver {

    static final String NO_TOKEN =
            "no stage token could be resolved: nothing handed to SpringApplication is annotated "
            + "@SpringBootApplication, so there is no package leaf to take. Set "
            + TelemetryProperties.STAGE + " explicitly. Publishing under a guessed name is worse "
            + "than publishing nothing, because the series looks healthy and names no stage";

    static final String AMBIGUOUS =
            "more than one application package leaf resolved: a service with two service.name values "
            + "publishes two job labels, which is the defect this wiring exists to prevent. Set "
            + TelemetryProperties.STAGE + " explicitly. Leaves: ";

    private StageIdentityResolver() {
    }

    /**
     * The tolerant door. The post-processor runs for EVERY Boot application that imports this
     * library, including ones with no fleet-shaped package, so failing here would break them all at
     * startup. An unresolvable stage stays fatal in {@link TelemetryAutoConfiguration}, and only
     * when the marker asks for telemetry.
     *
     * <p>The catch is narrowed to the two types the rule below actually throws. A bare
     * {@code RuntimeException} would also swallow a programming error in this class and report it as
     * "no identity", which is the quietest possible way to lose the whole feature.
     */
    static Optional<StageIdentity> resolveQuietly(final String explicitStage,
                                                  final Collection<Class<?>> candidates,
                                                  final String hostname) {
        try {
            return Optional.of(StageIdentity.of(token(explicitStage, candidates), hostname));
        } catch (final IllegalStateException | IllegalArgumentException deliberatelyTolerated) {
            return Optional.empty();
        }
    }

    private static String token(final String explicitStage, final Collection<Class<?>> candidates) {
        if (explicitStage != null && !explicitStage.isBlank()) {
            return explicitStage.trim();
        }
        final Set<String> leaves = packageLeaves(candidates);
        if (leaves.size() == 1) {
            return leaves.iterator().next();
        }
        if (leaves.isEmpty()) {
            throw new IllegalStateException(NO_TOKEN);
        }
        throw new IllegalStateException(AMBIGUOUS + leaves);
    }

    private static Set<String> packageLeaves(final Collection<Class<?>> candidates) {
        final Set<String> leaves = new LinkedHashSet<>();
        for (final Class<?> candidate : candidates) {
            if (candidate == null || !candidate.isAnnotationPresent(SpringBootApplication.class)) {
                continue;
            }
            final String pkg = ClassUtils.getUserClass(candidate).getPackageName();
            final String leaf = pkg.substring(pkg.lastIndexOf('.') + 1);
            if (!leaf.isBlank()) {
                leaves.add(leaf);
            }
        }
        return leaves;
    }

    /** Candidates before any bean exists: whatever was handed to {@code SpringApplication.run}. */
    static List<Class<?>> candidatesFrom(final SpringApplication application) {
        final List<Class<?>> candidates = new ArrayList<>();
        for (final Object source : application.getAllSources()) {
            if (source instanceof Class<?> type) {
                candidates.add(type);
            }
        }
        candidates.add(application.getMainApplicationClass());
        return candidates;
    }
}
