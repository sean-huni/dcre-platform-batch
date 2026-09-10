package za.co.fnb.dcre.platform.batch;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The only test that can prove the contract AGT actually observes: a unit test on
 * ExitCodeMain.exitCodeFor passes happily while the production process still exits
 * 1, because the code AGT reads is the POD's status, not a returned int. So this
 * forks a real JVM on the real classpath and asserts the real exit code.
 *
 * <p>No Docker: the harness is a bare @Configuration with no DataSource. The
 * classpath arrives as a system property injected by the Gradle test task; without
 * it (a bare IDE run) the test skips rather than guesses, so it can never go flaky.
 */
class ExitCodeMainForkIT {

    /** Injected by build.gradle; see the comment on the test task. */
    private static final String CLASSPATH_PROPERTY = "dcre.fork.classpath";

    private static final int FORK_TIMEOUT_SECONDS = 180;

    private record Fork(int exitCode, String output) {
    }

    private static Fork fork(final String... args) throws Exception {
        final String classpath = System.getProperty(CLASSPATH_PROPERTY);
        assumeTrue(classpath != null,
                "%s not set: run this through ./gradlew test, which injects the fork classpath"
                        .formatted(CLASSPATH_PROPERTY));

        final List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, ExitCodeMainForkHarness.class.getName()));
        command.addAll(List.of(args));

        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(FORK_TIMEOUT_SECONDS, TimeUnit.SECONDS), "forked JVM never terminated");
        return new Fork(process.exitValue(), output);
    }

    @Test
    void configImportFailureLeavesTheReservedCodeOnTheProcess() throws Exception {
        final Fork fork = fork("--spring.config.import=classpath:dcre-no-such-config.yml");
        assertEquals(ExitCodeMain.CONFIG_FAILURE_EXIT_CODE, fork.exitCode(),
                () -> "pre-runner failure must exit 78, not 1:\n" + fork.output());
    }

    @Test
    void runnerPhaseFailureStillExitsOne() throws Exception {
        // The regression that matters most: a real job failure must stay
        // TECH_FAILED, so the reserved code must NOT leak onto it.
        final Fork fork = fork("--%s=true".formatted(ExitCodeMainForkHarness.FAIL_IN_RUNNER));
        assertEquals(1, fork.exitCode(), () -> "runner-phase failure must be unchanged:\n" + fork.output());
        assertTrue(fork.output().contains("harness: runner-phase failure"),
                () -> "the failure must still be reported:\n" + fork.output());
    }

    @Test
    void cleanRunStillExitsZero() throws Exception {
        final Fork fork = fork();
        assertEquals(0, fork.exitCode(), () -> "clean run must exit 0:\n" + fork.output());
    }
}
