package za.co.fnb.dcre.platform.batch.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.repository.dao.jdbc.JdbcStepExecutionDao;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-101 tripwire: {@link PortalSafeStepExecutionDao} and
 * {@link PortalSafeJobRepositoryFactoryBean} were written by reading spring-batch-core's
 * sources line by line, and both cite those line numbers. They are only trustworthy
 * against the exact release they were read against.
 *
 * <p>The compiler cannot enforce that. This module pins its own {@code springBatchVersion}
 * ({@code gradle.properties}) and takes spring-batch-core {@code compileOnly}, so it
 * compiles against that pin whatever a consuming service resolves: a fleet-wide Batch bump
 * raises no compile error here at all, and the override just keeps running against the
 * consumer's runtime version. This test is the guard instead. Move the pin and the build
 * goes red until someone re-reads the two overrides against the new sources and moves
 * {@link #VERIFIED_AGAINST} deliberately.
 *
 * <p>It fires on the move that matters, an upgrade: the pin and the Spring Boot BOM both
 * feed the test runtime classpath and Gradle resolves the higher of the two, so raising
 * either one lands a new jar here. A deliberate DOWNgrade below the BOM's own managed
 * version does not change what resolves and does not fire.
 *
 * <p><b>Why the manifest is the authority.</b> The version is not re-declared from
 * {@code build.gradle} (a copy of the pin cannot detect the pin changing). It is read out
 * of the jar that actually supplied {@link JdbcStepExecutionDao} to this JVM, located
 * through that class's own {@code CodeSource}. {@code Implementation-Title} and
 * {@code Implementation-Version} are written into {@code META-INF/MANIFEST.MF} by Spring
 * Batch's own build from the artifact's coordinates, so they identify the compiled bytes
 * on the classpath rather than anything this repository asserts about them.
 */
class PortalSafeOverrideGuardTest {

    /** The spring-batch-core release both overrides were read against, line by line. */
    private static final String VERIFIED_AGAINST = "6.0.4";

    @Test
    @DisplayName("spring-batch-core resolves to the release the portal-safe overrides were verified against")
    void springBatchCoreResolvesToTheVerifiedRelease() throws Exception {
        final Attributes manifest = manifestOf(JdbcStepExecutionDao.class).getMainAttributes();

        assertThat(manifest.getValue("Implementation-Title"))
                .as("the jar supplying JdbcStepExecutionDao")
                .isEqualTo("spring-batch-core");
        assertThat(manifest.getValue("Implementation-Version"))
                .as("""
                        spring-batch-core moved off the release PortalSafeStepExecutionDao and \
                        PortalSafeJobRepositoryFactoryBean were read against. Re-read both against the \
                        new sources (getLastStepExecution, getStepExecution(long), createStepExecutionDao, \
                        determineClobTypeToUse), re-check every cited line number, re-run \
                        PortalSafeStepExecutionDaoIT, and only then move VERIFIED_AGAINST.""")
                .isEqualTo(VERIFIED_AGAINST);
    }

    @Test
    @DisplayName("the JVM agrees with the manifest about the resolved spring-batch-core release")
    void thePackageImplementationVersionAgreesWithTheManifest() {
        assertThat(JdbcStepExecutionDao.class.getPackage().getImplementationVersion())
                .isEqualTo(VERIFIED_AGAINST);
    }

    private static Manifest manifestOf(final Class<?> type) throws Exception {
        final URI jar = type.getProtectionDomain().getCodeSource().getLocation().toURI();
        try (JarFile file = new JarFile(Path.of(jar).toFile())) {
            return file.getManifest();
        }
    }
}
