package za.co.fnb.dcre.platform.batch;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * SYNTHETIC-CONTRACT (R-33/R-35): the ONE shared business-verdict seam
 * listener (SCRUM-58), replacing the 12 near-identical per-module
 * SeamListener records. Technical death (status != COMPLETED) writes
 * nothing: the exit code and the K8s Failed condition are the witnesses
 * (R-33 arbiter clause). The optional owner-local persistence hook runs
 * BEFORE the outcome file write: the DB row commits write-ahead of the
 * filesystem effect.
 */
public final class OutcomeSeamListener implements JobExecutionListener {

    /** The resolved seam outcome handed to the owner-local persistence hook. */
    public record SeamOutcome(String jobName, String verdict) {
    }

    private static final Consumer<SeamOutcome> NO_OP_HOOK = outcome -> {
    };

    private final String svc;
    private final String exchangeRoot;
    private final Function<JobExecution, String> verdict;
    private final Consumer<SeamOutcome> persistenceHook;

    public OutcomeSeamListener(final String svc, final String exchangeRoot,
                               final Function<JobExecution, String> verdict) {
        this(svc, exchangeRoot, verdict, NO_OP_HOOK);
    }

    public OutcomeSeamListener(final String svc, final String exchangeRoot,
                               final Function<JobExecution, String> verdict,
                               final Consumer<SeamOutcome> persistenceHook) {
        this.svc = Objects.requireNonNull(svc, "svc");
        this.exchangeRoot = Objects.requireNonNull(exchangeRoot, "exchangeRoot");
        this.verdict = Objects.requireNonNull(verdict, "verdict");
        this.persistenceHook = Objects.requireNonNull(persistenceHook, "persistenceHook");
    }

    @Override
    public void afterJob(final JobExecution execution) {
        if (execution.getStatus() != BatchStatus.COMPLETED) {
            return; // technical death: exit code + K8s condition are the witnesses (R-33)
        }
        final String jobName = OutcomeFileWriter.jobNameOrLocal(svc, execution.getId());
        final String v = verdict.apply(execution);
        persistenceHook.accept(new SeamOutcome(jobName, v));
        OutcomeFileWriter.write(Path.of(exchangeRoot), jobName, v);
    }
}
