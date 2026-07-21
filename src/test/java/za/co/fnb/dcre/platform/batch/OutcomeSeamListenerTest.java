package za.co.fnb.dcre.platform.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OutcomeSeamListenerTest {

    private static JobExecution execution(final long id, final BatchStatus status) {
        final JobExecution execution = new JobExecution(id, new JobInstance(1L, "tstJob"), new JobParameters());
        execution.setStatus(status);
        return execution;
    }

    @Test
    void envJobNamePresentWinsExactly() {
        assertEquals("dcre-cir-20260716-1200",
                OutcomeFileWriter.jobNameOrLocal(
                        Map.of("JOB_NAME", "dcre-cir-20260716-1200"), "cir", 42L));
    }

    @Test
    void envJobNameAbsentFallsBackToLocalSvcExecutionId() {
        assertEquals("local-cir-42", OutcomeFileWriter.jobNameOrLocal(Map.of(), "cir", 42L));
    }

    @Test
    void publicJobNameOrLocalReadsRealEnvironment() {
        assumeTrue(System.getenv("JOB_NAME") == null, "test JVM must not carry JOB_NAME");
        assertEquals("local-rpt-7", OutcomeFileWriter.jobNameOrLocal("rpt", 7L));
    }

    @Test
    void hookReceivesSeamOutcomeBeforeOutcomeFileExists(@TempDir final Path root) {
        assumeTrue(System.getenv("JOB_NAME") == null, "test JVM must not carry JOB_NAME");
        final Path seamFile = root.resolve("outcomes").resolve("local-tst-42");
        final AtomicReference<OutcomeSeamListener.SeamOutcome> seen = new AtomicReference<>();
        final AtomicBoolean fileExistedAtHookTime = new AtomicBoolean(true);

        final OutcomeSeamListener listener = new OutcomeSeamListener("tst", root.toString(),
                e -> "BUSINESS_ACCEPTED",
                outcome -> {
                    seen.set(outcome);
                    fileExistedAtHookTime.set(Files.exists(seamFile));
                });
        listener.afterJob(execution(42L, BatchStatus.COMPLETED));

        assertEquals(new OutcomeSeamListener.SeamOutcome("local-tst-42", "BUSINESS_ACCEPTED"), seen.get());
        assertFalse(fileExistedAtHookTime.get(), "persistence hook must run BEFORE the file write");
        assertTrue(Files.exists(seamFile), "outcome seam file must exist after afterJob");
    }

    @Test
    void verdictFunctionOutputWrittenByteExact(@TempDir final Path root) throws Exception {
        assumeTrue(System.getenv("JOB_NAME") == null, "test JVM must not carry JOB_NAME");
        final OutcomeSeamListener listener = new OutcomeSeamListener("ctv", root.toString(),
                e -> "BUSINESS_PARTIAL_for_execution_" + e.getId());
        listener.afterJob(execution(9L, BatchStatus.COMPLETED));

        final Path seamFile = root.resolve("outcomes").resolve("local-ctv-9");
        final byte[] expected = ("BUSINESS_PARTIAL_for_execution_9" + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(expected, Files.readAllBytes(seamFile));
    }

    @Test
    void defaultHookIsNoOp(@TempDir final Path root) {
        assumeTrue(System.getenv("JOB_NAME") == null, "test JVM must not carry JOB_NAME");
        new OutcomeSeamListener("crr", root.toString(), e -> "BUSINESS_ACCEPTED")
                .afterJob(execution(3L, BatchStatus.COMPLETED));
        assertTrue(Files.exists(root.resolve("outcomes").resolve("local-crr-3")));
    }

    @Test
    void technicalDeathWritesNothingAndSkipsHook(@TempDir final Path root) {
        final AtomicBoolean hookInvoked = new AtomicBoolean(false);
        final OutcomeSeamListener listener = new OutcomeSeamListener("tst", root.toString(),
                e -> "BUSINESS_ACCEPTED", outcome -> hookInvoked.set(true));
        listener.afterJob(execution(5L, BatchStatus.FAILED));

        assertFalse(hookInvoked.get(), "hook must not fire on technical death (R-33)");
        assertFalse(Files.exists(root.resolve("outcomes")), "no seam artifact on technical death (R-33)");
    }
}
