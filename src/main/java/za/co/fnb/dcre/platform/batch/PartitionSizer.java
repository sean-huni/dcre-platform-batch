package za.co.fnb.dcre.platform.batch;

/**
 * R-41 partition sizing: pods carry small CPU limits (often 2), so the split
 * is computed from the runtime, never assumed. availableProcessors() is
 * cgroup-aware on modern JDKs and reflects the pod limit, not the node.
 */
public final class PartitionSizer {

    private PartitionSizer() {
    }

    public static int partitions(int maxPartitions) {
        return partitions(maxPartitions, Runtime.getRuntime().availableProcessors());
    }

    static int partitions(int maxPartitions, int availableCpus) {
        return Math.max(1, Math.min(availableCpus, maxPartitions));
    }
}
