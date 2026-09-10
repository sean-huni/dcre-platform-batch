package za.co.fnb.dcre.platform.batch;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Child process of {@link ExitCodeMainForkIT}: a stage service in miniature. A
 * bare @Configuration on purpose, with no @EnableAutoConfiguration, so the fork
 * needs no DataSource and no Docker and still exercises the real
 * SpringApplication startup that ExitCodeMain wraps.
 *
 * <p>Which failure it stages is chosen entirely by the command line:
 * an unresolvable {@code spring.config.import} dies before the runner phase,
 * {@code --harness.fail-in-runner=true} dies inside it, no argument runs clean.
 */
public final class ExitCodeMainForkHarness {

    /** Set on the command line to make the runner phase, and only it, fail. */
    static final String FAIL_IN_RUNNER = "harness.fail-in-runner";

    @Configuration(proxyBeanMethods = false)
    static class HarnessApp {

        @Bean
        ApplicationRunner harnessRunner(final Environment env) {
            return args -> {
                if (env.getProperty(FAIL_IN_RUNNER, Boolean.class, false)) {
                    throw new IllegalStateException("harness: runner-phase failure");
                }
            };
        }
    }

    private ExitCodeMainForkHarness() {
    }

    public static void main(final String[] args) {
        ExitCodeMain.run(HarnessApp.class, args);
    }
}
