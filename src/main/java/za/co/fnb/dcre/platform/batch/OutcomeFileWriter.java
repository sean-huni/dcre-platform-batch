package za.co.fnb.dcre.platform.batch;

import za.co.fnb.dcre.platform.files.StagedWrite;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * SYNTHETIC-CONTRACT (R-35): the AGT-service business-verdict seam. Written
 * atomically; AGT treats absence as never-success (R-33 arbiter clause).
 */
public final class OutcomeFileWriter {

    private OutcomeFileWriter() {
    }

    public static void write(Path exchangeRoot, String jobName, String outcome) {
        try {
            StagedWrite.write(exchangeRoot.resolve("outcomes").resolve(jobName), List.of(outcome));
        } catch (IOException e) {
            throw new IllegalStateException("cannot write outcome seam for " + jobName, e);
        }
    }

    /**
     * Resolves the seam job name (SCRUM-58): env {@code JOB_NAME} when present
     * (the K8s Job name), else the self-describing local fallback
     * {@code local-<svc>-<executionId>}.
     */
    public static String jobNameOrLocal(final String svc, final long executionId) {
        return jobNameOrLocal(System.getenv(), svc, executionId);
    }

    /** Env-map seam for tests: never mutate the real environment. */
    static String jobNameOrLocal(final Map<String, String> env, final String svc, final long executionId) {
        return env.getOrDefault("JOB_NAME", "local-%s-%d".formatted(svc, executionId));
    }
}
