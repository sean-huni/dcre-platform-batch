package za.co.fnb.dcre.platform.batch;

import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;

/**
 * Marks the boundary between infrastructure startup and the runner phase for
 * {@link ExitCodeMain}: Boot publishes ApplicationStartedEvent after the context
 * refreshes and BEFORE any runner (and therefore before any Batch job) is called,
 * so an open gate means the failure that follows belongs to the job, not to the
 * platform.
 *
 * <p>Deliberately a named class, NOT a lambda: Spring cannot resolve a lambda's
 * generic event type, falls back to offering it every event and swallows the
 * resulting ClassCastException, which would leave the gate permanently closed and
 * report every job failure as a config failure.
 *
 * <p>Highest precedence so the gate opens before any other started-listener runs:
 * once refresh succeeded the platform did its part, and a sibling listener blowing
 * up stays a normal (retryable) technical failure.
 */
final class RunnerPhaseGate implements ApplicationListener<ApplicationStartedEvent>, Ordered {

    private volatile boolean reached;

    @Override
    public void onApplicationEvent(final ApplicationStartedEvent event) {
        this.reached = true;
    }

    /** True once startup completed and Boot was about to call the runners. */
    boolean reached() {
        return this.reached;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
