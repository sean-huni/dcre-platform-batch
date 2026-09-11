package za.co.fnb.dcre.platform.batch.config;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

import za.co.fnb.dcre.platform.batch.telemetry.StageIdentity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The ONE place the stage token is resolved, so the environment post-processor and the
 * autoconfiguration cannot answer differently.
 *
 * <p>They read candidate application classes from different places, because they run at different
 * moments: the post-processor runs before any bean exists and asks {@link SpringApplication}, the
 * autoconfiguration runs during bean creation and asks the bean factory. That is a genuine
 * difference of SOURCE. The RULE applied to those candidates is this class, once.
 *
 * <p>The instance id is not re-derived by the second caller. {@link StageIdentity#of} falls back to
 * a fresh {@code local-<uuid>} when {@code HOSTNAME} is unset, so deriving it twice would put a
 * different value in the exported resource attribute than in the published bean. The
 * autoconfiguration therefore reads back what the post-processor published.
 */
final class StageIdentityResolver {

    static final String INSTANCE_ID_KEY =
            "management.opentelemetry.resource-attributes.service.instance.id";

    static final String NO_TOKEN =
            "no stage token could be resolved: nothing here is annotated @SpringBootApplication to "
            + "take a package leaf from. Set dcre.telemetry.stage explicitly. Publishing under a "
            + "guessed name is worse than publishing nothing, because the series looks healthy and "
            + "names no stage";

    static final String AMBIGUOUS =
            "more than one application package leaf resolved: a service with two service.name values "
            + "publishes two job labels, which is the defect this wiring exists to prevent. Set "
            + "dcre.telemetry.stage explicitly. Leaves: ";

    private StageIdentityResolver() {
    }

    static StageIdentity resolveOrThrow(final String explicitStage,
                                        final Collection<Class<?>> candidates,
                                        final String hostname) {
        return StageIdentity.of(token(explicitStage, candidates), hostname);
    }

    /**
     * The tolerant door, for the post-processor, which runs for EVERY Boot application that imports
     * this library including ones with no fleet-shaped package. Failing there would break them all
     * at startup. An unresolvable stage stays fatal in the autoconfiguration, and only when the
     * marker asks for telemetry.
     */
    static Optional<StageIdentity> resolveQuietly(final String explicitStage,
                                                  final Collection<Class<?>> candidates,
                                                  final String hostname) {
        try {
            return Optional.of(resolveOrThrow(explicitStage, candidates, hostname));
        } catch (final RuntimeException deliberatelyTolerated) {
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
            if (candidate == null
                    || !candidate.isAnnotationPresent(SpringBootApplication.class)) {
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

    /** Candidates during bean creation. {@code getType} forces no instantiation. */
    static List<Class<?>> candidatesFrom(final ListableBeanFactory beans) {
        final List<Class<?>> candidates = new ArrayList<>();
        for (final String name : beans.getBeanNamesForAnnotation(SpringBootApplication.class)) {
            candidates.add(beans.getType(name));
        }
        return candidates;
    }

    /**
     * Reads back the instance id the post-processor published, so both halves name the same process.
     * Absent it (no post-processor, as in a bare application-context test) this falls through to
     * {@code HOSTNAME} and then to {@link StageIdentity}'s own fallback.
     */
    static String instanceId(final Environment environment) {
        final String published = environment.getProperty(INSTANCE_ID_KEY);
        return (published != null && !published.isBlank()) ? published : System.getenv("HOSTNAME");
    }
}
