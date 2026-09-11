package za.co.fnb.dcre.platform.batch.config;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;

import za.co.fnb.dcre.platform.batch.telemetry.ParentTraceContext;

/**
 * Makes the arrival's trace context current for exactly the window in which the Batch job runs, so
 * the job and step spans hang off the arrival rather than starting a fresh root trace each.
 *
 * <p>The window is chosen, not guessed. Boot publishes {@code ApplicationStartedEvent} after refresh
 * and BEFORE any runner, and {@code ApplicationReadyEvent} after every runner has returned, both on
 * the thread that calls the runners. A Spring Batch job runs inside
 * {@code JobLauncherApplicationRunner}, which is one of those runners, and
 * {@code BatchObservabilityBeanPostProcessor} (present in {@code spring-batch-core-6.0.4}) is what
 * turns its executions into observations. Micrometer's OpenTelemetry bridge parents a new span from
 * {@code Context.current()}, so opening the scope across that window is the whole mechanism.
 * {@link za.co.fnb.dcre.platform.batch.RunnerPhaseGate} already depends on the same event for the
 * same reason and documents it.
 *
 * <p><strong>The scope is opened and closed on the SAME thread, which is why the close is bound to
 * {@code ApplicationReadyEvent} and not to context shutdown.</strong> A {@link Scope} is a
 * thread-local, and Spring's shutdown hook runs on a DIFFERENT thread, so closing there would
 * restore the wrong thread's context and leave this one's dangling. Ready and Failed are the two
 * ways the runner phase ends and both are published on the runner thread.
 *
 * <p>Deliberately a named class and NOT a lambda, for the reason
 * {@link za.co.fnb.dcre.platform.batch.RunnerPhaseGate} records: Spring cannot resolve a lambda's
 * generic event type and falls back to offering it every event. This one asks for every event
 * anyway, so it states that in its signature rather than relying on that fallback.
 *
 * <p>Nothing here can fail a service. An absent or malformed value yields
 * {@link Context#root()}, which carries no valid span, and no scope is opened at all: the process
 * then starts its own trace, which is the correct answer for a clock window no arrival triggered.
 */
final class ParentTraceContextListener implements ApplicationListener<ApplicationEvent> {

    private final String traceparent;

    private volatile Scope scope;

    ParentTraceContextListener(final String traceparent) {
        this.traceparent = traceparent;
    }

    @Override
    public void onApplicationEvent(final ApplicationEvent event) {
        if (event instanceof ApplicationStartedEvent) {
            open();
        } else if (event instanceof ApplicationReadyEvent || event instanceof ApplicationFailedEvent) {
            close();
        }
    }

    private void open() {
        final Context parent = ParentTraceContext.extract(this.traceparent);
        if (!Span.fromContext(parent).getSpanContext().isValid()) {
            // No parent to adopt. Opening a scope on an invalid span would MASK any context this
            // process establishes for itself, which is worse than doing nothing.
            return;
        }
        this.scope = parent.makeCurrent();
    }

    private void close() {
        final Scope open = this.scope;
        if (open != null) {
            this.scope = null;
            open.close();
        }
    }
}
