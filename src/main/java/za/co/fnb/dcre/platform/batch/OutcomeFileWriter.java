package za.co.fnb.dcre.platform.batch;

import za.co.fnb.dcre.platform.files.StagedWrite;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

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
}
