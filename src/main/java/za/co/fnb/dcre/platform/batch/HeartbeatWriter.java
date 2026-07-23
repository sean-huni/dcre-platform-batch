package za.co.fnb.dcre.platform.batch;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * SCRUM-85 (R-47, M12 piece 2): while a Spring Batch stage job of THIS pod runs,
 * writes a liveness heartbeat into {@code agt_ops.launch_intent} so AGT (T3) can
 * detect a wedged-but-alive pod (k8s Job still Running, step hung, no Failed
 * condition, so AGT's k8s-status orphan path never fires).
 *
 * <p><b>Column-family single-writer.</b> AGT owns {@code status}/{@code attempt};
 * the running job owns ONLY {@code heartbeat_at} + {@code owner_pod} (same accepted
 * pattern as the mandate-spine per-stage columns), so there is no write contention
 * on the intent row.
 *
 * <p><b>Gating.</b> This is registered as a {@link JobExecutionListener} on the
 * service's Job; {@code beforeJob}/{@code afterJob} flip a running flag so the
 * {@code @Scheduled} {@link #tick()} heartbeats only between job start and end. It
 * is a clean no-op when no job of this pod runs or when {@code JOB_NAME} is unset
 * (local/dev/test: {@code jobName} is null). If the UPDATE hits 0 rows (the intent
 * row is absent) it WARNs once and never throws, so a bookkeeping gap can never
 * fail a running job.
 *
 * <p>The jobName/pod identity is constructor-injected ({@code HeartbeatDatasourceConfig}
 * resolves it from the {@code JOB_NAME}/{@code HOSTNAME} env in production), which keeps
 * this class env-free and directly unit-testable.
 */
public class HeartbeatWriter implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatWriter.class);
    private static final String UPDATE_HEARTBEAT =
            "UPDATE launch_intent SET heartbeat_at = now(), owner_pod = ? WHERE job_name = ?";

    private final JdbcTemplate agtOps;
    private final String jobName;
    private final String ownerPod;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean missingIntentWarned = new AtomicBoolean(false);

    public HeartbeatWriter(final JdbcTemplate agtOps, final String jobName, final String ownerPod) {
        this.agtOps = agtOps;
        this.jobName = jobName;
        this.ownerPod = ownerPod;
    }

    @Override
    public void beforeJob(final JobExecution jobExecution) {
        running.set(true);
    }

    @Override
    public void afterJob(final JobExecution jobExecution) {
        running.set(false);
    }

    @Scheduled(fixedDelayString = "${dcre.batch.heartbeat-seconds:10}000")
    public void tick() {
        if (!running.get() || jobName == null || jobName.isBlank()) {
            return;
        }
        final int updated = agtOps.update(UPDATE_HEARTBEAT, ownerPod, jobName);
        if (updated == 0 && missingIntentWarned.compareAndSet(false, true)) {
            log.warn("heartbeat: no launch_intent row for job_name={} (owner_pod={}); "
                    + "AGT cannot see this pod as alive", jobName, ownerPod);
        }
    }
}
