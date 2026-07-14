package za.co.fnb.dcre.platform.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.repeat.RepeatContext;
import org.springframework.batch.infrastructure.repeat.exception.ExceptionHandler;
import org.springframework.dao.TransientDataAccessException;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Step-level retry for CockroachDB serialization aborts (SQLSTATE 40001),
 * which surface as TransientDataAccessException subclasses, e.g.
 * CannotAcquireLockException on "JDBC commit". 40001 aborts are NORMAL under
 * contention (persistence.md): retry with backoff, never skip.
 *
 * <p>Why this covers COMMIT-time aborts (the observed failure mode): a tasklet
 * step's transaction commit happens inside TaskletStep's repeat loop
 * (stepOperations.iterate wraps the chunk TransactionTemplate.execute), and
 * this handler is registered on that RepeatTemplate. Swallowing the throwable
 * here makes the repeat loop re-run the WHOLE tasklet in a fresh transaction;
 * TaskletStep's ChunkTransactionCallback restores the StepExecution version
 * after a rolled-back commit, so the re-run starts from clean state.
 *
 * <p>Attempt counting lives in the RepeatContext (one per step execution), so
 * concurrent partition workers sharing this handler each get their own budget.
 *
 * <p>Shared platform copy of the CTV-local original (proven live 2026-07-14 at
 * 300k tx per copybook); the stage name is a constructor parameter so every
 * writer service logs its own retries.
 */
public class CrdbRetryExceptionHandler implements ExceptionHandler {

    static final int MAX_ATTEMPTS = 5;

    private static final long BASE_BACKOFF_MS = 100;
    private static final String ATTEMPTS_ATTRIBUTE = "crdb.retry.attempts";
    private static final Logger log = LoggerFactory.getLogger(CrdbRetryExceptionHandler.class);

    private final String stage;

    public CrdbRetryExceptionHandler(final String stage) {
        this.stage = stage;
    }

    @Override
    public void handleException(final RepeatContext context, final Throwable throwable) throws Throwable {
        if (!(throwable instanceof TransientDataAccessException)) {
            throw throwable;
        }
        final Integer previous = (Integer) context.getAttribute(ATTEMPTS_ATTRIBUTE);
        final int attempt = previous == null ? 1 : previous + 1;
        if (attempt >= MAX_ATTEMPTS) {
            throw throwable;
        }
        context.setAttribute(ATTEMPTS_ATTRIBUTE, attempt);
        final long backoffMs = (BASE_BACKOFF_MS << (attempt - 1))
                + ThreadLocalRandom.current().nextLong(BASE_BACKOFF_MS);
        log.warn("retrying stage={} step transaction attempt={}/{} after {} backoffMs={}",
                stage, attempt, MAX_ATTEMPTS, throwable.getClass().getSimpleName(), backoffMs);
        sleep(backoffMs, throwable);
    }

    private static void sleep(final long backoffMs, final Throwable throwable) throws Throwable {
        try {
            Thread.sleep(backoffMs);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw throwable;
        }
    }
}
