package za.co.fnb.dcre.platform.batch;

import org.springframework.boot.SpringApplication;

import java.util.OptionalInt;

/** R-34 exit-code wiring: the JVM exit code carries the Batch outcome. */
public final class ExitCodeMain {

    /**
     * Reserved for any failure BEFORE the runner phase: an unresolvable config
     * import, an unbindable property, a datasource that will not open at startup.
     * That is infrastructure, not a job verdict, so AGT classifies exit 78 as
     * TECH_CONFIG_FAILED and spends its own bounded budget instead of burning the
     * TECH_FAILED orphan budget on a defect-free arrival.
     *
     * <p>78 is EX_CONFIG from sysexits.h, the conventional "configuration error"
     * status, and it is the only quiet neighbourhood left. 0-7 are all claimed
     * (Boot's JobExecutionExitCodeGenerator returns BatchStatus.ordinal(), and 1
     * doubles as the JVM's uncaught-exception status); 126 and 127 are
     * shell-reserved (found-but-not-executable, not-found); 128+N are signal
     * deaths, of which 137 (SIGKILL) and 143 (SIGTERM) are load-bearing for the
     * chaos gate and must never be minted by application code.
     */
    public static final int CONFIG_FAILURE_EXIT_CODE = 78;

    private ExitCodeMain() {
    }

    public static void run(final Class<?> app, final String[] args) {
        final RunnerPhaseGate gate = new RunnerPhaseGate();
        final SpringApplication application = new SpringApplication(app);
        application.addListeners(gate);
        try {
            System.exit(SpringApplication.exit(application.run(args)));
        } catch (Throwable failure) {
            final OptionalInt reserved = exitCodeFor(gate.reached(), failure);
            if (reserved.isEmpty()) {
                throw failure; // job-phase death: propagate unchanged, the JVM still reports 1
            }
            System.exit(reserved.getAsInt());
        }
    }

    /**
     * Classification seam, unit-testable without forking a JVM: the reserved code
     * when the failure landed before the runner phase, else empty, meaning "not an
     * infrastructure failure, leave today's behaviour alone".
     *
     * @param runnerPhaseReached whether Boot got as far as publishing ApplicationStartedEvent
     * @param failure            the startup failure, or null when the run completed
     */
    static OptionalInt exitCodeFor(final boolean runnerPhaseReached, final Throwable failure) {
        return failure != null && !runnerPhaseReached
                ? OptionalInt.of(CONFIG_FAILURE_EXIT_CODE)
                : OptionalInt.empty();
    }
}
