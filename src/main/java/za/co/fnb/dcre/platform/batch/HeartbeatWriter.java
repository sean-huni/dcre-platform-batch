package za.co.fnb.dcre.platform.batch;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * SCRUM-85 (R-47, M12 piece 2): while a Spring Batch stage job of THIS pod runs,
 * writes a heartbeat / last-writer owner stamp into {@code agt_ops.launch_intent} so
 * AGT (T3) can detect a wedged-but-alive pod (k8s Job still Running, step hung, no
 * Failed condition, so AGT's k8s-status orphan path never fires).
 *
 * <p><b>Not a fenced lease.</b> The write is an unconditional last-writer stamp of
 * {@code heartbeat_at}/{@code owner_pod} (no CAS, no fencing token); T5 consumers must
 * not assume mutual-exclusion semantics from {@code owner_pod}. Column-family
 * single-writer keeps it safe: AGT owns {@code status}/{@code attempt}, the running job
 * owns ONLY these two columns (same accepted pattern as the mandate-spine per-stage
 * columns), so there is no write contention on the intent row.
 *
 * <p><b>Gating (ref-counted).</b> This is registered as a {@link JobExecutionListener}
 * on the service's Job; {@code beforeJob}/{@code afterJob} maintain a running-job
 * ref-count so the {@code @Scheduled} {@link #tick()} heartbeats while ANY job of this
 * JVM runs. A plain boolean would let the first job's {@code afterJob} silently stop a
 * concurrent second job's heartbeat (false orphan); this is a 12x-shared component, so
 * it ref-counts.
 *
 * <p><b>Never fails a job.</b> No-op when no job runs or {@code JOB_NAME} is unset
 * (local/dev/test: {@code jobName} null). A 0-row UPDATE (intent absent) WARNs once. A
 * DB error (connection refused, permission denied) WARNs once per error class, naming
 * {@code dcre.agtops-db-url} and the agt_ops UPDATE grant as likely causes, so a
 * wired-but-wrong URL or a missing grant is VISIBLE, not silent and not ERROR-spam.
 * Both paths swallow: heartbeat trouble must not fail a running job. Identity is
 * constructor-injected ({@code HeartbeatDatasourceConfig} resolves it from env in
 * production), keeping this class env-free and directly unit-testable.
 */
public class HeartbeatWriter implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatWriter.class);
    private static final String UPDATE_HEARTBEAT =
            "UPDATE launch_intent SET heartbeat_at = now(), owner_pod = ? WHERE job_name = ?";

    private final JdbcTemplate agtOps;
    private final String jobName;
    private final String ownerPod;
    private final AtomicInteger runningJobs = new AtomicInteger(0);
    private final AtomicBoolean missingIntentWarned = new AtomicBoolean(false);
    private final Set<String> warnedErrorClasses = ConcurrentHashMap.newKeySet();

    public HeartbeatWriter(final JdbcTemplate agtOps, final String jobName, final String ownerPod) {
        this.agtOps = agtOps;
        this.jobName = jobName;
        this.ownerPod = ownerPod;
    }

    @Override
    public void beforeJob(final JobExecution jobExecution) {
        runningJobs.incrementAndGet();
    }

    @Override
    public void afterJob(final JobExecution jobExecution) {
        runningJobs.updateAndGet(count -> Math.max(0, count - 1));
    }

    @Scheduled(fixedDelayString = "${dcre.batch.heartbeat-seconds:10}000")
    public void tick() {
        if (runningJobs.get() <= 0 || jobName == null || jobName.isBlank()) {
            return;
        }
        try {
            final int updated = agtOps.update(UPDATE_HEARTBEAT, ownerPod, jobName);
            if (updated == 0 && missingIntentWarned.compareAndSet(false, true)) {
                log.warn("heartbeat: no launch_intent row for job_name={} (owner_pod={}); "
                        + "AGT cannot see this pod as alive", jobName, ownerPod);
            }
        } catch (final DataAccessException e) {
            if (warnedErrorClasses.add(e.getClass().getName())) {
                log.warn("heartbeat: agt_ops UPDATE failed ({}) for job_name={} (owner_pod={}); "
                        + "check dcre.agtops-db-url and that the stage-service DB role has UPDATE on "
                        + "agt_ops.launch_intent", e.getClass().getSimpleName(), jobName, ownerPod, e);
            }
        }
    }
}
