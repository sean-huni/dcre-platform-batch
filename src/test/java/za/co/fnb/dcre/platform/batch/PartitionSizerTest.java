package za.co.fnb.dcre.platform.batch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R-41: partition count = clamp(availableProcessors, 1, maxPartitions); cgroup-aware. */
class PartitionSizerTest {

    @Test
    void clampsToMaxWhenCpusExceedIt() {
        assertEquals(5, PartitionSizer.partitions(5, 12));
    }

    @Test
    void usesCpuCountWhenBelowMax() {
        assertEquals(2, PartitionSizer.partitions(5, 2)); // typical 2-CPU pod
    }

    @Test
    void floorsAtOne() {
        assertEquals(1, PartitionSizer.partitions(5, 0));
        assertEquals(1, PartitionSizer.partitions(0, 8)); // max 0 misconfig -> sequential
    }

    @Test
    void runtimeVariantStaysWithinBounds() {
        int p = PartitionSizer.partitions(5);
        assertTrue(p >= 1 && p <= 5, "got " + p);
    }
}
